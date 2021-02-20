package uk.co.sgjbryan.hearts.lobby

import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import uk.co.sgjbryan.hearts.GameSettings
import uk.co.sgjbryan.hearts.game.Game
import uk.co.sgjbryan.hearts.utils.GameCreationResponse

object Lobby {

  sealed trait Message
  final case class FindGame(
      uuid: UUID,
      replyTo: ActorRef[Option[ActorRef[Game.Message]]]
  ) extends Message
  final case class CreateGame(
      settings: GameSettings,
      replyTo: ActorRef[GameCreationResponse]
  ) extends Message
  final case class EndGame(gameID: UUID) extends Message

  def withGames(
      openGames: Map[UUID, ActorRef[Game.Message]]
  ): Behavior[Message] =
    Behaviors.receive { (context, message) =>
      message match {
        case CreateGame(settings, replyTo) =>
          val uuid = UUID.randomUUID()
          val newGame = context.spawnAnonymous(Game(uuid, settings, replyTo))
          context.watchWith(newGame, EndGame(uuid))
          withGames(
            openGames + (uuid -> newGame)
          )
        case FindGame(uuid, replyTo) =>
          replyTo ! openGames.get(uuid)
          Behaviors.same
        case EndGame(gameID) => withGames(openGames - gameID)
      }
    }

  def apply(): Behavior[Message] = withGames(Map())

}
