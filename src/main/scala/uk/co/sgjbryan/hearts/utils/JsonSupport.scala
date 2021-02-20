package uk.co.sgjbryan.hearts.utils

import java.util.UUID

import uk.co.sgjbryan.hearts.deck._
import io.circe.{Decoder, Encoder, Json}
import scala.util.Try

case class SeatResponse(name: String, seatID: UUID, gameID: UUID)

case class CirceGameEvent(eventType: String, data: Json)

case class Points(player: GamePlayer, pointsTaken: Int)

case class CreatedSeat(name: String, seatID: UUID, href: String)

case class GameCreationResponse(
    gameID: UUID,
    seats: List[CreatedSeat]
)

case class Deal(
    hand: List[Card],
    passCount: Int,
    passTo: String,
    firstLead: String
)

case class TrickWon(
    winner: GamePlayer,
    scoringCards: Set[Card],
    pointsTaken: Int
)

case class CardPlayed(card: Card, toPlay: Option[String])

case class GamePlayer(name: String, id: UUID)

object JsonSupport {

  implicit val encodeCardValue: Encoder[CardValue] =
    Encoder.forProduct3("name", "shortName", "order")(value =>
      (value.displayName, value.shortName, value.order)
    )
  implicit val decodeCardValue: Decoder[CardValue] =
    Decoder.decodeString.emapTry { str =>
      Try {
        str.toLowerCase match {
          case "2"     => Two
          case "3"     => Three
          case "4"     => Four
          case "5"     => Five
          case "6"     => Six
          case "7"     => Seven
          case "8"     => Eight
          case "9"     => Nine
          case "10"    => Ten
          case "jack"  => Jack
          case "queen" => Queen
          case "king"  => King
          case "ace"   => Ace
        }
      }
    }

  implicit val encodeSuit: Encoder[Suit] =
    Encoder.forProduct4("name", "icon", "colour", "order")(suit =>
      (
        suit.displayName,
        suit.icon,
        suit.colour.toString.toLowerCase,
        suit.order
      )
    )
  implicit val decodeSuit: Decoder[Suit] = Decoder.decodeString.emapTry { str =>
    Try {
      str.toLowerCase match {
        case "hearts"   => Hearts
        case "clubs"    => Clubs
        case "diamonds" => Diamonds
        case "spades"   => Spades
      }
    }
  }
}
