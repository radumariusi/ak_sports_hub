package ro.ak.sportshub.core.sports

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Sofascore (furnizor opțional, API neoficial): programul și scorurile unei zile pentru un sport.
 * O cerere pe sport și zi, rar, cu răspunsul păstrat în fișier. Fără posturi TV (ar cere câte o cerere
 * pe meci): meciurile de aici primesc surse din EPG și din regulile de drepturi TV.
 */
object Sofascore {

    const val PROVIDER = "sofascore"
    private const val BASE = "https://api.sofascore.com/api/v1"

    private val slugs = mapOf(
        Sport.FOOTBALL to "football", Sport.BASKETBALL to "basketball", Sport.TENNIS to "tennis",
        Sport.ICE_HOCKEY to "ice-hockey", Sport.HANDBALL to "handball", Sport.VOLLEYBALL to "volleyball",
        Sport.RUGBY to "rugby", Sport.AMERICAN_FOOTBALL to "american-football", Sport.BASEBALL to "baseball",
        Sport.MOTORSPORT to "motorsport", Sport.FIGHTING to "mma", Sport.DARTS to "darts",
        Sport.SNOOKER to "snooker", Sport.CYCLING to "cycling", Sport.CRICKET to "cricket",
    )

    /** Null dacă Sofascore nu are sportul. */
    fun scheduleUrl(sport: Sport, day: LocalDate): String? = slugs[sport]?.let { "$BASE/sport/$it/scheduled-events/$day" }

    /** Categoriile care nu sunt țări: nu se pun în fața numelui competiției. */
    private val genericCategories = setOf("world", "europe", "international", "south america", "north america", "asia", "africa", "oceania")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, sport: Sport): List<SportEvent> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val home = o.obj("homeTeam")?.str("name") ?: return@mapNotNull null
            val away = o.obj("awayTeam")?.str("name") ?: return@mapNotNull null
            val startSec = (o["startTimestamp"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val type = o.obj("status")?.str("type")?.lowercase()
            val status = when (type) {
                "notstarted" -> MatchStatus.SCHEDULED
                "inprogress" -> MatchStatus.LIVE
                "finished" -> MatchStatus.FINISHED
                "canceled", "cancelled", "postponed", "interrupted", "suspended", "abandoned" -> return@mapNotNull null
                else -> null
            }
            val tournament = o.obj("tournament")
            val name = tournament?.obj("uniqueTournament")?.str("name") ?: tournament?.str("name")
            val category = tournament?.obj("category")?.str("name")
            val competition = when {
                name == null -> category
                category == null || Sport.fold(category) in genericCategories || Sport.fold(name).contains(Sport.fold(category)) -> name
                else -> "$category - $name"
            }
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = sport.sportsDbName,
                competition = competition,
                home = home,
                away = away,
                start = startSec * 1000,
                status = status,
                homeScore = o.obj("homeScore")?.int("current").takeIf { status != MatchStatus.SCHEDULED },
                awayScore = o.obj("awayScore")?.int("current").takeIf { status != MatchStatus.SCHEDULED },
                venue = o.obj("venue")?.str("name"),
            )
        }
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
