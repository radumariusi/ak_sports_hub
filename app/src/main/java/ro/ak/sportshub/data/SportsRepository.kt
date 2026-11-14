package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import java.time.LocalDate
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
import ro.ak.sportshub.core.sports.Confidence
import ro.ak.sportshub.core.sports.EpgBroadcast
import ro.ak.sportshub.core.sports.EpgMatchExtractor
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchAssembler
import ro.ak.sportshub.core.sports.MatchBroadcast
import ro.ak.sportshub.core.sports.MatchSource
import ro.ak.sportshub.core.sports.MatchSources
import ro.ak.sportshub.core.sports.SourceSummary
import ro.ak.sportshub.core.sports.SportChannels
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.StationIndex
import ro.ak.sportshub.core.sports.TheSportsDb

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
)

/**
 * Meciurile din Sports Hub: din EPG-ul tău și din TheSportsDB, cu sursele găsite în playlisturi.
 *
 * - TheSportsDB se cere rar (azi și mâine la 3 ore, restul la 12 ore), cerere cu cerere; răspunsurile se
 *   păstrează în fișiere, ca lista să meargă și fără rețea. O eroare nu șterge datele vechi.
 * - EPG-ul se citește doar din baza locală: nicio cerere la furnizorul de IPTV, deci nimic nu concurează
 *   cu playerul pentru conexiunea playlistului.
 * - Potrivirea: emisiunea EPG cu ambele echipe (sigur), apoi postul anunțat de furnizor (probabil).
 */
