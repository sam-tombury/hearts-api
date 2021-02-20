package uk.co.sgjbryan.hearts

import uk.co.sgjbryan.hearts.deck.{Ace, Card, Deck, Hearts, King, Queen, Spades}

case class GameSettings(players: List[String], hands: Int) {

  val playerCount = players.size
  require(playerCount == 3 || playerCount == 4)
  require(hands > 0)

  val (deck, passCount) = playerCount match {
    case 4 => (Deck.standard, 3)
    case 3 => (Deck.specialised, 4)
  }

  val scoreValue: Card => Int = {
    case Card(_, Hearts)     => 1
    case Card(Queen, Spades) => 13
    case Card(King, Spades)  => 10
    case Card(Ace, Spades)   => 7
    case _                   => 0
  }

  val shootValue: Int = (deck map scoreValue).sum

  val handSize: Int = deck.size / playerCount

}
