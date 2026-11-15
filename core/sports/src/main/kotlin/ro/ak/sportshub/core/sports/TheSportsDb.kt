package ro.ak.sportshub.core.sports

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * TheSportsDB (API v1, cheia gratuită `123` sau cheia ta): programul meciurilor pe zile și,
 * când contul o permite, posturile TV. Cererile nu merg la furnizorul de IPTV, deci nu folosesc
 * conexiunea playlisturilor; se fac rar, una câte una.
 */
object TheSportsDb {

    const val PROVIDER = "thesportsdb"
    const val DEFAULT_KEY = "123"
    private const val BASE = "https://www.thesportsdb.com/api/v1/json"

    /** Meciurile unei zile (UTC) pentru un sport. */
    fun eventsDayUrl(key: String, day: LocalDate, sport: Sport): String =
        "$BASE/${enc(key.ifBlank { DEFAULT_KEY })}/eventsday.php?d=$day&s=${enc(sport.sportsDbName)}"

    /** Posturile TV ale unei zile (pe conturile gratuite, rezultatele sunt puține sau lipsesc). */
    fun tvDayUrl(key: String, day: LocalDate): String =
        "$BASE/${enc(key.ifBlank { DEFAULT_KEY })}/eventstv.php?d=$day"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Un post TV pentru meciul [eventId] al furnizorului. */
    data class TvListing(val eventId: String, val station: TvStation)

    /** Citește răspunsul `eventsday.php`. Un răspuns stricat sau gol dă o listă goală. */
    fun parseEvents(body: String): List<SportEvent> = firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("idEvent") ?: return@mapNotNull null
        val homeTeam = o.str("strHomeTeam")
        val awayTeam = o.str("strAwayTeam")
        val (home, away) = if (homeTeam != null && awayTeam != null) {
            homeTeam to awayTeam
        } else {
            // Sporturi fără echipe (F1, golf): numele evenimentului, ca o singură „echipă”.
            val name = o.str("strEvent") ?: return@mapNotNull null
            val split = name.split(Regex("""\s+(?i:vs\.?|v)\s+"""), limit = 2)
            if (split.size == 2) split[0].trim() to split[1].trim() else name.trim() to ""
        }
        if (o.str("strPostponed")?.equals("yes", ignoreCase = true) == true) return@mapNotNull null
        if (isCancelled(o.str("strStatus"))) return@mapNotNull null
        val start = parseStart(o.str("strTimestamp"), o.str("dateEvent"), o.str("strTime")) ?: return@mapNotNull null
        SportEvent(
            provider = PROVIDER,
            providerId = id,
            sport = o.str("strSport"),
            competition = o.str("strLeague"),
            home = home,
            away = away,
            start = start,
            status = status(o.str("strStatus")),
            homeScore = o.str("intHomeScore")?.toIntOrNull(),
            awayScore = o.str("intAwayScore")?.toIntOrNull(),
            venue = o.str("strVenue"),
            homeLook = o.str("strHomeTeamBadge")?.let { TeamLook(logo = it) },
            awayLook = o.str("strAwayTeamBadge")?.let { TeamLook(logo = it) },
            competitionLogo = o.str("strLeagueBadge"),
        )
    }

    /** Citește răspunsul `eventstv.php`. */
    fun parseTv(body: String): List<TvListing> = firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("idEvent") ?: return@mapNotNull null
        val channel = o.str("strChannel") ?: return@mapNotNull null
        TvListing(id, TvStation(channel, o.str("strCountry")))
    }

    /** Ora de start în ms UTC: `strTimestamp`, altfel `dateEvent` + `strTime` (ambele UTC). */
    fun parseStart(timestamp: String?, date: String?, time: String?): Long? {
        timestamp?.let { ts ->
            runCatching { return OffsetDateTime.parse(ts).toInstant().toEpochMilli() }
            runCatching { return LocalDateTime.parse(ts.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }
        }
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val t = time?.substringBefore('+')?.substringBefore('Z')?.trim()
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: LocalTime.MIDNIGHT
        return d.atTime(t).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /** Starea după codurile TheSportsDB („NS”, „1H”, „HT”, „FT”, „Match Finished”…). */
    fun status(raw: String?): MatchStatus? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        return when {
            s in setOf("ns", "not started", "tbd", "time to be defined", "scheduled") -> MatchStatus.SCHEDULED
            s in setOf("ft", "aet", "pen", "awd", "wo", "match finished", "finished", "final", "ended", "after over time",
                "after penalties", "aot", "fin") || s.startsWith("match finished") -> MatchStatus.FINISHED
            isCancelled(s) -> null
            else -> MatchStatus.LIVE // 1H, HT, 2H, ET, P, Q1…Q4, OT, BT, LIVE, In Progress
        }
    }

    private fun isCancelled(raw: String?): Boolean =
        raw?.trim()?.lowercase() in setOf("canc", "pst", "abd", "cancelled", "postponed", "abandoned", "susp", "int")

    internal fun firstArray(body: String): List<JsonElement> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        if (root is JsonArray) return root
        val obj = runCatching { root.jsonObject }.getOrNull() ?: return emptyList()
        return obj.values.firstOrNull { it is JsonArray } as? JsonArray ?: emptyList()
    }

    internal fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
