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

    private fun club(side: JsonObject?): String? = side?.obj("club")?.let { it.text("name") ?: it.text("editorialName") }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
