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
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.syntax.kleisli._
import org.http4s.websocket.WebSocketFrame.Ping
import org.http4s.websocket.WebSocketFrame.Text
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
import zio.RIO
import zio.interop.catz._
import uk.co.sgjbryan.hearts.services.HeartsEnv
import scala.concurrent.ExecutionContext
import cats.effect.ConcurrentEffect
import uk.co.sgjbryan.hearts.services.{GameRepo, SeatHandler, SeatRepo}

object Http4sMain extends CatsApp {

  type HeartsTask[+A] = RIO[ZEnv with HeartsEnv, A]

  //TODO: abstract over the effect type, would need an F[_]-style actor
  object ServerApp {
    def serverStream(implicit ce: ConcurrentEffect[HeartsTask]) =
      BlazeServerBuilder[HeartsTask](ExecutionContext.global)
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
            resp <- Ok(GameRepo.createGame(settings) map {
              _.asJson
            })
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "plays" =>
          for {
            card <- req.asJsonDecode[Card]
            play <- SeatHandler.playCard(gameID, seatID, card)
            resp <- play match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPass(_)      => InternalServerError()
              case Seat.InvalidPlay(reason) => BadRequest(reason)
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) / "passes" =>
          for {
            cards <- req.asJsonDecode[List[Card]]
            pass <- SeatHandler.passCards(gameID, seatID, cards)
            resp <- pass match {
              case Seat.Ok()                => NoContent()
              case Seat.InvalidPass(reason) => BadRequest(reason)
              case Seat.InvalidPlay(reason) => InternalServerError()
            }
          } yield resp
        case req @ POST -> SeatPath(gameID, seatID) =>
          for {
            name <- req.as[String]
            pass <- SeatRepo.joinGame(gameID, seatID, name)
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
          val listen = Queue.unbounded[Task, Seat.Action]
          for {
            queue <- listen
            seat <- SeatRepo.findSeat(gameID, seatID)
            _ = seat ! Seat.AddListener(msg =>
              runtime.unsafeRun(queue.enqueue1(msg))
            )
            resp <- Ok(
              queue.dequeue
                .collect(toEvent)
                .asJsonArray
                .covary[HeartsTask]
            )
          } yield resp
        case GET -> SeatPath(gameID, seatID) / "ws" =>
          val listen = Queue.unbounded[Task, Seat.Action]
          for {
            queue <- listen
            seat <- SeatRepo.findSeat(gameID, seatID)
            /*
            This is why we can't use a generic F currently:
            queue.enqueue1 returns F[Unit], but the akka behaviour is impure - ideally the behavior could handle returning an F[Behavior[A]]
            Is there a way we can modify the Akka API to handle this better?

            We may be able to create a pretty simple DSL for our State Machines anyway...

            Parameters:
            Message[Res] <- message, expecting response of type Res. Maybe need an effect/error type here...
            State[M[_]] <- receives messages of type M[_], 'handleMessage' method: M[Res] => F[(State[M], Res)] returning new state and expected type
            case class State1 extends State[MyMessage]
            Then it's all handled with a ref... Ref[State] and update method
            Then we have Actor[M[_]] which encapsulates the Ref and exposes 'handleMessage' M[Res] => F[Res] - updating the ref and returning the Res
            So 'enacting' the messages will be via http etc... e.g. resp <- actor.handleMessage(Seat.AddListener)

            State <- base class which has a 'handleMessage' method:
            sealed abstract class A extends State[A]
            case class State1 extends A
            case class State2 extends A
            Message[B] <- base wrapper class for messages...
            sealed class MyMessage[B] extends Message[B]
             */
            _ = seat ! Seat.AddListener(msg =>
              runtime.unsafeRun(queue.enqueue1(msg))
            )
            resp <- WebSocketBuilder[HeartsTask]
              .build(
                queue.dequeue
                  .collect(toEvent)
                  .map(msg => Text(msg.asJson.noSpaces))
                  .merge(
                    Stream
                      .awakeEvery[HeartsTask](3.seconds)
                      .map(_ => Ping())
                  ),
                _.drain
              )
          } yield resp
      }

  }

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    RIO
      .concurrentEffectWith[ZEnv with HeartsEnv, Nothing, ExitCode] {
        implicit ce =>
          ServerApp.serverStream.compile.drain
            .fold(
              _ => ExitCode.failure,
              _ => ExitCode.success
            )
      }
      .provideCustomLayer(HeartsEnv.live)

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

}
