package ro.ak.sportshub.core.sports

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Sofascore (furnizor opțional, API neoficial): programul și scorurile unei zile pentru un sport.
 * O cerere pe sport și zi, rar, cu răspunsul păstrat în fișier.
 *
 * Posturile TV pe țară (toate sporturile) cer câte o cerere pe meci (`tv/event/{id}/country-channels`), deci se
 * cer doar pentru câteva meciuri pe rundă ([pickForTv]: favoritele, apoi cele fără sursă sigură), iar numele
 * posturilor se află o dată pe țară (`tv/country/{cod}/channels`) sau pe post și se păstrează. Formatul acestor
 * răspunsuri nu e documentat: citirea e tolerantă, iar un răspuns neînțeles dă doar „fără posturi”.
 */
object Sofascore {

    const val PROVIDER = "sofascore"
    private const val BASE = "https://api.sofascore.com/api/v1"

    private val slugs = mapOf(
        Sport.FOOTBALL to "football", Sport.BASKETBALL to "basketball", Sport.TENNIS to "tennis",
        Sport.ICE_HOCKEY to "ice-hockey", Sport.HANDBALL to "handball", Sport.VOLLEYBALL to "volleyball",
        Sport.RUGBY to "rugby", Sport.AMERICAN_FOOTBALL to "american-football", Sport.BASEBALL to "baseball",
        Sport.MOTORSPORT to "motorsport", Sport.FIGHTING to "mma", Sport.DARTS to "darts",
        Sport.SNOOKER to "snooker", Sport.CYCLING to "cycling", Sport.CRICKET to "cricket",
    )

    /** Null dacă Sofascore nu are sportul. */
    fun scheduleUrl(sport: Sport, day: LocalDate): String? = slugs[sport]?.let { "$BASE/sport/$it/scheduled-events/$day" }

