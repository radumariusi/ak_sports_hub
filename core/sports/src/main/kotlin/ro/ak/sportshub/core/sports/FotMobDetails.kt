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
        // Statisticile stau în `content.stats`; restul paginii (arbitrul, clasamentul) are și el liste „stats”.
        val pageProps = (root as? JsonObject)?.obj("props")?.obj("pageProps")
        val content = pageProps?.obj("content")
        collectStats(content?.get("stats") ?: root, stats, 0)
        val lineup = findLineup(content?.get("lineup") ?: root, 0).orEmpty()
        val homeId = pageProps?.obj("general")?.obj("homeTeam")?.text("id")
        val d = MatchDetails(
            stats = stats.distinctBy { it.name },
            lineup = lineup,
            momentum = content?.let(::momentum).orEmpty(),
            shots = content?.let { shots(it, homeId) }.orEmpty(),
            absent = content?.let(::absent).orEmpty(),
            venue = content?.let(::venue)?.takeIf { !it.isEmpty },
        )
        return d.takeIf { !it.isEmpty }
    }

    /**
     * Pagina redusă la ce citim (`general` și `content` fără statisticile jucătorilor, comentarii, știri), ca JSON:
     * ~1 MB → câteva zeci de KB, recitit des cât e deschis meciul. Null dacă pagina nu are datele Next.js.
     */
    fun compact(html: String): String? {
        val data = runCatching { Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val pp = root.obj("props")?.obj("pageProps") ?: return null
        val content = pp.obj("content")?.let { c ->
            JsonObject(c.filterKeys { it in KEPT_CONTENT }.mapValues { (k, v) ->
                if (k == "matchFacts" && v is JsonObject) JsonObject(v.filterKeys { it == "infoBox" || it == "momentum" }) else v
            })
        }
        val small = JsonObject(mapOf("props" to JsonObject(mapOf("pageProps" to JsonObject(listOfNotNull(
            pp["general"]?.let { "general" to it },
            content?.let { "content" to it },
        ).toMap())))))
        return small.toString()
    }

    private val KEPT_CONTENT = setOf("stats", "lineup", "momentum", "shotmap", "weather", "matchFacts")

    // ---------- datele în plus (formatul văzut pe pagini reale, 16.11.2026) ----------

    /** `content.momentum.main.data`: `{minute, value}`, valoarea pozitivă = gazdele. */
    private fun momentum(content: JsonObject): List<MomentumPoint> {
        val data = content.obj("momentum")?.obj("main")?.get("data") as? JsonArray
            ?: content.obj("matchFacts")?.obj("momentum")?.obj("main")?.get("data") as? JsonArray
            ?: return emptyList()
        return data.filterIsInstance<JsonObject>().mapNotNull { o ->
            val m = o.text("minute")?.toFloatOrNull() ?: return@mapNotNull null
            val v = o.text("value")?.toFloatOrNull() ?: return@mapNotNull null
            MomentumPoint(m, v.toInt().coerceIn(-100, 100))
        }.sortedBy { it.minute }
    }

    /**
     * `content.shotmap.shots`: `x` 0–105 spre poarta atacată (ambele echipe), `y` 0–68 pe lățime, `eventType`
     * („Goal”, „AttemptSaved”, „Miss”, „Post”), `isBlocked`, `teamId`, `min` / `minAdded`, `expectedGoals`.
     */
    private fun shots(content: JsonObject, homeId: String?): List<Shot> {
        val list = content.obj("shotmap")?.get("shots") as? JsonArray ?: return emptyList()
        return list.filterIsInstance<JsonObject>().mapNotNull { o ->
            val x = o.text("x")?.toFloatOrNull() ?: return@mapNotNull null
            val y = o.text("y")?.toFloatOrNull() ?: return@mapNotNull null
            val type = o.text("eventType")?.lowercase().orEmpty()
            val outcome = when {
                type == "goal" && o.text("isOwnGoal") != "true" -> ShotOutcome.GOAL
                type == "goal" -> return@mapNotNull null // autogolul nu e șutul echipei care îl primește
                o.text("isBlocked") == "true" -> ShotOutcome.BLOCKED
                "post" in type || "woodwork" in type -> ShotOutcome.POST
                type == "attemptsaved" || o.text("isOnTarget") == "true" -> ShotOutcome.SAVED
                else -> ShotOutcome.MISSED
            }
            Shot(
                x = (x / 105f).coerceIn(0f, 1f),
                y = (y / 68f).coerceIn(0f, 1f),
                minute = o.text("min")?.toFloatOrNull()?.toInt(),
                added = o.text("minAdded")?.toFloatOrNull()?.toInt(),
                home = homeId == null || o.text("teamId") == homeId,
                player = o.text("playerName") ?: o.text("fullName"),
                outcome = outcome,
                xg = o.text("expectedGoals")?.toFloatOrNull(),
            )
        }
    }

    /** `content.lineup.homeTeam|awayTeam.unavailable[]`: `name`, `unavailability.type` / `expectedReturn`. */
    private fun absent(content: JsonObject): List<AbsentPlayer> {
        val lineup = content.obj("lineup") ?: return emptyList()
        fun side(key: String, home: Boolean) = (lineup.obj(key)?.get("unavailable") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { p ->
            val name = p.text("name") ?: return@mapNotNull null
            val u = p.obj("unavailability")
            val back = MatchExtras.returnText(u?.text("expectedReturn"))?.let { "revine: $it" }
            AbsentPlayer(name, home, MatchExtras.fotmobKind(u?.text("type")), back)
        }
        return MatchExtras.sorted(side("homeTeam", true) + side("awayTeam", false))
    }

    /** `matchFacts.infoBox` (Stadium, Referee, Attendance) și `content.weather` (°C, descriere, șansa de ploaie). */
    private fun venue(content: JsonObject): VenueInfo {
        val info = content.obj("matchFacts")?.obj("infoBox")
        val stadium = info?.obj("Stadium")
        val w = content.obj("weather")
        return VenueInfo(
            stadium = stadium?.text("name"),
            city = stadium?.text("city"),
            capacity = stadium?.text("capacity")?.toIntOrNull()?.takeIf { it > 0 },
            referee = info?.obj("Referee")?.text("text"),
            attendance = info?.text("Attendance")?.toIntOrNull()?.takeIf { it > 0 },
            weather = w?.let {
                Weather(
                    celsius = it.text("temperature")?.toDoubleOrNull()?.let { t -> Math.round(t).toInt() },
                    description = MatchExtras.weatherText(it.text("description") ?: it.text("defaultTitle")),
                    rainChance = it.text("precipChance")?.toDoubleOrNull()?.toInt(),
                )
            }?.takeIf { it.celsius != null || it.description != null },
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

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