class SportsRepository(
    context: Context,
    private val db: AkDatabase,
    private val epg: EpgRepository,
    private val playlists: PlaylistRepository,
    private val variantStore: VariantStore,
    private val settings: SportsSettingsStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.noBackupFilesDir, "sports")
    private val fetchMutex = Mutex()
    private val computeMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(SportsSnapshot())
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
            ).debounce(1_500).collect { recompute() }
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
        "${s.sports.joinToString(",") { it.name }}|${s.sportsDbEnabled}|${s.sportsDbTv}|${s.daysAhead}"

    fun refreshInBackground(force: Boolean = true) {
        scope.launch { refreshDue(force) }
    }

    /** Cere de la TheSportsDB ce e vechi (sau tot, cu [force]), apoi recalculează lista. */
    suspend fun refreshDue(force: Boolean = false) {
        val s = settings.current
        if (!s.sportsDbEnabled) {
            recompute()
            return
        }
        val changed = fetchMutex.withLock { withContext(Dispatchers.IO) { fetch(s, force) } }
        if (changed || force || _snapshot.value.loading) recompute()
    }

    // ---------- TheSportsDB ----------

    private suspend fun fetch(s: SportsSettings, force: Boolean): Boolean {
        dir.mkdirs()
        val today = LocalDate.now(ZoneOffset.UTC)
        cleanup(today)
        var changed = false
        var errors = 0
        val jobs = ArrayList<Pair<String, File>>()
        for (offset in 0 until s.daysAhead.coerceIn(1, 7)) {
            val day = today.plusDays(offset.toLong())
            val maxAge = if (offset <= 1) FRESH_NEAR_MS else FRESH_FAR_MS
            for (sport in s.sports) {
                val f = File(dir, "events-${sport.name}-$day.json")
                if (force || isStale(f, maxAge)) jobs += TheSportsDb.eventsDayUrl(s.sportsDbKey, day, sport) to f
            }
            if (s.sportsDbTv && offset <= 1) {
                val f = File(dir, "tv-$day.json")
                if (force || isStale(f, maxAge)) jobs += TheSportsDb.tvDayUrl(s.sportsDbKey, day) to f
            }
        }
        jobs.forEachIndexed { i, (url, file) ->
            _status.value = "Se descarcă programul meciurilor (${i + 1}/${jobs.size})…"
            if (download(url, file)) changed = true else errors++
            if (i < jobs.lastIndex) delay(REQUEST_GAP_MS) // cereri rare, una câte una
        }
        _status.value = if (errors > 0) "TheSportsDB: $errors cereri nereușite; se folosesc datele salvate." else null
        return changed
    }

    private fun isStale(f: File, maxAge: Long) = !f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge

    private fun download(url: String, target: File): Boolean = runCatching {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body?.string() ?: return false
            // O pagină de eroare sau un răspuns fără listă nu înlocuiește fișierul bun de dinainte.
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

    /** Fișierele zilelor trecute (mai vechi de ieri) se șterg. */
    private fun cleanup(today: LocalDate) {
        val keepFrom = today.minusDays(1)
        dir.listFiles()?.forEach { f ->
            val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
            if (day.isBefore(keepFrom)) f.delete()
        }
    }

    private fun cachedEvents(s: SportsSettings): Pair<List<SportEvent>, List<TheSportsDb.TvListing>> {
        if (!s.sportsDbEnabled) return emptyList<SportEvent>() to emptyList()
        val files = dir.listFiles().orEmpty()
        val wanted = s.sports.map { it.name }.toSet()
        val events = files.filter { f -> f.name.startsWith("events-") && f.name.split('-').getOrNull(1) in wanted }
            .flatMap { f -> runCatching { TheSportsDb.parseEvents(f.readText()) }.getOrDefault(emptyList()) }
        val tv = if (!s.sportsDbTv) emptyList() else files.filter { it.name.startsWith("tv-") && it.name.endsWith(".json") }
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

        // Sursele probabile: posturile anunțate de furnizor, după nume.
        val stationIndex = if (matches.any { it.stations.isNotEmpty() }) StationIndex(db.channels().activeGroupKeys()) else null

        val keysByMatch = HashMap<String, Pair<Set<String>, Map<String, String>>>()
        val allKeys = HashSet<String>()
        for (m in matches) {
            val epgKeys = m.broadcasts.flatMap { b -> channelsByEpg[b.epgChannelId].orEmpty() }
                .mapNotNull { epgChannels[it]?.groupKey }.toSet()
            val stationKeys = LinkedHashMap<String, String>()
            if (stationIndex != null) {
                for (st in m.stations) for (k in stationIndex.find(st)) if (k !in epgKeys) stationKeys.putIfAbsent(k, st.name)
            }
            keysByMatch[m.id] = epgKeys to stationKeys
            allKeys += epgKeys
            allKeys += stationKeys.keys
        }
        val variants = allKeys.toList().chunked(IN_CHUNK).flatMap { db.channels().variants(it) }.groupBy { it.groupKey }

        val entries = matches.map { m ->
            val (epgKeys, stationKeys) = keysByMatch.getValue(m.id)
            val sources = ArrayList<MatchSource<ChannelWithPlaylist>>()
            for (k in epgKeys) variants[k].orEmpty().forEach { c ->
                sources += MatchSource(c, c.groupKey, c.language, c.quality, Confidence.EPG)
            }
            for ((k, station) in stationKeys) variants[k].orEmpty().forEach { c ->
                sources += MatchSource(c, c.groupKey, c.language, c.quality, Confidence.STATION, station)
            }
            val unique = sources.distinctBy { it.channel.id }
            MatchEntry(m, unique, MatchSources.summary(unique))
        }
        return SportsSnapshot(entries, now, loading = false)
    }

    /** Emisiunile EPG care arată a meci: de pe canalele de sport și cele cu categorie de sport. */
    private suspend fun loadBroadcasts(from: Long, until: Long): List<MatchBroadcast> {
        val sportChannelIds = db.channels().sportChannelCandidates()
            .filter { SportChannels.isSport(it.name, it.groupName) }
            .map { it.id }
        val sportEpg = epg.epgChannelsOf(sportChannelIds)
        val rows = (epg.programmesOn(sportEpg, from, until) + epg.sportCategoryProgrammes(from, until, MAX_CATEGORY_ROWS))
            .distinctBy { it.id }
        return rows.mapNotNull { r ->
            val b = EpgBroadcast(r.id, r.epgChannelId, r.start, r.stop, r.title, r.subtitle, null, r.category)
            EpgMatchExtractor.extract(b, channelIsSport = r.epgChannelId in sportEpg)?.let { MatchBroadcast(b, it) }
        }
    }

    private companion object {
        val FRESH_NEAR_MS = TimeUnit.HOURS.toMillis(3)
        val FRESH_FAR_MS = TimeUnit.HOURS.toMillis(12)
        const val REQUEST_GAP_MS = 2_500L
        val WINDOW_BACK_MS = TimeUnit.HOURS.toMillis(12)
        val WINDOW_AHEAD_MS = TimeUnit.DAYS.toMillis(7)
        const val IN_CHUNK = 500
        const val MAX_CATEGORY_ROWS = 20_000
        val DAY_IN_NAME = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
