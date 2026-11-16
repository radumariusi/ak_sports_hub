package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 365Scores, a treia sursă de statistici și formații (după ESPN și FotMob / statisticile oficiale): JSON-ul deschis
 * al site-ului (`webws.365scores.com`, fără cheie), pentru fotbal, baschet, tenis, hochei, handbal, volei, rugby…
 * - programul zilei pe sport (`/web/games/allscores/`, ora UTC), din care se ia id-ul meciului;
 * - statisticile (`/web/game/stats/?games={id}`: câte un rând pe echipă, `competitorId` + `name` + `value`);
 * - formațiile din pagina meciului (`/web/game/?gameId={id}`: `lineups.members` la fiecare echipă, numele în `members`).
 * Programul și statisticile au fost văzute pe răspunsuri reale (16.11.2026); formațiile nu (citire tolerantă).
 */
object Scores365 {

    private const val BASE = "https://webws.365scores.com/web"
    private const val COMMON = "appTypeId=5&langId=1&timezoneName=UTC&userCountryId=1"
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Id-ul sportului la 365Scores; null pentru sporturile fără meciuri între două echipe acolo. */
    fun sportId(sport: Sport?): Int? = when (sport) {
        Sport.FOOTBALL -> 1
        Sport.BASKETBALL -> 2
        Sport.TENNIS -> 3
        Sport.ICE_HOCKEY -> 4
        Sport.HANDBALL -> 5
        Sport.AMERICAN_FOOTBALL -> 6
        Sport.BASEBALL -> 7
        Sport.VOLLEYBALL -> 8
        Sport.RUGBY -> 9
        else -> null
    }

    fun gameDay(start: Long): LocalDate = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()

    fun scheduleUrl(sportId: Int, day: LocalDate): String {
        val d = day.format(DAY)
        return "$BASE/games/allscores/?$COMMON&sports=$sportId&startDate=$d&endDate=$d"
    }

    fun statsUrl(gameId: String): String = "$BASE/game/stats/?$COMMON&games=$gameId"

    fun gameUrl(gameId: String): String = "$BASE/game/?$COMMON&gameId=$gameId"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /** Meciurile din programul zilei (id-ul 365Scores în `providerId`). */
    fun parseSchedule(body: String): List<SportEvent> {
        val r = root(body) ?: return emptyList()
        return r.arr("games").filterIsInstance<JsonObject>().mapNotNull { g ->
            val id = g.text("id") ?: return@mapNotNull null
            val start = g.text("startTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            val home = g.obj("homeCompetitor")?.text("name") ?: return@mapNotNull null
            val away = g.obj("awayCompetitor")?.text("name") ?: return@mapNotNull null
            val status = when (g.text("statusGroup")) {
                "1", "2" -> MatchStatus.SCHEDULED
                "3" -> MatchStatus.LIVE
                "4" -> MatchStatus.FINISHED
                else -> null
            }
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = null,
                competition = g.text("competitionDisplayName"),
                home = home,
                away = away,
                start = start,
                status = status,
            )
        }
    }

    /**
     * Statisticile: rândurile echipei gazdă și ale oaspeților, unite după `id` (sau nume), în ordinea lor.
     * Gazdele sunt `competitorNum` 1 (sau prima echipă din `games`/`competitors`).
     */
    fun parseStats(body: String): List<StatLine> {
        val r = root(body) ?: return emptyList()
        val (homeId, awayId) = sides(r) ?: return emptyList()
        val rows = r.arr("statistics").filterIsInstance<JsonObject>()
        val home = LinkedHashMap<String, JsonObject>()
        val away = HashMap<String, JsonObject>()
        for (s in rows) {
            val key = s.text("id") ?: s.text("name") ?: continue
            when (s.text("competitorId")) {
                homeId -> home.putIfAbsent(key, s)
                awayId -> away.putIfAbsent(key, s)
            }
        }
        return home.entries
            .sortedBy { it.value.text("order")?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { (key, h) ->
                val a = away[key] ?: return@mapNotNull null
                val name = h.text("name") ?: return@mapNotNull null
                val hv = h.text("value") ?: return@mapNotNull null
                val av = a.text("value") ?: return@mapNotNull null
                StatLine(TheSportsDbDetails.statLabel(name), hv, av)
            }
            .distinctBy { it.name }
    }

    private fun sides(r: JsonObject): Pair<String, String>? {
        val g = r.arr("games").filterIsInstance<JsonObject>().firstOrNull()
        val h = g?.obj("homeCompetitor")?.text("id")
        val a = g?.obj("awayCompetitor")?.text("id")
        if (h != null && a != null) return h to a
        val comps = r.arr("competitors").filterIsInstance<JsonObject>()
        val home = comps.firstOrNull { it.text("competitorNum") == "1" } ?: comps.getOrNull(0)
        val away = comps.firstOrNull { it.text("competitorNum") == "2" } ?: comps.getOrNull(1)
        val hi = home?.text("id") ?: return null
        val ai = away?.text("id") ?: return null
        return hi to ai
    }

    /** Formațiile din pagina meciului: titularii (`status` 1), apoi rezervele; null dacă nu sunt. */
    fun parseLineup(body: String): List<LineupPlayer> {
        val r = root(body) ?: return emptyList()
        val game = r.obj("game") ?: r
        val names = HashMap<String, JsonObject>()
        (game.arr("members") + r.arr("members")).filterIsInstance<JsonObject>().forEach { m -> m.text("id")?.let { names.putIfAbsent(it, m) } }
        fun side(c: JsonObject?, home: Boolean): List<LineupPlayer> {
            val members = c?.obj("lineups")?.arr("members").orEmpty().filterIsInstance<JsonObject>()
            return members.mapNotNull { m ->
                val id = m.text("id")
                val info = id?.let { names[it] }
                val name = info?.text("name") ?: m.text("name") ?: return@mapNotNull null
                val status = m.text("status")
                val sub = status != null && status != "1"
                val pos = (m["position"] as? JsonObject)?.text("name") ?: m.text("position")
                LineupPlayer(
                    name = name,
                    position = if (sub) null else positionName(pos),
                    number = info?.text("jerseyNumber") ?: m.text("jerseyNumber"),
                    home = home,
                    substitute = sub,
                )
            }.sortedBy { it.substitute }
        }
        return side(game.obj("homeCompetitor"), true) + side(game.obj("awayCompetitor"), false)
    }

    private fun positionName(p: String?): String? = when (p?.lowercase()) {
        null -> null
        "goalkeeper", "keeper" -> "Goalkeeper"
        "defender", "defence", "defense" -> "Defender"
        "midfielder", "midfield" -> "Midfielder"
        "attacker", "forward", "striker" -> "Forward"
        else -> p
    }

    const val PROVIDER = "365scores"

    private fun JsonObject.text(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        val c = p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        // 365Scores dă numerele ca 86812 sau 1.0: id-urile și ordinea fără „.0”.
        return if (!p.isString && c.endsWith(".0")) c.dropLast(2) else c
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
