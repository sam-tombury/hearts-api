package uk.co.sgjbryan.hearts

import uk.co.sgjbryan.hearts.utils.{
  Ace,
  Card,
  Deck,
  Hearts,
  King,
  Queen,
  Spades
}

case class GameSettings(playerCount: Int, deck: List[Card], passCount: Int) {

  require(deck.size % playerCount == 0) //Ensure fair hands
  require(deck.size / playerCount >= passCount) //Ensure pass is playable

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

object GameSettings {

  val threePlayer = GameSettings(3, Deck.specialised, 4)
  val fourPlayer = GameSettings(4, Deck.standard, 3)

}
