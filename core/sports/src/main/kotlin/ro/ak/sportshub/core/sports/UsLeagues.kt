package ro.ak.sportshub.core.sports

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Ligile americane (furnizori opționali, JSON public): posturile exacte pentru SUA și Canada.
 * - ESPN (`site.api.espn.com`, neoficial): NFL, NBA, NHL, MLB și MLS, cu posturile naționale din SUA.
 * - NHL (`api-web.nhle.com`): și posturile din Canada (Sportsnet, TVA Sports, CBC).
 * - MLB (`statsapi.mlb.com`): posturile TV naționale și locale.
 * O cerere pe ligă și zi (azi și mâine), la 3 ore, doar dacă ai canale din SUA sau Canada; răspunsurile se
 * păstrează în fișiere. Echipele au numele complete („Boston Celtics”), ca la TheSportsDB, deci meciurile se unesc.
 */
object UsLeagues {

    /** O ligă ESPN: calea din API, sportul și numele competiției (cel din TheSportsDB, pentru reguli și unire). */
    enum class League(val path: String, val sport: Sport, val competition: String) {
        NFL("football/nfl", Sport.AMERICAN_FOOTBALL, "NFL"),
        NBA("basketball/nba", Sport.BASKETBALL, "NBA"),
        NHL("hockey/nhl", Sport.ICE_HOCKEY, "NHL"),
        MLB("baseball/mlb", Sport.BASEBALL, "MLB"),
        MLS("soccer/usa.1", Sport.FOOTBALL, "American Major League Soccer"),
    }

    const val ESPN = "espn"
    const val NHL_API = "nhl"
    const val MLB_API = "mlb"

    fun espnUrl(league: League, day: LocalDate): String =
        "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=" + day.toString().replace("-", "")

    fun nhlUrl(day: LocalDate): String = "https://api-web.nhle.com/v1/schedule/$day"

    fun mlbUrl(day: LocalDate): String = "https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=$day&hydrate=broadcasts(all)"

    /** Ligile de cerut, după sporturile alese în setări. */
    fun leaguesFor(sports: Collection<Sport>): List<League> = League.entries.filter { it.sport in sports }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- ESPN ----------

