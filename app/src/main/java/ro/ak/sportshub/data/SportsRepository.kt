package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.sports.EpgBroadcast
import ro.ak.sportshub.core.sports.EpgMatchExtractor
import ro.ak.sportshub.core.sports.FoundSource
import ro.ak.sportshub.core.sports.LiveSoccerTv
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchAssembler
import ro.ak.sportshub.core.sports.MatchBroadcast
import ro.ak.sportshub.core.sports.MatchSource
import ro.ak.sportshub.core.sports.MatchSources
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.Sofascore
import ro.ak.sportshub.core.sports.SourceFinder
import ro.ak.sportshub.core.sports.SourceSummary
import ro.ak.sportshub.core.sports.SportChannels
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.StationIndex
import ro.ak.sportshub.core.sports.StationNames
import ro.ak.sportshub.core.sports.TheSportsDb
import ro.ak.sportshub.core.sports.Verdict

/** Un meci cu sursele găsite în playlisturile tale. */
data class MatchEntry(
    val match: Match,
    val sources: List<MatchSource<ChannelWithPlaylist>>,
    val summary: SourceSummary,
)

data class SportsSnapshot(
    val matches: List<MatchEntry> = emptyList(),
    val computedAt: Long = 0,
    /** Încă nu s-a calculat nimic de la pornire. */
    val loading: Boolean = true,
    /** Posturile anunțate pentru meciurile din listă pe care nu le-am găsit în playlisturi (cele mai dese întâi). */
    val unmatchedStations: List<String> = emptyList(),
)

/**
 * Meciurile din Sports Hub: din EPG-ul tău, TheSportsDB și, opțional, LiveSoccerTV și Sofascore,
 * cu sursele găsite în playlisturi.
 *
 * - LiveSoccerTV: o pagină pe zi (azi și mâine), la 3 ore; Sofascore: o cerere pe sport și zi (azi și mâine), la 3 ore.
 *   Niciunul nu e furnizorul de IPTV, deci nu trec prin `ConnectionGate`.
 * - TheSportsDB se cere rar (azi și mâine la 3 ore, restul la 12 ore), cerere cu cerere; răspunsurile se
 *   păstrează în fișiere, ca lista să meargă și fără rețea. O eroare nu șterge datele vechi.
 * - EPG-ul se citește doar din baza locală: nicio cerere la furnizorul de IPTV, deci nimic nu concurează
 *   cu playerul pentru conexiunea playlistului.
 * - Potrivirea ([SourceFinder]): emisiunea EPG cu ambele echipe (sigur), postul anunțat de furnizor sau din dicționar
 *   (probabil), competiția confirmată anterior (învățat), regulile de drepturi TV (presupus). Confirmările și
 *   respingerile tale ([SportsKnowledgeStore]) au ultimul cuvânt.
 */
