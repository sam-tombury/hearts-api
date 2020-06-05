package uk.co.sgjbryan.hearts.game

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import uk.co.sgjbryan.hearts.GameSettings
import uk.co.sgjbryan.hearts.utils.Card

object Hand {

  sealed trait Message
  final case class TrickComplete(winner: Player, cards: List[Card]) extends Message
  final case class Pass(cards: List[Card], target: Player) extends Message

  def winnerFirst(players: List[Player], winner: Player): List[Player] = {
    players.dropWhile(_ != winner) ++ players.takeWhile(_ != winner)
  }

  def apply(players: List[Player], settings: GameSettings, dealer: ActorRef[Game.DealHand]): Behavior[Message] =
    new Hand(players, dealer, settings).passing()

}

class Hand private (players: List[Player], dealer: ActorRef[Game.DealHand], settings: GameSettings) {
  import Hand._
  private val totalTricks: Int = settings.handSize

  def calculatePoints(cardsTaken: Map[Player, List[Card]]): Map[Player, Int] = {
    val pointsTaken = cardsTaken.view.mapValues(cards => (cards map settings.scoreValue).sum).toMap
    if (pointsTaken exists {case (_, score) => score == settings.shootValue}) {
      pointsTaken.view.mapValues(score => if (score == settings.shootValue) 0 else settings.shootValue).toMap
    } else {
      pointsTaken
    }
  }

  def passing(passes: List[(Player, List[Card])] = List()): Behavior[Message] = Behaviors.receivePartial {
    case (context, Pass(cards, player)) =>
      val newPasses = passes :+ (player, cards)
      if (newPasses.size == players.size) {
        newPasses foreach {
          case (passTo, pass) => passTo.actor ! Seat.ReceivePass(pass)
        }
        val trick = context.spawnAnonymous(Trick(context.self, players))
        players.head.actor ! Seat.RequestLead(trick)
        playing(players, totalTricks - 1)
      } else {
        passing(newPasses)
      }
  }


  def playing(order: List[Player], tricksRemaining: Int, cardsTaken: Map[Player, List[Card]] = Map()): Behavior[Message] = Behaviors.receivePartial {
    case (context, TrickComplete(winner, cards)) =>
      val newCardsTaken = cardsTaken.updatedWith(winner)(cardsWon => Some(cardsWon.getOrElse(List()) ++ cards))
      if (tricksRemaining > 0) {
        val newOrder = winnerFirst(players, winner)
        val trick = context.spawnAnonymous(Trick(context.self, newOrder))
        newOrder.head.actor ! Seat.RequestLead(trick)
        playing(newOrder, tricksRemaining - 1, newCardsTaken)
      } else {
        val points = calculatePoints(newCardsTaken).toList
        players map {_.actor} foreach {_ ! Seat.HandEnded(points)}
        dealer ! Game.DealHand()
        Behaviors.stopped
      }
  }


}