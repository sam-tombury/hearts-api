package uk.co.sgjbryan.hearts

import java.util.UUID
import akka.actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import cats.data.OptionT
import cats.implicits._
import com.typesafe.config.ConfigFactory
import uk.co.sgjbryan.hearts.game.{Game, Player, Seat}
import uk.co.sgjbryan.hearts.lobby.Lobby
import uk.co.sgjbryan.hearts.deck._
import uk.co.sgjbryan.hearts.utils.{
  CardPlayed,
  CirceGameEvent,
  Deal,
  GameCreationResponse,
  GamePlayer,
  Points,
  SeatResponse,
  TrickWon
}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import cats.effect._
import cats.effect.IO.contextShift
import fs2.Stream
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.kleisli._
import org.http4s.server.blaze.BlazeServerBuilder
import io.circe.Json
import io.circe.syntax._
import io.circe.generic.auto._
import org.http4s.dsl.impl.QueryParamDecoderMatcher
import cats.Monad
import io.circe.Decoder
import io.circe.Encoder
import scala.util.Try

import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._
import fs2.Pipe
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.OverflowStrategy
import fs2.concurrent.Queue
import uk.co.sgjbryan.hearts.deck.CardValue
import org.http4s.dsl.impl.Responses.BadRequestOps

object Http4sMain extends IOApp {

  implicit val encodeCardValue: Encoder[CardValue] =
    Encoder.forProduct3("name", "shortName", "order")(value =>
      (value.displayName, value.shortName, value.order)
    )
  implicit val decodeCardValue: Decoder[CardValue] =
    Decoder.decodeString.emapTry { str =>
      Try {
        str.toLowerCase match {
          case "2"     => Two
          case "3"     => Three
          case "4"     => Four
          case "5"     => Five
          case "6"     => Six
          case "7"     => Seven
          case "8"     => Eight
          case "9"     => Nine
          case "10"    => Ten
          case "jack"  => Jack
          case "queen" => Queen
          case "king"  => King
          case "ace"   => Ace
        }
      }
    }

  implicit val encodeSuit: Encoder[Suit] =
    Encoder.forProduct4("name", "icon", "colour", "order")(suit =>
      (
        suit.displayName,
        suit.icon,
        suit.colour.toString.toLowerCase,
        suit.order
      )
    )
  implicit val decodeSuit: Decoder[Suit] = Decoder.decodeString.emapTry { str =>
    Try {
      str.toLowerCase match {
        case "hearts"   => Hearts
        case "clubs"    => Clubs
        case "diamonds" => Diamonds
        case "spades"   => Spades
      }
    }
  }

  //TODO: abstract over the effect type, need an F[_] actor
  object App {
    def serverStream: Stream[IO, ExitCode] =
      BlazeServerBuilder[IO](global)
        .bindHttp(port = 8080, host = "0.0.0.0")
        .withHttpApp(Routes().routes.orNotFound)
        .serve
  }

//Note: this is an object so that we have the unapply method for pattern matching
  object PlayersQueryParamMatcher
      extends QueryParamDecoderMatcher[Int]("players")

  case class Routes() extends Http4sDsl[IO] {

    import Http4sMain.lobby.scheduler
    implicit val timeout: Timeout = 3.seconds
    def fromFuture[A](thunk: => Future[A]): IO[A] = Async.fromFuture {
      IO {
        thunk
      }
    }

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

    val routes: HttpRoutes[IO] =
      HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "games" =>
          for {
            // TODO: verify player count in decoder to give nicer error response
            settings <- req.asJsonDecode[GameSettings]
            resp <- Ok(fromFuture(Http4sMain.createGame(settings) map {
              _.asJson
            }))
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "plays" =>
          for {
            card <- req.asJsonDecode[Card]
            play <- fromFuture(Http4sMain.playCard(gameID, seatID, card))
            resp <- play match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPlay(reason) => BadRequest(reason)
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "passes" =>
          for {
            cards <- req.asJsonDecode[List[Card]]
            pass <- fromFuture(Http4sMain.passCards(gameID, seatID, cards))
            resp <- pass match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPlay(reason) => BadRequest(reason)
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) =>
          for {
            name <- req.as[String]
            pass <- fromFuture(Http4sMain.joinGame(gameID, seatID, name))
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
        //TODO: just a streamed http response instead? We ignore client messages currently
        case GET -> Root / "api" / "ws" / "games" / UUIDVar(
              gameID
            ) / "seats" / UUIDVar(
              seatID
            ) / "listen" =>
          val listen = Queue.unbounded[IO, Seat.Action]
          for {
            queue <- listen
            seat <- fromFuture(Http4sMain.findSeat(gameID, seatID))
            /*
            This is why we can't use a generic F currently:
            queue.enqueue1 returns F[Unit], but the akka behaviour is impure - ideally the behavior could handle returning an F[Behavior[A]]
            As it stands, there's no way to arbitrarily run an F (it seems this may be possible in the future using a cats.effect.std.Dispatcher[F]) - so we need an explicit IO
             */
            _ = seat ! Seat.AddListenerEffect(
              queue.enqueue1(_).unsafeRunAsyncAndForget()
            )

            toClient = queue.dequeue
              .collect(toEvent)
              .map(ev => Text(ev.asJson.noSpaces))
              .merge(
                //Keep-alive pings
                Stream
                  .awakeEvery[IO](5.seconds)
                  .map(_ => WebSocketFrame.Ping())
              )
            socket <- WebSocketBuilder[IO].build(
              toClient,
              _ => Stream.empty //Ignore messages received from the client
            )
          } yield socket
      }

  }

  def run(args: List[String]): IO[ExitCode] =
    App.serverStream.compile.drain.as(ExitCode.Success)

  implicit val lobby: ActorSystem[Lobby.Message] = ActorSystem(Lobby(), "lobby")
  implicit val timeout: Timeout = 3.seconds
  implicit override def executionContext = super.executionContext

  //TODO: include seatSecret (either server-encrypted seatID or generated and returned when seat taken) for auth
  //TODO: we could keep the actor in session rather than running 'findSeat' each time
  val findSeat: (UUID, UUID) => Future[ActorRef[Seat.Action]] =
    (gameID: UUID, seatID: UUID) =>
      (for {
        game <- OptionT(
          lobby.ask[Option[ActorRef[Game.Message]]](Lobby.FindGame(gameID, _))
        )
        seat <- OptionT(
          game.ask[Option[ActorRef[Seat.Action]]](Game.FindSeat(seatID, _))
        )
      } yield seat).value collect { case Some(seat) =>
        seat
      }

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
    findSeat(gameID, seatID) flatMap {
      _.ask[Seat.Response](Seat.Play(card, _))
    }

  val passCards = (gameID: UUID, seatID: UUID, cards: List[Card]) =>
    findSeat(gameID, seatID) flatMap {
      _.ask[Seat.Response](Seat.Pass(cards, _))
    }

  val createGame = (settings: GameSettings) =>
    lobby.ask[GameCreationResponse](
      Lobby.CreateGame(settings, _)
    )

  //TODO: make these F[_] instead of Future
  val joinGame = (gameID: UUID, seatID: UUID, name: String) =>
    for {
      game <- lobby
        .ask[Option[ActorRef[Game.Message]]](Lobby.FindGame(gameID, _))
      resp <- game traverse {
        _.ask[Game.SeatResponse](Game.TakeSeat(name, seatID, _))
      }
    } yield resp getOrElse Game.SeatNotFound

}
