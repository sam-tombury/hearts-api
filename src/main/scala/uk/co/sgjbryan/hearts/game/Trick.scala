package uk.co.sgjbryan.hearts.game

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import uk.co.sgjbryan.hearts.deck.Card

object Trick {

  final case class Play(card: Card)

  def calculateWinner(
      players: List[Player],
      trick: List[Trick.Play]
  ): Player = {
    val leadingSuit = trick.head.card.suit
    (players zip trick filter { _._2.card.suit == leadingSuit } maxBy {
      _._2.card.value
    })._1
  }

  def apply(
      hand: ActorRef[Hand.TrickComplete],
      players: List[Player]
  ): Behavior[Play] = new Trick(hand, players).playing()

}

class Trick private (
    hand: ActorRef[Hand.TrickComplete],
    players: List[Player]
) {
  import Trick._
  def playing(trick: List[Play] = List()): Behavior[Play] = Behaviors.receive {
    case (context, Play(card)) =>
      val newTrick = trick :+ Play(card)
      val nextPlayer = (players drop newTrick.size).headOption
      players map { _.actor } foreach {
        _ ! Seat.CardPlayed(card, nextPlayer map { _.name })
      }
      nextPlayer foreach { player =>
        player.actor ! Seat.RequestPlay(context.self, newTrick.head.card.suit)
      }
      if (newTrick.size == players.size) {
        val winner = calculateWinner(players, newTrick)
        hand ! Hand.TrickComplete(winner, newTrick map { _.card })
        Behaviors.stopped
      } else {
        playing(newTrick)
      }
  }
}
