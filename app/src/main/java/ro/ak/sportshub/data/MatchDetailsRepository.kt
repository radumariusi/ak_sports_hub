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
import ro.ak.sportshub.core.sports.LiveDelay
import ro.ak.sportshub.core.sports.PanelView
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchDetails
import ro.ak.sportshub.core.sports.MatchInsights
import ro.ak.sportshub.core.sports.TheSportsDbDetails

/** Detaliile unui meci, cu starea cererilor. */
data class MatchDetailsState(
    val details: MatchDetails = MatchDetails(),
    val loading: Boolean = false,
    /** Ce se cere acum sau ultima problemă; null dacă nimic. */
    val status: String? = null,
)

/**
 * Datele pentru taburile Pre-meci și Live, de la TheSportsDB.
 *
 * - Se cer doar cât e deschisă fereastra meciului, una câte una, cu pauză între ele; nimic în fundal.
 * - Nu trec prin `ConnectionGate`: nu sunt cereri la furnizorul de IPTV, deci nu concurează cu playerul.
 * - Răspunsurile se păstrează în fișiere (câteva zile); o eroare sau un răspuns stricat nu șterge datele vechi.
 */
class MatchDetailsRepository(
    context: Context,
    private val settings: SportsSettingsStore,
    private val http: OkHttpClient,
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

    /** Pre-meci: detaliile, forma, meciurile directe, clasamentul și echipele de start. */
    suspend fun refreshPreMatch(match: Match, force: Boolean = false) =
        refresh(match, DetailsSchedule.Part.PRE_MATCH, force)

    /** Live: scorul și minutul, evenimentele și statisticile. */
    suspend fun refreshLive(match: Match, force: Boolean = false) =
        refresh(match, DetailsSchedule.Part.LIVE, force)

    private suspend fun refresh(match: Match, part: DetailsSchedule.Part, force: Boolean) {
        val s = settings.current
        if (!s.sportsDbEnabled) {
            set(match.id) { it.copy(status = "TheSportsDB e oprit în Setări → Sports Hub.") }
            return
        }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                cleanup()
                set(match.id) { it.copy(details = read(match), loading = true) }
                val stamp = File(dir, "${base(match)}-${part.name}.stamp")
                val now = System.currentTimeMillis()
                val due = force || DetailsSchedule.isDue(part, match.status, match.start, match.end, now, stamp.lastModifiedOrZero())
                var errors = 0
                if (due) {
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
                val fresh = read(match)
                if (part == DetailsSchedule.Part.LIVE) delays.getOrPut(match.id) { LiveDelay() }.observe(System.currentTimeMillis(), fresh)
                set(match.id) {
                    it.copy(
                        details = fresh,
                        loading = false,
                        status = if (errors > 0) "TheSportsDB: $errors cereri nereușite; se arată ce e salvat." else null,
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

    /** Ce e salvat pentru meci, citit din fișiere. */
    fun read(match: Match): MatchDetails {
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

    private fun download(url: String, target: File): Boolean = runCatching {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body?.string() ?: return false
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            true
        }
    }.getOrDefault(false)

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
        val KEEP_MS = TimeUnit.DAYS.toMillis(3)
    }
}