    fun parseEspn(body: String, league: League): List<SportEvent> {
        val root = parse(body) ?: return emptyList()
        return root.arr("events").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.text("id") ?: return@mapNotNull null
            val comp = o.arr("competitions").firstOrNull() as? JsonObject ?: return@mapNotNull null
            val teams = comp.arr("competitors").filterIsInstance<JsonObject>()
            val home = teams.firstOrNull { it.text("homeAway") == "home" } ?: return@mapNotNull null
            val away = teams.firstOrNull { it.text("homeAway") == "away" } ?: return@mapNotNull null
            val start = (o.text("date") ?: comp.text("date"))?.let(::isoMillis) ?: return@mapNotNull null
            val state = o.obj("status")?.obj("type")?.text("state") ?: comp.obj("status")?.obj("type")?.text("state")
            val status = when (state) {
                "pre" -> MatchStatus.SCHEDULED
                "in" -> MatchStatus.LIVE
                "post" -> MatchStatus.FINISHED
                else -> null
            }
            if (o.obj("status")?.obj("type")?.text("name")?.contains("POSTPONED") == true) return@mapNotNull null
            val names = LinkedHashSet<String>()
            comp.arr("broadcasts").filterIsInstance<JsonObject>().forEach { b -> b.arr("names").forEach { n -> n.str()?.let(names::add) } }
            comp.arr("geoBroadcasts").filterIsInstance<JsonObject>().forEach { g ->
                val type = g.obj("type")?.text("shortName")
                if (type == null || type.equals("TV", ignoreCase = true)) g.obj("media")?.let { m -> (m.text("shortName") ?: m.text("name"))?.let(names::add) }
            }
            SportEvent(
                provider = ESPN,
                providerId = "${league.name}-$id",
                sport = league.sport.sportsDbName,
                competition = league.competition,
                home = home.obj("team")?.let { it.text("displayName") ?: it.text("name") } ?: return@mapNotNull null,
                away = away.obj("team")?.let { it.text("displayName") ?: it.text("name") } ?: return@mapNotNull null,
                start = start,
                status = status,
                homeScore = home.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
                awayScore = away.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
                venue = comp.obj("venue")?.text("fullName"),
                stations = names.map { TvStation(it, "USA") },
            )
        }
    }

    // ---------- NHL ----------

    fun parseNhl(body: String, day: LocalDate? = null): List<SportEvent> {
        val root = parse(body) ?: return emptyList()
        val games = root.arr("gameWeek").filterIsInstance<JsonObject>()
            .filter { d -> day == null || d.text("date") == day.toString() }
            .flatMap { it.arr("games") } + root.arr("games")
        return games.filterIsInstance<JsonObject>().mapNotNull { g ->
            val id = g.text("id") ?: return@mapNotNull null
            val start = g.text("startTimeUTC")?.let(::isoMillis) ?: return@mapNotNull null
            val state = g.text("gameState")
            val status = when (state) {
                "FUT", "PRE" -> MatchStatus.SCHEDULED
                "LIVE", "CRIT" -> MatchStatus.LIVE
                "OFF", "FINAL" -> MatchStatus.FINISHED
                else -> null
            }
            if (g.text("gameScheduleState")?.let { it != "OK" } == true) return@mapNotNull null
            val stations = g.arr("tvBroadcasts").filterIsInstance<JsonObject>().mapNotNull { b ->
                val network = b.text("network") ?: return@mapNotNull null
                TvStation(network, when (b.text("countryCode")) { "CA" -> "Canada"; "US" -> "USA"; else -> null })
            }.distinct()
            SportEvent(
                provider = NHL_API,
                providerId = id,
                sport = Sport.ICE_HOCKEY.sportsDbName,
                competition = "NHL",
                home = nhlTeam(g.obj("homeTeam")) ?: return@mapNotNull null,
                away = nhlTeam(g.obj("awayTeam")) ?: return@mapNotNull null,
                start = start,
                status = status,
                homeScore = g.obj("homeTeam")?.int("score").takeIf { status != MatchStatus.SCHEDULED },
                awayScore = g.obj("awayTeam")?.int("score").takeIf { status != MatchStatus.SCHEDULED },
                venue = g.obj("venue")?.text("default"),
                stations = stations,
            )
        }
    }

    /** „Boston” + „Bruins” → „Boston Bruins” (cu `name` complet, dacă există). */
    private fun nhlTeam(t: JsonObject?): String? {
        if (t == null) return null
        t.obj("name")?.text("default")?.let { return it }
        val place = t.obj("placeName")?.text("default")
        val common = t.obj("commonName")?.text("default")
        return listOfNotNull(place, common).joinToString(" ").ifBlank { null }
    }

    // ---------- MLB ----------

    fun parseMlb(body: String): List<SportEvent> {
        val root = parse(body) ?: return emptyList()
        return root.arr("dates").filterIsInstance<JsonObject>().flatMap { it.arr("games") }.filterIsInstance<JsonObject>().mapNotNull { g ->
            val id = g.text("gamePk") ?: return@mapNotNull null
            val start = g.text("gameDate")?.let(::isoMillis) ?: return@mapNotNull null
            val st = g.obj("status")
            val status = when (st?.text("abstractGameState")) {
                "Preview" -> MatchStatus.SCHEDULED
                "Live" -> MatchStatus.LIVE
                "Final" -> MatchStatus.FINISHED
                else -> null
            }
            if (st?.text("detailedState")?.contains("Postponed") == true) return@mapNotNull null
            val teams = g.obj("teams")
            val stations = g.arr("broadcasts").filterIsInstance<JsonObject>()
                .filter { b -> b.text("type")?.equals("TV", ignoreCase = true) != false }
                .mapNotNull { b -> b.text("name")?.let { TvStation(it, if (b.text("name")?.contains("Sportsnet") == true) "Canada" else "USA") } }
                .distinct()
            SportEvent(
                provider = MLB_API,
                providerId = id,
                sport = Sport.BASEBALL.sportsDbName,
                competition = "MLB",
                home = teams?.obj("home")?.obj("team")?.text("name") ?: return@mapNotNull null,
                away = teams?.obj("away")?.obj("team")?.text("name") ?: return@mapNotNull null,
                start = start,
                status = status,
                homeScore = teams?.obj("home")?.int("score").takeIf { status != MatchStatus.SCHEDULED },
                awayScore = teams?.obj("away")?.int("score").takeIf { status != MatchStatus.SCHEDULED },
                venue = g.obj("venue")?.text("name"),
                stations = stations,
            )
        }
    }

    // ---------- JSON ----------

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
