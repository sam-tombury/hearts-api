package uk.co.sgjbryan.hearts.deck

sealed trait SuitColour
case object Black extends SuitColour
case object Red extends SuitColour

sealed abstract class Suit(
    val displayName: String,
    val icon: String,
    val colour: SuitColour,
    val order: Int
) {
  override def toString: String = displayName
}
case object Clubs extends Suit("Clubs", "♣", Black, 1)
case object Diamonds extends Suit("Diamonds", "♦", Red, 2)
case object Spades extends Suit("Spades", "♠", Black, 3)
case object Hearts extends Suit("Hearts", "♥", Red, 4)
