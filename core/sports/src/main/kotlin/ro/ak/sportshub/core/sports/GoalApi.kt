package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GOAL API (`api.goal-api.com/v1`, cheia ta în antetul `Authorization: Bearer`, planul gratuit 1.000 de cereri pe zi):
 * a cincea sursă de statistici și formații la fotbal, doar când celelalte n-au dat nimic. Formatul a fost văzut pe
 * răspunsuri reale (16.11.2026):
 * - programul zilei (`/fixtures/date/{zi}?limit=100&offset=N`, câte 100, ~800 pe zi): `data[]` cu `id`,
 *   `homeTeamName`, `awayTeamName`, `kickoffUtc`, `leagueName`, `matchStatus`; `pagination.hasMore`;
 * - statisticile (`/fixtures/{id}/statistics`): `data.match.fullTime[]` = `{type, home, away}` (unele de două ori:
 *   ultima valoare e cea bună);
 * - formațiile (`/fixtures/{id}/lineups`): `data.home|away.startingLineups[]` și `substitutes[]` cu `lineupPlayer`,
 *   `lineupNumber`, `playerPosition` (Goalkeepers, Defenders…).
 */
object GoalApi {

    const val PROVIDER = "goalapi"
    private const val BASE = "https://api.goal-api.com/v1"

    const val PAGE = 100

    fun fixturesUrl(day: LocalDate, page: Int = 0): String = "$BASE/fixtures/date/$day?limit=$PAGE&offset=${page * PAGE}"

