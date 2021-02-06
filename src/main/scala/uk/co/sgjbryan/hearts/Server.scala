package uk.co.sgjbryan.hearts

import java.util.UUID

import akka.actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.typed.scaladsl.ActorSource
import akka.util.Timeout
import cats.data.OptionT
import cats.implicits._
import com.typesafe.config.ConfigFactory
import spray.json._
import uk.co.sgjbryan.hearts.game.{Game, Player, Seat}
import uk.co.sgjbryan.hearts.lobby.Lobby
import uk.co.sgjbryan.hearts.deck.Card
import uk.co.sgjbryan.hearts.utils.{
  Deal,
  CardPlayed,
  GameCreationResponse,
  GameEvent,
  GamePlayer,
  JsonSupport,
  Points,
  SeatResponse,
  TrickWon
}

import scala.concurrent.Future
import scala.concurrent.duration._

object Server extends App with JsonSupport {

  implicit val lobby: ActorSystem[Lobby.Message] = ActorSystem(Lobby(), "lobby")
  implicit val timeout: Timeout =
    3.seconds //TODO: consider appropriate timeouts
  import Directives._
  import lobby.executionContext

  lazy val baseURL = ConfigFactory.load().getString("baseURL")

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

  val toEvent: PartialFunction[Seat.Action, GameEvent] = {
    case Seat.ReceiveDeal(holding, _, passTo, firstLead, passCount) =>
      GameEvent(
        "ReceiveHand",
        Deal(holding, passCount, passTo.name, firstLead.name).toJson
      )
    case Seat.ReceivePass(cards)   => GameEvent("ReceivePass", cards.toJson)
    case Seat.RequestLead(_)       => GameEvent("RequestLead", JsNull)
    case Seat.RequestPlay(_, suit) => GameEvent("RequestPlay", suit.toJson)
    case Seat.CardPlayed(card, toPlay) =>
      GameEvent("CardPlayed", CardPlayed(card, toPlay).toJson)
    case Seat.TrickEnded(player, scoringCards, pointsTaken) =>
      GameEvent(
        "TrickEnded",
        TrickWon(player.toGamePlayer, scoringCards, pointsTaken).toJson
      )
    case Seat.GameStarted(players) =>
      GameEvent("GameStarted", players.map(_.toGamePlayer).toJson)
    case Seat.HandEnded(points) =>
      GameEvent(
        "HandEnded",
        points.map(p => Points(p._1.toGamePlayer, p._2)).toJson
      )
  }

  val listenService = (gameID: UUID, seatID: UUID) =>
    Flow.fromSinkAndSource(
      Sink.ignore,
      Source.futureSource {
        findSeat(gameID, seatID) map { seat =>
          ActorSource
            .actorRef[Seat.Action]( //TODO: consider the best parameters here
              PartialFunction.empty, //TODO: add in completion and error messages from the seat
              PartialFunction.empty,
              5,
              OverflowStrategy.fail
            )
            .mapMaterializedValue { listener =>
              seat ! Seat.AddListener(listener)
            } collect toEvent map { event =>
            TextMessage.Strict(event.toJson.compactPrint)
          }
        }
      }
    )

  val playCard = (gameID: UUID, seatID: UUID, card: Card) =>
    findSeat(gameID, seatID) flatMap {
      _.ask[Seat.Response](Seat.Play(card, _))
    }

  val passCards = (gameID: UUID, seatID: UUID, cards: List[Card]) =>
    findSeat(gameID, seatID) flatMap {
      _.ask[Seat.Response](Seat.Pass(cards, _))
    }

  val listenRoute =
    path("api" / "ws" / "games" / JavaUUID / "seats" / JavaUUID / "listen") {
      (gameID, seatID) =>
        get {
          handleWebSocketMessages(listenService(gameID, seatID))
        }
    }

  val lobbyRoute = path("api" / "games") {
    post {
      parameter("players".as[Int] ? 4) { players =>
        val settings = players match {
          case 3 => GameSettings.threePlayer
          case 4 => GameSettings.fourPlayer
        }
        onSuccess(
          lobby.ask[GameCreationResponse](Lobby.CreateGame(settings, _))
        ) { gameCreated =>
          complete(StatusCodes.Created, gameCreated)
        }
      }
    }
  }

  val joinRoute =
    path("api" / "games" / JavaUUID / "seats" / JavaUUID) { (gameID, seatID) =>
      post {
        entity(as[String]) { name =>
          onSuccess(for {
            game <- lobby
              .ask[Option[ActorRef[Game.Message]]](Lobby.FindGame(gameID, _))
            resp <- game map {
              _.ask[Game.SeatResponse](Game.TakeSeat(name, seatID, _))
            } getOrElse { Future.successful(Game.SeatNotFound) }
          } yield resp) {
            case Game.UserAdded(player) =>
              complete(
                StatusCodes.OK,
                SeatResponse(
                  player.name,
                  seatID,
                  gameID
                )
              )
            case Game.SeatNotFound => complete(StatusCodes.NotFound)
          }
        }
      }
    }

  val playRoute =
    path("api" / "games" / JavaUUID / "seats" / JavaUUID / "plays") {
      (gameID, seatID) =>
        post {
          entity(as[Card]) { card =>
            onSuccess(playCard(gameID, seatID, card)) {
              case Seat.Ok() => complete(StatusCodes.NoContent)
              case Seat.InvalidPlay(reason) =>
                complete(StatusCodes.BadRequest, reason)
            }
          }
        }
    }

  val passRoute =
    path("api" / "games" / JavaUUID / "seats" / JavaUUID / "passes") {
      (gameID, seatID) =>
        post {
          entity(as[List[Card]]) { cards =>
            onSuccess(passCards(gameID, seatID, cards)) {
              case Seat.Ok() => complete(StatusCodes.NoContent)
              case Seat.InvalidPass(reason) =>
                complete(StatusCodes.BadRequest, reason)
            }
          }
        }
    }

  val clientRoute = path("client") {
    getFromResource("index.html")
  } ~ path("client" / "styles.css") {
    getFromResource("styles.css")
  }

  val bindingFuture = Http()
    .newServerAt("0.0.0.0", 8080)
    .bind(
      listenRoute ~ lobbyRoute ~ joinRoute ~ playRoute ~ clientRoute ~ passRoute
    )

  println(s"Server online at http://0.0.0.0:8080/")

}
