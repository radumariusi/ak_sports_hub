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
import ro.ak.sportshub.core.database.ChannelNameRow
import ro.ak.sportshub.core.sports.DedicatedChannels
import ro.ak.sportshub.core.sports.EpgBroadcast
import ro.ak.sportshub.core.sports.EventChannels
import ro.ak.sportshub.core.sports.EventSlot
import ro.ak.sportshub.core.sports.FutbolEnLaTv
import ro.ak.sportshub.core.sports.SourceDiagnosis
import ro.ak.sportshub.core.sports.SourceDiagnostics
import ro.ak.sportshub.core.sports.TeamNames
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

/** Un canal EVENT / PPV din playlist, cu ce scrie în nume și meciul de care s-a legat (dacă s-a legat). */
data class EventEntry(val channel: ChannelWithPlaylist, val slot: EventSlot, val matchId: String?)

/** Un canal de sport fără program EPG (pentru raportul „canale de sport fără EPG”). */
data class NoEpgChannel(val name: String, val group: String, val playlist: String, val variants: Int)

data class SportsSnapshot(
    val matches: List<MatchEntry> = emptyList(),
    val computedAt: Long = 0,
    /** Încă nu s-a calculat nimic de la pornire. */
    val loading: Boolean = true,
    /** Posturile anunțate pentru meciurile din listă pe care nu le-am găsit în playlisturi (cele mai dese întâi). */
    val unmatchedStations: List<String> = emptyList(),
    /** Canalele EVENT / PPV cu un eveniment în nume (ordonate după oră). */
    val events: List<EventEntry> = emptyList(),
    /** Canalele EVENT / PPV goale acum („EVENT 03”, „No event”). */
    val emptyEventChannels: Int = 0,
    /** Canalele de sport fără EPG (câte unul pe canal grupat), cele cu mai multe variante întâi. */
    val noEpgChannels: List<NoEpgChannel> = emptyList(),
    /** Câte canale de sport (variante) sunt în total. */
    val sportChannelCount: Int = 0,
)

/**
 * Ce se află o dată din numele canalelor (se reface doar când se schimbă playlisturile sau EPG-ul):
 * canalele de sport și canalele lor EPG, canalele EVENT / PPV și canalele dedicate unei competiții.
 */
private class ChannelScan(
    val sportEpg: Set<Long>,
    val sportChannelCount: Int,
    val events: List<ChannelNameRow>,
    /** Seria (f1, motogp…) → cheile de grupare ale canalelor dedicate. */
    val dedicated: Map<String, Set<String>>,
    val noEpg: List<NoEpgChannel>,
)

