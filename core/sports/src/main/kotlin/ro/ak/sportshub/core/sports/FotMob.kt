package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup

/**
 * FotMob TV guide (furnizor opțional, neoficial): programul TV pe țară pentru fotbal (inclusiv feminin),
 * pentru ~80 de țări, printre care România (`/tv-guide/ro`) și Marea Britanie (`/tv-guide/gb`).
 *
 * O pagină pe țară (doar țările din care ai canale, cel mult [MAX_COUNTRIES]), la 3 ore, păstrată în fișier.
 * Pagina e făcută cu Next.js: datele stau în JSON-ul din `<script id="__NEXT_DATA__">`. Formatul nu e documentat,
 * deci citirea e tolerantă: se caută în tot JSON-ul obiectele care au două echipe (gazde / oaspeți), o oră
 * (ISO sau ms) și o listă de posturi. Fără JSON (sau neînțeles) nu se citește nimic: orele din HTML nu au fus.
 */
object FotMob {

    const val PROVIDER = "fotmob"
    const val MAX_COUNTRIES = 4

    /** Pagina în engleză (numele echipelor ca la ceilalți furnizori), pentru țara [cc] (cod ISO cu două litere). */
    fun url(cc: String): String = "https://www.fotmob.com/tv-guide/${cc.lowercase()}"

    /** Prefixele de țară ale canalelor tale („uk”, „ro”, „usa”) → codurile FotMob („gb”, „ro”, „us”). */
    fun countriesFor(prefixes: Set<String>): List<String> {
        val map = mapOf("uk" to "gb", "en" to "gb", "gb" to "gb", "usa" to "us", "ger" to "de")
        val codes = prefixes.map { map[it] ?: it }.filter { it.length == 2 && it.all(Char::isLetter) }.distinct()
        // Întâi țările mari cu ghid TV (prefixele scurte din playlist, ca „af” sau „al”, sunt adesea altceva:
        // „/tv-guide/af” dă 404), apoi restul în ordine alfabetică.
        val ordered = codes.sortedWith(compareBy<String> { preferred.indexOf(it).let { i -> if (i < 0) preferred.size else i } }.thenBy { it })
        return ordered.ifEmpty { listOf("gb") }.take(MAX_COUNTRIES)
    }

    private val preferred = listOf("ro", "gb", "us", "it", "es", "de", "fr", "pt", "nl", "ie", "ca", "au", "pl", "gr", "be", "ch", "at")

    fun looksValid(html: String): Boolean = html.contains("__NEXT_DATA__") || html.contains("tv-guide")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val homeKeys = listOf("home", "homeTeam", "home_team", "homeName")
    private val awayKeys = listOf("away", "awayTeam", "away_team", "awayName")
    private val timeKeys = listOf("utcTime", "startTime", "startDate", "matchTime", "kickoff", "time", "date", "startTimeUtc", "matchDate")
    private val leagueKeys = listOf("league", "leagueName", "tournament", "competition", "parentLeagueName")

    /** Meciurile din pagina țării [cc]. */
    fun parse(html: String, cc: String): List<SportEvent> {
        val data = runCatching { Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() }.getOrNull()
            ?: html.takeIf { it.trimStart().startsWith("{") }
            ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, SportEvent>()
        walk(root, null, cc.lowercase(), out, 0)
        return out.values.toList()
    }

