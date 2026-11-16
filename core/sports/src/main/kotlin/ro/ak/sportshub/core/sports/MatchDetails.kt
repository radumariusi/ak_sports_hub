package ro.ak.sportshub.core.sports

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Detaliile unui meci de la TheSportsDB (`lookupevent.php`). */
data class EventDetails(
    val eventId: String,
    val home: String,
    val away: String,
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    val leagueId: String? = null,
    val league: String? = null,
    val season: String? = null,
    val round: String? = null,
    val start: Long? = null,
    val status: MatchStatus? = null,
    /** Codul stării, așa cum vine („1H”, „HT”, „FT”). */
    val statusCode: String? = null,
    /** Minutul, când furnizorul îl dă („67”, „45+2”). */
    val progress: String? = null,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val venue: String? = null,
    val city: String? = null,
    val referee: String? = null,
    val spectators: Int? = null,
)

/** Un rând din clasament. */
data class StandingRow(
    val rank: Int,
    val team: String,
    val teamId: String?,
    val played: Int?,
    val won: Int?,
    val drawn: Int?,
    val lost: Int?,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    val points: Int?,
)

/** Un jucător din echipa de start sau de pe bancă. */
data class LineupPlayer(
    val name: String,
    val position: String?,
    val number: String?,
    val home: Boolean,
    val substitute: Boolean,
)

enum class TimelineKind(val label: String) {
    GOAL("Gol"),
    OWN_GOAL("Autogol"),
    PENALTY("Gol din penalty"),
    MISSED_PENALTY("Penalty ratat"),
    YELLOW("Cartonaș galben"),
    RED("Cartonaș roșu"),
    SUBSTITUTION("Schimbare"),
    VAR("VAR"),
    OTHER("Fază"),
}

/** Un eveniment din meci: gol, cartonaș, schimbare. */
data class TimelineEntry(
    val minute: Int?,
    val kind: TimelineKind,
    val home: Boolean,
    val player: String?,
    /** Pasa decisivă sau jucătorul care iese, la schimbări. */
    val other: String?,
)

/** O statistică a meciului, ca text (posesia vine ca „55”, golurile așteptate ca „1.32”). */
data class StatLine(val name: String, val home: String, val away: String)

/** Tot ce știm despre un meci pentru taburile Pre-meci și Live. Fiecare parte poate lipsi. */
data class MatchDetails(
    val event: EventDetails? = null,
    /** Înainte de meci: statisticile sezonului ale echipelor și cei mai buni jucători (ESPN). */
    val seasonStats: List<StatLine> = emptyList(),
    /** Ultimele meciuri ale gazdelor și ale oaspeților, cele mai noi primele. */
    val homeRecent: List<SportEvent> = emptyList(),
    val awayRecent: List<SportEvent> = emptyList(),
    val headToHead: List<SportEvent> = emptyList(),
    val table: List<StandingRow> = emptyList(),
    val lineup: List<LineupPlayer> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    val stats: List<StatLine> = emptyList(),
    /** Șansele de câștig (ESPN: previziunea înainte de meci, apoi seria live). */
    val winProbability: WinProbability? = null,
    /** Presiunea pe minute (FotMob): pozitiv = gazdele, negativ = oaspeții, între -100 și 100. */
    val momentum: List<MomentumPoint> = emptyList(),
    /** Șuturile cu locul lor pe teren (FotMob). */
    val shots: List<Shot> = emptyList(),
    /** Jucătorii indisponibili: accidentați, suspendați, incerți (ESPN la sporturile americane, FotMob la fotbal). */
    val absent: List<AbsentPlayer> = emptyList(),
    /** Stadionul, arbitrul și vremea, completate din FotMob când ESPN nu le are. */
    val venue: VenueInfo? = null,
) {
    val isEmpty: Boolean
        get() = event == null && homeRecent.isEmpty() && awayRecent.isEmpty() && headToHead.isEmpty() &&
            table.isEmpty() && lineup.isEmpty() && timeline.isEmpty() && stats.isEmpty() && seasonStats.isEmpty() &&
            winProbability == null && momentum.isEmpty() && shots.isEmpty() && absent.isEmpty() && venue == null
}

/** Rezultatul unei echipe într-un meci trecut. */
enum class FormResult(val letter: String) { WIN("V"), DRAW("E"), LOSS("Î") }

