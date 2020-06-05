package uk.co.sgjbryan.hearts.utils

sealed abstract class CardValue(val displayName: String, val order: Int) extends Ordered[CardValue] {
  override def toString: String = displayName
  def compare(that: CardValue): Int = order.compareTo(that.order)
}
case object Two extends CardValue("2", 2)
case object Three extends CardValue("3", 3)
case object Four extends CardValue("4", 4)
case object Five extends CardValue("5", 5)
case object Six extends CardValue("6", 6)
case object Seven extends CardValue("7", 7)
case object Eight extends CardValue("8", 8)
case object Nine extends CardValue("9", 9)
case object Ten extends CardValue("10", 10)
case object Jack extends CardValue("Jack", 11)
case object Queen extends CardValue("Queen", 12)
case object King extends CardValue("King", 12)
case object Ace extends CardValue("Ace", 13)



sealed abstract class Suit(val displayName: String) {
  override def toString: String = displayName
}
case object Hearts extends Suit("Hearts")
case object Diamonds extends Suit("Diamonds")
case object Clubs extends Suit("Clubs")
case object Spades extends Suit("Spades")

case class Card(value: CardValue, suit: Suit) {
  override def toString: String = s"$value of $suit"
}

object Deck {
  val standard: List[Card] = for {
    value <- List(Two, Three, Four, Five, Six, Seven, Eight, Nine, Ten, Jack, Queen, King, Ace)
    suit <- List(Clubs, Spades, Hearts, Diamonds)
  } yield Card(value, suit)
  val specialised: List[Card] = standard drop 1
}