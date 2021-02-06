package uk.co.sgjbryan.hearts.game

import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import uk.co.sgjbryan.hearts.deck.{Card, Suit}
import uk.co.sgjbryan.hearts.utils.GamePlayer

case class Player(name: String, id: UUID, actor: ActorRef[Seat.Action]) {
  lazy val toGamePlayer: GamePlayer = GamePlayer(name, id)
}

object Seat {

  sealed trait Action
  final case class ReceiveDeal(
      hand: List[Card],
      replyTo: ActorRef[Hand.Pass],
      passTo: Player,
      firstLead: Player,
      passCount: Int
  ) extends Action
  final case class RequestLead(trick: ActorRef[Trick.Play]) extends Action
  final case class RequestPlay(trick: ActorRef[Trick.Play], suit: Suit)
      extends Action
  final case class ChangeTurn(name: String) extends Action
  final case class Play(card: Card, replyTo: ActorRef[Response]) extends Action
  final case class Pass(cards: List[Card], replyTo: ActorRef[Response])
      extends Action
  final case class ReceivePass(cards: List[Card]) extends Action
  final case class AddListener(listener: ActorRef[Seat.Action]) extends Action
  final case class CardPlayed(card: Card, toPlay: Option[String]) extends Action
  final case class TrickEnded(
      winner: Player,
      scoringCards: Set[Card],
      pointsTaken: Int
  ) extends Action
  final case class HandEnded(points: List[(Player, Int)]) extends Action
  final case class GameStarted(players: List[Player]) extends Action

  sealed trait Response
  final case class Ok() extends Response
  final case class InvalidPlay(reason: String) extends Response
  final case class InvalidPass(reason: String) extends Response

  def apply(seatID: UUID, game: ActorRef[Game.Message]): Behavior[Action] =
    Behaviors.receiveMessagePartial { case AddListener(listener) =>
      game ! Game.PlayerReady(seatID)
      new Seat(listener)
        .waitingForDeal() //TODO: make it possible to replace the listener in case of dropped connection (with seat secrets)
    }

}

class Seat private (listener: ActorRef[Seat.Action]) {

  import Seat._

  def waitingForDeal(): Behavior[Action] = Behaviors.monitor(
    listener,
    Behaviors.receiveMessagePartial {
      case ReceiveDeal(hand, replyTo, passTo, _, passCount: Int) =>
        passing(hand, replyTo, passTo, passCount)
    }
  )

  def passing(
      hand: List[Card],
      replyTo: ActorRef[Hand.Pass],
      passTo: Player,
      passCount: Int
  ): Behavior[Action] = Behaviors.receiveMessagePartial {
    case Pass(cards, passResponseTo) =>
      if (cards.size != passCount) {
        passResponseTo ! InvalidPass(
          s"Incorrect number of cards, expected $passCount"
        )
        Behaviors.same
      } else if (cards exists { card => !(hand contains card) }) {
        passResponseTo ! InvalidPass("A passed card is not in hand")
        Behaviors.same
      } else if (cards.distinct.size != cards.size) {
        passResponseTo ! InvalidPass("Distinct cards are required")
        Behaviors.same
      } else {
        passResponseTo ! Ok()
        replyTo ! Hand.Pass(cards, passTo)
        awaitingPass(hand filterNot {
          cards.contains
        })
      }
  }

  def awaitingPass(hand: List[Card]): Behavior[Action] =
    Behaviors.receiveMessagePartial { case ReceivePass(cards) =>
      waiting(hand ++ cards)
    }

  def waiting(hand: List[Card]): Behavior[Action] =
    Behaviors.receiveMessagePartial {
      case RequestLead(trick)       => playing(trick, hand, None)
      case RequestPlay(trick, suit) => playing(trick, hand, Some(suit))
      case Play(_, replyTo) =>
        replyTo ! InvalidPlay("Not your turn")
        Behaviors.same
      case TrickEnded(_, _, _) | CardPlayed(_, _) => Behaviors.same
    }

  def playing(
      trick: ActorRef[Trick.Play],
      hand: List[Card],
      lead: Option[Suit]
  ): Behavior[Action] = Behaviors.receiveMessagePartial {
    case Play(card, replyTo) =>
      if (!(hand contains card)) {
        replyTo ! InvalidPlay("You don't have that card in your card")
        Behaviors.same
      } else if (
        lead exists { leadSuit =>
          card.suit != leadSuit && hand.exists(_.suit == leadSuit)
        }
      ) {
        replyTo ! InvalidPlay("You can't play that card, check the lead suit")
        Behaviors.same
      } else {
        trick ! Trick.Play(card)
        replyTo ! Ok()
        val newHand = hand filter { _ != card }
        if (newHand.isEmpty) {
          waitingForDeal()
        } else {
          waiting(newHand)
        }
      }
  }

}