/**
 * Cererile și citirea răspunsurilor TheSportsDB pentru previzualizarea unui meci.
 * Cererile nu merg la furnizorul de IPTV (nu folosesc conexiunea playlisturilor).
 */
object TheSportsDbDetails {

    private const val BASE = "https://www.thesportsdb.com/api/v1/json"

    private fun base(key: String) = "$BASE/${enc(key.ifBlank { TheSportsDb.DEFAULT_KEY })}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun lookupEventUrl(key: String, eventId: String) = "${base(key)}/lookupevent.php?id=${enc(eventId)}"
    fun lastEventsUrl(key: String, teamId: String) = "${base(key)}/eventslast.php?id=${enc(teamId)}"
    fun tableUrl(key: String, leagueId: String, season: String) =
        "${base(key)}/lookuptable.php?l=${enc(leagueId)}&s=${enc(season)}"
    fun lineupUrl(key: String, eventId: String) = "${base(key)}/lookuplineup.php?id=${enc(eventId)}"
    fun timelineUrl(key: String, eventId: String) = "${base(key)}/lookuptimeline.php?id=${enc(eventId)}"
    fun statsUrl(key: String, eventId: String) = "${base(key)}/lookupeventstats.php?id=${enc(eventId)}"

    /** Căutarea „Gazde vs Oaspeți” (dă meciurile directe și, des, meciul însuși). */
    fun searchUrl(key: String, home: String, away: String): String {
        val name = "${home.trim()} vs ${away.trim()}".replace(Regex("""\s+"""), "_")
        return "${base(key)}/searchevents.php?e=${enc(name)}"
    }

    /** Id-ul TheSportsDB din id-ul meciului din Sports Hub („thesportsdb:12345”). */
    fun eventIdOf(matchId: String): String? =
        matchId.takeIf { it.startsWith(TheSportsDb.PROVIDER + ":") }?.substringAfter(':')?.takeIf { it.isNotBlank() }

    fun parseEvent(body: String): EventDetails? = TheSportsDb.firstArray(body).firstNotNullOfOrNull { e ->
        val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
        val id = o.s("idEvent") ?: return@firstNotNullOfOrNull null
        val code = o.s("strStatus")
        EventDetails(
            eventId = id,
            home = o.s("strHomeTeam") ?: o.s("strEvent") ?: "",
            away = o.s("strAwayTeam") ?: "",
            homeTeamId = o.s("idHomeTeam"),
            awayTeamId = o.s("idAwayTeam"),
            leagueId = o.s("idLeague"),
            league = o.s("strLeague"),
            season = o.s("strSeason"),
            round = o.s("intRound")?.takeIf { it != "0" },
            start = TheSportsDb.parseStart(o.s("strTimestamp"), o.s("dateEvent"), o.s("strTime")),
            status = TheSportsDb.status(code),
            statusCode = code,
            progress = o.s("strProgress"),
            homeScore = o.s("intHomeScore")?.toIntOrNull(),
            awayScore = o.s("intAwayScore")?.toIntOrNull(),
            venue = o.s("strVenue"),
            city = o.s("strCity"),
            referee = o.s("strOfficial") ?: o.s("strReferee"),
            spectators = o.s("intSpectators")?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    /** `eventslast.php` și `searchevents.php`: meciuri, cu scor când s-au jucat. */
    fun parseResults(body: String): List<SportEvent> = TheSportsDb.parseEvents(body)

    fun parseTable(body: String): List<StandingRow> = TheSportsDb.firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val team = o.s("strTeam") ?: o.s("name") ?: return@mapNotNull null
        StandingRow(
            rank = o.s("intRank")?.toIntOrNull() ?: return@mapNotNull null,
            team = team,
            teamId = o.s("idTeam") ?: o.s("teamid"),
            played = o.s("intPlayed")?.toIntOrNull(),
            won = o.s("intWin")?.toIntOrNull(),
            drawn = o.s("intDraw")?.toIntOrNull(),
            lost = o.s("intLoss")?.toIntOrNull(),
            goalsFor = o.s("intGoalsFor")?.toIntOrNull(),
            goalsAgainst = o.s("intGoalsAgainst")?.toIntOrNull(),
            points = o.s("intPoints")?.toIntOrNull(),
        )
    }.sortedBy { it.rank }

    fun parseLineup(body: String): List<LineupPlayer> = TheSportsDb.firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o.s("strPlayer") ?: return@mapNotNull null
        LineupPlayer(
            name = name,
            position = o.s("strPosition"),
            number = o.s("intSquadNumber"),
            home = o.yes("strHome"),
            substitute = o.yes("strSubstitute"),
        )
    }