    private fun walk(el: JsonElement, league: String?, cc: String, out: MutableMap<String, SportEvent>, depth: Int) {
        if (depth > 40) return
        when (el) {
            is JsonArray -> el.forEach { walk(it, league, cc, out, depth + 1) }
            is JsonObject -> {
                val match = asMatch(el, league, cc)
                if (match != null) {
                    out.putIfAbsent(match.providerId, match)
                    return
                }
                // Un grup de meciuri (o competiție): numele lui e competiția meciurilor din el.
                val name = el.text("name") ?: el.text("leagueName")
                val hasList = el.values.any { it is JsonArray && it.isNotEmpty() }
                val nextLeague = if (name != null && hasList) name else league
                el.values.forEach { walk(it, nextLeague, cc, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun asMatch(o: JsonObject, league: String?, cc: String): SportEvent? {
        val home = homeKeys.firstNotNullOfOrNull { teamName(o[it]) } ?: return null
        val away = awayKeys.firstNotNullOfOrNull { teamName(o[it]) } ?: return null
        val start = (timeKeys.firstNotNullOfOrNull { time(o[it]) } ?: o.obj("status")?.let { s -> timeKeys.firstNotNullOfOrNull { time(s[it]) } })
            ?: return null
        val stations = stations(o)
        val comp = leagueKeys.firstNotNullOfOrNull { k -> o[k]?.let { v -> (v as? JsonPrimitive)?.contentOrNull ?: (v as? JsonObject)?.text("name") } }
            ?: league
        val status = o.obj("status")
        val state = when {
            status?.bool("cancelled") == true || status?.bool("postponed") == true -> return null
            status?.bool("finished") == true -> MatchStatus.FINISHED
            status?.bool("ongoing") == true || status?.bool("started") == true -> MatchStatus.LIVE
            else -> null
        }
        val id = o.text("id") ?: o.text("matchId") ?: "$start ${Sport.fold(home)}|${Sport.fold(away)}"
        return SportEvent(
            provider = PROVIDER,
            providerId = "$cc-$id",
            sport = Sport.FOOTBALL.sportsDbName,
            competition = comp,
            home = home,
            away = away,
            start = start,
            status = state,
            stations = stations.map { TvStation(it, cc) },
        )
    }

    private fun teamName(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { el.isString && it.length >= 2 }
        is JsonObject -> el.text("name") ?: el.text("longName") ?: el.text("shortName")
        else -> null
    }

    /** Ora: ISO („2026-11-15T19:45:00Z”, „…+00:00”) sau ms / secunde de la 1970. */
    internal fun time(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        p.longOrNull?.let { n -> return if (n > 100_000_000_000L) n else if (n > 1_000_000_000L) n * 1000 else null }
        return isoMillis(p.contentOrNull ?: return null)
    }

    /** Posturile meciului: orice listă cu „channel”, „station”, „broadcast” sau „tv” în nume (texte sau obiecte cu nume). */
    private fun stations(o: JsonObject): List<String> {
        val out = ArrayList<String>()
        for ((k, v) in o) {
            val key = k.lowercase()
            if (!(key.contains("channel") || key.contains("station") || key.contains("broadcast") || key == "tv" || key.contains("tvlist"))) continue
            collectNames(v, out, 0)
        }
        return out.map { it.trim() }.filter { it.length >= 2 }.distinctBy { StationIndex.compact(it) }
    }

    private fun collectNames(el: JsonElement, out: MutableList<String>, depth: Int) {
        if (depth > 4) return
        when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { el.isString && !it.startsWith("http") && !it.contains('/') }?.let { out += it }
            is JsonArray -> el.forEach { collectNames(it, out, depth + 1) }
            is JsonObject -> {
                val n = el.text("name") ?: el.text("channelName") ?: el.text("title") ?: el.text("stationName")
                if (n != null) out += n else el.values.forEach { collectNames(it, out, depth + 1) }
            }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

/** „2026-11-15T19:45:00Z”, „2026-11-15T19:45Z”, „2026-11-15T19:45:00+01:00” → ms UTC (null dacă nu e o oră). */
internal fun isoMillis(text: String): Long? {
    val t = text.trim()
    if (t.length < 16 || t[4] != '-' || t[10] != 'T') return null
    runCatching { return Instant.parse(t).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
    // Fără secunde: „2026-11-15T19:45Z”.
    if (t.length >= 17 && t[16] != ':') {
        val withSeconds = t.substring(0, 16) + ":00" + t.substring(16)
        runCatching { return Instant.parse(withSeconds).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(withSeconds).toInstant().toEpochMilli() }
    }
    return null
}
