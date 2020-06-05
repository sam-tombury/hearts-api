package uk.co.sgjbryan.hearts.utils

import java.net.URL
import java.util.UUID

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}
case class SeatResponse(name: String, seatID: UUID, gameID: UUID)

case class GameEvent(eventType: String, data: JsValue)

case class Points(player: String, pointsTaken: Int)

case class GameCreationResponse(gameID: UUID, seatIDs: List[UUID], seatHrefs: List[URL])

case class Deal(hand: List[Card], passCount: Int)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object CardValueFormat extends JsonFormat[CardValue] {
    def write(cv: CardValue) = JsString(cv.displayName)
    def read(json: JsValue): CardValue = json match {
      case JsString(string) => string.toLowerCase match {
        case "2" => Two
        case "3" => Three
        case "4" => Four
        case "5" => Five
        case "6" => Six
        case "7" => Seven
        case "8" => Eight
        case "9" => Nine
        case "10" => Ten
        case "jack" => Jack
        case "queen" => Queen
        case "king" => King
        case "ace" => Ace
        case _ => deserializationError("Card value expected")
      }
      case _ => deserializationError("String expected")
    }
  }
  implicit object SuitFormat extends JsonFormat[Suit] {
    def write(suit: Suit) = JsString(suit.displayName)
    def read(json: JsValue): Suit = json match {
      case JsString(string) => string.toLowerCase match {
        case "hearts" => Hearts
        case "clubs" => Clubs
        case "diamonds" => Diamonds
        case "spades" => Spades
        case _ => deserializationError("Suit expected")
      }
      case _ => deserializationError("String expected")
    }
  }
  implicit val cardFormat: RootJsonFormat[Card] = jsonFormat2(Card)
  implicit val pointsFormat: RootJsonFormat[Points] = jsonFormat2(Points)
  implicit object UUIDFormat extends JsonFormat[UUID] {
    def write(uuid: UUID) = JsString(uuid.toString)
    def read(json: JsValue): UUID = json match {
      case JsString(string) => UUID.fromString(string)
      case _ => deserializationError("String expected")
    }
  }
  implicit object URLFormat extends JsonFormat[URL] {
    def write(url: URL) = JsString(url.toString)
    def read(json: JsValue): URL = json match {
      case JsString(string) => new URL(string)
      case _ => deserializationError("String expected")
    }
  }
  implicit val seatResponseFormat: RootJsonFormat[SeatResponse] = jsonFormat3(SeatResponse)
  implicit val gameEventFormat: RootJsonFormat[GameEvent] = jsonFormat2(GameEvent)
  implicit val gameCreationFormat: RootJsonFormat[GameCreationResponse] = jsonFormat3(GameCreationResponse)
  implicit val dealFormat: RootJsonFormat[Deal] = jsonFormat2(Deal)
}