    fun parseTimeline(body: String): List<TimelineEntry> = TheSportsDb.firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val type = o.s("strTimeline")?.lowercase().orEmpty()
        val detail = o.s("strTimelineDetail")?.lowercase().orEmpty()
        val kind = when {
            "var" in type || "var" in detail.split(' ') -> TimelineKind.VAR
            type.startsWith("goal") && "own" in detail -> TimelineKind.OWN_GOAL
            type.startsWith("goal") && "missed" in detail -> TimelineKind.MISSED_PENALTY
            type.startsWith("goal") && "penalty" in detail -> TimelineKind.PENALTY
            type.startsWith("goal") -> TimelineKind.GOAL
            "card" in type && ("red" in detail || "second yellow" in detail) -> TimelineKind.RED
            "card" in type -> TimelineKind.YELLOW
            type.startsWith("subst") -> TimelineKind.SUBSTITUTION
            else -> TimelineKind.OTHER
        }
        val player = o.s("strPlayer")
        if (player == null && kind == TimelineKind.OTHER) return@mapNotNull null
        TimelineEntry(
            minute = o.s("intTime")?.toIntOrNull(),
            kind = kind,
            home = o.yes("strHome"),
            player = player,
            other = o.s("strAssist"),
        )
    }.sortedBy { it.minute ?: Int.MAX_VALUE }

    fun parseStats(body: String): List<StatLine> = TheSportsDb.firstArray(body).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o.s("strStat") ?: return@mapNotNull null
        StatLine(statLabel(name), o.s("intHome") ?: "0", o.s("intAway") ?: "0")
    }

    private val statNames = mapOf(
        "ball possession" to "Posesie (%)",
        "shots on goal" to "Șuturi pe poartă",
        "shots off goal" to "Șuturi pe lângă",
        "total shots" to "Total șuturi",
        "blocked shots" to "Șuturi blocate",
        "shots insidebox" to "Șuturi din careu",
        "shots outsidebox" to "Șuturi din afara careului",
        "fouls" to "Faulturi",
        "corner kicks" to "Cornere",
        "offsides" to "Ofsaiduri",
        "yellow cards" to "Cartonașe galbene",
        "red cards" to "Cartonașe roșii",
        "goalkeeper saves" to "Parade",
        "total passes" to "Pase",
        "passes accurate" to "Pase reușite",
        "passes %" to "Precizia paselor (%)",
        "expected_goals" to "Goluri așteptate (xG)",
        "expected goals" to "Goluri așteptate (xG)",
        // Numele FotMob și 365Scores, văzute pe răspunsuri reale (16.11.2026).
        "possession" to "Posesie (%)",
        "expected goals (xg)" to "Goluri așteptate (xG)",
        "shots on target" to "Șuturi pe poartă",
        "shots off target" to "Șuturi pe lângă",
        "shots blocked" to "Șuturi blocate",
        "shots inside box" to "Șuturi din careu",
        "shots inside the box" to "Șuturi din careu",
        "shots outside box" to "Șuturi din afara careului",
        "shots outside the box" to "Șuturi din afara careului",
        "hit woodwork" to "Șuturi în bară",
        "big chances" to "Ocazii mari",
        "big chances created" to "Ocazii mari",
        "big chances missed" to "Ocazii mari ratate",
        "xg on target (xgot)" to "xG pe poartă (xGOT)",
        "expected goals on target" to "xG pe poartă (xGOT)",
        "xg open play" to "xG din joc",
        "xg set play" to "xG din faze fixe",
        "xg non-penalty" to "xG fără penalty",
        "expected assists" to "Pase de gol așteptate (xA)",
        "touches in opposition box" to "Atingeri în careul advers",
        "corners" to "Cornere",
        "free kicks" to "Lovituri libere",
        "attacks" to "Atacuri",
        "passes" to "Pase",
        "accurate passes" to "Pase reușite",
        "passes completed" to "Pase reușite",
        "own half" to "Pase în propria jumătate",
        "passes own half" to "Pase în propria jumătate",
        "opposition half" to "Pase în jumătatea adversă",
        "passes opposition half" to "Pase în jumătatea adversă",
        "accurate long balls" to "Pase lungi reușite",
        "long passes completed" to "Pase lungi reușite",
        "accurate crosses" to "Centrări reușite",
        "crosses completed" to "Centrări reușite",
        "key passes" to "Pase cheie",
        "throws" to "Aruncări de la margine",
        "throw-ins" to "Aruncări de la margine",
        "tackles" to "Deposedări",
        "tackles won" to "Deposedări reușite",
        "interceptions" to "Intercepții",
        "blocks" to "Blocaje",
        "clearances" to "Degajări",
        "keeper saves" to "Parade",
        "duels won" to "Dueluri câștigate",
        "ground duels won" to "Dueluri la sol câștigate",
        "aerial duels won" to "Dueluri aeriene câștigate",
        "successful dribbles" to "Driblinguri reușite",
        "fouls committed" to "Faulturi",
        "was fouled" to "Faulturi suferite",
        "possession lost" to "Mingi pierdute",
        "distance covered" to "Distanță parcursă",
    )

    /** Numele statisticii în română, când îl știm; altfel cel primit. */
    fun statLabel(name: String): String = statNames[name.trim().lowercase()] ?: name.trim()

    private fun JsonObject.s(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.yes(name: String): Boolean = s(name)?.lowercase() in setOf("yes", "true", "1", "y")
}