class SportsRepository(
    context: Context,
    private val db: AkDatabase,
    private val epg: EpgRepository,
    private val playlists: PlaylistRepository,
    private val variantStore: VariantStore,
    private val settings: SportsSettingsStore,
    private val knowledge: SportsKnowledgeStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.noBackupFilesDir, "sports")
    private val fetchMutex = Mutex()
    private val computeMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(SportsSnapshot())

    /** Canalele EPG ale canalelor de sport (se reface doar când se schimbă playlisturile sau EPG-ul). */
    @Volatile
    private var sportEpgCache: Set<Long>? = null

    /** Numele canalelor active, pentru potrivirea pe nume de post (se reface ca [sportEpgCache]). */
    @Volatile
    private var stationIndexCache: StationIndex? = null

    /** Fișierele de la furnizori, citite o dată (cheia: cale + data modificării). */
    private val parsedFiles = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SportEvent>>>()
    private val _status = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<SportsSnapshot> = _snapshot.asStateFlow()

    /** Ce face acum („Se descarcă programul…”) sau ultima eroare; null dacă nimic. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun find(id: String): MatchEntry? = _snapshot.value.matches.firstOrNull { it.match.id == id }

    /** Pornește recalcularea la orice schimbare (EPG, playlisturi, corecții, setări) și la fiecare minut. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            merge(
                epg.observeStates().map { "epg" },
                playlists.changes.map { "playlists" },
                variantStore.state.map { it.corrections }.distinctUntilChanged().map { "corrections" },
                settings.settings.map { st: SportsSettings -> fetchKey(st) }.distinctUntilChanged().map { "settings" },
                knowledge.state.map { "knowledge" },
            ).debounce(1_000).collect { reason ->
                // Canalele sau potrivirile EPG s-au putut schimba: lista canalelor de sport se reface.
                if (reason != "knowledge" && reason != "settings") {
                    sportEpgCache = null
                    stationIndexCache = null
                }
                recompute()
            }
        }
        scope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(1))
                recompute()
            }
        }
    }

    /** Ce din setări schimbă lista (nu și filtrele sau favoritele, care se aplică în ecran). */
    private fun fetchKey(s: SportsSettings): String =
        "${s.sports.joinToString(",") { it.name }}|${s.sportsDbEnabled}|${s.sportsDbTv}|${s.daysAhead}|" +
            "${s.liveSoccerTvEnabled}|${s.sofascoreEnabled}"

    fun refreshInBackground(force: Boolean = true) {
        scope.launch { refreshDue(force) }
    }

    /**
     * Ai spus despre sursa [groupKey] a meciului [match] că e ([Verdict.CONFIRMED]) sau nu e meciul
     * ([Verdict.REJECTED]); null anulează alegerea. [source] e sursa așa cum a fost găsită (null: adăugată de mână).
     */
    fun decide(match: Match, groupKey: String, verdict: Verdict?, source: MatchSource<*>?) {
        val found = source?.let { FoundSource(groupKey, it.confidence, it.station, it.rejected, it.origin) }
        knowledge.update { it.decide(match, groupKey, verdict, found, System.currentTimeMillis()) }
        scope.launch { recompute() }
    }

    /** Cere de la furnizori ce e vechi (sau tot, cu [force]), apoi recalculează lista. */
    suspend fun refreshDue(force: Boolean = false) {
        val s = settings.current
        if (!s.sportsDbEnabled && !s.liveSoccerTvEnabled && !s.sofascoreEnabled) {
            recompute()
            return
        }
        val changed = fetchMutex.withLock { withContext(Dispatchers.IO) { fetch(s, force) } }
        if (changed || force || _snapshot.value.loading) recompute()
    }

    // ---------- furnizorii ----------

    /** O cerere de făcut: adresa, fișierul în care se păstrează și dacă e o pagină HTML (LiveSoccerTV). */
    private data class Job(val url: String, val file: File, val html: Boolean = false)

    private suspend fun fetch(s: SportsSettings, force: Boolean): Boolean {
        dir.mkdirs()
        val today = LocalDate.now(ZoneOffset.UTC)
        cleanup(today.minusDays(1))
        var changed = false
        var errors = 0
        val jobs = ArrayList<Job>()
        val localToday = LocalDate.now(ZoneId.systemDefault())
        for (offset in 0..1) {
            val day = localToday.plusDays(offset.toLong())
            if (s.liveSoccerTvEnabled) {
                val f = File(dir, "lstv-$day.html")
                if (force || isStale(f, FRESH_NEAR_MS)) jobs += Job(LiveSoccerTv.scheduleUrl(day), f, html = true)
            }
            if (s.sofascoreEnabled) {
                for (sport in s.sports) {
                    val url = Sofascore.scheduleUrl(sport, day) ?: continue
                    val f = File(dir, "sofa-${sport.name}-$day.json")
                    if (force || isStale(f, FRESH_NEAR_MS)) jobs += Job(url, f)
                }
            }
        }
        if (s.sportsDbEnabled) addSportsDbJobs(s, today, force, jobs)
        jobs.forEachIndexed { i, job ->
            _status.value = "Se descarcă programul meciurilor (${i + 1}/${jobs.size})…"
            if (download(job)) changed = true else errors++
            if (i < jobs.lastIndex) delay(REQUEST_GAP_MS) // cereri rare, una câte una
        }
        _status.value = if (errors > 0) "$errors cereri nereușite; se folosesc datele salvate." else null
        return changed
    }

    private fun addSportsDbJobs(s: SportsSettings, today: LocalDate, force: Boolean, jobs: MutableList<Job>) {
        for (offset in 0 until s.daysAhead.coerceIn(1, 7)) {
            val day = today.plusDays(offset.toLong())
            val maxAge = if (offset <= 1) FRESH_NEAR_MS else FRESH_FAR_MS
            for (sport in s.sports) {
                val f = File(dir, "events-${sport.name}-$day.json")
                if (force || isStale(f, maxAge)) jobs += Job(TheSportsDb.eventsDayUrl(s.sportsDbKey, day, sport), f)
            }
            if (s.sportsDbTv && offset <= 1) {
                val f = File(dir, "tv-$day.json")
                if (force || isStale(f, maxAge)) jobs += Job(TheSportsDb.tvDayUrl(s.sportsDbKey, day), f)
            }
        }
    }

    private fun isStale(f: File, maxAge: Long) = !f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge

    private fun download(job: Job): Boolean = runCatching {
        val target = job.file
        val request = Request.Builder().url(job.url)
            .header("Accept", if (job.html) "text/html,application/xhtml+xml" else "application/json")
            .apply { if (job.html || job.url.contains("sofascore")) header("User-Agent", BROWSER_UA) }
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body?.string() ?: return false
            // O pagină de eroare (sau de verificare anti-robot) nu înlocuiește fișierul bun de dinainte.
            if (job.html) {
                if (!LiveSoccerTv.looksValid(body)) return false
            } else {
                val trimmed = body.trimStart()
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            true
        }
    }.getOrDefault(false)

    /** Fișierele zilelor dinainte de [keepFrom] se șterg. */
    private fun cleanup(keepFrom: LocalDate) {
        dir.listFiles()?.forEach { f ->
            val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
            if (day.isBefore(keepFrom)) {
                f.delete()
                parsedFiles.remove(f.path)
            }
        }
    }

    /** Un fișier de la furnizor, citit o singură dată cât nu se schimbă. */
    private fun parsed(f: File, parse: (String) -> List<SportEvent>): List<SportEvent> {
        val modified = f.lastModified()
        parsedFiles[f.path]?.let { (at, events) -> if (at == modified) return events }
        val events = runCatching { parse(f.readText()) }.getOrDefault(emptyList())
        parsedFiles[f.path] = modified to events
        return events
    }

    private fun cachedEvents(s: SportsSettings): Pair<List<SportEvent>, List<TheSportsDb.TvListing>> {
        val files = dir.listFiles().orEmpty().filterNot { it.name.endsWith(".tmp") }
        val wanted = s.sports.map { it.name }.toSet()
        val events = ArrayList<SportEvent>()
        if (s.sportsDbEnabled) {
            files.filter { f -> f.name.startsWith("events-") && f.name.split('-').getOrNull(1) in wanted }
                .forEach { f -> events += parsed(f, TheSportsDb::parseEvents) }
        }
        if (s.liveSoccerTvEnabled) {
            val zone = ZoneId.systemDefault()
            files.filter { it.name.startsWith("lstv-") && it.name.endsWith(".html") }.forEach { f ->
                val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                events += parsed(f) { LiveSoccerTv.parse(it, day, zone) }
            }
        }
        if (s.sofascoreEnabled) {
            files.filter { it.name.startsWith("sofa-") }.forEach { f ->
                val sport = f.name.split('-').getOrNull(1)?.takeIf { it in wanted }?.let(Sport::fromName) ?: return@forEach
                events += parsed(f) { Sofascore.parse(it, sport) }
            }
        }
        val tv = if (!s.sportsDbEnabled || !s.sportsDbTv) emptyList() else files.filter { it.name.startsWith("tv-") && it.name.endsWith(".json") }
            .flatMap { f -> runCatching { TheSportsDb.parseTv(f.readText()) }.getOrDefault(emptyList()) }
        return events to tv
    }

    // ---------- lista de meciuri ----------

    suspend fun recompute() {
        computeMutex.withLock {
            val next: SportsSnapshot? = try {
                withContext(Dispatchers.IO) { compute(System.currentTimeMillis()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (next != null) {
                _snapshot.value = next
            } else if (_snapshot.value.loading) {
                _snapshot.value = SportsSnapshot(loading = false)
            }
        }
    }

    private suspend fun compute(now: Long): SportsSnapshot {
        val s = settings.current
        val from = now - WINDOW_BACK_MS
        val until = now + WINDOW_AHEAD_MS

        val (events, tv) = cachedEvents(s)
        val broadcasts = loadBroadcasts(from, until)
        val matches = MatchAssembler.assemble(events.filter { it.start in (from - WINDOW_BACK_MS)..until }, broadcasts, now, tv)
            .filter { it.end > from && it.start < until }

        // Sursele sigure: canalele pe care EPG-ul arată meciul, cu toate variantele lor (alte calități ale aceluiași canal).
        val epgIds = matches.flatMap { m -> m.broadcasts.map { it.epgChannelId } }.toSet()
        val channelsByEpg = epg.channelsShowing(epgIds)
        val epgChannelIds = channelsByEpg.values.flatten().distinct()
        val epgChannels = epgChannelIds.chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.associateBy { it.id }

        val index = stationIndexCache ?: StationIndex(db.channels().activeGroupKeys()).also { stationIndexCache = it }
        val finder = SourceFinder(index, knowledge.current)

        val foundByMatch = HashMap<String, List<FoundSource>>()
        val allKeys = HashSet<String>()
        val unmatched = HashMap<String, Int>()
        val aliases = knowledge.current.stationAliases
        for (m in matches) {
            val epgKeys = m.broadcasts.flatMap { b -> channelsByEpg[b.epgChannelId].orEmpty() }
                .mapNotNull { epgChannels[it]?.groupKey }.toSet()
            val found = finder.find(m, epgKeys)
            foundByMatch[m.id] = found
            found.forEach { allKeys += it.groupKey }
            if (m.status != MatchStatus.FINISHED) {
                for (st in m.stations) {
                    val compact = StationIndex.compact(StationNames.splitCountry(st.name).first)
                    if (compact !in aliases && index.find(st).isEmpty()) unmatched.merge(st.name, 1, Int::plus)
                }
            }
        }
        val variants = allKeys.toList().chunked(IN_CHUNK).flatMap { db.channels().variants(it) }.groupBy { it.groupKey }

        val entries = matches.map { m ->
            val sources = ArrayList<MatchSource<ChannelWithPlaylist>>()
            for (f in foundByMatch.getValue(m.id)) variants[f.groupKey].orEmpty().forEach { c ->
                sources += MatchSource(c, c.groupKey, c.language, c.quality, f.confidence, f.station, f.rejected, f.origin)
            }
            val unique = sources.distinctBy { it.channel.id }
            MatchEntry(m, unique, MatchSources.summary(unique))
        }
        val stations = unmatched.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_UNMATCHED).map { it.key }
        return SportsSnapshot(entries, now, loading = false, unmatchedStations = stations)
    }

    /** Emisiunile EPG care arată a meci: de pe canalele de sport și cele cu categorie de sport. */
    private suspend fun loadBroadcasts(from: Long, until: Long): List<MatchBroadcast> {
        val sportEpg = sportEpgCache ?: loadSportEpgChannels().also { sportEpgCache = it }
        val rows = (epg.programmesOn(sportEpg, from, until) + epg.sportCategoryProgrammes(from, until, MAX_CATEGORY_ROWS))
            .distinctBy { it.id }
        return rows.mapNotNull { r ->
            val b = EpgBroadcast(r.id, r.epgChannelId, r.start, r.stop, r.title, r.subtitle, null, r.category)
            EpgMatchExtractor.extract(b, channelIsSport = r.epgChannelId in sportEpg)?.let { MatchBroadcast(b, it) }
        }
    }

    /** Canalele de sport (după nume și grup), citite pe pagini, și canalele lor EPG. */
    private suspend fun loadSportEpgChannels(): Set<Long> {
        val ids = ArrayList<Long>()
        var after = 0L
        while (true) {
            val page = db.channels().channelNames(after, PAGE)
            page.forEach { if (SportChannels.isSport(it.name, it.groupName)) ids += it.id }
            if (page.size < PAGE) break
            after = page.last().id
        }
        return epg.epgChannelsOf(ids)
    }

    private companion object {
        const val PAGE = 5_000
        val FRESH_NEAR_MS = TimeUnit.HOURS.toMillis(3)
        val FRESH_FAR_MS = TimeUnit.HOURS.toMillis(12)
        const val REQUEST_GAP_MS = 2_500L
        val WINDOW_BACK_MS = TimeUnit.HOURS.toMillis(12)
        val WINDOW_AHEAD_MS = TimeUnit.DAYS.toMillis(7)
        const val IN_CHUNK = 500
        const val MAX_CATEGORY_ROWS = 20_000
        const val MAX_UNMATCHED = 150
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        val DAY_IN_NAME = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

/** Sursa care pornește direct (prima găsită, în ordinea preferințelor tale), fără cele respinse. */
fun MatchEntry.bestSource(settings: SportsSettings): ChannelWithPlaylist? =
    MatchSources.order(sources, isFavorite = settings.favorites.isFavorite(match), prefs = settings.sourcePreferences)
        .firstOrNull { !it.members.first().rejected }
        ?.members?.firstOrNull()?.channel

/** Prima sursă (în aceeași ordine) al cărei canal are arhivă (catch-up), pentru „de la început”. */
fun MatchEntry.archiveSource(settings: SportsSettings): ChannelWithPlaylist? =
    MatchSources.order(sources, isFavorite = settings.favorites.isFavorite(match), prefs = settings.sourcePreferences)
        .flatMap { it.members }
        .firstOrNull { !it.rejected && (it.channel.catchupDays ?: 0) > 0 }
        ?.channel
