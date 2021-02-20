package uk.co.sgjbryan.hearts

import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import akka.actor
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.OverflowStrategy
import akka.util.Timeout
import cats.Monad
import cats.data.OptionT
import cats.implicits._
import com.typesafe.config.ConfigFactory
import fs2.Stream
import fs2.concurrent.Queue
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.QueryParamDecoderMatcher
import org.http4s.dsl.impl.Responses.BadRequestOps
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import uk.co.sgjbryan.hearts.deck.CardValue
import uk.co.sgjbryan.hearts.deck._
import uk.co.sgjbryan.hearts.game.Game
import uk.co.sgjbryan.hearts.game.Player
import uk.co.sgjbryan.hearts.game.Seat
import uk.co.sgjbryan.hearts.lobby.Lobby
import uk.co.sgjbryan.hearts.utils.CardPlayed
import uk.co.sgjbryan.hearts.utils.CirceGameEvent
import uk.co.sgjbryan.hearts.utils.Deal
import uk.co.sgjbryan.hearts.utils.GameCreationResponse
import uk.co.sgjbryan.hearts.utils.GamePlayer
import uk.co.sgjbryan.hearts.utils.JsonSupport._
import uk.co.sgjbryan.hearts.utils.Points
import uk.co.sgjbryan.hearts.utils.SeatResponse
import uk.co.sgjbryan.hearts.utils.TrickWon
import zio.ExitCode
import zio.Task
import zio.URIO
import zio.ZEnv
import zio.ZIO
import zio.clock.Clock
import zio.console.putStrLn
import zio.interop.catz._

object Http4sMain extends CatsApp {

  type HeartsTask[+A] = ZIO[ZEnv, Throwable, A]

  //TODO: abstract over the effect type, would need an F[_]-style actor
  object App {
    def serverStream =
      BlazeServerBuilder[HeartsTask](runtime.platform.executor.asEC)
        .bindHttp(port = 8080, host = "0.0.0.0")
        .withHttpApp(Routes().routes.orNotFound)
        .serve
  }

