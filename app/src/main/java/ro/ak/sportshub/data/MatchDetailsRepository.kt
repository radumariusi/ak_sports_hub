package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.sportshub.core.sports.DetailsSchedule
import ro.ak.sportshub.core.sports.Espn
import ro.ak.sportshub.core.sports.GoalApi
import ro.ak.sportshub.core.sports.EuroLeague
import ro.ak.sportshub.core.sports.LiveDelay
import ro.ak.sportshub.core.sports.LiveScoreCom
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.PanelView
import ro.ak.sportshub.core.sports.StandingRow
import ro.ak.sportshub.core.sports.TeamNames
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchDetails
import ro.ak.sportshub.core.sports.MatchInsights
import ro.ak.sportshub.core.sports.OfficialStats
import ro.ak.sportshub.core.sports.Scores365
import ro.ak.sportshub.core.sports.TheSportsDbDetails
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.TheSportsDb

/** Detaliile unui meci, cu starea cererilor. */
data class MatchDetailsState(
    val details: MatchDetails = MatchDetails(),
    val loading: Boolean = false,
    /** Ce se cere acum sau ultima problemă; null dacă nimic. */
    val status: String? = null,
)

/**
 * Datele pentru taburile Pre-meci și Live: de la ESPN (o singură cerere, `summary`, cu tot: starea, formațiile,
 * fazele, statisticile, clasamentul și forma) când meciul e în programul ESPN, altfel de la TheSportsDB.
 *
 * - Se cer doar cât e deschisă fereastra meciului, una câte una, cu pauză între ele; nimic în fundal.
 * - Nu trec prin `ConnectionGate`: nu sunt cereri la furnizorul de IPTV, deci nu concurează cu playerul.
 * - Răspunsurile se păstrează în fișiere (câteva zile); o eroare sau un răspuns stricat nu șterge datele vechi.
 */
