package ro.ak.sportshub.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

/**
 * Statisticile și formațiile unui meci de fotbal de la FotMob, a doua sursă după ESPN (doar fotbal). Pagina
 * meciului (`/match/{id}`, id-ul din programul FotMob deja descărcat) e făcută cu Next.js: datele stau în JSON-ul
 * din `__NEXT_DATA__`. Formatul nu e documentat, deci citirea e tolerantă:
 * - statisticile: obiectele cu `title` și `stats` = două valori (gazde, oaspeți), o dată pe nume;
 * - formațiile: obiectul `lineup` cu `homeTeam` / `awayTeam`, fiecare cu `starters` și `subs` (sau `bench`).
 */
object FotMobDetails {

    fun url(matchId: String): String = "https://www.fotmob.com/match/$matchId"

    /** Id-ul numeric al meciului din `providerId`-ul FotMob („gb-4506263” → 4506263); null dacă nu e un număr. */
    fun matchId(providerId: String): String? =
        providerId.substringAfterLast('-').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(html: String): MatchDetails? {
        val data = runCatching { Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() }.getOrNull()
            ?: html.takeIf { it.trimStart().startsWith("{") }
            ?: return null
        val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
        val stats = ArrayList<StatLine>()
        collectStats(root, stats, 0)
        val lineup = findLineup(root, 0).orEmpty()
        val d = MatchDetails(stats = stats.distinctBy { it.name }, lineup = lineup)
        return d.takeIf { it.stats.isNotEmpty() || it.lineup.isNotEmpty() }
    }

    private fun collectStats(el: JsonElement, out: MutableList<StatLine>, depth: Int) {
        if (depth > 40) return
        when (el) {
            is JsonArray -> el.forEach { collectStats(it, out, depth + 1) }
            is JsonObject -> {
                val title = el.text("title") ?: el.text("name")
                val values = el["stats"] as? JsonArray
                if (title != null && values != null && values.size == 2 && values.all { it is JsonPrimitive || it == kotlinx.serialization.json.JsonNull }) {
                    val h = (values[0] as? JsonPrimitive)?.contentOrNull
                    val a = (values[1] as? JsonPrimitive)?.contentOrNull
                    if (h != null && a != null) out += StatLine(TheSportsDbDetails.statLabel(title), h, a)
                    return
                }
                // Statisticile pe reprize ar dubla rândurile: doar „All” (tot meciul), dacă există.
                val periods = el["Periods"] as? JsonObject
                if (periods != null) {
                    (periods["All"] ?: periods.values.firstOrNull())?.let { collectStats(it, out, depth + 1) }
                    return
                }
                el.values.forEach { collectStats(it, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun findLineup(el: JsonElement, depth: Int): List<LineupPlayer>? {
        if (depth > 40) return null
        when (el) {
            is JsonArray -> el.forEach { e -> findLineup(e, depth + 1)?.let { return it } }
            is JsonObject -> {
                val home = el["homeTeam"] as? JsonObject
                val away = el["awayTeam"] as? JsonObject
                if (home != null && away != null && (home["starters"] is JsonArray || away["starters"] is JsonArray)) {
                    val out = players(home, true) + players(away, false)
                    if (out.isNotEmpty()) return out
                }
                el.values.forEach { e -> findLineup(e, depth + 1)?.let { return it } }
            }
            else -> Unit
        }
        return null
    }

    private fun players(team: JsonObject, home: Boolean): List<LineupPlayer> {
        fun list(key: String, sub: Boolean) = (team[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { p ->
            val name = p.text("name") ?: (p["name"] as? JsonObject)?.let { it.text("fullName") ?: it.text("lastName") } ?: return@mapNotNull null
            LineupPlayer(
                name = name,
                position = if (sub) null else position(p),
                number = p.text("shirtNumber") ?: p.text("shirt"),
                home = home,
                substitute = sub,
            )
        }
        return list("starters", false) + list("subs", true).ifEmpty { list("bench", true) }
    }

    /** `usualPlayingPositionId`: 0 portar, 1 fundaș, 2 mijlocaș, 3 atacant. */
    private fun position(p: JsonObject): String? = when (p.text("usualPlayingPositionId") ?: p.text("role")) {
        "0", "Keeper", "GK" -> "Goalkeeper"
        "1", "Defender" -> "Defender"
        "2", "Midfielder" -> "Midfielder"
        "3", "Attacker", "Forward" -> "Forward"
        else -> null
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
