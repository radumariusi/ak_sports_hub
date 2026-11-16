package ro.ak.sportshub.core.sports

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * API-ul oficial EuroLeague (`api-live.euroleague.net`, JSON public, fără cheie): programul complet al sezonului
 * EuroLeague și EuroCup, cu orele UTC, scorurile meciurilor jucate și sala. ESPN nu are baschetul european.
 *
 * Răspunsul nu se poate filtra pe zile, așa că se cere tot sezonul, rar (la 12 ore), și se păstrează în fișier.
 * Cererile nu merg la furnizorul de IPTV.
 */
object EuroLeague {

    const val PROVIDER = "euroleague"

    enum class Competition(val code: String, val label: String) {
        EUROLEAGUE("E", "EuroLeague"),
        EUROCUP("U", "EuroCup"),
    }

    /** Sezonul care cuprinde ziua [day]: începe în iulie („E2026” = 2026–27). */
    fun seasonYear(day: LocalDate): Int = if (day.monthValue >= 7) day.year else day.year - 1

    fun url(c: Competition, day: LocalDate): String =
        "https://api-live.euroleague.net/v2/competitions/${c.code}/seasons/${c.code}${seasonYear(day)}/games"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Meciurile sezonului din răspuns. Un răspuns stricat dă o listă goală. */
    fun parse(body: String, c: Competition): List<SportEvent> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val games = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: root.values.firstOrNull { it is JsonArray } as? JsonArray
            else -> null
        } ?: return emptyList()
        return games.filterIsInstance<JsonObject>().mapNotNull { g ->
            val id = g.text("identifier") ?: g.text("gameCode")?.let { "${c.code}-$it" } ?: g.text("id") ?: return@mapNotNull null
            val start = g.text("utcDate")?.let(::isoMillis) ?: return@mapNotNull null
            val status = g.text("gameStatus")?.lowercase().orEmpty()
            if ("postpon" in status || "cancel" in status) return@mapNotNull null
            val local = g.obj("local")
            val road = g.obj("road")
            val home = club(local) ?: return@mapNotNull null
            val away = club(road) ?: return@mapNotNull null
            val played = g.text("played") == "true"
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = Sport.BASKETBALL.sportsDbName,
                competition = c.label,
                home = home,
                away = away,
                start = start,
                status = if (played) MatchStatus.FINISHED else null,
                homeScore = local?.text("score")?.toIntOrNull().takeIf { played },
                awayScore = road?.text("score")?.toIntOrNull().takeIf { played },
                venue = g.obj("venue")?.text("name"),
            )
        }
    }

    /**
     * Statisticile oficiale ale meciului (`live.euroleague.net/api/BoxScore`, fără cheie; format văzut pe un răspuns
     * real): totalurile echipelor (`Stats[].totr`) și jucătorii (`PlayersStats`, titularii cu `IsStarter` = 1).
     * [providerId] e `identifier` din program („E2026_379”); null dacă nu se poate citi.
     */
    fun boxScoreUrl(providerId: String): String? {
        val season = providerId.substringBefore('_', "").takeIf { it.length > 1 && it.drop(1).all(Char::isDigit) } ?: return null
        val code = providerId.substringAfter('_').takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        return "https://live.euroleague.net/api/BoxScore?gamecode=$code&seasoncode=$season"
    }

    private val boxStats = listOf(
        "Aruncări de 2 puncte" to ("FieldGoalsMade2" to "FieldGoalsAttempted2"),
        "Aruncări de 3 puncte" to ("FieldGoalsMade3" to "FieldGoalsAttempted3"),
        "Aruncări libere" to ("FreeThrowsMade" to "FreeThrowsAttempted"),
        "Recuperări" to ("TotalRebounds" to null),
        "Recuperări în atac" to ("OffensiveRebounds" to null),
        "Recuperări în apărare" to ("DefensiveRebounds" to null),
        "Pase de coș" to ("Assistances" to null),
        "Intercepții" to ("Steals" to null),
        "Mingi pierdute" to ("Turnovers" to null),
        "Blocaje (aruncări blocate)" to ("BlocksFavour" to null),
        "Faulturi" to ("FoulsCommited" to null),
        "Evaluare (PIR)" to ("Valuation" to null),
    )

    fun parseBoxScore(body: String): MatchDetails? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val teams = (root["Stats"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (teams.size < 2) return null
        val h = teams[0].obj("totr")
        val a = teams[1].obj("totr")
        val stats = if (h == null || a == null) emptyList() else boxStats.mapNotNull { (label, keys) ->
            val (made, attempted) = keys
            val hm = h.text(made) ?: return@mapNotNull null
            val am = a.text(made) ?: return@mapNotNull null
            if (attempted == null) return@mapNotNull StatLine(label, hm, am)
            val ht = h.text(attempted)
            val at = a.text(attempted)
            StatLine(label, hm + (ht?.let { " din $it" } ?: ""), am + (at?.let { " din $it" } ?: ""))
        }
        val lineup = players(teams[0], true) + players(teams[1], false)
        return MatchDetails(stats = stats, lineup = lineup).takeIf { stats.isNotEmpty() || lineup.isNotEmpty() }
    }

    private fun players(team: JsonObject, home: Boolean) =
        (team["PlayersStats"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { p ->
            val raw = p.text("Player") ?: return@mapNotNull null
            // „LARKIN, SHANE” → „Shane Larkin”.
            val name = raw.split(',').map { part ->
                part.trim().lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
            }.reversed().joinToString(" ").trim()
            LineupPlayer(name = name, position = null, number = p.text("Dorsal"), home = home, substitute = p.text("IsStarter") != "1")
        }.sortedBy { it.substitute }

    private fun club(side: JsonObject?): String? = side?.obj("club")?.let { it.text("name") ?: it.text("editorialName") }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