class MatchDetailsRepository(
    context: Context,
    private val settings: SportsSettingsStore,
    private val http: OkHttpClient,
    private val log: DiagnosticLog? = null,
    /** Meciul ESPN pentru un meci din Sports Hub („EPL-401879243”), din programul descărcat; null dacă nu e. */
    private val espnEventFor: (Match) -> String? = { null },
    /** Clasamentele ESPN (aceleași fișiere ca tabul „Clasamente”), pentru Pre-meci când `summary` nu are clasament. */
    private val standings: StandingsRepository? = null,
    /** Meciul FotMob (fotbal) din programul descărcat, a doua sursă pentru statistici și formații; null dacă nu e. */
    private val fotmobEventFor: (Match) -> String? = { null },
    /** Meciul EuroLeague / EuroCup din programul oficial („E2026_379”), pentru statisticile oficiale; null dacă nu e. */
    private val euroleagueEventFor: (Match) -> String? = { null },
    /** Siglele și culorile echipelor; echipele fără siglă se caută o dată la TheSportsDB. */
    private val teamLooks: TeamLookStore? = null,
    /** Cheia GOAL API (a cincea sursă la fotbal); null = sursa nu se folosește. */
    private val goalApiKey: () -> String? = { null },
) {
    private val dir = File(context.noBackupFilesDir, "sports/details")
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, MatchDetailsState>>(emptyMap())
    private val _revealed = MutableStateFlow<Set<String>>(emptySet())

    val states: StateFlow<Map<String, MatchDetailsState>> = _states.asStateFlow()

    /** Meciurile pentru care ai cerut să vezi scorul, în modul „fără spoilere” (până la repornire). */
    val revealed: StateFlow<Set<String>> = _revealed.asStateFlow()

    fun reveal(matchId: String) = _revealed.update { it + matchId }

    fun hide(matchId: String) = _revealed.update { it - matchId }

    /** Ce s-a văzut la fiecare cerere live, pe meci, pentru spoilerele sincronizate cu stream-ul (doar în memorie). */
    private val delays = java.util.concurrent.ConcurrentHashMap<String, LiveDelay>()

    /**
     * Panoul meciului din player, live: starea de acum [delayMs] în urmă (cât e întârziat stream-ul).
     * Null până la prima cerere live.
     */
    fun delayedView(match: Match, now: Long, delayMs: Long): PanelView? = delays[match.id]?.view(now, delayMs, match)

    /** Detaliile live întârziate cât stream-ul (tabul Live, Statistici, alertele); [raw] dacă nu s-a cerut live. */
    fun delayedDetails(match: Match, now: Long, delayMs: Long, raw: MatchDetails): MatchDetails =
        delays[match.id]?.details(now, delayMs) ?: raw

    /** Pre-meci: detaliile, forma, meciurile directe, clasamentul și echipele de start. */
    suspend fun refreshPreMatch(match: Match, force: Boolean = false) =
        refresh(match, DetailsSchedule.Part.PRE_MATCH, force)

    /** Live: scorul și minutul, evenimentele și statisticile. */
    suspend fun refreshLive(match: Match, force: Boolean = false) =
        refresh(match, DetailsSchedule.Part.LIVE, force)

    private suspend fun refresh(match: Match, part: DetailsSchedule.Part, force: Boolean) {
        val s = settings.current
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                cleanup()
                set(match.id) { it.copy(details = read(match), loading = true) }
                if (s.sportsDbEnabled) fillTeamLooks(match, s.sportsDbKey)
                // Meciul nu e în programul ESPN descărcat (azi și mâine): îl caut în programul ligii, în ziua lui.
                val espn = espnRef(match) ?: lookupEspn(match)
                // NBA, NHL, MLB: statisticile oficiale ale ligii completează ce nu are ESPN (sau îl înlocuiesc).
                if (officialGaps(match, espn != null)) fetchOfficial(match, force)
                if (espn == null && !s.sportsDbEnabled) {
                    if (gaps365(match, part)) fetch365(match, part, force)
                    if (isFootball(match) && gaps365(match, part)) fetchLiveScore(match, force)
                    if (isFootball(match) && gaps365(match, part)) fetchGoal(match, force)
                    set(match.id) {
                        it.copy(
                            details = read(match),
                            loading = false,
                            status = "Meciul nu e la ESPN, iar TheSportsDB e oprit în Setări → Sports Hub.".takeIf { read(match).let { d -> d.stats.isEmpty() && d.lineup.isEmpty() } },
                        )
                    }
                    return@withContext
                }
                val stamp = File(dir, "${base(match)}-${part.name}.stamp")
                val now = System.currentTimeMillis()
                val due = force || DetailsSchedule.isDue(part, match.status, match.start, match.end, now, stamp.lastModifiedOrZero())
                var errors = 0
                if (due && espn != null) {
                    // ESPN: o singură cerere dă și Pre-meci, și Live.
                    set(match.id) { it.copy(status = "Se cer datele meciului (ESPN)…") }
                    if (download(Espn.summaryUrl(espn.first, espn.second), File(dir, "${base(match)}-espn.json"))) {
                        stamp.writeText(now.toString())
                    } else {
                        errors++
                    }
                }
                // Clasamentul ligii, când `summary` nu-l are (înainte de meci, ESPN nu-l pune): din tabul „Clasamente”.
                if (espn != null && part == DetailsSchedule.Part.PRE_MATCH && espnDetails(match)?.table.isNullOrEmpty()) {
                    runCatching { standings?.load(espn.first) }
                }
                // ESPN fără statistici sau formații (fotbal): FotMob completează, iar `read` le amestecă
                // (ce are ESPN rămâne de la ESPN). Rar: e un site protejat anti-robot.
                if (espn != null && espnGaps(match, part)) fetchFotmob(match, part, force)
                // Fără ESPN (sau dacă ESPN n-a dat nimic): TheSportsDB, ca înainte (e lent și dă puțin cu cheia gratuită).
                if (due && s.sportsDbEnabled && (espn == null || espnDetails(match) == null)) {
                    val requests = plan(match, part, s.sportsDbKey)
                    var ok = 0
                    var i = 0
                    while (i < requests.size) {
                        val r = requests[i]
                        set(match.id) { it.copy(status = "Se cer datele meciului (${i + 1})…") }
                        if (i > 0) delay(REQUEST_GAP_MS)
                        if (download(r.url, File(dir, r.file))) ok++ else errors++
                        // Ce a sosit apare imediat; cererile următoare pot depinde de el (id-urile echipelor).
                        set(match.id) { it.copy(details = read(match)) }
                        if (r.file.endsWith("-search1.json") || r.file.endsWith("-search2.json") || r.file.endsWith("-event.json")) {
                            val more = plan(match, part, s.sportsDbKey).filter { n -> requests.none { it.file == n.file } }
                            requests.addAll(more)
                        }
                        i++
                    }
                    if (ok > 0) stamp.writeText(now.toString())
                }
                // Ce lipsește încă (statistici sau formații): 365Scores, a treia sursă.
                if (gaps365(match, part)) fetch365(match, part, force)
                // Tot lipsește ceva la fotbal: LiveScore.com, a patra sursă.
                if (isFootball(match) && gaps365(match, part)) fetchLiveScore(match, force)
                // Și tot lipsește: GOAL API, doar cu cheia ta (1.000 de cereri pe zi pe planul gratuit).
                if (isFootball(match) && gaps365(match, part)) fetchGoal(match, force)
                val fresh = read(match)
                if (part == DetailsSchedule.Part.LIVE) delays.getOrPut(match.id) { LiveDelay() }.observe(System.currentTimeMillis(), fresh)
                set(match.id) {
                    it.copy(
                        details = fresh,
                        loading = false,
                        status = if (errors > 0) "$errors cereri nereușite; se arată ce e salvat." else null,
                    )
                }
            }
        }
    }

    private data class Req(val url: String, val file: String)

    /** Cererile necesare acum; unele apar abia după ce știm id-ul meciului sau al echipelor. */
    private fun plan(match: Match, part: DetailsSchedule.Part, key: String): MutableList<Req> {
        val b = base(match)
        val out = ArrayList<Req>()
        val eventId = eventId(match)
        if (part == DetailsSchedule.Part.PRE_MATCH) {
            if (match.away.isNotBlank()) {
                out += Req(TheSportsDbDetails.searchUrl(key, match.home, match.away), "$b-search1.json")
                out += Req(TheSportsDbDetails.searchUrl(key, match.away, match.home), "$b-search2.json")
            }
            if (eventId != null) {
                out += Req(TheSportsDbDetails.lookupEventUrl(key, eventId), "$b-event.json")
                val ev = parseFile("$b-event.json") { TheSportsDbDetails.parseEvent(it) }
                ev?.homeTeamId?.let { out += Req(TheSportsDbDetails.lastEventsUrl(key, it), "$b-last-home.json") }
                ev?.awayTeamId?.let { out += Req(TheSportsDbDetails.lastEventsUrl(key, it), "$b-last-away.json") }
                val league = ev?.leagueId
                val season = ev?.season
                if (league != null && season != null) out += Req(TheSportsDbDetails.tableUrl(key, league, season), "$b-table.json")
                out += Req(TheSportsDbDetails.lineupUrl(key, eventId), "$b-lineup.json")
            }
        } else if (eventId != null) {
            out += Req(TheSportsDbDetails.lookupEventUrl(key, eventId), "$b-event.json")
            out += Req(TheSportsDbDetails.timelineUrl(key, eventId), "$b-timeline.json")
            out += Req(TheSportsDbDetails.statsUrl(key, eventId), "$b-stats.json")
        }
        return out
    }

    /** Id-ul TheSportsDB: din id-ul meciului sau, pentru meciurile doar din EPG, din căutare. */
    private fun eventId(match: Match): String? {
        TheSportsDbDetails.eventIdOf(match.id)?.let { return it }
        val b = base(match)
        val found = listOf("$b-search1.json", "$b-search2.json")
            .flatMap { f -> parseFile(f) { TheSportsDbDetails.parseResults(it) }.orEmpty() }
        return MatchInsights.pickEvent(found, match.home, match.away, match.start)?.providerId
    }

    /** Liga și id-ul ESPN (doar ligile cu echipe: ESPN nu are pagina meciului pentru curse sau turnee de golf). */
    private fun espnRef(match: Match): Pair<Espn.League, String>? {
        val ref = runCatching { espnEventFor(match) }.getOrNull()
            ?: File(dir, "${base(match)}-espnref.txt").takeIf { it.exists() }?.let { f -> runCatching { f.readText().trim() }.getOrNull() }
            ?: return null
        val (league, id) = Espn.splitId(ref) ?: return null
        return if (league.kind == Espn.Kind.TEAM) league to id else null
    }

    /**
     * Caută meciul în programul ESPN al ligii lui (după numele competiției, cel mult două ligi), în ziua meciului:
     * pentru meciurile de peste două zile sau venite de la alți furnizori. O cerere pe ligă, reluată cel mult la 6 ore.
     */
    private fun lookupEspn(match: Match): Pair<Espn.League, String>? {
        if (match.away.isBlank()) return null
        val b = base(match)
        val day = Espn.today(match.start)
        for (league in Espn.leaguesFor(match.sport, match.competition)) {
            if (league.kind != Espn.Kind.TEAM) continue
            val f = File(dir, "$b-espnday-${league.key}.json")
            if (System.currentTimeMillis() - f.lastModifiedOrZero() > LOOKUP_MS) {
                set(match.id) { it.copy(status = "Caut meciul în programul ESPN (${league.competition})…") }
                download(Espn.scoreboardUrl(league, day), f)
            }
            val events = parseFile(f.name) { Espn.parse(it, league, day) }.orEmpty()
            val found = MatchInsights.pickEvent(events, match.home, match.away, match.start) ?: continue
            runCatching { File(dir, "$b-espnref.txt").writeText(found.providerId) }
            log?.log("statistici", "Găsit la ESPN după competiție: ${league.key}")
            return Espn.splitId(found.providerId)
        }
        return null
    }

    /** Clasamentul ligii ESPN a meciului, din fișierul tabului „Clasamente”: grupa cu una din echipe. */
    private fun espnTable(match: Match): List<StandingRow> {
        val league = espnRef(match)?.first ?: return emptyList()
        val groups = standings?.cached(league).orEmpty()
        val group = groups.firstOrNull { g -> g.rows.any { r -> TeamNames.same(r.team, match.home) || TeamNames.same(r.team, match.away) } }
            ?: return emptyList()
        return group.rows.map { r ->
            StandingRow(r.rank, r.team, r.teamId, r.played, r.won, r.drawn, r.lost, r.goalsFor, r.goalsAgainst, r.points?.toIntOrNull())
        }
    }

    @Volatile private var fotmobPausedUntil = 0L

    /** Pagina meciului FotMob: Live la un minut (ca ESPN, doar pe tabul Live), Pre-meci la 6 ore; un refuz oprește FotMob 6 ore. */
    private fun fetchFotmob(match: Match, part: DetailsSchedule.Part, force: Boolean) {
        val now = System.currentTimeMillis()
        if (now < fotmobPausedUntil || !settings.current.fotMobEnabled) return
        val id = runCatching { fotmobEventFor(match) }.getOrNull() ?: return
        val stamp = File(dir, "${base(match)}-fotmob.stamp")
        val every = if (part == DetailsSchedule.Part.LIVE && match.status == MatchStatus.LIVE) FOTMOB_LIVE_MS else FOTMOB_PRE_MS
        if (!force && now - stamp.lastModifiedOrZero() < every) return
        runCatching { stamp.writeText(now.toString()) }
        set(match.id) { it.copy(status = "Se cer statisticile și formațiile (FotMob)…") }
        val f = File(dir, "${base(match)}-fotmob.html")
        val tmp = File(dir, "${base(match)}-fotmob.new")
        if (download(ro.ak.sportshub.core.sports.FotMobDetails.url(id), tmp, html = true)) {
            // O pagină fără date nu înlocuiește una bună.
            val ok = runCatching { ro.ak.sportshub.core.sports.FotMobDetails.parse(tmp.readText()) }.getOrNull() != null
            if (ok && !tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            tmp.delete()
        } else {
            tmp.delete()
            fotmobPausedUntil = now + FOTMOB_PAUSE_MS
            log?.log("statistici", "FotMob a refuzat pagina meciului; pauză 6 h")
        }
    }

    /** NBA / NHL / MLB după start, când ESPN lipsește sau n-are statistici ori jucători. */
    private fun officialGaps(match: Match, hasEspn: Boolean): Boolean {
        if (match.away.isBlank() || match.status == MatchStatus.SCHEDULED) return false
        if (OfficialStats.leagueOf(match.competition, match.sport) == null && euroleagueUrl(match) == null) return false
        if (!hasEspn) return true
        val d = espnDetails(match) ?: return true
        return d.stats.isEmpty() || d.lineup.isEmpty()
    }

    /**
     * Statisticile oficiale (NBA `cdn.nba.com`, NHL `api-web.nhle.com`, MLB `statsapi.mlb.com`): întâi programul
     * zilei, o dată (id-ul meciului se ține în `-offref.txt`), apoi statisticile la un minut cât e live; după final,
     * o ultimă cerere. Un răspuns fără date nu înlocuiește unul bun.
     */
    private fun fetchOfficial(match: Match, force: Boolean) {
        val now = System.currentTimeMillis()
        val b = base(match)
        val stamp = File(dir, "$b-off.stamp")
        val data = File(dir, "$b-off.json")
        val last = stamp.lastModifiedOrZero()
        if (!force) {
            if (now - last < OFFICIAL_LIVE_MS) return
            if (match.status == MatchStatus.FINISHED && data.exists() && last > match.end + OFFICIAL_LIVE_MS) return
        }
        // EuroLeague / EuroCup: id-ul e deja în programul oficial, o singură cerere.
        euroleagueUrl(match)?.let { url ->
            runCatching { stamp.writeText(now.toString()) }
            set(match.id) { it.copy(status = "Se cer statisticile oficiale EuroLeague…") }
            keepIfGood(url, data) { EuroLeague.parseBoxScore(it) != null }
            return
        }
        val league = OfficialStats.leagueOf(match.competition, match.sport) ?: return
        runCatching { stamp.writeText(now.toString()) }
        val ref = File(dir, "$b-offref.txt")
        var id = ref.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        if (id == null) {
            val day = OfficialStats.gameDay(match.start)
            val sched = File(dir, "off-${league.key}-$day.json")
            if (!sched.exists() || now - sched.lastModified() > OFFICIAL_SCHEDULE_MS) {
                set(match.id) { it.copy(status = "Se caută meciul în programul ${league.name}…") }
                download(OfficialStats.scheduleUrl(league, day), sched)
            }
            val games = parseFile(sched.name) { OfficialStats.parseSchedule(league, it, day) }.orEmpty()
            id = MatchInsights.pickEvent(games, match.home, match.away, match.start)?.providerId ?: return
            runCatching { ref.writeText(id) }
        }
        set(match.id) { it.copy(status = "Se cer statisticile oficiale ${league.name}…") }
        val tmp = File(dir, "$b-off.new")
        if (download(OfficialStats.statsUrl(league, id), tmp)) {
            val ok = runCatching { OfficialStats.parseStats(league, tmp.readText()) }.getOrNull() != null
            if (ok && !tmp.renameTo(data)) { data.delete(); tmp.renameTo(data) }
        }
        tmp.delete()
    }

    @Volatile private var pausedUntil365 = 0L

    /** Meciul între două echipe, la un sport 365Scores, căruia îi lipsesc statisticile (după start) sau formațiile (după start sau cu 90 min înainte). */
    private fun gaps365(match: Match, part: DetailsSchedule.Part): Boolean {
        if (match.away.isBlank() || Scores365.sportId(match.sport) == null) return false
        val d = read(match)
        val started = match.status != MatchStatus.SCHEDULED
        val soon = match.start - System.currentTimeMillis() < FOTMOB_LINEUP_BEFORE_MS
        return when (part) {
            DetailsSchedule.Part.LIVE -> started && (d.stats.isEmpty() || d.lineup.isEmpty())
            DetailsSchedule.Part.PRE_MATCH -> d.lineup.isEmpty() && soon
        }
    }

    /**
     * 365Scores: programul zilei pe sport (o dată la 30 min, id-ul în `-365ref.txt`), apoi statisticile și pagina
     * meciului (formațiile) la un minut cât e live, la 6 ore înainte de meci, o dată după final. Un refuz îl oprește 6 ore.
     */
    private fun fetch365(match: Match, part: DetailsSchedule.Part, force: Boolean) {
        val sportId = Scores365.sportId(match.sport) ?: return
        val now = System.currentTimeMillis()
        if (now < pausedUntil365) return
        val b = base(match)
        val stamp = File(dir, "$b-365.stamp")
        val statsFile = File(dir, "$b-365stats.json")
        val gameFile = File(dir, "$b-365game.json")
        val last = stamp.lastModifiedOrZero()
        val live = match.status == MatchStatus.LIVE
        if (!force) {
            if (now - last < (if (live) OFFICIAL_LIVE_MS else FOTMOB_PRE_MS)) return
            if (match.status == MatchStatus.FINISHED && statsFile.exists() && last > match.end + OFFICIAL_LIVE_MS) return
        }
        runCatching { stamp.writeText(now.toString()) }
        val ref = File(dir, "$b-365ref.txt")
        var id = ref.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        if (id == null) {
            val day = Scores365.gameDay(match.start)
            val sched = File(dir, "365-$sportId-$day.json")
            if (!sched.exists() || now - sched.lastModified() > OFFICIAL_SCHEDULE_MS) {
                set(match.id) { it.copy(status = "Se caută meciul la 365Scores…") }
                if (!download(Scores365.scheduleUrl(sportId, day), sched)) {
                    pausedUntil365 = now + FOTMOB_PAUSE_MS
                    log?.log("statistici", "365Scores a refuzat programul; pauză 6 h")
                    return
                }
            }
            val games = parseFile(sched.name) { Scores365.parseSchedule(it) }.orEmpty()
            id = MatchInsights.pickEvent(games, match.home, match.away, match.start)?.providerId ?: return
            runCatching { ref.writeText(id) }
        }
        set(match.id) { it.copy(status = "Se cer statisticile și formațiile (365Scores)…") }
        if (match.status != MatchStatus.SCHEDULED) {
            keepIfGood(Scores365.statsUrl(id), statsFile) { Scores365.parseStats(it).isNotEmpty() }
        }
        keepIfGood(Scores365.gameUrl(id), gameFile) { Scores365.parseLineup(it).isNotEmpty() }
    }

    private fun isFootball(match: Match) = match.sport == ro.ak.sportshub.core.sports.Sport.FOOTBALL

    @Volatile private var pausedUntilLs = 0L

    /** LiveScore.com (fotbal): programul zilei UTC o dată la 30 min (id-ul în `-lsref.txt`), apoi statisticile și formațiile, ca la 365Scores. */
    private fun fetchLiveScore(match: Match, force: Boolean) {
        val now = System.currentTimeMillis()
        if (now < pausedUntilLs) return
        val b = base(match)
        val stamp = File(dir, "$b-ls.stamp")
        val statsFile = File(dir, "$b-lsstats.json")
        val lineupFile = File(dir, "$b-lslineup.json")
        val last = stamp.lastModifiedOrZero()
        if (!force) {
            if (now - last < (if (match.status == MatchStatus.LIVE) OFFICIAL_LIVE_MS else FOTMOB_PRE_MS)) return
            if (match.status == MatchStatus.FINISHED && statsFile.exists() && last > match.end + OFFICIAL_LIVE_MS) return
        }
        runCatching { stamp.writeText(now.toString()) }
        val ref = File(dir, "$b-lsref.txt")
        var id = ref.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        if (id == null) {
            val day = Scores365.gameDay(match.start)
            val sched = File(dir, "ls-$day.json")
            if (!sched.exists() || now - sched.lastModified() > OFFICIAL_SCHEDULE_MS) {
                set(match.id) { it.copy(status = "Se caută meciul la LiveScore…") }
                if (!download(LiveScoreCom.scheduleUrl(day), sched)) {
                    pausedUntilLs = now + FOTMOB_PAUSE_MS
                    log?.log("statistici", "LiveScore a refuzat programul; pauză 6 h")
                    return
                }
            }
            val games = parseFile(sched.name) { LiveScoreCom.parseSchedule(it) }.orEmpty()
            id = MatchInsights.pickEvent(games, match.home, match.away, match.start)?.providerId ?: return
            runCatching { ref.writeText(id) }
        }
        set(match.id) { it.copy(status = "Se cer statisticile și formațiile (LiveScore)…") }
        if (match.status != MatchStatus.SCHEDULED) {
            keepIfGood(LiveScoreCom.statsUrl(id), statsFile) { LiveScoreCom.parseStats(it).isNotEmpty() }
        }
        keepIfGood(LiveScoreCom.lineupsUrl(id), lineupFile) { LiveScoreCom.parseLineup(it).isNotEmpty() }
    }

    @Volatile private var pausedUntilGoal = 0L

    /** GOAL API: programul zilei UTC (id-ul în `-goalref.txt`), apoi statisticile și formațiile, ca la LiveScore; cheia în antet. */
    private fun fetchGoal(match: Match, force: Boolean) {
        val key = runCatching { goalApiKey() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now < pausedUntilGoal) return
        val b = base(match)
        val stamp = File(dir, "$b-goal.stamp")
        val statsFile = File(dir, "$b-goalstats.json")
        val lineupFile = File(dir, "$b-goallineup.json")
        val last = stamp.lastModifiedOrZero()
        if (!force) {
            if (now - last < (if (match.status == MatchStatus.LIVE) OFFICIAL_LIVE_MS else FOTMOB_PRE_MS)) return
            if (match.status == MatchStatus.FINISHED && statsFile.exists() && last > match.end + OFFICIAL_LIVE_MS) return
        }
        runCatching { stamp.writeText(now.toString()) }
        val ref = File(dir, "$b-goalref.txt")
        var id = ref.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        if (id == null) {
            // Programul zilei vine pe pagini de câte 100 (~800 de meciuri): paginile se păstrează 6 ore, căutarea se
            // oprește la pagina cu meciul, deci de obicei câteva cereri pe zi pentru toate meciurile.
            val day = Scores365.gameDay(match.start)
            set(match.id) { it.copy(status = "Se caută meciul la GOAL API…") }
            for (page in 0 until GOAL_MAX_PAGES) {
                val sched = File(dir, "goal-$day-p$page.json")
                if (!sched.exists() || now - sched.lastModified() > FOTMOB_PRE_MS) {
                    if (!download(GoalApi.fixturesUrl(day, page), sched, bearer = key)) {
                        pausedUntilGoal = now + FOTMOB_PAUSE_MS
                        log?.log("statistici", "GOAL API a refuzat programul (cheie greșită sau limită atinsă?); pauză 6 h")
                        return
                    }
                }
                val body = runCatching { sched.readText() }.getOrNull() ?: return
                val games = runCatching { GoalApi.parseFixtures(body) }.getOrDefault(emptyList())
                id = MatchInsights.pickEvent(games, match.home, match.away, match.start)?.providerId
                if (id != null || !GoalApi.hasMore(body)) break
            }
            if (id == null) return
            runCatching { ref.writeText(id) }
        }
        set(match.id) { it.copy(status = "Se cer statisticile și formațiile (GOAL API)…") }
        if (match.status != MatchStatus.SCHEDULED) {
            keepIfGood(GoalApi.statisticsUrl(id), statsFile, bearer = key) { GoalApi.parseStatistics(it).isNotEmpty() }
        }
        keepIfGood(GoalApi.lineupsUrl(id), lineupFile, bearer = key) { GoalApi.parseLineups(it).isNotEmpty() }
    }

    private fun readGoal(match: Match): MatchDetails? {
        val b = base(match)
        val stats = parseFile("$b-goalstats.json") { GoalApi.parseStatistics(it) }.orEmpty()
        val lineup = parseFile("$b-goallineup.json") { GoalApi.parseLineups(it) }.orEmpty()
        return MatchDetails(stats = stats, lineup = lineup).takeIf { stats.isNotEmpty() || lineup.isNotEmpty() }
    }

    private fun readLiveScore(match: Match): MatchDetails? {
        val b = base(match)
        val stats = parseFile("$b-lsstats.json") { LiveScoreCom.parseStats(it) }.orEmpty()
        val lineup = parseFile("$b-lslineup.json") { LiveScoreCom.parseLineup(it) }.orEmpty()
        return MatchDetails(stats = stats, lineup = lineup).takeIf { stats.isNotEmpty() || lineup.isNotEmpty() }
    }

    private val teamsDir = File(context.noBackupFilesDir, "sports/echipe")

    /**
     * Echipele meciului fără siglă în carte (de obicei cele pe care ESPN nu le are): TheSportsDB `searchteams`
     * (gratuit, un rezultat), cel mult o dată la 30 de zile pe echipă; sigla, culorile și prescurtarea intră în carte.
     */
    private fun fillTeamLooks(match: Match, key: String) {
        val store = teamLooks ?: return
        if (match.away.isBlank()) return
        teamsDir.mkdirs()
        val now = System.currentTimeMillis()
        for (name in listOf(match.home, match.away)) {
            if (store.book.value.team(name)?.logo != null) continue
            val f = File(teamsDir, sha(TeamNames.key(name).ifBlank { name }) + ".json")
            if (f.exists() && now - f.lastModified() < TEAM_LOOK_MS) continue
            // Fișierul se scrie și când nu s-a găsit nimic, ca echipa să nu se mai ceară luna asta.
            if (!download(TheSportsDb.searchTeamUrl(key, name), f)) runCatching { f.writeText("{}") }
            val look = parseFile0(f) { TheSportsDb.parseTeamLook(it, name) } ?: continue
            store.learnLooks(listOf(SportEvent(TheSportsDb.PROVIDER, "team", null, null, name, "", 0L, homeLook = look)))
        }
    }

    private fun <T> parseFile0(f: File, parse: (String) -> T): T? = if (!f.exists()) null else runCatching { parse(f.readText()) }.getOrNull()

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Descarcă într-un fișier temporar și îl păstrează doar dacă are date (un răspuns gol nu înlocuiește unul bun). */
    private fun keepIfGood(url: String, target: File, bearer: String? = null, good: (String) -> Boolean) {
        val tmp = File(dir, target.name + ".new")
        if (download(url, tmp, bearer = bearer)) {
            val ok = runCatching { good(tmp.readText()) }.getOrDefault(false)
            if (ok && !tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
        tmp.delete()
    }

    private fun read365(match: Match): MatchDetails? {
        val b = base(match)
        val stats = parseFile("$b-365stats.json") { Scores365.parseStats(it) }.orEmpty()
        val lineup = parseFile("$b-365game.json") { Scores365.parseLineup(it) }.orEmpty()
        return MatchDetails(stats = stats, lineup = lineup).takeIf { stats.isNotEmpty() || lineup.isNotEmpty() }
    }

    private fun euroleagueUrl(match: Match): String? =
        runCatching { euroleagueEventFor(match) }.getOrNull()?.let { EuroLeague.boxScoreUrl(it) }

    private fun official(match: Match): MatchDetails? {
        if (euroleagueUrl(match) != null) return parseFile("${base(match)}-off.json") { EuroLeague.parseBoxScore(it) }
        val league = OfficialStats.leagueOf(match.competition, match.sport) ?: return null
        return parseFile("${base(match)}-off.json") { OfficialStats.parseStats(league, it) }
    }

    /** ESPN n-are ce poate da FotMob (doar fotbal): Live fără statistici sau formații, Pre fără formații cu 90 min înainte. */
    private fun espnGaps(match: Match, part: DetailsSchedule.Part): Boolean {
        if (match.away.isBlank() || (match.sport != null && match.sport != ro.ak.sportshub.core.sports.Sport.FOOTBALL)) return false
        val d = espnDetails(match) ?: return true
        val started = match.status != MatchStatus.SCHEDULED
        return when (part) {
            DetailsSchedule.Part.LIVE -> started && (d.stats.isEmpty() || d.lineup.isEmpty())
            DetailsSchedule.Part.PRE_MATCH -> d.lineup.isEmpty() && match.start - System.currentTimeMillis() < FOTMOB_LINEUP_BEFORE_MS
        }
    }

    private fun espnDetails(match: Match): MatchDetails? =
        parseFile("${base(match)}-espn.json") { Espn.parseSummary(it, base(match)) }?.takeIf { !it.isEmpty }

    /** Ce e salvat pentru meci, citit din fișiere: ESPN întâi, iar ce lipsește de acolo din TheSportsDB. */
    fun read(match: Match): MatchDetails {
        val espn = espnDetails(match)
        val db = readSportsDb(match)
        val off = official(match)
        val s365 = read365(match)
        val ls = readLiveScore(match)
        val goal = readGoal(match)
        if (espn == null) {
            if (off == null && s365 == null && ls == null && goal == null) return db
            return db.copy(
                stats = off?.stats.orEmpty().ifEmpty { s365?.stats.orEmpty() }.ifEmpty { ls?.stats.orEmpty() }.ifEmpty { goal?.stats.orEmpty() }.ifEmpty { db.stats },
                lineup = off?.lineup.orEmpty().ifEmpty { s365?.lineup.orEmpty() }.ifEmpty { ls?.lineup.orEmpty() }.ifEmpty { goal?.lineup.orEmpty() }.ifEmpty { db.lineup },
            )
        }
        val fm = parseFile("${base(match)}-fotmob.html") { ro.ak.sportshub.core.sports.FotMobDetails.parse(it) }
        return MatchDetails(
            event = espn.event ?: db.event,
            seasonStats = espn.seasonStats,
            homeRecent = espn.homeRecent.ifEmpty { db.homeRecent },
            awayRecent = espn.awayRecent.ifEmpty { db.awayRecent },
            headToHead = espn.headToHead.ifEmpty { db.headToHead },
            table = espn.table.ifEmpty { espnTable(match) }.ifEmpty { db.table },
            lineup = espn.lineup.ifEmpty { fm?.lineup.orEmpty() }.ifEmpty { off?.lineup.orEmpty() }.ifEmpty { s365?.lineup.orEmpty() }.ifEmpty { ls?.lineup.orEmpty() }.ifEmpty { goal?.lineup.orEmpty() }.ifEmpty { db.lineup },
            timeline = espn.timeline.ifEmpty { db.timeline },
            // Statisticile după start (înainte de meci ESPN pune sezonul în `seasonStats`).
            stats = espn.stats.ifEmpty { fm?.stats.orEmpty() }.ifEmpty { off?.stats.orEmpty() }.ifEmpty { s365?.stats.orEmpty() }.ifEmpty { ls?.stats.orEmpty() }.ifEmpty { goal?.stats.orEmpty() }.ifEmpty { db.stats },
        )
    }

    private fun readSportsDb(match: Match): MatchDetails {
        val b = base(match)
        val searched = listOf("$b-search1.json", "$b-search2.json")
            .flatMap { f -> parseFile(f) { TheSportsDbDetails.parseResults(it) }.orEmpty() }
        return MatchDetails(
            event = parseFile("$b-event.json") { TheSportsDbDetails.parseEvent(it) },
            homeRecent = MatchInsights.recent(parseFile("$b-last-home.json") { TheSportsDbDetails.parseResults(it) }.orEmpty(), match.start),
            awayRecent = MatchInsights.recent(parseFile("$b-last-away.json") { TheSportsDbDetails.parseResults(it) }.orEmpty(), match.start),
            headToHead = if (match.away.isBlank()) emptyList() else MatchInsights.headToHead(searched, match.home, match.away, match.start),
            table = parseFile("$b-table.json") { TheSportsDbDetails.parseTable(it) }.orEmpty(),
            lineup = parseFile("$b-lineup.json") { TheSportsDbDetails.parseLineup(it) }.orEmpty(),
            timeline = parseFile("$b-timeline.json") { TheSportsDbDetails.parseTimeline(it) }.orEmpty(),
            stats = parseFile("$b-stats.json") { TheSportsDbDetails.parseStats(it) }.orEmpty(),
        )
    }

    private fun <T> parseFile(name: String, parse: (String) -> T): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    private fun set(id: String, transform: (MatchDetailsState) -> MatchDetailsState) =
        _states.update { it + (id to transform(it[id] ?: MatchDetailsState())) }

    private fun download(url: String, target: File, html: Boolean = false, bearer: String? = null): Boolean {
        val started = System.currentTimeMillis()
        var code: Int? = null
        var bytes: Int? = null
        var outcome = "ok"
        val ok = runCatching { fetch(url, target, { code = it }, { bytes = it }, { outcome = it }, html, bearer) }
            .onFailure { outcome = "EȘEC: ${it.javaClass.simpleName}" }
            .getOrDefault(false)
        // Jurnalul statisticilor: ce endpoint, ce cod și cât de mare (un `null` de la TheSportsDB = fără date).
        log?.request("statistici", url, code, bytes, System.currentTimeMillis() - started, outcome)
        return ok
    }

    private fun fetch(
        url: String, target: File, code: (Int) -> Unit, bytes: (Int) -> Unit, outcome: (String) -> Unit,
        html: Boolean = false, bearer: String? = null,
    ): Boolean {
        val request = Request.Builder().url(url)
            .header("Accept", if (html) "text/html" else "application/json")
            .apply { if (html) header("User-Agent", SportsRepository.BROWSER_UA) }
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        return http.newCall(request).execute().use { r ->
            code(r.code)
            if (!r.isSuccessful) {
                outcome("EȘEC: HTTP ${r.code}")
                return false
            }
            val body = r.body?.string() ?: return false
            bytes(body.length)
            val trimmed = body.trimStart()
            if (html && !trimmed.contains("__NEXT_DATA__")) {
                outcome("EȘEC: pagină fără date (verificare anti-robot?)")
                return false
            }
            if (!html && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                outcome("EȘEC: nu e JSON: " + trimmed.take(80).replace('\n', ' '))
                return false
            }
            if (trimmed.length < 200) outcome("ok: " + trimmed.replace('\n', ' '))
            // GOAL API: forma răspunsului nu e documentată; începutul lui (fără cheie) ajunge în jurnal.
            else if (bearer != null) outcome("ok: " + trimmed.take(400).replace('\n', ' '))
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            true
        }
    }

    /** Fișierele mai vechi de câteva zile se șterg. */
    private fun cleanup() {
        val limit = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    private fun File.lastModifiedOrZero(): Long = if (exists()) lastModified() else 0L

    /** Numele fișierelor unui meci: un rezumat al id-ului (fără caractere ciudate în nume). */
    private fun base(match: Match): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(match.id.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REQUEST_GAP_MS = 1_200L
        val LOOKUP_MS = TimeUnit.HOURS.toMillis(6)
        val KEEP_MS = TimeUnit.DAYS.toMillis(3)
        val FOTMOB_LIVE_MS = TimeUnit.SECONDS.toMillis(60)
        val OFFICIAL_LIVE_MS = TimeUnit.SECONDS.toMillis(60)
        val OFFICIAL_SCHEDULE_MS = TimeUnit.MINUTES.toMillis(30)
        val TEAM_LOOK_MS = TimeUnit.DAYS.toMillis(30)
        const val GOAL_MAX_PAGES = 10
        val FOTMOB_PRE_MS = TimeUnit.HOURS.toMillis(6)
        val FOTMOB_PAUSE_MS = TimeUnit.HOURS.toMillis(6)
        val FOTMOB_LINEUP_BEFORE_MS = TimeUnit.MINUTES.toMillis(90)
    }
}
