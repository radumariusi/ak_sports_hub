package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Un meci (o manșă) dintr-o dublă manșă sau un meci unic din tablou. */
data class BracketGame(
    val start: Long,
    val status: MatchStatus?,
    val home: String,
    val away: String,
    /** Scorul gazdelor / oaspeților, sau seturile la tenis („6 4 6”); null înainte de meci. */
    val homeScore: String?,
    val awayScore: String?,
)

/**
 * O confruntare din tablou: [first] și [second] (gazda primei manșe întâi), manșele, scorul general și cine merge
 * mai departe ([winner]: 0 = [first], 1 = [second], null = nu se știe încă).
 */
data class BracketTie(
    val first: String,
    val second: String,
    val games: List<BracketGame>,
    /** Scorul general (dubla manșă), scorul meciului unic sau seturile la tenis; null înainte de primul meci. */
    val firstScore: String?,
    val secondScore: String?,
    val winner: Int?,
    /** „după prelungiri”, „după penaltiuri”; null altfel. */
    val note: String?,
) {
    val start: Long get() = games.minOf { it.start }
    val live: Boolean get() = games.any { it.status == MatchStatus.LIVE }
    val started: Boolean get() = games.any { it.status != null && it.status != MatchStatus.SCHEDULED }
    val finished: Boolean get() = winner != null || games.all { it.status == MatchStatus.FINISHED }
}

data class BracketRound(val key: String, val name: String, val ties: List<BracketTie>) {
    val start: Long get() = ties.minOfOrNull { it.start } ?: Long.MAX_VALUE
}

/** Tabloul unei cupe sau al unui turneu: rundele eliminatorii, de la prima la finală. */
data class Bracket(val title: String, val season: String?, val rounds: List<BracketRound>) {
    /**
     * Runda de arătat întâi: cea în curs, altfel prima care n-a început, altfel ultima.
     */
    fun currentRound(): Int {
        val live = rounds.indexOfFirst { r -> r.ties.any { it.live } }
        if (live >= 0) return live
        val open = rounds.indexOfFirst { r -> r.ties.any { !it.finished } }
        return if (open >= 0) open else (rounds.size - 1).coerceAtLeast(0)
    }
}

/**
 * A6: tabloul cupelor și al turneelor, de la ESPN (același JSON public ca programul). La fotbal, programul ligii pe un
 * an întreg (`scoreboard?dates=AAAA&limit=500`: un răspuns, toate meciurile anului; intervalele de zile nu merg), din
 * care se păstrează doar fazele eliminatorii ale celui mai nou sezon care le are (`season.slug`, `series` cu scorul
 * general, câștigătorul). La tenis, programul zilei al turneului de Grand Slam în curs (tot tabloul e în el, pe
 * `groupings` și `round`). Cerut doar la alegerea competiției în tabul „Clasamente”, cel mult o dată la câteva ore.
 * Formatul e verificat pe răspunsuri reale (Liga Campionilor 2025/26, Mondialul 2026, Wimbledon 2025).
 */
object Brackets {

    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"

    /** Cupele și turneele cu fază eliminatorie (cheile `Espn.leagues`). */
    val CUPS: Set<String> = setOf(
        "UCL", "UEL", "UECL", "FACUP", "EFLCUP", "EFLTROPHY", "COPADELREY", "COPPA", "POKAL", "CDF", "TACA", "KNVB",
        "SCOCUP", "SCOLCUP", "NATIONS", "EURO", "WORLDCUP", "CWC", "GOLDCUP", "COPAAMERICA", "AFCON", "ASIANCUP",
        "LIBERTADORES", "SUDAMERICANA", "CONCACAFCC", "LEAGUESCUP", "AFCCL", "WEURO", "WWC", "CONCACAFNL", "UWCL",
    )

    /** Cupele fără grupe sau clasament: se deschid direct pe tablou. */
    val KNOCKOUT_ONLY: Set<String> = setOf("FACUP", "EFLCUP", "COPADELREY", "COPPA", "POKAL", "CDF", "TACA", "KNVB", "SCOCUP")

    /** Turneele de tenis (Grand Slam) cu tablou: ATP = simplu masculin, WTA = simplu feminin. */
    val TENNIS: Set<String> = setOf("ATP", "WTA")