/** Calculele pentru taburile Pre-meci și Live, fără rețea. */
object MatchInsights {

    /** Căutarea poate da meciul cu altă oră (fus orar, reprogramare): acceptăm până la 3 ore diferență. */
    private val SEARCH_WINDOW_MS = TimeUnit.HOURS.toMillis(3)

    /** Meciul TheSportsDB pentru un meci doar din EPG, din rezultatele căutării. */
    fun pickEvent(candidates: List<SportEvent>, home: String, away: String, start: Long): SportEvent? =
        candidates
            .filter { TeamNames.samePair(it.home, it.away, home, away) && abs(it.start - start) <= SEARCH_WINDOW_MS }
            .minByOrNull { abs(it.start - start) }

    /** Meciurile directe jucate înainte de [before], cele mai noi primele. */
    fun headToHead(candidates: List<SportEvent>, home: String, away: String, before: Long, limit: Int = 6): List<SportEvent> =
        candidates
            .filter { TeamNames.samePair(it.home, it.away, home, away) && it.start < before && it.hasScore }
            .distinctBy { it.providerId }
            .sortedByDescending { it.start }
            .take(limit)

    /** Ultimele meciuri jucate înainte de [before], cele mai noi primele. */
    fun recent(results: List<SportEvent>, before: Long, limit: Int = 5): List<SportEvent> =
        results.filter { it.start < before && it.hasScore }
            .distinctBy { it.providerId }
            .sortedByDescending { it.start }
            .take(limit)

    /** Rezultatul echipei [team] într-un meci jucat; null dacă echipa nu e în meci sau nu are scor. */
    fun resultFor(e: SportEvent, team: String): FormResult? {
        val hs = e.homeScore ?: return null
        val aw = e.awayScore ?: return null
        val (mine, theirs) = when {
            TeamNames.same(e.home, team) -> hs to aw
            TeamNames.same(e.away, team) -> aw to hs
            else -> return null
        }
        return when {
            mine > theirs -> FormResult.WIN
            mine < theirs -> FormResult.LOSS
            else -> FormResult.DRAW
        }
    }

    /** Forma, ca „V V E Î V” (cel mai nou primul). */
    fun form(results: List<SportEvent>, team: String): List<FormResult> = results.mapNotNull { resultFor(it, team) }

    /** Bilanțul meciurilor directe din perspectiva gazdelor: victorii, egaluri, înfrângeri. */
    fun headToHeadSummary(h2h: List<SportEvent>, home: String): Triple<Int, Int, Int> {
        val r = h2h.mapNotNull { resultFor(it, home) }
        return Triple(r.count { it == FormResult.WIN }, r.count { it == FormResult.DRAW }, r.count { it == FormResult.LOSS })
    }

