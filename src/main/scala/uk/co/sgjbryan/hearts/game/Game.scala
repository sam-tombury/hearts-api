package uk.co.sgjbryan.hearts.game

import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import uk.co.sgjbryan.hearts.GameSettings
import uk.co.sgjbryan.hearts.deck.Card
import uk.co.sgjbryan.hearts.utils.{CreatedSeat, GameCreationResponse}

import scala.util.Random
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Game {

  sealed trait Message
  final case class DealHand() extends Message
  final case class TakeSeat(
      name: String,
      seatID: UUID,
      replyTo: ActorRef[Game.SeatResponse]
  ) extends Message
  final case class PlayerReady(seatID: UUID) extends Message
  final case class FindSeat(
      seatID: UUID,
      replyTo: ActorRef[Option[ActorRef[Seat.Action]]]
  ) extends Message

  sealed trait SeatResponse
  final case class UserAdded(player: Player) extends SeatResponse
  final case object SeatNotFound extends SeatResponse

  def apply(
      gameID: UUID,
      settings: GameSettings,
      replyTo: ActorRef[GameCreationResponse]
  ): Behavior[Message] = Behaviors.setup { context =>
    context.log.info("Game {} is awaiting players", gameID)
    val seats = for {
      name <- settings.players
      seatID = UUID.randomUUID()
      actor = context.spawnAnonymous(Seat(seatID, context.self))
    } yield (name, seatID, actor)
    replyTo ! GameCreationResponse(
      gameID,
      seats map { case (name, seatID, _) =>
        CreatedSeat(
          name,
          seatID,
          s"/play/game/$gameID/seat/$seatID?name=${URLEncoder
            .encode(name, StandardCharsets.UTF_8)}"
        )
      }
    )
    new ScheduledGame(
      gameID,
      settings.deck,
      (seats.map { case (_, seatID, actor) =>
        (seatID, actor)
      }).toMap,
      settings
    ).awaitingPlayers()
  }
}

class ScheduledGame(
    gameID: UUID,
    deck: List[Card],
    seats: Map[UUID, ActorRef[Seat.Action]],
    settings: GameSettings
) {
  import Game._

  def awaitingPlayers(
      players: List[Player] = List(),
      readySeats: Set[UUID] = Set(),
      openSeats: Map[UUID, ActorRef[Seat.Action]] = seats
  ): Behavior[Game.Message] = Behaviors.receiveMessagePartial {
    case TakeSeat(name, seatID, replyTo) =>
      openSeats.get(seatID) map { seat =>
        val player = Player(name, seatID, seat)
        replyTo ! UserAdded(player)
        awaitingPlayers(
          players :+ player,
          readySeats,
          openSeats.removed(seatID)
        )
      } getOrElse {
        replyTo ! SeatNotFound
        Behaviors.same
      }
    case PlayerReady(seatID) =>
      val newReady = readySeats + seatID
      if (openSeats.isEmpty && newReady.size == players.size) {
        players map {
          _.actor
        } foreach {
          _ ! Seat.GameStarted(players)
        }
        new Game(gameID, deck, players, seats, settings).firstHand()
      } else {
        awaitingPlayers(players, newReady, openSeats)
      }
    case FindSeat(seatID, replyTo) =>
      replyTo ! seats.get(seatID)
      Behaviors.same
  }
}

class Game(
    gameID: UUID,
    deck: List[Card],
    players: List[Player],
    seats: Map[UUID, ActorRef[Seat.Action]],
    settings: GameSettings
) {
  import Game._

  def withSeatListener(
      onMessage: PartialFunction[
        (ActorContext[Game.Message], Game.Message),
        Behavior[Game.Message]
      ]
  ): Behavior[Game.Message] =
    Behaviors.receivePartial {
      ({ case (_, FindSeat(seatID, replyTo)) =>
        replyTo ! seats.get(seatID)
        Behaviors.same
      }: PartialFunction[(ActorContext[Game.Message], Game.Message), Behavior[
        Game.Message
      ]]).orElse(onMessage)
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

  val calculateFirstLead: Int => List[Player] = hand => {
    val sequence = hand % players.size
    players.drop(sequence) ++ players.take(sequence)
  }

  def dealAndPlay(
      hand: Int
  )(context: ActorContext[Game.Message]): Behavior[Game.Message] = {
    context.log.info("Dealing hand {}", hand)
    val passList = calculatePassTo(hand)
    val orderedPlayers = calculateFirstLead(hand)
    val firstLead = orderedPlayers.head
    val replyTo =
      context.spawnAnonymous(Hand(orderedPlayers, settings, context.self))
    Random
      .shuffle(deck)
      .grouped(settings.handSize)
      .toList zip players zip passList foreach {
      case ((cards, player), passTo) =>
        player.actor ! Seat.ReceiveDeal(
          cards,
          replyTo,
          passTo,
          firstLead,
          settings.passCount
        )
    }
    if (hand >= settings.hands)
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
      context.log.info("Game {} is completed", gameID)
      Behaviors.stopped
  }
}
