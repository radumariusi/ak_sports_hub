package ro.ak.sportshub.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Zona unui loc din clasament („Champions League”, „Relegation”), cu culoarea dată de ESPN. */
data class StandingZone(val description: String, val color: Long?)

/** Un rând din ecranul de clasamente. */
data class TableEntry(
    val rank: Int,
    val team: String,
    val teamId: String?,
    val abbreviation: String?,
    val logo: String?,
    val played: Int?,
    val won: Int?,
    val drawn: Int?,
    val lost: Int?,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    /** Punctele (fotbal, hochei, rugby) sau, la ligile fără puncte (NBA, NFL, MLB), procentul de victorii („.731”). */
    val points: String?,
    /** Locuri câștigate (+) sau pierdute (−) față de clasamentul anterior; 0 sau null = la fel. */
    val change: Int?,
    val zone: StandingZone?,
)

/** O grupă din clasament (o conferință, o grupă de cupă, sau tot campionatul). */
data class TableGroup(val name: String, val rows: List<TableEntry>)

/**
 * Clasamentele de la ESPN (`/apis/v2/sports/{liga}/standings`, același JSON public ca programul): pe grupe
 * (conferințe la NBA/NHL/NFL/MLB, grupe la cupe), cu zonele colorate (calificare, retrogradare) și schimbarea
 * de loc. Cerute doar cât e deschis ecranul de clasamente; răspunsul se păstrează în fișier.
 */
object Standings {

    private const val BASE = "https://site.api.espn.com/apis/v2/sports"

    fun url(league: Espn.League): String = "$BASE/${league.path}/standings"

    /** Ligile cu clasament: cele cu echipe (fără tenis, curse, gale, golf), fără amicale. */
    val leagues: List<Espn.League> = Espn.leagues.filter { l ->
        l.kind == Espn.Kind.TEAM && l.key !in setOf("FRIENDLY", "CLUBFRIENDLY", "RUGBYTEST", "USUPER", "ESPSUPER", "ITASUPER")
    }

    /**
     * Ligile în ordinea din ecran: întâi cele ale favoritelor (competiții, apoi ligile echipelor favorite din [teamLeagues]),
     * apoi cele cu meciuri în listă ([withMatches]), apoi restul catalogului.
     */
    fun ordered(favorites: SportsFavorites, teamLeagues: Set<String>, withMatches: Set<String>): List<Espn.League> {
        fun rank(l: Espn.League): Int = when {
            favorites.isCompetition(l.competition) -> 0
            l.key in teamLeagues -> 1
            l.sport in favorites.sports -> 2
            l.key in withMatches -> 3
            else -> 4
        }
        return leagues.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    /** Sporturile la care clasamentul e pe puncte; la celelalte (NBA, NFL, MLB) e pe procentul de victorii. */
    private val POINT_SPORTS = setOf(Sport.FOOTBALL, Sport.ICE_HOCKEY, Sport.RUGBY, Sport.HANDBALL, Sport.VOLLEYBALL)

    fun parse(body: String, sport: Sport = Sport.FOOTBALL): List<TableGroup> {
        val usePoints = sport in POINT_SPORTS
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val groups = ArrayList<TableGroup>()
        fun walk(o: JsonObject, fallback: String) {
            val name = o.text("name") ?: o.text("abbreviation") ?: fallback
            val entries = o.obj("standings")?.arr("entries")?.filterIsInstance<JsonObject>().orEmpty()
            if (entries.isNotEmpty()) groups += TableGroup(name, rows(entries, usePoints))
            o.arr("children").filterIsInstance<JsonObject>().forEach { walk(it, name) }
        }
        walk(root, root.text("name").orEmpty())
        return groups.filter { it.rows.isNotEmpty() }
    }

    private fun rows(entries: List<JsonObject>, usePoints: Boolean): List<TableEntry> {
        val parsed = entries.mapIndexedNotNull { i, e ->
            val team = e.obj("team") ?: return@mapIndexedNotNull null
            val name = team.text("displayName") ?: team.text("name") ?: return@mapIndexedNotNull null
            val stats = HashMap<String, JsonObject>()
            e.arr("stats").filterIsInstance<JsonObject>().forEach { s ->
                (s.text("name") ?: s.text("type"))?.let { stats[it.lowercase()] = s }
            }
            fun num(key: String): Double? = stats[key.lowercase()]?.let { s ->
                (s["value"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: s.text("displayValue")?.removePrefix("+")?.toDoubleOrNull()
            }
            fun int(key: String): Int? = num(key)?.toInt()
            val points = if (usePoints || !stats.containsKey("winpercent")) {
                int("points")?.toString()
            } else {
                stats["winpercent"]?.text("displayValue") ?: num("winpercent")?.let { String.format(java.util.Locale.ROOT, "%.3f", it).removePrefix("0") }
            }
            val note = e.obj("note")
            val rank = int("rank") ?: int("playoffseed") ?: note?.text("rank")?.toIntOrNull()
            Triple(
                rank,
                TableEntry(
                    rank = rank ?: (i + 1),
                    team = name,
                    teamId = team.text("id"),
                    abbreviation = team.text("abbreviation"),
                    logo = team.arr("logos").filterIsInstance<JsonObject>().firstOrNull()?.text("href"),
                    played = int("gamesPlayed"),
                    won = int("wins"),
                    drawn = int("ties") ?: int("draws"),
                    lost = int("losses"),
                    goalsFor = int("pointsFor"),
                    goalsAgainst = int("pointsAgainst"),
                    points = points,
                    change = int("rankChange")?.takeIf { it != 0 },
                    zone = note?.text("description")?.let { StandingZone(it, color(note.text("color"))) },
                ),
                if (usePoints) num("points") else num("winpercent"),
            )
        }
        // ESPN nu dă mereu rândurile în ordine (NBA: după altceva); fără loc, după puncte / procent.
        return if (parsed.all { it.first != null }) {
            parsed.map { it.second }.sortedBy { it.rank }
        } else {
            parsed.sortedByDescending { it.third ?: Double.NEGATIVE_INFINITY }.mapIndexed { i, t -> t.second.copy(rank = i + 1) }
        }
    }

    /** „#81D6AC” (și „##B5E7CE”, cum apare uneori) → 0xFF81D6AC. */
    fun color(text: String?): Long? {
        val hex = text?.trim()?.trimStart('#') ?: return null
        if (hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    /**
     * Schimbarea de loc față de clasamentul salvat înainte ([previous]: echipa → locul), când ESPN nu o dă:
     * pozitiv = a urcat.
     */
    fun withChanges(groups: List<TableGroup>, previous: Map<String, Int>): List<TableGroup> =
        groups.map { g ->
            g.copy(rows = g.rows.map { r ->
                if (r.change != null) r else previous[key(g, r)]?.let { before -> r.copy(change = (before - r.rank).takeIf { it != 0 }) } ?: r
            })
        }

    /** Locurile de acum, pentru comparația de data viitoare. */
    fun ranks(groups: List<TableGroup>): Map<String, Int> =
        groups.flatMap { g -> g.rows.map { key(g, it) to it.rank } }.toMap()

    private fun key(g: TableGroup, r: TableEntry) = g.name + "|" + (r.teamId ?: r.team)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
