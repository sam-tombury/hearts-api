package uk.co.sgjbryan.hearts.game

import java.net.URL
import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import uk.co.sgjbryan.hearts.GameSettings
import uk.co.sgjbryan.hearts.utils.{Card, GameCreationResponse}

import scala.util.Random

object Game {

  sealed trait Message
  final case class DealHand() extends Message
  final case class TakeSeat(name: String, seatID: UUID, replyTo: ActorRef[Game.UserAdded]) extends Message
  final case class PlayerReady(seatID: UUID) extends Message
  final case class FindSeat(seatID: UUID, replyTo: ActorRef[ActorRef[Seat.Action]]) extends Message
  final case class UserAdded(player: Player)

  def apply(gameID: UUID, settings: GameSettings, replyTo: ActorRef[GameCreationResponse]): Behavior[Message] = Behaviors.setup {
    context =>
      context.log.info("Game {} is awaiting players", gameID)
      val seats = (for {
        _ <- 1 to settings.playerCount
        seatID = UUID.randomUUID()
        actor = context.spawnAnonymous(Seat(seatID, context.self))
      } yield (seatID, actor)).toMap
      replyTo ! GameCreationResponse(gameID, seats.keys.toList, seats.keys.toList map {
        seatID => new URL(s"http://localhost:8080/client?gameID=$gameID&seatID=$seatID")
      })
      new ScheduledGame(gameID, settings.deck, seats, settings).awaitingPlayers()
  }
}

class ScheduledGame(gameID: UUID, deck: List[Card], seats: Map[UUID, ActorRef[Seat.Action]], settings: GameSettings) {
  import Game._

  def awaitingPlayers(players: List[Player] = List(), readySeats: Set[UUID] = Set(), openSeats: Map[UUID, ActorRef[Seat.Action]] = seats): Behavior[Game.Message] = Behaviors.receiveMessagePartial {
    case TakeSeat(name, seatID, replyTo) =>
      val seat = openSeats(seatID)//TODO: improve this to handle missing seats?
      val player = Player(name, seat)
      replyTo ! UserAdded(player)
      awaitingPlayers(players :+ player, readySeats, openSeats.removed(seatID))
    case PlayerReady(seatID) =>
      val newReady = readySeats + seatID
      if (openSeats.isEmpty && newReady.size == players.size) {
        players map {
          _.actor
        } foreach {
          _ ! Seat.GameStarted(players map {
            _.name
          })
        }
        new Game(gameID, deck, players, seats, settings).firstHand()
      } else {
        awaitingPlayers(players, newReady, openSeats)
      }
    case FindSeat(seatID, replyTo) =>
      val seat = seats(seatID)//TODO: handle missing better
      replyTo ! seat
      Behaviors.same
  }
}

class Game(gameID: UUID, deck: List[Card], players: List[Player], seats: Map[UUID, ActorRef[Seat.Action]], settings: GameSettings) {
  import Game._

  def withSeatListener(onMessage: PartialFunction[(ActorContext[Game.Message],Game.Message), Behavior[Game.Message]]): Behavior[Game.Message] =
    Behaviors.receivePartial {
      ({
        case (_, FindSeat(seatID, replyTo)) =>
          val seat = seats(seatID) //TODO: handle missing better
          replyTo ! seat
          Behaviors.same
      }: PartialFunction[(ActorContext[Game.Message],Game.Message), Behavior[Game.Message]]).orElse(onMessage)
    }

  val calculatePassTo: Int => List[Player] = hand => {
    val sequence = hand % (players.size - 1)
    val passType = if (sequence == 0) {
      players.size - 1
    } else {
      sequence
    }
    players.drop(passType) ++ players.take(passType)
  }

  def dealAndPlay(hand: Int)(context: ActorContext[Game.Message]): Behavior[Game.Message] = {
    context.log.info("Dealing hand {}", hand)
    val passList = calculatePassTo(hand)
    val replyTo = context.spawnAnonymous(Hand(players, settings, context.self))
    Random.shuffle(deck).grouped(settings.handSize).toList zip players zip passList foreach {
      case ((cards, player), passTo) =>
        player.actor ! Seat.ReceiveDeal(cards, replyTo, passTo, settings.passCount)
    }
    if (hand >= 3)
      lastHand()
    else
      playing(hand)
  }

  def firstHand(): Behavior[Game.Message] = Behaviors.setup {
    dealAndPlay(1)
  }

  def playing(hand: Int): Behavior[Game.Message] = withSeatListener {
    case (context, DealHand()) => dealAndPlay(hand + 1)(context)
  }

  def lastHand(): Behavior[Game.Message] = withSeatListener {
    case (context, DealHand()) =>
      context.log.info("Thanks for playing!")
      Behaviors.stopped
  }
}