    /** Rândurile din clasament ale celor două echipe (după id, altfel după nume). */
    fun tableRowsFor(table: List<StandingRow>, event: EventDetails?, home: String, away: String): Set<Int> =
        table.filter { row ->
            (event?.homeTeamId != null && row.teamId == event.homeTeamId) ||
                (event?.awayTeamId != null && row.teamId == event.awayTeamId) ||
                TeamNames.same(row.team, home) || (away.isNotBlank() && TeamNames.same(row.team, away))
        }.map { it.rank }.toSet()

    /** Starea pe scurt, în română („Repriza 2 · 67'”, „Pauză”, „Final”). */
    fun liveLabel(status: MatchStatus?, code: String?, progress: String?): String {
        val c = code?.trim()?.uppercase().orEmpty()
        val phase = when (c) {
            "1H" -> "Repriza 1"
            "2H" -> "Repriza 2"
            "HT" -> "Pauză"
            "ET" -> "Prelungiri"
            "BT" -> "Pauză înainte de prelungiri"
            "P", "PEN LIVE" -> "Lovituri de departajare"
            "FT", "MATCH FINISHED" -> "Final"
            "AET" -> "Final după prelungiri"
            "PEN" -> "Final după penalty-uri"
            "Q1", "Q2", "Q3", "Q4" -> "Sfertul ${c.last()}"
            "OT" -> "Prelungiri"
            "NS" -> "Nu a început"
            else -> when (status) {
                MatchStatus.SCHEDULED -> "Nu a început"
                MatchStatus.LIVE -> "În desfășurare"
                MatchStatus.FINISHED -> "Încheiat"
                null -> ""
            }
        }
        val minute = progress?.trim()?.takeIf { it.isNotEmpty() && status == MatchStatus.LIVE && c != "HT" }
            ?.let { if (it.last().isDigit()) "$it'" else it }
        return listOfNotNull(phase.ifEmpty { null }, minute).joinToString(" · ")
    }

    private val SportEvent.hasScore: Boolean get() = homeScore != null && awayScore != null
}

/**
 * Modul „fără spoilere”: scorul, evenimentele și statisticile unui meci început sau terminat
 * rămân ascunse până le ceri pentru acel meci.
 */
object Spoilers {
    fun hide(noSpoilers: Boolean, revealed: Boolean, status: MatchStatus?): Boolean =
        noSpoilers && !revealed && status != null && status != MatchStatus.SCHEDULED
}

/**
 * Cât de des se cer din nou detaliile. Cererile sunt rare: pre-meci la câteva ore (des doar în ora
 * dinaintea startului, când apar echipele de start), live la un minut doar cât e deschis tabul Live.
 * Un meci terminat nu se mai cere după ce avem datele de după final.
 */
object DetailsSchedule {

    enum class Part { PRE_MATCH, LIVE }

    val LIVE_REFRESH_MS: Long = TimeUnit.SECONDS.toMillis(60)
    private val FAR_REFRESH_MS = TimeUnit.HOURS.toMillis(6)
    private val NEAR_REFRESH_MS = TimeUnit.MINUTES.toMillis(20)
    private val NEAR_WINDOW_MS = TimeUnit.HOURS.toMillis(2)

    /**
     * Vârsta maximă a datelor salvate pentru [part]; null = nu se mai cer (sau încă nu au sens).
     * [fetchedAt] e momentul ultimei cereri reușite (0 dacă n-a fost niciuna).
     */
    fun maxAge(part: Part, status: MatchStatus, start: Long, end: Long, now: Long, fetchedAt: Long): Long? = when (part) {
        Part.PRE_MATCH -> when {
            status == MatchStatus.FINISHED && fetchedAt > end -> null
            status == MatchStatus.SCHEDULED && start - now > NEAR_WINDOW_MS -> FAR_REFRESH_MS
            else -> NEAR_REFRESH_MS
        }
        Part.LIVE -> when (status) {
            MatchStatus.SCHEDULED -> null
            MatchStatus.LIVE -> LIVE_REFRESH_MS
            MatchStatus.FINISHED -> if (fetchedAt > end) null else 0L
        }
    }

    fun isDue(part: Part, status: MatchStatus, start: Long, end: Long, now: Long, fetchedAt: Long): Boolean {
        val age = maxAge(part, status, start, end, now, fetchedAt) ?: return false
        return fetchedAt == 0L || now - fetchedAt >= age
    }
}