  case class Routes() extends Http4sDsl[HeartsTask] {

    //Note: this is an object so that we have the unapply method for pattern matching
    object PlayersQueryParamMatcher
        extends QueryParamDecoderMatcher[Int]("players")

    object SeatPath {

      def unapply(path: Path): Option[(UUID, UUID)] = path.some collect {
        case Root / "api"
            / "games" / UUIDVar(
              gameID
            ) / "seats" / UUIDVar(
              seatID
            ) =>
          (gameID, seatID)
      }
    }

    //TODO: generic F rather than Task
    val routes: HttpRoutes[HeartsTask] =
      HttpRoutes.of[HeartsTask] {
        case req @ POST -> Root / "api" / "games" =>
          for {
            // TODO: verify player count in decoder to give nicer error response
            settings <- req.asJsonDecode[GameSettings]
            resp <- Ok(Http4sMain.createGame(settings) map {
              _.asJson
            })
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "plays" =>
          for {
            card <- req.asJsonDecode[Card]
            play <- Http4sMain.playCard(gameID, seatID, card)
            resp <- play match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPass(_)      => InternalServerError()
              case Seat.InvalidPlay(reason) => BadRequest(reason)
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "passes" =>
          for {
            cards <- req.asJsonDecode[List[Card]]
            pass <- Http4sMain.passCards(gameID, seatID, cards)
            resp <- pass match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPass(reason) => BadRequest(reason)
              case Seat.InvalidPlay(reason) => InternalServerError()
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) =>
          for {
            name <- req.as[String]
            pass <- Http4sMain.joinGame(gameID, seatID, name)
            resp <- pass match {
              case Game.UserAdded(player) =>
                Ok(
                  SeatResponse(
                    player.name,
                    seatID,
                    gameID
                  ).asJson
                )
              case Game.SeatNotFound => NotFound()
            }
          } yield resp
        case GET -> SeatPath(gameID, seatID) / "listen" =>
          val listen = Queue.unbounded[HeartsTask, Seat.Action]
          for {
            queue <- listen
            seat <- Http4sMain.findSeat(gameID, seatID)
            /*
            This is why we can't use a generic F currently:
            queue.enqueue1 returns F[Unit], but the akka behaviour is impure - ideally the behavior could handle returning an F[Behavior[A]]
            Is there a way we can modify the Akka API to handle this better?
             */
            _ = seat ! Seat.AddListenerEffect(msg =>
              runtime.unsafeRun(queue.enqueue1(msg))
            )
            resp <- Ok(
              queue.dequeue
                .collect(toEvent)
                .asJsonArray
            )
          } yield resp
      }

  }

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    App.serverStream.compile.drain.fold(
      _ => ExitCode.failure,
      _ => ExitCode.success
    )

  implicit val lobby: ActorSystem[Lobby.Message] = ActorSystem(Lobby(), "lobby")
  implicit val timeout: Timeout = 3.seconds

  //TODO: include seatSecret (either server-encrypted seatID or generated and returned when seat taken) for auth
  //TODO: we could keep the actor in session rather than running 'findSeat' each time
  val findSeat: (UUID, UUID) => Task[ActorRef[Seat.Action]] =
    (gameID: UUID, seatID: UUID) =>
      for {
        maybeGame <- Task.fromFuture { implicit ec =>
          lobby.ask[Option[ActorRef[Game.Message]]](Lobby.FindGame(gameID, _))
        }
        game <- Task.fromEither {
          Either.fromOption(maybeGame, new Exception(s"Game $gameID not found"))
        }
        maybeSeat <- Task.fromFuture { implicit ec =>
          game.ask[Option[ActorRef[Seat.Action]]](Game.FindSeat(seatID, _))
        }
        //TODO: clean these up
        seat <- Task.fromEither {
          Either.fromOption(maybeSeat, new Exception(s"Seat $seatID not found"))
        }
      } yield seat

  val toEvent: PartialFunction[Seat.Action, CirceGameEvent] = {
    case Seat.ReceiveDeal(holding, _, passTo, firstLead, passCount) =>
      CirceGameEvent(
        "ReceiveHand",
        Deal(holding, passCount, passTo.name, firstLead.name).asJson
      )
    case Seat.ReceivePass(cards)   => CirceGameEvent("ReceivePass", cards.asJson)
    case Seat.RequestLead(_)       => CirceGameEvent("RequestLead", Json.Null)
    case Seat.RequestPlay(_, suit) => CirceGameEvent("RequestPlay", suit.asJson)
    case Seat.CardPlayed(card, toPlay) =>
      CirceGameEvent("CardPlayed", CardPlayed(card, toPlay).asJson)
    case Seat.TrickEnded(player, scoringCards, pointsTaken) =>
      CirceGameEvent(
        "TrickEnded",
        TrickWon(player.toGamePlayer, scoringCards, pointsTaken).asJson
      )
    case Seat.GameStarted(players) =>
      CirceGameEvent("GameStarted", players.map(_.toGamePlayer).asJson)
    case Seat.HandEnded(points) =>
      CirceGameEvent(
        "HandEnded",
        points.map(p => Points(p._1.toGamePlayer, p._2)).asJson
      )
  }

  val playCard = (gameID: UUID, seatID: UUID, card: Card) =>
    findSeat(gameID, seatID) flatMap { seat =>
      Task.fromFuture(_ => seat.ask[Seat.Response](Seat.Play(card, _)))
    }

  val passCards = (gameID: UUID, seatID: UUID, cards: List[Card]) =>
    findSeat(gameID, seatID) flatMap { seat =>
      Task.fromFuture(_ => seat.ask[Seat.Response](Seat.Pass(cards, _)))
    }

  val createGame = (settings: GameSettings) =>
    Task.fromFuture(_ =>
      lobby.ask[GameCreationResponse](
        Lobby.CreateGame(settings, _)
      )
    )

  //TODO: make these F[_] instead of Task?
  val joinGame = (gameID: UUID, seatID: UUID, name: String) =>
    for {
      game <- Task.fromFuture(_ =>
        lobby
          .ask[Option[ActorRef[Game.Message]]](Lobby.FindGame(gameID, _))
      )
      resp <- game traverse { g =>
        Task.fromFuture(_ =>
          g.ask[Game.SeatResponse](Game.TakeSeat(name, seatID, _))
        )
      }
    } yield resp getOrElse Game.SeatNotFound

}