/**
 * Meciurile din Sports Hub: din EPG-ul tău, TheSportsDB și, opțional, LiveSoccerTV și Sofascore,
 * cu sursele găsite în playlisturi.
 *
 * - LiveSoccerTV: o pagină pe zi (azi și mâine), la 3 ore; Sofascore: o cerere pe sport și zi (azi și mâine), la 3 ore,
 *   plus posturile TV pentru cel mult [SOFA_TV_PER_ROUND] meciuri pe rundă; FutbolEnLaTV: o pagină, la 3 ore.
 *   Niciunul nu e furnizorul de IPTV, deci nu trec prin `ConnectionGate`.
 * - Canalele EVENT / PPV ([EventChannels]) și cele dedicate ([DedicatedChannels]) se citesc doar din numele
 *   canalelor din baza locală: zero rețea.
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

    /** Ce s-a aflat din numele canalelor (se reface doar când se schimbă playlisturile sau EPG-ul). */
    @Volatile
    private var scanCache: ChannelScan? = null

    /** Numele canalelor active, pentru potrivirea pe nume de post (se reface ca [scanCache]). */
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
                    scanCache = null
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
            "${s.liveSoccerTvEnabled}|${s.sofascoreEnabled}|${s.sofascoreTv}|${s.futbolEnLaTvEnabled}|${s.eventChannels}|${s.eventZone}"

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
        if (!s.sportsDbEnabled && !s.liveSoccerTvEnabled && !s.sofascoreEnabled && !s.futbolEnLaTvEnabled) {
            recompute()
            return
        }
        val changed = fetchMutex.withLock { withContext(Dispatchers.IO) { fetch(s, force) } }
        if (changed || force || _snapshot.value.loading) recompute()
    }

    // ---------- furnizorii ----------

    /** O cerere de făcut: adresa, fișierul în care se păstrează și pagina HTML care trebuie să pară validă, dacă e HTML. */
    private data class Job(val url: String, val file: File, val html: ((String) -> Boolean)? = null)

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
                if (force || isStale(f, FRESH_NEAR_MS)) jobs += Job(LiveSoccerTv.scheduleUrl(day), f, html = LiveSoccerTv::looksValid)
            }
            if (s.sofascoreEnabled) {
                for (sport in s.sports) {
                    val url = Sofascore.scheduleUrl(sport, day) ?: continue
                    val f = File(dir, "sofa-${sport.name}-$day.json")
                    if (force || isStale(f, FRESH_NEAR_MS)) jobs += Job(url, f)
                }
            }
        }
        if (s.futbolEnLaTvEnabled) {
            val f = File(dir, "fetv-${LocalDate.now(FutbolEnLaTv.ZONE)}.html")
            if (force || isStale(f, FRESH_NEAR_MS)) jobs += Job(FutbolEnLaTv.URL, f, html = FutbolEnLaTv::looksValid)
        }
        if (s.sportsDbEnabled) addSportsDbJobs(s, today, force, jobs)
        val (ok, failed) = runJobs(jobs, "Se descarcă programul meciurilor")
        changed = ok > 0
        errors += failed
        // Apoi posturile TV de la Sofascore, pentru câteva meciuri (programul e deja la zi).
        if (s.sofascoreEnabled && s.sofascoreTv) {
            val (tvOk, tvFailed) = fetchSofascoreTv(s)
            if (tvOk > 0) changed = true
            errors += tvFailed
        }
        _status.value = if (errors > 0) "$errors cereri nereușite; se folosesc datele salvate." else null
        return changed
    }

    /** Face cererile una câte una, cu pauză între ele. Întoarce (reușite, nereușite). */
    private suspend fun runJobs(jobs: List<Job>, label: String): Pair<Int, Int> {
        var ok = 0
        var failed = 0
        jobs.forEachIndexed { i, job ->
            _status.value = "$label (${i + 1}/${jobs.size})…"
            if (download(job)) ok++ else failed++
            if (i < jobs.lastIndex) delay(REQUEST_GAP_MS) // cereri rare, una câte una
        }
        return ok to failed
    }

    // ---------- Sofascore: posturile TV ----------

    /** Țările (codurile ISO) din care ai canale: posturile din alte țări nu se cer și nu se arată. */
    private fun wantedCountry(prefixes: Set<String>): (String) -> Boolean = { cc ->
        prefixes.isEmpty() || Sofascore.prefixes(cc).any { it in prefixes }
    }

    private suspend fun playlistCountries(): Set<String> =
        (stationIndexCache ?: StationIndex(db.channels().activeGroupKeys()).also { stationIndexCache = it }).countries()

    /**
     * Posturile pe țară pentru cel mult [SOFA_TV_PER_ROUND] meciuri Sofascore (favoritele, apoi cele fără sursă sigură),
     * fiecare reîmprospătat la 12 ore; apoi numele posturilor necunoscute: lista țării (o dată pe săptămână),
     * altfel câte un post, cel mult [SOFA_NAMES_PER_ROUND] pe rundă.
     */
    private suspend fun fetchSofascoreTv(s: SportsSettings): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val wanted = s.sports.map { it.name }.toSet()
        val events = dir.listFiles().orEmpty()
            .filter { it.name.startsWith("sofa-") && it.name.endsWith(".json") }
            .flatMap { f ->
                val sport = f.name.split('-').getOrNull(1)?.takeIf { it in wanted }?.let(Sport::fromName) ?: return@flatMap emptyList()
                parsed(f) { Sofascore.parse(it, sport) }
            }
        val covered = _snapshot.value.matches.filter { it.summary.sure > 0 && !it.match.isSingleEvent }
            .map { pairKey(it.match.home, it.match.away) }.toSet()
        val k = knowledge.current
        val picked = Sofascore.pickForTv(
            events, now, SOFA_TV_PER_ROUND,
            favorite = { e -> s.favorites.teams.any { t -> TeamNames.same(t, e.home) || TeamNames.same(t, e.away) } || s.favorites.isCompetition(e.competition) },
            covered = { e -> pairKey(e.home, e.away) in covered },
            important = { e -> k.rules.any { it.appliesTo(e.competition) } || e.competition?.let { Sport.fold(it) } in k.learnedRights },
        )
        val jobs = picked.mapNotNull { e ->
            val day = java.time.Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate()
            val f = File(dir, "sofatv-$day-${e.providerId}.json")
            if (isStale(f, SOFA_TV_FRESH_MS)) Job(Sofascore.tvUrl(e.providerId), f) else null
        }
        var (ok, failed) = runJobs(jobs, "Se descarcă posturile TV (Sofascore)")

        // Numele posturilor fără nume, doar din țările tale.
        val wantedCc = wantedCountry(playlistCountries())
        val names = sofaChannelNames()
        val missing = HashMap<String, MutableSet<String>>()
        tvFiles().forEach { f ->
            val refs = parsedTv(f)
            Sofascore.unnamed(refs, names, wantedCc).forEach { (cc, ids) -> missing.getOrPut(cc) { HashSet() } += ids }
        }
        if (missing.isEmpty()) return ok to failed
        val countryJobs = missing.keys.mapNotNull { cc ->
            val f = File(dir, "sofa-tvcountry-$cc.json")
            if (isStale(f, SOFA_NAMES_FRESH_MS)) Job(Sofascore.countryChannelsUrl(cc), f) else null
        }
        runJobs(countryJobs, "Se descarcă numele posturilor (Sofascore)").let { (a, b) -> ok += a; failed += b }
        val known = sofaChannelNames()
        val stillMissing = missing.values.flatten().filter { it !in known }.distinct().take(SOFA_NAMES_PER_ROUND)
        val channelJobs = stillMissing.mapNotNull { id ->
            val f = File(dir, "sofa-tvchannel-$id.json")
            if (isStale(f, SOFA_NAMES_FRESH_MS)) Job(Sofascore.channelUrl(id), f) else null
        }
        runJobs(channelJobs, "Se descarcă numele posturilor (Sofascore)").let { (a, b) -> ok += a; failed += b }
        return ok to failed
    }

    private fun pairKey(home: String, away: String) = listOf(TeamNames.key(home), TeamNames.key(away)).sorted().joinToString("|")

    private fun tvFiles(): List<File> = dir.listFiles().orEmpty().filter { it.name.startsWith("sofatv-") && it.name.endsWith(".json") }

    /** Răspunsurile `country-channels`, citite o dată cât nu se schimbă fișierul. */
    private val parsedTvFiles = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, List<Sofascore.ChannelRef>>>>()

    private fun parsedTv(f: File): Map<String, List<Sofascore.ChannelRef>> {
        val modified = f.lastModified()
        parsedTvFiles[f.path]?.let { (at, refs) -> if (at == modified) return refs }
        val refs = runCatching { Sofascore.parseCountryChannels(f.readText()) }.getOrDefault(emptyMap())
        parsedTvFiles[f.path] = modified to refs
        return refs
    }

    @Volatile
    private var namesCache: Pair<Long, Map<String, String>>? = null

    /** Id post → nume, din listele de țări și din programele de post salvate. */
    private fun sofaChannelNames(): Map<String, String> {
        val files = dir.listFiles().orEmpty().filter { (it.name.startsWith("sofa-tvcountry-") || it.name.startsWith("sofa-tvchannel-")) && it.name.endsWith(".json") }
        val stamp = files.sumOf { it.lastModified() } + files.size
        namesCache?.let { (at, names) -> if (at == stamp) return names }
        val names = HashMap<String, String>()
        files.forEach { f -> runCatching { Sofascore.parseChannelNames(f.readText()) }.getOrNull()?.forEach { (id, n) -> names.putIfAbsent(id, n) } }
        namesCache = stamp to names
        return names
    }

    /** Posturile Sofascore ale meciurilor (id meci → posturi), din țările tale. */
    private suspend fun sofascoreStations(): Map<String, List<ro.ak.sportshub.core.sports.TvStation>> {
        val files = tvFiles()
        if (files.isEmpty()) return emptyMap()
        val names = sofaChannelNames()
        val wanted = wantedCountry(playlistCountries())
        return files.associate { f ->
            val id = f.name.removeSuffix(".json").substringAfterLast('-')
            id to Sofascore.stations(parsedTv(f), names, wanted)
        }.filterValues { it.isNotEmpty() }
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
            .header("Accept", if (job.html != null) "text/html,application/xhtml+xml" else "application/json")
            .apply { if (job.html != null || job.url.contains("sofascore")) header("User-Agent", BROWSER_UA) }
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body?.string() ?: return false
            // O pagină de eroare (sau de verificare anti-robot) nu înlocuiește fișierul bun de dinainte.
            if (job.html != null) {
                if (!job.html.invoke(body)) return false
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
                parsedTvFiles.remove(f.path)
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

    private suspend fun cachedEvents(s: SportsSettings): Pair<List<SportEvent>, List<TheSportsDb.TvListing>> {
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
            val tvStations = if (s.sofascoreTv) sofascoreStations() else emptyMap()
            files.filter { it.name.startsWith("sofa-") && it.name.endsWith(".json") }.forEach { f ->
                val sport = f.name.split('-').getOrNull(1)?.takeIf { it in wanted }?.let(Sport::fromName) ?: return@forEach
                parsed(f) { Sofascore.parse(it, sport) }.forEach { e ->
                    val st = tvStations[e.providerId]
                    events += if (st.isNullOrEmpty()) e else e.copy(stations = (e.stations + st).distinct())
                }
            }
        }
        if (s.futbolEnLaTvEnabled) {
            files.filter { it.name.startsWith("fetv-") && it.name.endsWith(".html") }.forEach { f ->
                val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                events += parsed(f) { FutbolEnLaTv.parse(it, day) }
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
        val scan = scanCache ?: scanChannels().also { scanCache = it }
        val broadcasts = loadBroadcasts(scan.sportEpg, from, until)
        val zone = s.eventZoneId()
        val slots = if (!s.eventChannels) emptyList() else scan.events.map { EventChannels.parse(it.id, it.name, it.groupName, now, zone) }
        val activeSlots = slots.filter { it.home != null && (it.start == null || it.start in from..until) }
        val matches = MatchAssembler.assemble(events.filter { it.start in (from - WINDOW_BACK_MS)..until }, broadcasts, now, tv, activeSlots)
            .filter { it.end > from && it.start < until }
        val slotChannels = activeSlots.map { it.channelId }.distinct().chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.associateBy { it.id }

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
            val eventKeys = m.eventChannels.mapNotNull { slotChannels[it]?.groupKey }.toSet()
            val dedicatedKeys = DedicatedChannels.seriesOfMatch(m)?.let { scan.dedicated[it] }.orEmpty()
            val found = finder.find(m, epgKeys, eventKeys, dedicatedKeys)
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
        val matchOfChannel = HashMap<Long, String>()
        matches.forEach { m -> m.eventChannels.forEach { matchOfChannel.putIfAbsent(it, m.id) } }
        val eventEntries = activeSlots.mapNotNull { slot ->
            slotChannels[slot.channelId]?.let { EventEntry(it, slot, matchOfChannel[slot.channelId]) }
        }.sortedWith(compareBy<EventEntry> { it.slot.start ?: Long.MAX_VALUE }.thenBy { it.slot.title })
        return SportsSnapshot(
            entries, now, loading = false, unmatchedStations = stations,
            events = eventEntries,
            emptyEventChannels = slots.count { it.home == null },
            noEpgChannels = scan.noEpg,
            sportChannelCount = scan.sportChannelCount,
        )
    }

    /** „De ce nu are sursă?” pentru meciul [id] (null dacă nu mai e în listă). */
    suspend fun diagnose(id: String): SourceDiagnosis? = withContext(Dispatchers.IO) {
        val m = find(id)?.match ?: return@withContext null
        val index = stationIndexCache ?: StationIndex(db.channels().activeGroupKeys()).also { stationIndexCache = it }
        SourceDiagnostics.diagnose(m, index, knowledge.current)
    }

    /** Leagă postul anunțat [station] de canalul [groupKey] (dicționarul tău) și recalculează lista. */
    fun linkStation(station: String, groupKey: String) {
        knowledge.update { it.addStationAlias(station, groupKey) }
        scope.launch { recompute() }
    }

    /** Emisiunile EPG care arată a meci (sau un eveniment fără echipe): de pe canalele de sport și cele cu categorie de sport. */
    private suspend fun loadBroadcasts(sportEpg: Set<Long>, from: Long, until: Long): List<MatchBroadcast> {
        val rows = (epg.programmesOn(sportEpg, from, until) + epg.sportCategoryProgrammes(from, until, MAX_CATEGORY_ROWS))
            .distinctBy { it.id }
        return rows.mapNotNull { r ->
            val b = EpgBroadcast(r.id, r.epgChannelId, r.start, r.stop, r.title, r.subtitle, null, r.category)
            val sport = r.epgChannelId in sportEpg
            (EpgMatchExtractor.extract(b, channelIsSport = sport) ?: EpgMatchExtractor.extractEvent(b, channelIsSport = sport))
                ?.let { MatchBroadcast(b, it) }
        }
    }

    /**
     * O trecere prin numele canalelor active, pe pagini: canalele de sport (și canalele lor EPG, plus cele fără EPG),
     * canalele EVENT / PPV și canalele dedicate unei competiții.
     */
    private suspend fun scanChannels(): ChannelScan {
        val sportIds = ArrayList<Long>()
        val events = ArrayList<ChannelNameRow>()
        val dedicatedIds = HashMap<String, MutableList<Long>>()
        var after = 0L
        while (true) {
            val page = db.channels().channelNames(after, PAGE)
            page.forEach { row ->
                val isEvent = EventChannels.isEventChannel(row.name, row.groupName)
                if (isEvent) events += row
                if (SportChannels.isSport(row.name, row.groupName)) {
                    sportIds += row.id
                    if (!isEvent) DedicatedChannels.seriesOfChannel(row.name)?.let { dedicatedIds.getOrPut(it) { ArrayList() } += row.id }
                }
            }
            if (page.size < PAGE) break
            after = page.last().id
        }
        val dedicated = dedicatedIds.mapValues { (_, ids) ->
            ids.chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.map { it.groupKey }.toSet()
        }
        // Canalele de sport fără EPG (în afară de cele EVENT / PPV, care oricum n-au program).
        val eventIds = events.map { it.id }.toHashSet()
        val candidates = sportIds.filter { it !in eventIds }
        val mapped = epg.mappedChannels(candidates)
        val withoutIds = candidates.filter { it !in mapped }
        val noEpg = withoutIds.take(MAX_NO_EPG_SCAN).chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }
            .groupBy { it.groupKey }
            .map { (_, list) -> NoEpgChannel(list.first().name, list.first().groupName, list.first().playlistName, list.size) }
            .sortedWith(compareByDescending<NoEpgChannel> { it.variants }.thenBy { it.name })
            .take(MAX_NO_EPG)
        return ChannelScan(
            sportEpg = epg.epgChannelsOf(sportIds),
            sportChannelCount = sportIds.size,
            events = events,
            dedicated = dedicated,
            noEpg = noEpg,
        )
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
        const val MAX_NO_EPG = 300
        const val MAX_NO_EPG_SCAN = 5_000
        const val SOFA_TV_PER_ROUND = 25
        const val SOFA_NAMES_PER_ROUND = 15
        val SOFA_TV_FRESH_MS = TimeUnit.HOURS.toMillis(12)
        val SOFA_NAMES_FRESH_MS = TimeUnit.DAYS.toMillis(7)
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
