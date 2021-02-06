package uk.co.sgjbryan.hearts.deck

sealed abstract class CardValue(val order: Int) extends Ordered[CardValue] {
  val displayName = order.toString
  override def toString: String = displayName
  def compare(that: CardValue): Int = order.compareTo(that.order)
  val shortName: String = displayName
}
sealed abstract class FaceValue(
    override val displayName: String,
    override val order: Int
) extends CardValue(order) {
  override val shortName: String = displayName.head.toString
}
case object Two extends CardValue(2)
case object Three extends CardValue(3)
case object Four extends CardValue(4)
case object Five extends CardValue(5)
case object Six extends CardValue(6)
case object Seven extends CardValue(7)
case object Eight extends CardValue(8)
case object Nine extends CardValue(9)
case object Ten extends CardValue(10)
case object Jack extends FaceValue("Jack", 11)
case object Queen extends FaceValue("Queen", 12)
case object King extends FaceValue("King", 13)
case object Ace extends FaceValue("Ace", 14)

case class Card(value: CardValue, suit: Suit) {
  override def toString: String = s"$value of $suit"
}
