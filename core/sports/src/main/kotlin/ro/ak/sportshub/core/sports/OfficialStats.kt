package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Statisticile oficiale ale ligilor americane, a doua sursă după ESPN (gratuite, fără cheie, JSON public):
 * - NBA: `cdn.nba.com` (programul zilei de azi, apoi `boxscore_{id}.json`: statisticile echipelor și jucătorii);
 * - NHL: `api-web.nhle.com` (programul zilei, apoi `gamecenter/{id}/right-rail`: șuturi, face-off, power play…);
 * - MLB: `statsapi.mlb.com` (programul zilei, apoi `game/{id}/boxscore`: lovituri, home run-uri, erori, ordinea la bătaie).
 * Formatul nu a putut fi verificat din mediul de lucru, deci citirea e tolerantă: ce lipsește nu apare.
 */
object OfficialStats {

    enum class League(val key: String) { NBA("nba"), NHL("nhl"), MLB("mlb") }

    /** Ziua meciului în calendarul ligilor (fusul SUA Est). */
    val US_EAST: ZoneId = ZoneId.of("America/New_York")

    fun leagueOf(competition: String?, sport: Sport?): League? {
        val c = competition?.trim()?.uppercase() ?: return null
        return when {
            (c == "NBA" || c.startsWith("NBA ")) && sport != Sport.ICE_HOCKEY && sport != Sport.BASEBALL -> League.NBA
            (c == "NHL" || c.startsWith("NHL ")) && (sport == null || sport == Sport.ICE_HOCKEY) -> League.NHL
            (c == "MLB" || c.startsWith("MLB ")) && (sport == null || sport == Sport.BASEBALL) -> League.MLB
            else -> null
        }
    }

    fun gameDay(start: Long): LocalDate = Instant.ofEpochMilli(start).atZone(US_EAST).toLocalDate()

    /** Programul zilei (NBA dă gratuit doar ziua de azi). */
    fun scheduleUrl(league: League, day: LocalDate): String = when (league) {
        League.NBA -> "https://cdn.nba.com/static/json/liveData/scoreboard/todaysScoreboard_00.json"
        League.NHL -> UsLeagues.nhlUrl(day)
        League.MLB -> "https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=$day"
    }

    fun parseSchedule(league: League, body: String, day: LocalDate?): List<SportEvent> = when (league) {
        League.NBA -> parseNbaScoreboard(body)
        League.NHL -> UsLeagues.parseNhl(body, day)
        League.MLB -> UsLeagues.parseMlb(body)
    }

    fun statsUrl(league: League, gameId: String): String = when (league) {
        League.NBA -> "https://cdn.nba.com/static/json/liveData/boxscore/boxscore_$gameId.json"
        League.NHL -> "https://api-web.nhle.com/v1/gamecenter/$gameId/right-rail"
        League.MLB -> "https://statsapi.mlb.com/api/v1/game/$gameId/boxscore"
    }

    /** Statisticile (și, la NBA și MLB, jucătorii); null dacă răspunsul nu are nimic de folosit. */
    fun parseStats(league: League, body: String): MatchDetails? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val d = when (league) {
            League.NBA -> parseNba(root)
            League.NHL -> parseNhl(root)
            League.MLB -> parseMlb(root)
        }
        return d.takeIf { it.stats.isNotEmpty() || it.lineup.isNotEmpty() }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- NBA ----------

