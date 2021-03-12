package uk.co.sgjbryan.hearts

import scala.concurrent.duration._
import cats.implicits._
import zio.{Has, ULayer, ZLayer}
import java.util.UUID
import zio.Task
import zio.macros.accessible
import zio.interop.catz._
import akka.util.Timeout
import akka.actor.typed.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import uk.co.sgjbryan.hearts.game.Seat
import uk.co.sgjbryan.hearts.game.Game
import uk.co.sgjbryan.hearts.deck.Card
import uk.co.sgjbryan.hearts.lobby.Lobby
import uk.co.sgjbryan.hearts.utils.GameCreationResponse
import uk.co.sgjbryan.hearts.GameSettings
import akka.actor.typed.RecipientRef

package object services {

  implicit val timeout: Timeout = 3.seconds
  implicit class TaskAskable[T](askable: RecipientRef[T])(implicit
      system: ActorSystem[_]
  ) {
    def askTask[Res](replyTo: ActorRef[Res] => T): Task[Res] = Task.fromFuture {
      _ => askable.ask(replyTo)
    }
  }

  type LobbySystem = Has[ActorSystem[Lobby.Message]]
  type GameRepo = Has[GameRepo.Service]
  type SeatRepo = Has[SeatRepo.Service]
  type SeatHandler = Has[SeatHandler.Service]

  type HeartsEnv = GameRepo with SeatRepo with SeatHandler with LobbySystem

  object HeartsEnv {
    val live: ULayer[HeartsEnv] =
      LobbySystem.live >+>
        GameRepo.live >+>
        SeatRepo.live >+>
        SeatHandler.live
  }

  object LobbySystem {
    val live: ULayer[LobbySystem] =
      ZLayer.succeed(ActorSystem(Lobby(), "lobby")) //TODO: shutdown hook maybe?
  }

  @accessible
  object GameRepo {
    trait Service {
      def createGame(settings: GameSettings): Task[GameCreationResponse]
      def findGame(gameID: UUID): Task[ActorRef[Game.Message]]
    }

    val live: ZLayer[LobbySystem, Nothing, GameRepo] =
      ZLayer.fromFunction(system =>
        new Service {

          implicit val lobby: ActorSystem[Lobby.Message] = system.get

          def createGame(settings: GameSettings): Task[GameCreationResponse] =
            lobby.askTask(Lobby.CreateGame(settings, _))

          def findGame(gameID: UUID): Task[ActorRef[Game.Message]] = for {
            maybeGame <- lobby.askTask(Lobby.FindGame(gameID, _))
            game <- Task.fromEither {
              Either.fromOption(
                maybeGame,
                new Exception(s"Game $gameID not found")
              )
            }
          } yield game

        }
      )

  }

  @accessible
  object SeatRepo {
    trait Service {
      def findSeat(gameID: UUID, seatID: UUID): Task[ActorRef[Seat.Action]]
      def joinGame(
          gameID: UUID,
          seatID: UUID,
          name: String
      ): Task[Game.SeatResponse]
    }

    val live: ZLayer[GameRepo with LobbySystem, Nothing, SeatRepo] =
      ZLayer.fromFunction(gameRepo =>
        new Service {
          implicit val lobby = gameRepo.get[ActorSystem[Lobby.Message]]

          def findSeat(
              gameID: UUID,
              seatID: UUID
          ): Task[ActorRef[Seat.Action]] = for {
            game <- gameRepo.get.findGame(gameID)
            maybeSeat <- game.askTask(Game.FindSeat(seatID, _))
            seat <- Task.fromEither {
              Either.fromOption(
                maybeSeat,
                new Exception(s"Seat $seatID in game $gameID not found")
              )
            }
          } yield seat

          def joinGame(
              gameID: UUID,
              seatID: UUID,
              name: String
          ): Task[Game.SeatResponse] = (for {
            game <- gameRepo.get.findGame(gameID)
            resp <- game.askTask(Game.TakeSeat(name, seatID, _))
          } yield resp).recover { _ =>
            Game.SeatNotFound
          }
        }
      )

  }

  @accessible
  object SeatHandler {
    trait Service {
      def playCard(
          gameID: UUID,
          seatID: UUID,
          card: Card
      ): Task[Seat.Response]
      def passCards(
          gameID: UUID,
          seatID: UUID,
          cards: List[Card]
      ): Task[Seat.Response]
    }

    val live: ZLayer[SeatRepo with LobbySystem, Nothing, SeatHandler] =
      ZLayer.fromFunction(seatRepo =>
        new Service {
          implicit val lobby = seatRepo.get[ActorSystem[Lobby.Message]]

          def playCard(
              gameID: UUID,
              seatID: UUID,
              card: Card
          ): Task[Seat.Response] = for {
            seat <- seatRepo.get.findSeat(gameID, seatID)
            resp <- seat.askTask(Seat.Play(card, _))
          } yield resp

          def passCards(
              gameID: UUID,
              seatID: UUID,
              cards: List[Card]
          ): Task[Seat.Response] = for {
            seat <- seatRepo.get.findSeat(gameID, seatID)
            resp <- seat.askTask(Seat.Pass(cards, _))
          } yield resp
        }
      )
  }

}