    /** Mai sunt pagini după aceasta (`pagination.hasMore`)? */
    fun hasMore(body: String): Boolean = runCatching {
        ((json.parseToJsonElement(body) as JsonObject)["pagination"] as JsonObject).text("hasMore") == "true"
    }.getOrDefault(false)
    fun statisticsUrl(id: String): String = "$BASE/fixtures/$id/statistics"
    fun lineupsUrl(id: String): String = "$BASE/fixtures/$id/lineups"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun data(body: String): JsonElement? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        return (root as? JsonObject)?.get("data") ?: root
    }

    /** Programul zilei: id-ul, echipele (obiect cu `name` sau text) și ora (`kickoffUtc`). */
    fun parseFixtures(body: String): List<SportEvent> {
        val d = data(body)
        val list = when (d) {
            is JsonArray -> d
            is JsonObject -> d.values.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        }.orEmpty()
        return list.filterIsInstance<JsonObject>().mapNotNull { f ->
            val id = f.text("id") ?: f.text("match_id") ?: f.text("fixtureId") ?: return@mapNotNull null
            if (f.text("matchStatus") == "POSTPONED") return@mapNotNull null
            val home = team(f, "home") ?: return@mapNotNull null
            val away = team(f, "away") ?: return@mapNotNull null
            val start = (f.text("kickoffUtc") ?: f.text("kickoff_utc") ?: f.text("date"))
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = Sport.FOOTBALL.sportsDbName,
                competition = f.text("leagueName") ?: (f["league"] as? JsonObject)?.text("name"),
                home = home,
                away = away,
                start = start,
            )
        }
    }

    private fun team(f: JsonObject, side: String): String? {
        for (key in listOf("${side}Team", "${side}_team", side, "match_${side}team_name", "${side}TeamName")) {
            when (val v = f[key]) {
                is JsonObject -> v.text("name")?.let { return it }
                is JsonPrimitive -> v.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
        }
        val teams = f["teams"] as? JsonObject ?: return null
        return (teams[side] as? JsonObject)?.text("name")
    }

    /**
     * Statisticile, în una din formele obișnuite: rânduri cu nume + gazde + oaspeți (`type`/`name`, `home`/`away`),
     * sau două blocuri de echipă cu `statistics: [{type, value}]` (gazdele primele).
     */
    /** Rânduri fără folos (mereu 0 sau dublate de altele). */
    private val noise = setOf("throw in", "free kick", "goal kick", "substitution", "on target", "off target")

    fun parseStatistics(body: String): List<StatLine> {
        val d = data(body) ?: return emptyList()
        val full = ((d as? JsonObject)?.get("match") as? JsonObject)?.get("fullTime") as? JsonArray
        if (full != null) {
            val byName = LinkedHashMap<String, StatLine>()
            full.filterIsInstance<JsonObject>().forEach { r ->
                val name = r.text("type") ?: return@forEach
                if (name.lowercase() in noise) return@forEach
                val h = r.text("home") ?: return@forEach
                val a = r.text("away") ?: return@forEach
                byName[name.lowercase()] = StatLine(TheSportsDbDetails.statLabel(name), h, a)
            }
            return byName.values.toList()
        }
        val out = ArrayList<StatLine>()
        val list = (d as? JsonArray) ?: (d as? JsonObject)?.let { o -> (o["statistics"] ?: o["stats"]) as? JsonArray }
        if (list != null) {
            val rows = list.filterIsInstance<JsonObject>()
            // Forma 1: un rând pe statistică.
            rows.forEach { r ->
                val name = r.text("type") ?: r.text("name") ?: r.text("label") ?: return@forEach
                val h = r.text("home") ?: r.text("homeValue") ?: r.text("home_value") ?: return@forEach
                val a = r.text("away") ?: r.text("awayValue") ?: r.text("away_value") ?: return@forEach
                out += StatLine(TheSportsDbDetails.statLabel(name), h, a)
            }
            // Forma 2: două echipe, fiecare cu lista ei.
            if (out.isEmpty() && rows.size == 2) {
                val h = teamStats(rows[0])
                val a = teamStats(rows[1])
                h.forEach { (name, hv) -> a[name]?.let { av -> out += StatLine(TheSportsDbDetails.statLabel(name), hv, av) } }
            }
        }
        // Forma 3: {"home": {...}, "away": {...}} cu perechi nume → valoare.
        if (out.isEmpty() && d is JsonObject) {
            val h = d["home"] as? JsonObject
            val a = d["away"] as? JsonObject
            if (h != null && a != null) {
                h.keys.forEach { k -> val hv = h.text(k); val av = a.text(k); if (hv != null && av != null) out += StatLine(TheSportsDbDetails.statLabel(k), hv, av) }
            }
        }
        return out.distinctBy { it.name }
    }

    private fun teamStats(block: JsonObject): Map<String, String> {
        val list = (block["statistics"] ?: block["stats"]) as? JsonArray ?: return emptyMap()
        return list.filterIsInstance<JsonObject>().mapNotNull { s ->
            val name = s.text("type") ?: s.text("name") ?: return@mapNotNull null
            val v = s.text("value") ?: return@mapNotNull null
            name to v
        }.toMap()
    }

    /** Formațiile: titularii și rezervele fiecărei echipe, în forme obișnuite (`startXI`/`starting`/`lineup`, `substitutes`/`bench`). */
    fun parseLineups(body: String): List<LineupPlayer> {
        val d = data(body) ?: return emptyList()
        val sides: List<Pair<JsonObject, Boolean>> = when (d) {
            is JsonArray -> d.filterIsInstance<JsonObject>().take(2).mapIndexed { i, o -> o to (i == 0) }
            is JsonObject -> listOfNotNull(
                ((d["home"] ?: d["homeTeam"]) as? JsonObject)?.let { it to true },
                ((d["away"] ?: d["awayTeam"]) as? JsonObject)?.let { it to false },
            )
            else -> emptyList()
        }
        return sides.flatMap { (o, home) ->
            players(o, listOf("startingLineups", "startXI", "starting", "startingXI", "lineup", "starters"), home, false) +
                players(o, listOf("substitutes", "bench", "subs"), home, true)
        }
    }

    private fun players(team: JsonObject, keys: List<String>, home: Boolean, sub: Boolean): List<LineupPlayer> {
        val list = keys.firstNotNullOfOrNull { team[it] as? JsonArray } ?: return emptyList()
        return list.filterIsInstance<JsonObject>().mapNotNull { e ->
            val p = (e["player"] as? JsonObject) ?: e
            val name = e.text("lineupPlayer") ?: p.text("name") ?: p.text("player_name") ?: return@mapNotNull null
            val pos = e.text("playerPosition") ?: p.text("pos") ?: p.text("position")
            LineupPlayer(
                name = name,
                position = if (sub) null else when (pos?.uppercase()) {
                    "G", "GK", "GOALKEEPERS", "GOALKEEPER" -> "Goalkeeper"
                    "D", "DF", "DEFENDERS", "DEFENDER" -> "Defender"
                    "M", "MF", "MIDFIELDERS", "MIDFIELDER" -> "Midfielder"
                    "F", "FW", "FORWARDS", "FORWARD", "ATTACKERS" -> "Forward"
                    else -> pos
                },
                number = e.text("lineupNumber") ?: p.text("number") ?: p.text("shirt_number"),
                home = home,
                substitute = sub,
            )
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
