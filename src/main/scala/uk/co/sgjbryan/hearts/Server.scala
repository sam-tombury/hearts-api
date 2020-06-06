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
import com.typesafe.config.ConfigFactory
import spray.json._
import uk.co.sgjbryan.hearts.game.{Game, Seat}
import uk.co.sgjbryan.hearts.lobby.Lobby
import uk.co.sgjbryan.hearts.utils.{Card, Deal, GameCreationResponse, GameEvent, JsonSupport, Points, SeatResponse}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn

object Server extends App with JsonSupport {

  implicit val lobby: ActorSystem[Lobby.Message] = ActorSystem(Lobby(), "lobby")
  implicit val timeout: Timeout = 3.seconds //TODO: consider appropriate timeouts
  implicit val classicSystem: actor.ActorSystem = lobby.classicSystem //Classic (non-typed) system needed for AkkaHTTP
  import Directives._
  import classicSystem.dispatcher

  lazy val baseURL = ConfigFactory.load().getString("baseURL")

  //TODO: include seatSecret (either server-encrypted seatID or generated and returned when seat taken) for auth
  //TODO: we could keep the actor in session rather than running 'findSeat' each time
  val findSeat: (UUID, UUID) => Future[ActorRef[Seat.Action]] = (gameID: UUID, seatID: UUID) =>
    for {
      game <- lobby.ask[ActorRef[Game.Message]](Lobby.FindGame(gameID, _))
      seat <- game.ask[ActorRef[Seat.Action]](Game.FindSeat(seatID, _))
    } yield seat

  val toEvent: PartialFunction[Seat.Action, GameEvent] = {
    case Seat.ReceiveDeal(holding, _, _, passCount) => GameEvent("ReceiveHand", Deal(holding, passCount).toJson)
    case Seat.ReceivePass(cards) => GameEvent("ReceivePass", cards.toJson)
    case Seat.RequestLead(_) => GameEvent("RequestLead", JsNull)
    case Seat.RequestPlay(_, suit) => GameEvent("RequestPlay", suit.toJson)
    case Seat.CardPlayed(card) => GameEvent("CardPlayed", card.toJson)
    case Seat.TrickEnded(player) => GameEvent("TrickEnded", Map("winner" -> player.name).toJson)
    case Seat.GameStarted(players) => GameEvent("GameStarted", players.toJson)
    case Seat.HandEnded(points) => GameEvent("HandEnded", points.map(p => Points(p._1.name, p._2)).toJson)
  }

  val listenService = (gameID: UUID, seatID: UUID) =>
    Flow.fromSinkAndSource(
      Sink.ignore,
      Source.futureSource {
        findSeat(gameID, seatID) map {
          seat =>
            ActorSource.actorRef[Seat.Action]( //TODO: consider the best parameters here
              PartialFunction.empty, //TODO: add in completion and error messages from the seat
              PartialFunction.empty,
              5,
              OverflowStrategy.fail
            ).mapMaterializedValue {
              listener => seat ! Seat.AddListener(listener)
            } collect toEvent map {
              event => TextMessage.Strict(event.toJson.compactPrint)
            }
        }
      }
    )

  val playCard = (gameID: UUID, seatID: UUID, card: Card) => findSeat(gameID, seatID) flatMap {
    _.ask[Seat.Response](Seat.Play(card, _))
  }

  val passCards = (gameID: UUID, seatID: UUID, cards: List[Card]) => findSeat(gameID, seatID) flatMap {
    _.ask[Seat.Response](Seat.Pass(cards, _))
  }

  val listenRoute =
    path("ws" / "games" / JavaUUID / "seats" / JavaUUID / "listen") { (gameID, seatID) =>
      get {
        handleWebSocketMessages(listenService(gameID, seatID))
      }
    }

  val lobbyRoute = path("games") {
    post {
      parameter("players".as[Int] ? 4) { players =>
        val settings = players match {
          case 3 => GameSettings.threePlayer
          case 4 => GameSettings.fourPlayer
        }
        onSuccess(lobby.ask[GameCreationResponse](Lobby.CreateGame(settings, _))) {
          gameCreated => complete(StatusCodes.Created, gameCreated)
        }
      }
    }
  }

  val joinRoute =
    path("games" / JavaUUID / "seats" / JavaUUID) { (gameID, seatID) =>
      post {
        entity(as[String]) { name =>
          onSuccess(for {
            game <- lobby.ask[ActorRef[Game.Message]](Lobby.FindGame(gameID, _))
            added <- game.ask[Game.UserAdded](Game.TakeSeat(name, seatID, _))
          } yield added.player.name) {
            name =>
              complete(StatusCodes.OK,
                SeatResponse(
                  name,
                  seatID,
                  gameID
                )
              )
          }
        }
      }
    }

  val playRoute =
    path("games" / JavaUUID / "seats" / JavaUUID / "plays") { (gameID, seatID) =>
      post {
        entity(as[Card]) {
          card =>
            onSuccess(playCard(gameID, seatID, card)) {
              case Seat.Ok() => complete(StatusCodes.NoContent)
              case Seat.InvalidPlay(reason) => complete(StatusCodes.BadRequest, reason)
            }
        }
      }
    }

  val passRoute =
    path("games" / JavaUUID / "seats" / JavaUUID / "passes") { (gameID, seatID) =>
      post {
        entity(as[List[Card]]) {
          cards =>
            onSuccess(passCards(gameID, seatID, cards)) {
              case Seat.Ok() => complete(StatusCodes.NoContent)
              case Seat.InvalidPass(reason) => complete(StatusCodes.BadRequest, reason)
            }
        }
      }
    }

  val clientRoute = path("client") {
    getFromResource("index.html")
  } ~ path("client" / "styles.css") {
    getFromResource("styles.css")
  }

  implicit val materializer: Materializer = Materializer.matFromSystem(lobby) //Explicitly specify the system provider
  val bindingFuture = Http().bindAndHandle(
    Route.handlerFlow(listenRoute ~ lobbyRoute ~ joinRoute ~ playRoute ~ clientRoute ~ passRoute),
    "0.0.0.0",
    8080
  )

  println(s"Server online at http://0.0.0.0:8080/")
  StdIn.readLine() // This doesn't work with revolver...

  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => lobby.terminate()) // and shutdown when done

}