    fun parseNbaScoreboard(body: String): List<SportEvent> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return root.obj("scoreboard")?.arr("games").orEmpty().filterIsInstance<JsonObject>().mapNotNull { g ->
            val id = g.text("gameId") ?: return@mapNotNull null
            val start = g.text("gameTimeUTC")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
            val status = when (g.text("gameStatus")) { "1" -> MatchStatus.SCHEDULED; "2" -> MatchStatus.LIVE; "3" -> MatchStatus.FINISHED; else -> null }
            SportEvent(
                provider = "nba",
                providerId = id,
                sport = Sport.BASKETBALL.sportsDbName,
                competition = "NBA",
                home = nbaTeam(g.obj("homeTeam")) ?: return@mapNotNull null,
                away = nbaTeam(g.obj("awayTeam")) ?: return@mapNotNull null,
                start = start,
                status = status,
            )
        }
    }

    private fun nbaTeam(t: JsonObject?): String? {
        t ?: return null
        return listOfNotNull(t.text("teamCity"), t.text("teamName")).joinToString(" ").ifBlank { null }
    }

    private val nbaStats = listOf(
        "Aruncări reușite" to listOf("fieldGoalsMade", "fieldGoalsAttempted"),
        "Aruncări (%)" to listOf("fieldGoalsPercentage"),
        "Aruncări de 3 puncte" to listOf("threePointersMade", "threePointersAttempted"),
        "Aruncări de 3 puncte (%)" to listOf("threePointersPercentage"),
        "Aruncări libere" to listOf("freeThrowsMade", "freeThrowsAttempted"),
        "Aruncări libere (%)" to listOf("freeThrowsPercentage"),
        "Recuperări" to listOf("reboundsTotal"),
        "Recuperări în atac" to listOf("reboundsOffensive"),
        "Recuperări în apărare" to listOf("reboundsDefensive"),
        "Pase de coș" to listOf("assists"),
        "Intercepții" to listOf("steals"),
        "Blocaje (aruncări blocate)" to listOf("blocks"),
        "Mingi pierdute" to listOf("turnoversTotal", "turnovers"),
        "Faulturi" to listOf("foulsPersonal"),
        "Puncte în zonă" to listOf("pointsInThePaint"),
        "Puncte din contraatac" to listOf("pointsFastBreak"),
        "Puncte din a doua șansă" to listOf("pointsSecondChance"),
        "Puncte din mingi pierdute" to listOf("pointsFromTurnovers"),
        "Puncte de pe bancă" to listOf("benchPoints"),
        "Cel mai mare avantaj" to listOf("biggestLead"),
    )

    private fun parseNba(root: JsonObject): MatchDetails {
        val game = root.obj("game") ?: root
        val h = game.obj("homeTeam")
        val a = game.obj("awayTeam")
        val hs = h?.obj("statistics")
        val aw = a?.obj("statistics")
        val stats = if (hs == null || aw == null) emptyList() else nbaStats.mapNotNull { (label, keys) ->
            if (label.endsWith("(%)")) {
                val hv = hs.num(keys[0]) ?: return@mapNotNull null
                val av = aw.num(keys[0]) ?: return@mapNotNull null
                StatLine(label, percent(hv), percent(av))
            } else if (keys.size == 2 && label != "Mingi pierdute") {
                val hm = hs.num(keys[0]) ?: return@mapNotNull null
                val am = aw.num(keys[0]) ?: return@mapNotNull null
                val ht = hs.num(keys[1])
                val at = aw.num(keys[1])
                // „38 din 85”: bara se face din reușite (primul număr).
                StatLine(label, whole(hm) + (ht?.let { " din " + whole(it) } ?: ""), whole(am) + (at?.let { " din " + whole(it) } ?: ""))
            } else {
                val key = keys.firstOrNull { hs.num(it) != null && aw.num(it) != null } ?: return@mapNotNull null
                StatLine(label, whole(hs.num(key)!!), whole(aw.num(key)!!))
            }
        }
        val lineup = nbaPlayers(h, true) + nbaPlayers(a, false)
        return MatchDetails(stats = stats, lineup = lineup)
    }

    private fun nbaPlayers(team: JsonObject?, home: Boolean): List<LineupPlayer> =
        team?.arr("players").orEmpty().filterIsInstance<JsonObject>().mapNotNull { p ->
            val name = p.text("name") ?: listOfNotNull(p.text("firstName"), p.text("familyName")).joinToString(" ").ifBlank { null }
                ?: return@mapNotNull null
            val starter = p.text("starter") == "1"
            LineupPlayer(name = name, position = p.text("position"), number = p.text("jerseyNum"), home = home, substitute = !starter)
        }.sortedBy { it.substitute }

    // ---------- NHL ----------

    private val nhlStats = mapOf(
        "sog" to "Șuturi pe poartă",
        "faceoffWinningPctg" to "Face-off câștigate (%)",
        "powerPlay" to "Powerplay (reușite/ocazii)",
        "powerPlayPctg" to "Powerplay (%)",
        "pim" to "Minute de penalizare",
        "hits" to "Contacte (hits)",
        "blockedShots" to "Șuturi blocate",
        "giveaways" to "Mingi pierdute (giveaways)",
        "takeaways" to "Recuperări (takeaways)",
    )

    private fun parseNhl(root: JsonObject): MatchDetails {
        val list = root.arr("teamGameStats").filterIsInstance<JsonObject>()
        val stats = list.mapNotNull { s ->
            val cat = s.text("category") ?: return@mapNotNull null
            val label = nhlStats[cat] ?: return@mapNotNull null
            val h = s["homeValue"]?.str() ?: return@mapNotNull null
            val a = s["awayValue"]?.str() ?: return@mapNotNull null
            if (cat.endsWith("Pctg")) {
                val hv = h.toDoubleOrNull()
                val av = a.toDoubleOrNull()
                if (hv != null && av != null) return@mapNotNull StatLine(label, percent(hv), percent(av))
            }
            StatLine(label, h, a)
        }
        return MatchDetails(stats = stats)
    }

    // ---------- MLB ----------

    private val mlbBatting = listOf(
        "Lovituri (hits)" to "hits",
        "Home run-uri" to "homeRuns",
        "Puncte aduse (RBI)" to "rbi",
        "Duble" to "doubles",
        "Triple" to "triples",
        "Baze din walk" to "baseOnBalls",
        "Strikeout-uri la bătaie" to "strikeOuts",
        "Baze furate" to "stolenBases",
        "Rămași pe bază" to "leftOnBase",
        "Medie la bătaie" to "avg",
    )
    private val mlbPitching = listOf(
        "Aruncări (pitch-uri)" to "numberOfPitches",
        "Strikeout-uri date" to "strikeOuts",
        "Puncte primite meritate" to "earnedRuns",
    )

    private fun parseMlb(root: JsonObject): MatchDetails {
        val teams = root.obj("teams")
        val h = teams?.obj("home")
        val a = teams?.obj("away")
        val stats = ArrayList<StatLine>()
        fun add(group: String, pairs: List<Pair<String, String>>) {
            val hs = h?.obj("teamStats")?.obj(group) ?: return
            val aw = a?.obj("teamStats")?.obj(group) ?: return
            for ((label, key) in pairs) {
                val hv = hs[key]?.str() ?: continue
                val av = aw[key]?.str() ?: continue
                stats += StatLine(label, hv, av)
            }
        }
        add("batting", mlbBatting)
        add("pitching", mlbPitching)
        add("fielding", listOf("Erori" to "errors"))
        val lineup = mlbPlayers(h, true) + mlbPlayers(a, false)
        return MatchDetails(stats = stats, lineup = lineup)
    }

    /** Ordinea la bătaie (titularii), apoi aruncătorii folosiți. */
    private fun mlbPlayers(team: JsonObject?, home: Boolean): List<LineupPlayer> {
        team ?: return emptyList()
        val players = team.obj("players") ?: return emptyList()
        fun player(id: String, sub: Boolean): LineupPlayer? {
            val p = players.obj("ID$id") ?: return null
            val name = p.obj("person")?.text("fullName") ?: return null
            return LineupPlayer(name, p.obj("position")?.text("abbreviation"), p.text("jerseyNumber"), home, sub)
        }
        val order = team.arr("battingOrder").mapNotNull { it.str() }
        val pitchers = team.arr("pitchers").mapNotNull { it.str() }.filter { it !in order }
        return order.mapNotNull { player(it, false) } + pitchers.mapNotNull { player(it, true) }
    }

    // ---------- comune ----------

    /** 0.512 → „51.2%”; 51.2 → „51.2%”. */
    private fun percent(v: Double): String {
        val p = if (v <= 1.0) v * 100 else v
        val r = Math.round(p * 10) / 10.0
        return (if (r == Math.floor(r)) r.toLong().toString() else r.toString()) + "%"
    }

    private fun whole(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun JsonObject.text(key: String): String? = this[key]?.str()

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
