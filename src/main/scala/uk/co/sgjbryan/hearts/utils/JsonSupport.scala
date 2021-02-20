package uk.co.sgjbryan.hearts.utils

import java.util.UUID

import uk.co.sgjbryan.hearts.deck._
import io.circe.Json

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