    fun has(league: Espn.League): Boolean = league.key in CUPS || league.key in TENNIS

    /** Anii ceruți: sezonul european trece peste Anul Nou (iulie–iunie); vara se cere și anul următor (tragerile la sorți). */
    fun years(now: Long): List<Int> {
        val d = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        return if (d.monthValue >= 7) listOf(d.year, d.year + 1) else listOf(d.year - 1, d.year)
    }

    fun yearUrl(league: Espn.League, year: Int): String = "$SITE/${league.path}/scoreboard?dates=$year&limit=500"

    fun dayUrl(league: Espn.League, day: LocalDate): String = Espn.scoreboardUrl(league, day)

    // ---------- fotbal (și orice sport pe echipe) ----------

    private class Raw(val id: String, val year: Int, val slug: String, val start: Long, val comp: JsonObject)

    /**
     * Tabloul din răspunsurile pe ani ([bodies], în orice ordine; meciurile dublate se iau o dată). Null dacă nu există
     * nicio fază eliminatorie.
     */
    fun parseCup(bodies: List<String>, title: String): Bracket? {
        val seen = HashSet<String>()
        val raws = ArrayList<Raw>()
        val seasonNames = HashMap<Int, String>()
        for (body in bodies) {
            val root = parse(body) ?: continue
            (root.arr("leagues").firstOrNull() as? JsonObject)?.obj("season")?.let { s ->
                val y = s.text("year")?.toIntOrNull()
                val n = s.text("displayName")
                if (y != null && n != null) seasonNames[y] = n
            }
            for (e in root.arr("events").filterIsInstance<JsonObject>()) {
                val id = e.text("id") ?: continue
                if (!seen.add(id)) continue
                val season = e.obj("season") ?: continue
                val slug = season.text("slug") ?: continue
                if (!knockout(slug)) continue
                val year = season.text("year")?.toIntOrNull() ?: continue
                val comp = e.arr("competitions").firstOrNull() as? JsonObject ?: continue
                if (cancelled(comp)) continue
                val start = (e.text("date") ?: comp.text("date"))?.let(::isoMillis) ?: continue
                raws += Raw(id, year, slug, start, comp)
            }
        }
        if (raws.isEmpty()) return null
        val year = raws.maxOf { it.year }
        val season = raws.filter { it.year == year }
        val rounds = season.groupBy { it.slug }.map { (slug, games) -> BracketRound(slug, roundName(slug), ties(games)) }
            .sortedBy { it.start }
        return Bracket(title, seasonNames[year]?.let(::seasonLabel) ?: seasonLabel(year, season.map { it.start }), rounds)
    }

