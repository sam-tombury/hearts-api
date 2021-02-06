package uk.co.sgjbryan.hearts.deck

object Deck {
  val standard: List[Card] = for {
    value <- List(
      Two,
      Three,
      Four,
      Five,
      Six,
      Seven,
      Eight,
      Nine,
      Ten,
      Jack,
      Queen,
      King,
      Ace
    )
    suit <- List(Clubs, Spades, Hearts, Diamonds)
  } yield Card(value, suit)
  val specialised: List[Card] = standard drop 1
}
