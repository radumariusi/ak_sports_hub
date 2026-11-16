package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenLigaDB (`api.openligadb.de`, JSON public, fără cheie): programul complet al sezonului pentru câteva competiții
 * pe care ESPN nu le are sau le are neverificate: Frauen-Bundesliga, 3. Liga, Liga Campionilor feminină, hocheiul
 * german (DEL) și Champions Hockey League. O cerere pe competiție dă tot sezonul (`/getmatchdata/{liga}/{sezon}`),
 * cerută la 12 ore. Câmpurile (`matchID`, `matchDateTimeUTC`, `team1/2.teamName`, `matchIsFinished`, `matchResults`,
 * `goals`) au fost văzute pe răspunsuri reale (16.11.2026, sezonul 2026/27).
 */
object OpenLigaDb {

    const val PROVIDER = "openligadb"
    private const val BASE = "https://api.openligadb.de"

    /** [forFavorites]: se cere doar cu echipe favorite (filtrul de competiții oricum le păstrează doar pentru ele). */
    data class League(val shortcut: String, val competition: String, val sport: Sport, val forFavorites: Boolean = false)

    val leagues = listOf(
        League("ffb1", "German Frauen-Bundesliga", Sport.FOOTBALL, forFavorites = true),
        League("bl3", "German 3. Liga", Sport.FOOTBALL, forFavorites = true),
        League("WCL", "UEFA Women's Champions League", Sport.FOOTBALL, forFavorites = true),
        League("del", "DEL", Sport.ICE_HOCKEY),
        League("CHL", "Champions Hockey League", Sport.ICE_HOCKEY),
    )

    fun league(shortcut: String): League? = leagues.firstOrNull { it.shortcut.equals(shortcut, ignoreCase = true) }

    /** Sezonul care cuprinde ziua [day]: începe în iulie (2026 = 2026/27). */
    fun seasonYear(day: LocalDate): Int = if (day.monthValue >= 7) day.year else day.year - 1

    fun url(l: League, day: LocalDate): String = "$BASE/getmatchdata/${l.shortcut}/${seasonYear(day)}"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Meciurile sezonului; un răspuns stricat dă o listă goală. */
    fun parse(body: String, l: League): List<SportEvent> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.filterIsInstance<JsonObject>().mapNotNull { m ->
            val id = m.text("matchID") ?: return@mapNotNull null
            val start = (m.text("matchDateTimeUTC") ?: return@mapNotNull null).let(::utcMillis) ?: return@mapNotNull null
            val home = (m["team1"] as? JsonObject)?.text("teamName") ?: return@mapNotNull null
            val away = (m["team2"] as? JsonObject)?.text("teamName") ?: return@mapNotNull null
            val finished = m.text("matchIsFinished") == "true"
            val result = if (finished) finalResult(m) else null
            SportEvent(
                provider = PROVIDER,
                providerId = "${l.shortcut}-$id",
                sport = l.sport.sportsDbName,
                competition = l.competition,
                home = home,
                away = away,
                start = start,
                status = if (finished) MatchStatus.FINISHED else null,
                homeScore = result?.first,
                awayScore = result?.second,
                venue = (m["location"] as? JsonObject)?.text("locationStadium"),
                homeAlt = listOfNotNull((m["team1"] as? JsonObject)?.text("shortName")?.takeIf { it != home }),
                awayAlt = listOfNotNull((m["team2"] as? JsonObject)?.text("shortName")?.takeIf { it != away }),
            )
        }
    }

    /** Scorul final: rezultatul „Endergebnis” (`resultTypeID` 2), altfel cel cu ordinea cea mai mare, altfel ultimul gol. */
    private fun finalResult(m: JsonObject): Pair<Int, Int>? {
        val results = (m["matchResults"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val r = results.firstOrNull { it.text("resultTypeID") == "2" }
            ?: results.maxByOrNull { it.text("resultOrderID")?.toIntOrNull() ?: 0 }
        if (r != null) {
            val h = r.text("pointsTeam1")?.toIntOrNull()
            val a = r.text("pointsTeam2")?.toIntOrNull()
            if (h != null && a != null) return h to a
        }
        val last = (m["goals"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().lastOrNull() ?: return null
        val h = last.text("scoreTeam1")?.toIntOrNull() ?: return null
        val a = last.text("scoreTeam2")?.toIntOrNull() ?: return null
        return h to a
    }

    private fun utcMillis(s: String): Long? = runCatching { Instant.parse(if (s.endsWith("Z") || '+' in s.drop(10)) s else "${s}Z").toEpochMilli() }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