    /** „2025-26 UEFA Champions League” → „2025/26”; „2026 FIFA World Cup” → „2026”. */
    private fun seasonLabel(name: String): String? {
        val m = SEASON.find(name) ?: return null
        return m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "/" + it.takeLast(2) } ?: "")
    }

    /** Sezonul fără nume de la ESPN: anul lui, iar dacă meciurile trec în anul următor, „2025/26”. */
    private fun seasonLabel(year: Int, starts: List<Long>): String {
        val last = starts.maxOfOrNull { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year } ?: year
        return if (last > year) "$year/${((year + 1) % 100).toString().padStart(2, '0')}" else year.toString()
    }

    private val SEASON = Regex("""(\d{4})(?:[-/](\d{2,4}))?""")

    /** Faza e eliminatorie (nu grupe, faza ligii sau sezonul regulat). */
    fun knockout(slug: String): Boolean {
        val s = slug.lowercase()
        return !("group" in s || "league-phase" in s || "regular" in s || "qualif" in s || "preseason" in s || s == "league")
    }

    private fun ties(games: List<Raw>): List<BracketTie> {
        val byPair = LinkedHashMap<String, MutableList<Raw>>()
        for (g in games.sortedBy { it.start }) {
            val ids = competitors(g.comp).map { it.text("id") ?: it.obj("team")?.text("id") ?: name(it) }.sorted()
            byPair.getOrPut(ids.joinToString("|")) { ArrayList() } += g
        }
        return byPair.values.mapNotNull(::tie).sortedBy { it.start }
    }

    private fun competitors(comp: JsonObject): List<JsonObject> = comp.arr("competitors").filterIsInstance<JsonObject>()

    private fun name(c: JsonObject): String =
        c.obj("team")?.let { it.text("displayName") ?: it.text("name") } ?: c.obj("athlete")?.text("displayName") ?: "?"

    private fun tie(legs: List<Raw>): BracketTie? {
        val firstLeg = legs.first()
        val teams = competitors(firstLeg.comp)
        if (teams.size < 2) return null
        val home = teams.firstOrNull { it.text("homeAway") == "home" } ?: teams[0]
        val away = teams.firstOrNull { it !== home } ?: return null
        val firstId = home.text("id") ?: home.obj("team")?.text("id")
        val firstName = name(home)
        val secondName = name(away)
        fun isFirst(c: JsonObject): Boolean = (c.text("id") ?: c.obj("team")?.text("id"))?.let { it == firstId } ?: (name(c) == firstName)

        val games = legs.map { leg ->
            val cs = competitors(leg.comp)
            val h = cs.firstOrNull { it.text("homeAway") == "home" } ?: cs[0]
            val a = cs.firstOrNull { it !== h } ?: cs[0]
            val status = status(leg.comp)
            BracketGame(
                start = leg.start,
                status = status,
                home = name(h),
                away = name(a),
                homeScore = h.text("score")?.takeIf { status != MatchStatus.SCHEDULED && status != null },
                awayScore = a.text("score")?.takeIf { status != MatchStatus.SCHEDULED && status != null },
            )
        }

        // Scorul general: din `series` (ESPN îl dă la dubla manșă), altfel suma manșelor jucate.
        val last = legs.last()
        val series = legs.asReversed().firstNotNullOfOrNull { it.comp.obj("series")?.takeIf { s -> s.arr("competitors").isNotEmpty() } }
        var firstAgg: Int? = null
        var secondAgg: Int? = null
        var winner: Int? = null
        if (series != null) {
            for (c in series.arr("competitors").filterIsInstance<JsonObject>()) {
                val agg = c.text("aggregateScore")?.toDoubleOrNull()?.toInt()
                val first = c.text("id") == firstId
                if (first) firstAgg = agg else secondAgg = agg
                if (series.text("completed") == "true" && c.text("winner") == "true") winner = if (first) 0 else 1
            }
        }
        val played = legs.filter { status(it.comp).let { s -> s == MatchStatus.LIVE || s == MatchStatus.FINISHED } }
        if (firstAgg == null || secondAgg == null) {
            if (played.isNotEmpty()) {
                firstAgg = played.sumOf { l -> competitors(l.comp).firstOrNull(::isFirst)?.text("score")?.toIntOrNull() ?: 0 }
                secondAgg = played.sumOf { l -> competitors(l.comp).firstOrNull { !isFirst(it) }?.text("score")?.toIntOrNull() ?: 0 }
            }
        }
        if (winner == null && status(last.comp) == MatchStatus.FINISHED && legs.all { status(it.comp) == MatchStatus.FINISHED }) {
            val flagged = competitors(last.comp).firstOrNull { it.text("winner") == "true" }
            winner = when {
                flagged != null -> if (isFirst(flagged)) 0 else 1
                firstAgg != null && secondAgg != null && firstAgg != secondAgg -> if (firstAgg!! > secondAgg!!) 0 else 1
                else -> null
            }
        }
        val statusName = last.comp.obj("status")?.obj("type")?.text("name")?.uppercase().orEmpty()
        val headline = last.comp.arr("notes").filterIsInstance<JsonObject>().mapNotNull { it.text("headline") }.joinToString(" ").lowercase()
        val note = when {
            "PEN" in statusName || "SHOOTOUT" in statusName || "penalt" in headline -> "după penaltiuri"
            "AET" in statusName || "EXTRA" in statusName || "extra time" in headline -> "după prelungiri"
            else -> null
        }
        return BracketTie(
            first = firstName,
            second = secondName,
            games = games,
            firstScore = firstAgg?.toString(),
            secondScore = secondAgg?.toString(),
            winner = winner,
            note = note,
        )
    }

    // ---------- tenis ----------

    /**
     * Tabloul turneului de Grand Slam în curs din programul unei zile ATP/WTA: simplul masculin ([men]) sau feminin,
     * fără calificări. Null dacă azi nu e niciun Grand Slam.
     */
    fun parseTennis(body: String, men: Boolean): Bracket? {
        val root = parse(body) ?: return null
        val event = root.arr("events").filterIsInstance<JsonObject>().firstOrNull { it.text("major") == "true" } ?: return null
        val wanted = if (men) "mens-singles" else "womens-singles"
        val group = event.arr("groupings").filterIsInstance<JsonObject>()
            .firstOrNull { (it.obj("grouping")?.text("slug") ?: it.text("slug")) == wanted } ?: return null
        val byRound = LinkedHashMap<String, MutableList<BracketTie>>()
        for (c in group.arr("competitions").filterIsInstance<JsonObject>()) {
            val round = c.obj("round")?.text("displayName") ?: continue
            if ("qualif" in round.lowercase()) continue
            if (cancelled(c)) continue
            val start = (c.text("date") ?: c.text("startDate"))?.let(::isoMillis) ?: continue
            val players = competitors(c).sortedBy { it.text("order")?.toIntOrNull() ?: 0 }
            if (players.size < 2) continue
            val status = status(c)
            val sets = players.map { p ->
                p.arr("linescores").filterIsInstance<JsonObject>().mapNotNull { it.text("value")?.toDoubleOrNull()?.toInt()?.toString() }
                    .takeIf { it.isNotEmpty() && status != MatchStatus.SCHEDULED }?.joinToString(" ")
            }
            val a = name(players[0])
            val b = name(players[1])
            val winner = players.indexOfFirst { it.text("winner") == "true" }.takeIf { it >= 0 && status == MatchStatus.FINISHED }
            byRound.getOrPut(round) { ArrayList() } += BracketTie(
                first = a,
                second = b,
                games = listOf(BracketGame(start, status, a, b, sets[0], sets[1])),
                firstScore = sets[0],
                secondScore = sets[1],
                winner = winner,
                note = null,
            )
        }
        if (byRound.isEmpty()) return null
        val rounds = byRound.map { (name, ties) -> BracketRound(name, roundName(name), ties.sortedBy { it.start }) }.sortedBy { it.start }
        val year = event.text("date")?.let(::isoMillis)?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
        return Bracket(event.text("name") ?: "Grand Slam", year?.toString(), rounds)
    }

    // ---------- numele rundelor ----------

    /** Runda, pe românește („round-of-16” → „Optimi”, „Quarterfinal” → „Sferturi”). */
    fun roundName(raw: String): String {
        val s = raw.lowercase().replace('_', '-').replace(' ', '-')
        ROUND_N.find(s)?.let { return "Turul ${it.groupValues[1]}" }
        return when {
            "3rd" in s || "third-place" in s -> "Finala mică"
            s.startsWith("quarter") -> "Sferturi"
            s.startsWith("semi") -> "Semifinale"
            s == "final" || s == "finals" || s == "final-round" -> "Finala"
            "knockout-round-playoff" in s || "playoff" in s || "play-off" in s -> "Baraj"
            s == "round-of-128" -> "Turul 1 (128)"
            s == "round-of-64" -> "1/32 de finală"
            s == "round-of-32" -> "Șaisprezecimi"
            s == "round-of-16" -> "Optimi"
            else -> ORDINALS.entries.firstOrNull { s.startsWith(it.key + "-round") }?.let { "Turul ${it.value}" }
                ?: raw.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private val ROUND_N = Regex("""^round-(\d+)$""")

    private val ORDINALS = linkedMapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
    )

    // ---------- JSON ----------

    private fun status(o: JsonObject): MatchStatus? = when (o.obj("status")?.obj("type")?.text("state")) {
        "pre" -> MatchStatus.SCHEDULED
        "in" -> MatchStatus.LIVE
        "post" -> MatchStatus.FINISHED
        else -> null
    }

    private fun cancelled(o: JsonObject): Boolean {
        val name = o.obj("status")?.obj("type")?.text("name").orEmpty()
        return "POSTPONED" in name || "CANCELED" in name || "CANCELLED" in name || "ABANDONED" in name
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
