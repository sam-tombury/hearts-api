package uk.co.sgjbryan.hearts.utils

import java.net.URL
import java.util.UUID

import uk.co.sgjbryan.hearts.deck._

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{
  DefaultJsonProtocol,
  JsNumber,
  JsObject,
  JsString,
  JsValue,
  JsonFormat,
  RootJsonFormat,
  deserializationError
}
case class SeatResponse(name: String, seatID: UUID, gameID: UUID)

case class GameEvent(eventType: String, data: JsValue)

case class Points(player: GamePlayer, pointsTaken: Int)

case class GameCreationResponse(
    gameID: UUID,
    seatIDs: List[UUID],
    seatHrefs: List[URL]
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

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object CardValueFormat extends JsonFormat[CardValue] {
    def write(cv: CardValue) = JsObject(
      "name" -> JsString(cv.displayName),
      "shortName" -> JsString(cv.shortName),
      "order" -> JsNumber(cv.order)
    )
    def read(json: JsValue): CardValue = json match {
      case JsString(string) =>
        string.toLowerCase match {
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
          case _       => deserializationError("Card value expected")
        }
      case _ => deserializationError("String expected")
    }
  }
  implicit object SuitFormat extends JsonFormat[Suit] {
    def write(suit: Suit) = JsObject(
      "name" -> JsString(suit.displayName),
      "icon" -> JsString(suit.icon),
      "colour" -> JsString(suit.colour.toString),
      "order" -> JsNumber(suit.order)
    )
    def read(json: JsValue): Suit = json match {
      case JsString(string) =>
        string.toLowerCase match {
          case "hearts"   => Hearts
          case "clubs"    => Clubs
          case "diamonds" => Diamonds
          case "spades"   => Spades
          case _          => deserializationError("Suit expected")
        }
      case _ => deserializationError("String expected")
    }
  }
  implicit val cardFormat: RootJsonFormat[Card] = jsonFormat2(Card)
  implicit object UUIDFormat extends JsonFormat[UUID] {
    def write(uuid: UUID) = JsString(uuid.toString)
    def read(json: JsValue): UUID = json match {
      case JsString(string) => UUID.fromString(string)
      case _                => deserializationError("String expected")
    }
  }
  implicit object URLFormat extends JsonFormat[URL] {
    def write(url: URL) = JsString(url.toString)
    def read(json: JsValue): URL = json match {
      case JsString(string) => new URL(string)
      case _                => deserializationError("String expected")
    }
  }
  implicit val seatResponseFormat: RootJsonFormat[SeatResponse] = jsonFormat3(
    SeatResponse
  )
  implicit val gameEventFormat: RootJsonFormat[GameEvent] = jsonFormat2(
    GameEvent
  )
  implicit val gameCreationFormat: RootJsonFormat[GameCreationResponse] =
    jsonFormat3(GameCreationResponse)
  implicit val dealFormat: RootJsonFormat[Deal] = jsonFormat4(Deal)
  implicit val cardPlayedFormat: RootJsonFormat[CardPlayed] = jsonFormat2(
    CardPlayed
  )
  implicit val gamePlayerFormat: RootJsonFormat[GamePlayer] = jsonFormat2(
    GamePlayer
  )
  implicit val trickWonFormat: RootJsonFormat[TrickWon] = jsonFormat3(TrickWon)
  implicit val pointsFormat: RootJsonFormat[Points] = jsonFormat2(Points)
}