    /** Categoriile care nu sunt țări: nu se pun în fața numelui competiției. */
    private val genericCategories = setOf("world", "europe", "international", "south america", "north america", "asia", "africa", "oceania")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, sport: Sport): List<SportEvent> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val home = o.obj("homeTeam")?.str("name") ?: return@mapNotNull null
            val away = o.obj("awayTeam")?.str("name") ?: return@mapNotNull null
            val startSec = (o["startTimestamp"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val type = o.obj("status")?.str("type")?.lowercase()
            val status = when (type) {
                "notstarted" -> MatchStatus.SCHEDULED
                "inprogress" -> MatchStatus.LIVE
                "finished" -> MatchStatus.FINISHED
                "canceled", "cancelled", "postponed", "interrupted", "suspended", "abandoned" -> return@mapNotNull null
                else -> null
            }
            val tournament = o.obj("tournament")
            val name = tournament?.obj("uniqueTournament")?.str("name") ?: tournament?.str("name")
            val category = tournament?.obj("category")?.str("name")
            val competition = when {
                name == null -> category
                category == null || Sport.fold(category) in genericCategories || Sport.fold(name).contains(Sport.fold(category)) -> name
                else -> "$category - $name"
            }
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = sport.sportsDbName,
                competition = competition,
                home = home,
                away = away,
                start = startSec * 1000,
                status = status,
                homeScore = o.obj("homeScore")?.int("current").takeIf { status != MatchStatus.SCHEDULED },
                awayScore = o.obj("awayScore")?.int("current").takeIf { status != MatchStatus.SCHEDULED },
                venue = o.obj("venue")?.str("name"),
            )
        }
    }

    // ---------- posturile TV ----------

    /** Posturile pe țară ale meciului [eventId] (doar id-uri sau id + nume). */
    fun tvUrl(eventId: String): String = "$BASE/tv/event/$eventId/country-channels"

    /** Posturile cunoscute dintr-o țară (codul ISO cu două litere), cu numele lor. */
    fun countryChannelsUrl(country: String): String = "$BASE/tv/country/${country.uppercase()}/channels"

    /** Un singur post (pentru numele lui), când lista țării nu l-a avut. */
    fun channelUrl(channelId: String): String = "$BASE/tv/channel/$channelId/schedule"

    /** Un post din răspunsul unui meci: id-ul și, uneori, numele. */
    data class ChannelRef(val id: String?, val name: String?)

    /** `{"countryChannels": {"GB": [123, …] sau [{"id":…,"name":…}, …]}}` → țară → posturi. */
    fun parseCountryChannels(body: String): Map<String, List<ChannelRef>> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val map = (root["countryChannels"] ?: root["channels"]) as? JsonObject ?: return emptyMap()
        return map.mapNotNull { (country, value) ->
            val arr = value as? JsonArray ?: return@mapNotNull null
            val refs = arr.mapNotNull { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull?.let { ChannelRef(it, null) }
                    is JsonObject -> ChannelRef(el.idString(), el.str("name") ?: el.str("shortName")).takeIf { it.id != null || it.name != null }
                    else -> null
                }
            }
            if (refs.isEmpty()) null else country.uppercase() to refs
        }.toMap()
    }

    /**
     * Numele posturilor din orice răspuns (lista unei țări, programul unui post): obiectele cu `id` și `name`,
     * fără meciurile, echipele și competițiile din ele.
     */
    fun parseChannelNames(body: String): Map<String, String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun walk(el: kotlinx.serialization.json.JsonElement, depth: Int) {
            if (depth > 6) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val id = el.idString()
                    val name = el.str("name")
                    if (id != null && name != null && "homeTeam" !in el && "startTimestamp" !in el) out.putIfAbsent(id, name)
                    for ((k, v) in el) if (k !in skipped) walk(v, depth + 1)
                }
                else -> Unit
            }
        }
        walk(root, 0)
        return out
    }

    private val skipped = setOf("events", "event", "homeTeam", "awayTeam", "tournament", "uniqueTournament", "category", "season", "country")

    /** Prefixele de țară din playlisturi pentru codul ISO [country] („GB” → uk, gb, en). */
    fun prefixes(country: String): Set<String> = Countries.codes(country.lowercase())

    /**
     * Posturile meciului, cu numele aflate ([names]: id → nume), doar din țările [wanted] (codurile ISO;
     * gol = toate). Țara rămâne pe post, pentru potrivirea cu canalele din aceeași țară.
     */
    fun stations(refs: Map<String, List<ChannelRef>>, names: Map<String, String>, wanted: (String) -> Boolean): List<TvStation> =
        refs.filterKeys(wanted).flatMap { (country, list) ->
            list.mapNotNull { r -> (r.name ?: r.id?.let(names::get))?.let { TvStation(it, country) } }
        }.distinct()

    /** Id-urile posturilor fără nume cunoscut, din țările [wanted]. */
    fun unnamed(refs: Map<String, List<ChannelRef>>, names: Map<String, String>, wanted: (String) -> Boolean): Map<String, Set<String>> =
        refs.filterKeys(wanted).mapValues { (_, list) -> list.filter { it.name == null && it.id != null && it.id !in names }.mapNotNull { it.id }.toSet() }
            .filterValues { it.isNotEmpty() }

    /**
     * Meciurile pentru care merită cerute posturile acum (cel mult [max]): cele care n-au început de mult
     * și încep în cel mult 30 de ore, întâi [favorite], apoi cele fără sursă sigură ([covered] = au deja),
     * apoi cele din competițiile [important], fiecare grup după oră.
     */
    fun pickForTv(
        events: List<SportEvent>,
        now: Long,
        max: Int,
        favorite: (SportEvent) -> Boolean,
        covered: (SportEvent) -> Boolean,
        important: (SportEvent) -> Boolean,
    ): List<SportEvent> {
        val from = now - java.util.concurrent.TimeUnit.HOURS.toMillis(3)
        val until = now + java.util.concurrent.TimeUnit.HOURS.toMillis(30)
        return events.asSequence()
            .filter { it.provider == PROVIDER && it.start in from..until && it.status != MatchStatus.FINISHED }
            .distinctBy { it.providerId }
            .sortedWith(
                compareBy<SportEvent>(
                    { if (favorite(it)) 0 else 1 },
                    { if (covered(it)) 1 else 0 },
                    { if (important(it)) 0 else 1 },
                    { it.start },
                ),
            )
            .take(max)
            .toList()
    }

    private fun JsonObject.idString(): String? = (this["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
