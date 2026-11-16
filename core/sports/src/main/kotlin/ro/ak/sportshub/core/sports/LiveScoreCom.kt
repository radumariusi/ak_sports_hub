package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * LiveScore.com, a patra sursă de statistici și formații la fotbal (după ESPN, FotMob și 365Scores): JSON-ul deschis
 * al aplicației (`prod-public-api.livescore.com`, fără cheie). Formatul a fost văzut pe răspunsuri reale (16.11.2026):
 * - programul zilei (`/app/date/soccer/{yyyyMMdd}/0`, ora UTC): `Stages[].Events[]` cu `Eid`, `T1[0].Nm`, `T2[0].Nm`,
 *   `Esd` (yyyyMMddHHmmss), `Eps` (NS, FT, HT, minutul);
 * - statisticile (`/app/statistics/soccer/{Eid}`): `Stat[]` pe echipă (`Tnb` 1 = gazdele), chei scurte (`Pss`, `Shon`…);
 * - formațiile (`/app/lineups/soccer/{Eid}`): `Lu[]` pe echipă cu `Ps[]` (`Fn`, `Ln`, `Pon`, `Snu`), rezervele în `Subs`.
 */
object LiveScoreCom {

    const val PROVIDER = "livescore"
    private const val BASE = "https://prod-public-api.livescore.com/v1/api/app"
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun scheduleUrl(day: LocalDate): String = "$BASE/date/soccer/${day.format(DAY)}/0?MD=1"
    fun statsUrl(eid: String): String = "$BASE/statistics/soccer/$eid"
    fun lineupsUrl(eid: String): String = "$BASE/lineups/soccer/$eid"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    fun parseSchedule(body: String): List<SportEvent> {
        val r = root(body) ?: return emptyList()
        return r.arr("Stages").filterIsInstance<JsonObject>().flatMap { stage ->
            val competition = stage.text("CompN") ?: stage.text("Cnm")
            stage.arr("Events").filterIsInstance<JsonObject>().mapNotNull { e ->
                val id = e.text("Eid") ?: return@mapNotNull null
                val home = (e.arr("T1").firstOrNull() as? JsonObject)?.text("Nm") ?: return@mapNotNull null
                val away = (e.arr("T2").firstOrNull() as? JsonObject)?.text("Nm") ?: return@mapNotNull null
                val start = e.text("Esd")?.let { runCatching { LocalDateTime.parse(it, TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
                    ?: return@mapNotNull null
                val status = when (val s = e.text("Eps")) {
                    null -> null
                    "NS" -> MatchStatus.SCHEDULED
                    "FT", "AET", "AP" -> MatchStatus.FINISHED
                    else -> if (s == "HT" || s.endsWith("'")) MatchStatus.LIVE else null
                }
                SportEvent(
                    provider = PROVIDER,
                    providerId = id,
                    sport = Sport.FOOTBALL.sportsDbName,
                    competition = competition,
                    home = home,
                    away = away,
                    start = start,
                    status = status,
                )
            }
        }
    }

    /** Cheile scurte LiveScore, în ordinea aplicațiilor de statistici. `Gks` = paradele portarului (verificat pe un meci). */
    private val statKeys = listOf(
        "Pss" to "Posesie (%)",
        "Shon" to "Șuturi pe poartă",
        "Shof" to "Șuturi pe lângă",
        "Shbl" to "Șuturi blocate",
        "Shwd" to "Bare",
        "Cos" to "Cornere",
        "Crs" to "Centrări",
        "Gks" to "Parade",
        "Fls" to "Faulturi",
        "Ofs" to "Ofsaid",
        "Ycs" to "Cartonașe galbene",
        "Rcs" to "Cartonașe roșii",
        "Ths" to "Aruncări de la margine",
    )

    fun parseStats(body: String): List<StatLine> {
        val r = root(body) ?: return emptyList()
        val teams = r.arr("Stat").filterIsInstance<JsonObject>()
        val h = teams.firstOrNull { it.text("Tnb") == "1" } ?: return emptyList()
        val a = teams.firstOrNull { it.text("Tnb") == "2" } ?: return emptyList()
        return statKeys.mapNotNull { (key, label) ->
            val hv = h.text(key) ?: return@mapNotNull null
            val av = a.text(key) ?: return@mapNotNull null
            StatLine(label, hv, av)
        }
    }

    fun parseLineup(body: String): List<LineupPlayer> {
        val r = root(body) ?: return emptyList()
        val subs = r["Subs"]
        val out = ArrayList<LineupPlayer>()
        for (team in r.arr("Lu").filterIsInstance<JsonObject>()) {
            val tnb = team.text("Tnb") ?: continue
            val home = tnb == "1"
            team.arr("Ps").filterIsInstance<JsonObject>().forEach { p -> player(p, home, false)?.let(out::add) }
            val bench = when (subs) {
                is JsonObject -> (subs[tnb] as? JsonArray).orEmpty()
                is JsonArray -> subs.filterIsInstance<JsonObject>().firstOrNull { it.text("Tnb") == tnb }?.arr("Ps").orEmpty()
                else -> emptyList()
            }
            bench.filterIsInstance<JsonObject>().forEach { p -> player(p, home, true)?.let(out::add) }
        }
        return out.sortedWith(compareBy({ !it.home }, { it.substitute }))
    }

    private fun player(p: JsonObject, home: Boolean, sub: Boolean): LineupPlayer? {
        val pon = p.text("Pon")
        // Antrenorul stă în aceeași listă.
        if (pon != null && (pon.contains("coach", true) || pon.contains("manager", true))) return null
        if (!sub && p.text("Snu") == null && p.text("Fp") == null) return null
        val name = listOfNotNull(p.text("Fn"), p.text("Ln")).joinToString(" ").ifBlank { p.text("Snm") } ?: return null
        val position = when (pon?.lowercase()) {
            "goalkeeper" -> "Goalkeeper"
            "defender" -> "Defender"
            "midfielder" -> "Midfielder"
            "forward", "attacker", "striker" -> "Forward"
            else -> pon
        }
        return LineupPlayer(name = name, position = if (sub) null else position, number = p.text("Snu"), home = home, substitute = sub)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
