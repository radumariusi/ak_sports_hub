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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
import ro.ak.sportshub.core.sports.CompetitionFilter
import ro.ak.sportshub.core.sports.DedicatedChannels
import ro.ak.sportshub.core.sports.EpgBroadcast
import ro.ak.sportshub.core.sports.EventChannels
import ro.ak.sportshub.core.sports.EventSlot
import ro.ak.sportshub.core.sports.FutbolEnLaTv
import ro.ak.sportshub.core.sports.Espn
import ro.ak.sportshub.core.sports.EuroLeague
import ro.ak.sportshub.core.sports.BusyChannels
import ro.ak.sportshub.core.sports.MatchInsights
import ro.ak.sportshub.core.sports.FotMob
import ro.ak.sportshub.core.sports.Highlights
import ro.ak.sportshub.core.sports.LiveOnSat
import ro.ak.sportshub.core.sports.UsLeagues
import ro.ak.sportshub.core.sports.SourceDiagnosis
import ro.ak.sportshub.core.sports.SourceDiagnostics
import ro.ak.sportshub.core.sports.TeamNames
import ro.ak.sportshub.core.sports.EpgMatchExtractor
import ro.ak.sportshub.core.sports.ExtractedMatch
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
import ro.ak.sportshub.core.sports.FollowedNames
import ro.ak.sportshub.core.sports.SourceFinder
import ro.ak.sportshub.core.sports.SourceSummary
import ro.ak.sportshub.core.sports.SportChannels
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.StationIndex
import ro.ak.sportshub.core.sports.StationNames
import ro.ak.sportshub.core.sports.TheSportsDb
import ro.ak.sportshub.core.sports.Verdict
import ro.ak.sportshub.core.sports.ProviderPolicy
import ro.ak.sportshub.core.common.DiagnosticText
import ro.ak.sportshub.core.common.ChannelNameParser

/** Un meci cu sursele găsite în playlisturile tale. */
data class MatchEntry(
    val match: Match,
    val sources: List<MatchSource<ChannelWithPlaylist>>,
    val summary: SourceSummary,
)

/** Un canal EVENT / PPV din playlist, cu ce scrie în nume și meciul de care s-a legat (dacă s-a legat). */
data class EventEntry(val channel: ChannelWithPlaylist, val slot: EventSlot, val matchId: String?)

/** Un rezumat sau un magazin sportiv din arhiva unui canal (catch-up), găsit în EPG. */
data class HighlightEntry(
    val title: String,
    val subtitle: String?,
    val start: Long,
    val stop: Long,
    val channel: ChannelWithPlaylist,
    /** Titlul pomenește o echipă sau o competiție favorită. */
    val favorite: Boolean,
)

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
    private val observed: ObservedVideoStore,
    private val settings: SportsSettingsStore,
    private val knowledge: SportsKnowledgeStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val log: DiagnosticLog,
    /** Playerul rulează un stream: calculele și cererile se amână, ca toată puterea să meargă la redare. */
    private val playing: StateFlow<Boolean> = MutableStateFlow(false),
    /** Siglele și culorile echipelor, învățate din programul furnizorilor. */
    private val looks: TeamLookStore? = null,
) {
    private val dir = File(context.noBackupFilesDir, "sports")

    /** Lista salvată după fiecare calcul (apare imediat la intrarea în aplicație). */
    private val stored = SportsSnapshotFile(File(context.noBackupFilesDir, "sports-lista.bin"))

    /** Canalele de sport și EVENT citite din nume, salvate (se refac doar când se schimbă playlisturile). */
    private val namesFile = File(context.noBackupFilesDir, "sports-canale.bin")

    /** Amprenta intrărilor din care s-a calculat lista curentă (null: încă niciun calcul). */
    @Volatile private var computedKey: String? = null
    /** Pauzele furnizorilor care ne-au refuzat ([ProviderPolicy.Block]); nu intră în backup. */
    private val blocks = context.getSharedPreferences("sports_backoff", Context.MODE_PRIVATE)
    /** Furnizorii în pauză deja notați în jurnal în runda curentă. */
    private val pausedLogged = HashSet<String>()
    /** A doua încercare, ca un browser, pentru site-urile care refuză clientul HTTP (403). */
    private val browser = WebViewFetcher(context)
    /** Furnizorii care au răspuns 429 în runda curentă: cererile lor rămase se amână la runda următoare. */
    private val throttled = HashSet<String>()
    private val fetchMutex = Mutex()
    private val computeMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(SportsSnapshot())

    /** Ce s-a aflat din numele canalelor și din potrivirile EPG (se reface când se schimbă playlisturile sau EPG-ul). */
    @Volatile
    private var scanCache: ChannelScan? = null

    /** Doar ce se află din numele canalelor (se reface doar când se schimbă playlisturile). */
    @Volatile
    private var nameScanCache: NameScan? = null

    /** Numele canalelor active, pentru potrivirea pe nume de post (se reface ca [scanCache]). */
    @Volatile
    private var stationIndexCache: StationIndex? = null

    /** Fișierele de la furnizori, citite o dată (cheia: cale + data modificării). */
    private val parsedFiles = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SportEvent>>>()
    private val _status = MutableStateFlow<String?>(null)
    private val _health = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())

    /** Cum a mers ultima rundă de cereri la fiecare furnizor (cheia: [providerOf]) și câte meciuri a dat. */
    val health: StateFlow<Map<String, ProviderHealth>> = _health.asStateFlow()

    val snapshot: StateFlow<SportsSnapshot> = _snapshot.asStateFlow()

    /** Ce face acum („Se descarcă programul…”) sau ultima eroare; null dacă nimic. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun find(id: String): MatchEntry? = _snapshot.value.matches.firstOrNull { it.match.id == id }

    /** Pornește recalcularea la orice schimbare (EPG, playlisturi, corecții, setări) și la fiecare minut. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Lista de data trecută apare imediat (și după ce Android a închis aplicația); se recalculează doar dacă
            // s-a schimbat ceva de atunci.
            withContext(Dispatchers.IO) { stored.read() }?.let { st ->
                if (_snapshot.value.loading) {
                    _snapshot.value = requality(SportsSnapshotFile.retime(st.snapshot, System.currentTimeMillis()))
                    computedKey = st.key
                }
            }
            merge(
                epg.observeStates().map { "epg" },
                // Potrivirea canal → program se reface după ce se salvează starea sursei EPG:
                // fără asta, lista canalelor de sport rămânea cea de dinainte (fără EPG, deci fără surse).
                epg.observeMappedCount().map { "epg-map" },
                playlists.changes.map { "playlists" },
                variantStore.state.map { it.corrections }.distinctUntilChanged().map { "corrections" },
                settings.settings.map { st: SportsSettings -> fetchKey(st) }.distinctUntilChanged().map { "settings" },
                knowledge.state.map { "knowledge" },
                // Rezoluția văzută la redare schimbă ordinea surselor.
                observed.state.map { "observed" },
            ).debounce(1_000).collect { reason ->
                // Rezoluția văzută la redare schimbă doar eticheta și ordinea surselor: se aplică pe lista existentă,
                // fără recalcul (după ce se oprește playerul, ca nimic să nu încetinească redarea).
                if (reason == "observed") {
                    if (playing.value) playing.first { !it }
                    computeMutex.withLock {
                        val next = requality(_snapshot.value)
                        if (next !== _snapshot.value) {
                            _snapshot.value = next
                            computedKey?.let { key -> withContext(Dispatchers.IO) { stored.write(next, key) } }
                        }
                    }
                    return@collect
                }
                // Canalele sau potrivirile EPG s-au putut schimba: lista canalelor de sport se reface.
                if (reason == "playlists") nameScanCache = null
                if (reason != "knowledge" && reason != "settings" && reason != "observed") {
                    scanCache = null
                    stationIndexCache = null
                    nearWindow = null
                    farWindow = null
                }
                recomputeIfChanged()
            }
        }
        scope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(1))
                // La fiecare minut doar starea meciurilor (LIVE / terminat), fără calcul; calculul doar dacă s-a
                // schimbat ceva sau a trecut o oră.
                _snapshot.update { if (it.loading) it else SportsSnapshotFile.retime(it, System.currentTimeMillis()) }
                recomputeIfChanged()
            }
        }
    }

    /** Ce din setări schimbă lista (nu și filtrele sau favoritele, care se aplică în ecran). */
    private fun fetchKey(s: SportsSettings): String =
        "${s.sports.joinToString(",") { it.name }}|${s.sportsDbEnabled}|${s.sportsDbTv}|${s.daysAhead}|" +
            "${s.liveSoccerTvEnabled}|${s.sofascoreEnabled}|${s.sofascoreTv}|${s.futbolEnLaTvEnabled}|${s.eventChannels}|${s.eventZone}|" +
            "${s.liveOnSatEnabled}|${s.fotMobEnabled}|${s.usLeaguesEnabled}|${s.euroLeagueEnabled}|${s.topCompetitionsOnly}|" +
            s.favorites.names.joinToString(",") + "|" + s.favorites.teams.joinToString(",") + "|" + s.favorites.competitions.joinToString(",")

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
        // Cererile și citirea paginilor așteaptă oprirea playerului (doar „Actualizează acum” nu așteaptă).
        if (!force && playing.value) playing.first { !it }
        val s = settings.current
        if (!s.sportsDbEnabled && !s.liveSoccerTvEnabled && !s.sofascoreEnabled && !s.futbolEnLaTvEnabled &&
            !s.liveOnSatEnabled && !s.fotMobEnabled && !s.usLeaguesEnabled && !s.euroLeagueEnabled
        ) {
            recompute()
            return
        }
        val changed = fetchMutex.withLock { withContext(Dispatchers.IO) { fetch(s, force) } }
        if (changed || force || _snapshot.value.loading) recompute()
    }

    // ---------- furnizorii ----------

    /** O cerere de făcut: adresa, fișierul în care se păstrează și pagina HTML care trebuie să pară validă, dacă e HTML. */
    private data class Job(
        val url: String,
        val file: File,
        val html: ((String) -> Boolean)? = null,
        /** Liga ESPN: după răspuns i se salvează calendarul (zilele cu meciuri). */
        val espn: Espn.League? = null,
    )

    private suspend fun fetch(s: SportsSettings, force: Boolean): Boolean {
        dir.mkdirs()
        throttled.clear()
        pausedLogged.clear()
        sofascoreWarmed = false
        log.log("runda", "pornită" + if (force) " (forțat)" else "")
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
                if (force || isStale(f, fresh(LiveSoccerTv.PROVIDER, offset))) jobs += Job(LiveSoccerTv.scheduleUrl(day), f, html = LiveSoccerTv::looksValid)
            }
            if (s.sofascoreEnabled) {
                for (sport in s.sports) {
                    val url = Sofascore.scheduleUrl(sport, day) ?: continue
                    val f = File(dir, "sofa-${sport.name}-$day.json")
                    if (force || isStale(f, fresh(Sofascore.PROVIDER, offset))) jobs += Job(url, f)
                }
            }
        }
        if (s.futbolEnLaTvEnabled) {
            // Spania (toate sporturile) și site-urile surori ale țărilor canalelor tale (posturile lor).
            for (site in listOf(FutbolEnLaTv.SPAIN) + FutbolEnLaTv.sitesFor(playlistCountries())) {
                val f = File(dir, "fetv-${site.code}-${LocalDate.now(site.zone)}.html")
                if (force || isStale(f, fresh(FutbolEnLaTv.PROVIDER, 0))) jobs += Job(site.url, f, html = FutbolEnLaTv::looksValid)
            }
        }
        if (s.liveOnSatEnabled) {
            val f = File(dir, "los-$localToday.html")
            if (force || isStale(f, fresh(LiveOnSat.PROVIDER, 0))) jobs += Job(LiveOnSat.URL, f, html = LiveOnSat::looksValid)
        }
        if (s.fotMobEnabled) {
            for (cc in FotMob.countriesFor(playlistCountries())) {
                val f = File(dir, "fotmob-$cc-$localToday.html")
                if (force || isStale(f, fresh(FotMob.PROVIDER, 0))) jobs += Job(FotMob.url(cc), f, html = FotMob::looksValid)
            }
        }
        if (s.usLeaguesEnabled) {
            addEspnJobs(s, force, jobs)
            if (hasNorthAmerica()) addUsJobs(s, localToday, force, jobs)
        }
        if (s.euroLeagueEnabled && Sport.BASKETBALL in s.sports) {
            for (c in EuroLeague.Competition.entries) {
                // Tot sezonul dintr-o cerere (fără dată în nume: nu se șterge la schimbarea zilei).
                val f = File(dir, "euroleague-${c.code}-s${EuroLeague.seasonYear(localToday)}.json")
                if (force || isStale(f, fresh(EuroLeague.PROVIDER, 0))) jobs += Job(EuroLeague.url(c, localToday), f)
            }
        }
        if (s.sportsDbEnabled) addSportsDbJobs(s, today, force, jobs)
        // Întâi furnizorii deschiși și site-urile simple; lista se reface din ei, apoi (rar) cei protejați anti-robot.
        val (guarded, open) = jobs.partition { ProviderPolicy.tier(providerOf(it.url)) == ProviderPolicy.Tier.GUARDED }
        val (ok, failed) = runJobs(open.sortedBy { ProviderPolicy.fetchRank(providerOf(it.url)) }, "Se descarcă programul meciurilor", force)
        val (gOk, gFailed) = runJobs(guarded.sortedBy { ProviderPolicy.fetchRank(providerOf(it.url)) }, "Se descarcă programul meciurilor (furnizori protejați)", force)
        changed = ok + gOk > 0
        errors += failed + gFailed
        // Apoi posturile TV de la Sofascore, pentru câteva meciuri (programul e deja la zi).
        if (s.sofascoreEnabled && s.sofascoreTv) {
            val (tvOk, tvFailed) = fetchSofascoreTv(s, force)
            if (tvOk > 0) changed = true
            errors += tvFailed
        }
        _status.value = if (errors > 0) failureSummary() else null
        return changed
    }

    /** Face cererile una câte una, cu pauză între ele. Întoarce (reușite, nereușite). */
    private suspend fun runJobs(jobs: List<Job>, label: String, force: Boolean): Pair<Int, Int> {
        var ok = 0
        var failed = 0
        var sinceRecompute = 0
        var lastRecompute = 0L
        jobs.forEachIndexed { i, job ->
            val provider = providerOf(job.url)
            // După un 429 repetat, furnizorul nu mai e întrebat în runda asta: rămân datele salvate.
            if (provider in throttled) return@forEachIndexed
            // Un furnizor care ne-a refuzat stă în pauză (6 h, apoi tot mai mult); „Actualizează acum” trece peste.
            val block = blockOf(provider)
            if (!force && block.active(System.currentTimeMillis())) {
                if (pausedLogged.add(provider)) log.log(provider, "în pauză până la ${java.time.Instant.ofEpochMilli(block.until)} (refuzuri la rând: ${block.strikes}); cererea sărită")
                return@forEachIndexed
            }
            _status.value = "$label (${i + 1}/${jobs.size})…"
            var result = download(job)
            // Refuz anti-robot: a doua încercare printr-un browser adevărat (WebView).
            if (result is Download.Failed && result.blocked) result = viaBrowser(job, result)
            // Prea multe cereri sau serverul ocupat: o singură reîncercare, după o pauză lungă.
            if (result is Download.Failed && result.retry) {
                delay(if (result.tooMany) TOO_MANY_GAP_MS else RETRY_GAP_MS)
                result = download(job)
                if (result is Download.Failed && result.tooMany) throttled += provider
            }
            record(provider, result)
            noteBlock(provider, result)
            job.espn?.let { league -> noteCalendar(league, job.file, result) }
            if (result == Download.Ok) {
                ok++
                sinceRecompute++
            } else {
                failed++
            }
            // Lista se reface pe parcurs (după fiecare furnizor și la fiecare 8 cereri reușite), nu abia la final:
            // meciurile de la primii furnizori apar în câteva secunde, nu după toată runda.
            val next = jobs.getOrNull(i + 1)?.let { providerOf(it.url) }
            // ESPN are zeci de ligi mici: lista se reface mai rar pe parcurs (fiecare calcul costă câteva secunde).
            val every = if (provider == Espn.PROVIDER) ESPN_RECOMPUTE_EVERY else RECOMPUTE_EVERY
            // Și cel mult o dată la [MID_ROUND_GAP_MS] (jurnalul arăta calcule la 10 s, de câte 5–12 s fiecare),
            // în afară de prima listă, care apare cât mai repede.
            val gapOk = System.currentTimeMillis() - lastRecompute >= MID_ROUND_GAP_MS || _snapshot.value.matches.isEmpty()
            if (sinceRecompute > 0 && (next != provider || sinceRecompute >= every) && gapOk) {
                recompute()
                sinceRecompute = 0
                lastRecompute = System.currentTimeMillis()
            }
            if (i < jobs.lastIndex) delay(gapFor(provider)) // cereri rare, una câte una
        }
        return ok to failed
    }

    /** Cât rămâne bun fișierul unui furnizor ([ProviderPolicy.freshMs]). */
    private fun fresh(provider: String, dayOffset: Int): Long = ProviderPolicy.freshMs(provider, dayOffset)

    fun blockOf(provider: String): ProviderPolicy.Block =
        ProviderPolicy.Block.decode(blocks.getString(provider, null)) ?: ProviderPolicy.Block()

    /** Un refuz (403, 429, pagină de verificare, JSON cu „error”) mărește pauza; o reușită o șterge. */
    private fun noteBlock(provider: String, result: Download) {
        val now = System.currentTimeMillis()
        when {
            result == Download.Ok -> if (blocks.contains(provider)) {
                blocks.edit().remove(provider).apply()
                log.log(provider, "pauza ștearsă după o cerere reușită")
            }
            result is Download.Failed && (result.blocked || result.tooMany) -> {
                val next = blockOf(provider).refused(now)
                blocks.edit().putString(provider, next.encode()).apply()
                log.log(provider, "refuz nr. ${next.strikes}: pauză până la ${java.time.Instant.ofEpochMilli(next.until)}")
                throttled += provider
            }
        }
        _health.update { all -> all + (provider to (all[provider] ?: ProviderHealth()).copy(pausedUntil = blockOf(provider).until.takeIf { it > now })) }
    }

    /** TheSportsDB (cheia gratuită) are o limită pe minut: cererile lui se răresc mai mult. */
    private fun gapFor(provider: String): Long = when (provider) {
        TheSportsDb.PROVIDER -> SPORTSDB_GAP_MS
        // API-ul ESPN e un CDN deschis: cererile pot fi mai dese (multe ligi, câteva KB fiecare).
        Espn.PROVIDER -> ESPN_GAP_MS
        else -> REQUEST_GAP_MS
    }

    /** Cererea refuzată, încercată din nou într-un WebView; pentru Sofascore, întâi o vizită pe site (cookie-uri). */
    private suspend fun viaBrowser(job: Job, first: Download.Failed): Download {
        val isHtml = job.html != null
        val accept: (String) -> Boolean = { text ->
            if (isHtml) job.html!!.invoke(text) else text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        }
        if (job.url.contains("sofascore") && !sofascoreWarmed) {
            sofascoreWarmed = true
            browser.fetch("https://www.sofascore.com/", html = true, accept = { it.length > 1000 })
        }
        val started = System.currentTimeMillis()
        val provider = providerOf(job.url)
        val result = when (val r = browser.fetch(job.url, isHtml, accept)) {
            is WebViewFetcher.Result.Ok -> {
                val bad = validate(job, r.text)
                if (bad == null) {
                    save(job.file, r.text)
                    Download.Ok
                } else {
                    log.sample(provider, job.url, "browser (${bad.reason})\n" + r.text)
                    Download.Failed("${first.reason}; și prin browser: ${bad.reason}", blocked = true)
                }
            }
            is WebViewFetcher.Result.Failed -> {
                r.lastText?.takeIf { it.isNotBlank() }?.let { log.sample(provider, job.url, "browser (${r.reason})\n" + it) }
                Download.Failed("${first.reason}; și prin browser: ${r.reason}", blocked = true)
            }
        }
        log.request(
            provider, job.url, null, (result as? Download.Ok)?.let { job.file.length().toInt() }, System.currentTimeMillis() - started,
            if (result is Download.Failed) "EȘEC: ${result.reason}" else "ok", via = "browser",
        )
        return result
    }

    @Volatile
    private var sofascoreWarmed = false

    private fun record(provider: String, result: Download) {
        _health.update { all ->
            val old = all[provider] ?: ProviderHealth()
            val now = System.currentTimeMillis()
            // O rundă nouă (după mai mult de 10 minute) pornește numărătoarea de la zero.
            val base = if (now - old.at > ROUND_MS) old.copy(ok = 0, failed = 0, lastError = null) else old
            all + (
                provider to when (result) {
                    Download.Ok -> base.copy(ok = base.ok + 1, at = now, lastSuccess = now)
                    is Download.Failed -> base.copy(failed = base.failed + 1, at = now, lastError = result.reason)
                }
                )
        }
    }

    /** „12 cereri nereușite (TheSportsDB 10, LiveSoccerTV 2)…”, din runda curentă. */
    private fun failureSummary(): String {
        val now = System.currentTimeMillis()
        val parts = _health.value.filter { (_, h) -> h.failed > 0 && now - h.at <= ROUND_MS }
        val total = parts.values.sumOf { it.failed }
        val detail = parts.entries.joinToString(", ") { (p, h) -> "${providerLabel(p)} ${h.failed}" }
        return "$total cereri nereușite ($detail); se folosesc datele salvate. Detalii: Setări → Sports Hub."
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
    private suspend fun fetchSofascoreTv(s: SportsSettings, force: Boolean): Pair<Int, Int> {
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
            important = { e -> k.allRules.any { it.appliesTo(e.competition) } || e.competition?.let { Sport.fold(it) } in k.learnedRights },
        )
        val jobs = picked.mapNotNull { e ->
            val day = java.time.Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate()
            val f = File(dir, "sofatv-$day-${e.providerId}.json")
            if (isStale(f, SOFA_TV_FRESH_MS)) Job(Sofascore.tvUrl(e.providerId), f) else null
        }
        var (ok, failed) = runJobs(jobs, "Se descarcă posturile TV (Sofascore)", force)

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
        runJobs(countryJobs, "Se descarcă numele posturilor (Sofascore)", force).let { (a, b) -> ok += a; failed += b }
        val known = sofaChannelNames()
        val stillMissing = missing.values.flatten().filter { it !in known }.distinct().take(SOFA_NAMES_PER_ROUND)
        val channelJobs = stillMissing.mapNotNull { id ->
            val f = File(dir, "sofa-tvchannel-$id.json")
            if (isStale(f, SOFA_NAMES_FRESH_MS)) Job(Sofascore.channelUrl(id), f) else null
        }
        runJobs(channelJobs, "Se descarcă numele posturilor (Sofascore)", force).let { (a, b) -> ok += a; failed += b }
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

    /** Ai canale din SUA sau Canada? Altfel posturile ligilor americane nu folosesc la nimic. */
    private suspend fun hasNorthAmerica(): Boolean = playlistCountries().any { it in NORTH_AMERICA }

    /** NHL și MLB (o cerere pe zi), azi și mâine: posturile din SUA și Canada, doar cu canale de acolo. */
    private fun addUsJobs(s: SportsSettings, localToday: LocalDate, force: Boolean, jobs: MutableList<Job>) {
        for (offset in 0..1) {
            val day = localToday.plusDays(offset.toLong())
            if (Sport.ICE_HOCKEY in s.sports) {
                val f = File(dir, "nhl-$day.json")
                if (force || isStale(f, fresh(UsLeagues.NHL_API, offset))) jobs += Job(UsLeagues.nhlUrl(day), f)
            }
            if (Sport.BASEBALL in s.sports) {
                val f = File(dir, "mlb-$day.json")
                if (force || isStale(f, fresh(UsLeagues.MLB_API, offset))) jobs += Job(UsLeagues.mlbUrl(day), f)
            }
        }
    }

    /**
     * ESPN: o cerere pe ligă și zi (azi și mâine, zile ESPN), doar în zilele cu meciuri din calendarul ligii.
     * Calendarul se reîmprospătează cam o dată pe săptămână (cu cererea pentru azi); o ligă inexistentă (404) se
     * mai încearcă abia peste o lună. „Actualizează acum” nu trece peste calendar (ar însemna sute de cereri).
     */
    private fun addEspnJobs(s: SportsSettings, force: Boolean, jobs: MutableList<Job>) {
        val today = Espn.today()
        val now = System.currentTimeMillis()
        for (league in Espn.leaguesFor(s.sports)) {
            for (offset in 0..1) {
                val day = today.plusDays(offset.toLong())
                if (!espnWanted(league, day, offset, now)) continue
                val f = File(dir, "espn-${league.key}-$day.json")
                if (force || isStale(f, fresh(Espn.PROVIDER, offset))) jobs += Job(Espn.scoreboardUrl(league, day), f, espn = league)
            }
        }
    }

    private fun calendarFile(league: Espn.League) = File(dir, "espncal-${league.key}.txt")

    /** Liga are meciuri în ziua [day]? Fără calendar (sau cu unul vechi) se cere ziua de azi, ca să-l aflăm. */
    private fun espnWanted(league: Espn.League, day: LocalDate, offset: Int, now: Long): Boolean {
        val f = calendarFile(league)
        if (!f.exists()) return offset == 0
        val age = now - f.lastModified()
        val text = runCatching { f.readText() }.getOrNull() ?: return offset == 0
        if (text == CAL_MISSING) return offset == 0 && age > CAL_MISSING_MS
        // Reîmprospătarea săptămânală, eșalonată pe ligi (nu toate în aceeași zi).
        val refresh = CAL_REFRESH_MS + TimeUnit.HOURS.toMillis((league.key.hashCode() and 0x7fffffff) % 48L)
        if (offset == 0 && age > refresh) return true
        val cal = Espn.Calendar.decode(text) ?: return offset == 0
        return cal.has(day)
    }

    /** După răspunsul ESPN: calendarul ligii (sau „lipsă”, la 404). */
    private fun noteCalendar(league: Espn.League, file: File, result: Download) {
        val cal = calendarFile(league)
        when {
            result == Download.Ok -> {
                val parsed = runCatching { Espn.calendar(file.readText()) }.getOrNull() ?: return
                runCatching { cal.writeText(parsed.encode()) }
            }
            result is Download.Failed && (result.reason.startsWith("HTTP 404") || result.reason.startsWith("HTTP 400")) -> {
                runCatching { cal.writeText(CAL_MISSING) }
                log.log(Espn.PROVIDER, "liga ${league.key} (${league.path}) nu există la ESPN: se mai încearcă peste o lună")
            }
        }
    }

    /**
     * TheSportsDB (cheia gratuită dă doar câteva meciuri pe sport și zi): doar sporturile pe care nu le acoperă ESPN
     * sau EuroLeague ([ProviderPolicy.sportsDbSports]), azi și mâine; posturile TV doar cu o cheie proprie
     * (cheia gratuită dă un singur rezultat).
     */
    private fun addSportsDbJobs(s: SportsSettings, today: LocalDate, force: Boolean, jobs: MutableList<Job>) {
        val sports = if (s.usLeaguesEnabled) ProviderPolicy.sportsDbSports(s.sports) else s.sports
        for (offset in 0 until s.daysAhead.coerceIn(1, 2)) {
            val day = today.plusDays(offset.toLong())
            val maxAge = if (offset <= 1) FRESH_NEAR_MS else FRESH_FAR_MS
            for (sport in sports) {
                val f = File(dir, "events-${sport.name}-$day.json")
                if (force || isStale(f, maxAge)) jobs += Job(TheSportsDb.eventsDayUrl(s.sportsDbKey, day, sport), f)
            }
            if (s.sportsDbTv && offset <= 1 && s.sportsDbKey != TheSportsDb.DEFAULT_KEY) {
                val f = File(dir, "tv-$day.json")
                if (force || isStale(f, maxAge)) jobs += Job(TheSportsDb.tvDayUrl(s.sportsDbKey, day), f)
            }
        }
    }

    private fun isStale(f: File, maxAge: Long) = !f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge

    private sealed interface Download {
        data object Ok : Download
        data class Failed(
            val reason: String,
            val retry: Boolean = false,
            /** Refuz anti-robot (403): se poate încerca printr-un browser. */
            val blocked: Boolean = false,
            /** HTTP 429: prea multe cereri. */
            val tooMany: Boolean = false,
        ) : Download
    }

    /** [fetchOnce] cu rândul lui în jurnal (codul, mărimea, durata, rezultatul). */
    private fun download(job: Job): Download {
        val started = System.currentTimeMillis()
        lastCode = null
        lastBytes = null
        val result = fetchOnce(job)
        log.request(
            providerOf(job.url), job.url, lastCode, lastBytes, System.currentTimeMillis() - started,
            if (result is Download.Failed) "EȘEC: ${result.reason}" else "ok",
        )
        return result
    }

    // Codul și mărimea ultimului răspuns, pentru jurnal (cererile sunt una câte una, sub fetchMutex).
    @Volatile private var lastCode: Int? = null
    @Volatile private var lastBytes: Int? = null

    private fun fetchOnce(job: Job): Download = try {
        val target = job.file
        val request = Request.Builder().url(job.url)
            .header("Accept", if (job.html != null) "text/html,application/xhtml+xml" else "application/json")
            .apply { if (job.html != null || job.url.contains("sofascore")) header("User-Agent", BROWSER_UA) }
            // Paginile în engleză (FotMob dădea numele în italiană: „Norvegia – Galles” nu se unea cu „Norway – Wales”).
            .apply { if (job.html != null) header("Accept-Language", "en-GB,en;q=0.9") }
            .apply {
                // API-ul Sofascore refuză des cererile care nu par venite de pe site-ul lui.
                if (job.url.contains("sofascore")) {
                    header("Referer", "https://www.sofascore.com/")
                    header("Origin", "https://www.sofascore.com")
                    header("Accept-Language", "en-US,en;q=0.9")
                }
            }
            .build()
        http.newCall(request).execute().use { r ->
            lastCode = r.code
            if (!r.isSuccessful) {
                // Pagina de refuz (Cloudflare, „challenge”) se păstrează pentru jurnal.
                runCatching { r.peekBody(MAX_SAMPLE_BYTES).string() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { log.sample(providerOf(job.url), job.url, "HTTP ${r.code}\n" + it) }
                return Download.Failed(
                    httpReason(r.code),
                    retry = r.code == 429 || r.code >= 500,
                    blocked = r.code == 403,
                    tooMany = r.code == 429,
                )
            }
            val body = r.body?.string() ?: return Download.Failed("răspuns gol")
            lastBytes = body.length
            // O pagină de eroare (sau de verificare anti-robot) nu înlocuiește fișierul bun de dinainte.
            validate(job, body)?.let { failed ->
                log.sample(providerOf(job.url), job.url, "HTTP ${r.code} (${failed.reason})\n" + body)
                return failed
            }
            save(target, body)
            Download.Ok
        }
    } catch (e: java.net.UnknownHostException) {
        Download.Failed("fără rețea sau adresă blocată (DNS)")
    } catch (e: java.net.SocketTimeoutException) {
        Download.Failed("timp depășit", retry = true)
    } catch (e: javax.net.ssl.SSLException) {
        Download.Failed("eroare de conexiune securizată (SSL)")
    } catch (e: Exception) {
        Download.Failed("eroare de rețea (${e.javaClass.simpleName})")
    }

    /** Null dacă răspunsul e bun; altfel motivul (pagină de verificare, nu e JSON, JSON cu „error”). */
    private fun validate(job: Job, body: String): Download.Failed? {
        if (job.html != null) {
            return if (job.html.invoke(body)) null else Download.Failed("pagină de verificare anti-robot sau cu alt format", blocked = true)
        }
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return Download.Failed(if (trimmed.isEmpty()) "răspuns gol" else "răspunsul nu e JSON (limită sau cheie)", retry = true)
        }
        // Sofascore răspunde la un refuz cu `{"error": {"code": 403, …}}`; nu e un program gol.
        DiagnosticText.jsonError(trimmed)?.let { return Download.Failed("refuz în JSON: $it", blocked = true) }
        return null
    }

    /** Scrie fișierul printr-unul temporar, ca o eroare la mijloc să nu strice fișierul bun. */
    private fun save(target: File, body: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        403 -> "HTTP 403: acces refuzat (blocare anti-robot)"
        404 -> "HTTP 404: pagina nu există"
        429 -> "HTTP 429: prea multe cereri"
        in 500..599 -> "HTTP $code: eroare la server"
        else -> "HTTP $code"
    }

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
        val result = runCatching { parse(f.readText()) }
        val events = result.getOrDefault(emptyList())
        parsedFiles[f.path] = modified to events
        val provider = f.name.substringBefore('-')
        result.exceptionOrNull()?.let { e -> log.log(provider, "citire EȘUATĂ ${f.name} (${f.length()}B): ${e.javaClass.simpleName}: ${e.message?.take(200)}") }
            ?: log.log(provider, "citit ${f.name} (${f.length()}B): ${events.size} meciuri")
        return events
    }

    private suspend fun cachedEvents(s: SportsSettings): Pair<List<SportEvent>, List<TheSportsDb.TvListing>> {
        val files = dir.listFiles().orEmpty().filterNot { it.name.endsWith(".tmp") }
        val wanted = s.sports.map { it.name }.toSet()
        val events = ArrayList<SportEvent>()
        if (s.sportsDbEnabled) {
            val dbWanted = (if (s.usLeaguesEnabled) ProviderPolicy.sportsDbSports(s.sports) else s.sports).map { it.name }.toSet()
            files.filter { f -> f.name.startsWith("events-") && f.name.split('-').getOrNull(1) in dbWanted }
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
            val sites = (listOf(FutbolEnLaTv.SPAIN) + FutbolEnLaTv.sitesFor(playlistCountries())).map { it.code }.toSet()
            files.filter { it.name.startsWith("fetv-") && it.name.endsWith(".html") }.forEach { f ->
                val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                // „fetv-gb-2026-11-15.html”; cele vechi („fetv-2026-11-15.html”) sunt ale Spaniei.
                val code = f.name.split('-').getOrNull(1)?.takeIf { c -> c.any { !it.isDigit() } } ?: FutbolEnLaTv.SPAIN.code
                val site = FutbolEnLaTv.site(code)?.takeIf { it.code in sites } ?: return@forEach
                events += parsed(f) { FutbolEnLaTv.parse(it, day, site) }
            }
        }
        if (s.liveOnSatEnabled) {
            files.filter { it.name.startsWith("los-") && it.name.endsWith(".html") }.forEach { f ->
                val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                events += parsed(f) { LiveOnSat.parse(it, day) }
            }
        }
        if (s.fotMobEnabled) {
            files.filter { it.name.startsWith("fotmob-") && it.name.endsWith(".html") }.forEach { f ->
                val cc = f.name.split('-').getOrNull(1) ?: return@forEach
                events += parsed(f) { FotMob.parse(it, cc) }
            }
        }
        if (s.usLeaguesEnabled) {
            files.filter { it.name.endsWith(".json") && (it.name.startsWith("espn-") || it.name.startsWith("nhl-") || it.name.startsWith("mlb-")) }.forEach { f ->
                val day = DAY_IN_NAME.find(f.name)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                events += when {
                    f.name.startsWith("espn-") -> {
                        val league = f.name.split('-').getOrNull(1)?.let(Espn::league)
                        if (league == null || league.sport !in s.sports) emptyList() else parsed(f) { Espn.parse(it, league, day) }
                    }
                    f.name.startsWith("nhl-") -> if (Sport.ICE_HOCKEY in s.sports) parsed(f) { UsLeagues.parseNhl(it, day) } else emptyList()
                    else -> if (Sport.BASEBALL in s.sports) parsed(f) { UsLeagues.parseMlb(it) } else emptyList()
                }
            }
        }
        if (s.euroLeagueEnabled && Sport.BASKETBALL in s.sports) {
            files.filter { it.name.startsWith("euroleague-") && it.name.endsWith(".json") }.forEach { f ->
                val c = EuroLeague.Competition.entries.firstOrNull { it.code == f.name.split('-').getOrNull(1) } ?: return@forEach
                events += parsed(f) { EuroLeague.parse(it, c) }
            }
        }
        val tv = if (!s.sportsDbEnabled || !s.sportsDbTv) emptyList() else files.filter { it.name.startsWith("tv-") && it.name.endsWith(".json") }
            .flatMap { f -> runCatching { TheSportsDb.parseTv(f.readText()) }.getOrDefault(emptyList()) }
        return events to tv
    }

    // ---------- lista de meciuri ----------

    /**
     * Amprenta a tot ce intră în calcul: setările, dicționarul, corecțiile, rezoluțiile văzute, playlisturile,
     * EPG-ul, fișierele furnizorilor și ora (lista se reface cel puțin o dată pe oră, pentru fereastra de timp).
     */
    private suspend fun inputsKey(now: Long): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append(INPUTS_VERSION).append('|').append(now / HOUR_MS).append('|')
        sb.append(fetchKey(settings.current)).append('|')
        sb.append(knowledge.current.hashCode()).append('|')
        sb.append(variantStore.current.corrections.hashCode()).append('|')
        db.playlists().all().forEach { p -> sb.append(p.id).append(':').append(p.enabled).append(':').append(p.lastUpdated).append(':').append(p.channelCount).append(',') }
        sb.append('|').append(epg.observeStates().first().hashCode())
        sb.append('|').append(epg.observeMappedCount().first())
        sb.append('|')
        dir.listFiles().orEmpty().filterNot { it.name.endsWith(".tmp") }.sortedBy { it.name }
            .forEach { f -> sb.append(f.name).append(':').append(f.lastModified()).append(':').append(f.length()).append(',') }
        sb.toString()
    }

    /**
     * Calitatea surselor după ce s-a văzut la redare (corecția ta, altfel rezoluția reală, altfel playlistul):
     * o sursă văzută urcă sau coboară în listă după eticheta verificată, fără să refacă lista de meciuri.
     */
    private fun requality(s: SportsSnapshot): SportsSnapshot {
        val seen = observed.current
        val variantState = variantStore.current
        var changed = false
        val matches = s.matches.map { e ->
            var touched = false
            val sources = e.sources.map { src ->
                val c = src.channel
                val q = qualityView(c.playlistId, c.name, c.quality, seen, variantState).quality
                if (q == src.quality) src else { touched = true; src.copy(quality = q) }
            }
            if (!touched) e else { changed = true; e.copy(sources = sources, summary = MatchSources.summary(sources)) }
        }
        return if (changed) s.copy(matches = matches) else s
    }

    /**
     * Meciul ESPN pentru [match] („EPL-401879243”), pentru Pre-meci și Live: din id, altfel din programul ESPN deja
     * descărcat (aceleași echipe, ora apropiată). Fără rețea.
     */
    fun espnEventFor(match: Match): String? {
        if (match.id.startsWith(Espn.PROVIDER + ":")) {
            return match.id.substringAfter(':').substringBefore('#').takeIf { Espn.splitId(it) != null }
        }
        if (match.away.isBlank()) return null
        val candidates = parsedFiles.entries.asSequence()
            .filter { File(it.key).name.startsWith("espn-") }
            .flatMap { it.value.second.asSequence() }
            .toList()
        return MatchInsights.pickEvent(candidates, match.home, match.away, match.start)?.providerId
    }

    /** Recalculează doar dacă s-a schimbat ceva de la ultimul calcul (sau de la lista salvată). */
    suspend fun recomputeIfChanged() {
        val key = runCatching { inputsKey(System.currentTimeMillis()) }.getOrNull()
        if (key != null && key == computedKey && !_snapshot.value.loading) return
        recompute()
    }

    suspend fun recompute() {
        // Cât rulează playerul, nimic greu în fundal: calculul așteaptă oprirea redării.
        if (playing.value) playing.first { !it }
        computeMutex.withLock {
            val now = System.currentTimeMillis()
            val key = runCatching { inputsKey(now) }.getOrNull()
            val next: SportsSnapshot? = try {
                withContext(Dispatchers.IO) { compute(now) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log("calcul", "EȘUAT: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                null
            }
            if (next != null) {
                _snapshot.value = next
                computedKey = key
                if (key != null) withContext(Dispatchers.IO) { stored.write(next, key) }
            } else if (_snapshot.value.loading) {
                _snapshot.value = SportsSnapshot(loading = false)
            }
        }
        // Prima listă s-a făcut doar cu programul EPG de azi și mâine; restul săptămânii se citește acum, separat.
        if (farWindow == null && !farLoading) {
            farLoading = true
            scope.launch {
                try {
                    recompute()
                } finally {
                    farLoading = false
                }
            }
        }
    }

    /** Emisiunile EPG citite pentru o fereastră de timp, ținute în memorie (nu se recitesc la fiecare minut). */
    private class BroadcastWindow(
        val from: Long,
        val until: Long,
        val loadedAt: Long,
        val list: List<MatchBroadcast>,
        /** Emisiunile care pomenesc un nume urmărit ([names]), recunoscute sau nu ca meci. */
        val followed: List<EpgBroadcast> = emptyList(),
        val names: List<String> = emptyList(),
    )

    /** Azi și mâine (cu 30 de ore în urmă, pentru reluări): recitit la o oră sau când se schimbă EPG-ul. */
    @Volatile private var nearWindow: BroadcastWindow? = null

    /** Restul săptămânii: citit după prima listă, recitit la 6 ore. */
    @Volatile private var farWindow: BroadcastWindow? = null

    @Volatile private var farLoading = false

    /** Competițiile scoase de „Doar competițiile importante” la ultimul calcul (pentru jurnal). */
    @Volatile private var droppedCompetitions: Map<String, Int> = emptyMap()

    private fun eventSport(e: SportEvent): Sport? = Sport.fromSportsDb(e.sport) ?: Sport.detect(e.sport, e.competition)

    /** Canalele EVENT citite: id → (nume, grup, fus, ora din zi) și rezultatul. */
    private val slotCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, EventSlot>>()

    private suspend fun compute(now: Long): SportsSnapshot {
        val s = settings.current
        val from = now - WINDOW_BACK_MS
        val until = now + WINDOW_AHEAD_MS

        val timing = StringBuilder()
        var mark = System.currentTimeMillis()
        val began = mark
        fun lap(label: String) {
            val t = System.currentTimeMillis()
            timing.append(label).append(' ').append(t - mark).append("ms, ")
            mark = t
        }
        val (allEvents, tv) = cachedEvents(s)
        // Siglele și culorile echipelor vin din același program (fără cereri în plus).
        looks?.learn(allEvents)
        val counts = allEvents.groupingBy { it.provider }.eachCount()
        _health.update { all ->
            (all.keys + counts.keys).associateWith { p -> (all[p] ?: ProviderHealth()).copy(events = counts[p] ?: 0) }
        }
        // Doar competițiile importante, înainte de orice calcul (setare).
        val knownTeams = HashSet<String>()
        val events = if (!s.topCompetitionsOnly) allEvents else {
            val dropped = HashMap<String, Int>()
            val verdicts = allEvents.map { e -> CompetitionFilter.verdict(eventSport(e), e.competition) }
            allEvents.forEachIndexed { i, e ->
                if (verdicts[i] == CompetitionFilter.Verdict.KEEP && e.away.isNotBlank()) {
                    knownTeams += TeamNames.key(e.home)
                    knownTeams += TeamNames.key(e.away)
                }
            }
            allEvents.filterIndexed { i, e ->
                val keep = verdicts[i] == CompetitionFilter.Verdict.KEEP ||
                    CompetitionFilter.keepMatch(eventSport(e), e.competition, e.home, e.away, knownTeams, fromProvider = true) ||
                    // Echipele și competițiile tale favorite rămân, oricare ar fi competiția.
                    s.favorites.isCompetition(e.competition) || s.favorites.isTeam(e.home) || (e.away.isNotBlank() && s.favorites.isTeam(e.away))
                if (!keep) dropped.merge("${e.sport ?: "?"} · ${e.competition ?: "fără competiție"}", 1, Int::plus)
                keep
            }.also { droppedCompetitions = dropped }
        }
        lap("fișiere")
        val scan = scanCache ?: scanChannels().also { scanCache = it; lap("canale") }
        // Emisiunile de până la 30 de ore în urmă: transmisia de aseară face din emisiunea de dimineață o reluare.
        // Întâi azi și mâine (lista apare repede), apoi restul săptămânii; amândouă rămân în memorie.
        // Numele urmărite se caută în aceeași citire a EPG-ului (alt nume urmărit = fereastra se recitește).
        val names = s.favorites.names
        val near = nearWindow?.takeIf { now - it.loadedAt < NEAR_RELOAD_MS && it.names == names }
            ?: (now + NEAR_AHEAD_MS).let { end ->
                loadBroadcasts(scan.sportEpg, now - BROADCAST_BACK_MS, end, names)
                    .let { (list, followed) -> BroadcastWindow(now - BROADCAST_BACK_MS, end, now, list, followed, names) }
            }.also { nearWindow = it; lap("EPG azi-mâine (${it.list.size} emisiuni)") }
        val far = farWindow?.takeIf { now - it.loadedAt < FAR_RELOAD_MS && it.names == names }
            ?: if (_snapshot.value.loading && farWindow == null) {
                null
            } else {
                loadBroadcasts(scan.sportEpg, near.until, until, names)
                    .let { (list, followed) -> BroadcastWindow(near.until, until, now, list, followed, names) }
                    .also { farWindow = it; lap("EPG restul săptămânii (${it.list.size} emisiuni)") }
            }
        val broadcasts = if (far == null) near.list else (near.list + far.list).distinctBy { it.broadcast.programmeId }
        val zone = s.eventZoneId()
        // Numele canalelor EVENT se citesc o dată pe oră (nu la fiecare minut, pentru mii de canale).
        val hour = now / HOUR_MS
        val slots = if (!s.eventChannels) emptyList() else scan.events.map { c ->
            val key = "${c.name}\u0000${c.groupName}\u0000$zone\u0000$hour"
            slotCache[c.id]?.takeIf { it.first == key }?.second
                ?: EventChannels.parse(c.id, c.name, c.groupName, now, zone).also { slotCache[c.id] = key to it }
        }
        val activeSlots = slots.filter { it.home != null && (it.start == null || it.start in from..until) }
        val assembled = MatchAssembler.assemble(events.filter { it.start in (from - WINDOW_BACK_MS)..until }, broadcasts, now, tv, activeSlots)
            .filter { it.end > from && it.start < until }
            .filter { m ->
                // Meciurile doar din EPG sau din canale EVENT: după competiție, dacă se știe, altfel după echipe.
                !s.topCompetitionsOnly || m.providers.any { it != MatchAssembler.EPG_PROVIDER && it != MatchAssembler.EVENT_PROVIDER } ||
                    CompetitionFilter.keepMatch(m.sport, m.competition, m.home, m.away, knownTeams, fromProvider = false) ||
                    (names.isNotEmpty() && s.favorites.isFavorite(m))
            }
        // F4: emisiunile care pomenesc un nume urmărit și nu sunt deja într-un meci (filtrul de competiții nu li se aplică).
        val matches = if (names.isEmpty()) assembled else {
            val used = HashSet<Long>()
            assembled.forEach { m -> m.broadcasts.forEach { used += it.programmeId } }
            val followed = (near.followed + far?.followed.orEmpty()).distinctBy { it.programmeId }.filter { it.programmeId !in used }
            assembled + FollowedNames.matches(followed, names, now).filter { it.end > from && it.start < until }
        }
        val slotChannels = activeSlots.map { it.channelId }.distinct().chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.associateBy { it.id }
        lap("asamblare (${matches.size} meciuri)")

        // Sursele sigure: canalele pe care EPG-ul arată meciul, cu toate variantele lor (alte calități ale aceluiași canal).
        val epgIds = matches.flatMap { m -> m.broadcasts.map { it.epgChannelId } }.toSet()
        val channelsByEpg = epg.channelsShowing(epgIds)
        val epgChannelIds = channelsByEpg.values.flatten().distinct()
        val epgChannels = epgChannelIds.chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.associateBy { it.id }

        val index = stationIndexCache ?: StationIndex(db.channels().activeGroupKeys()).also { stationIndexCache = it }
        val finder = SourceFinder(index, knowledge.current)

        // C3: ce meci arată EPG-ul fiecărui canal (cheia de grupare) și când; un post anunțat care arată alt meci nu e sursă probabilă.
        val busy = HashMap<String, MutableList<BusyChannels.Slot>>()
        for (m in matches) for (b in m.broadcasts) {
            channelsByEpg[b.epgChannelId].orEmpty().mapNotNull { epgChannels[it]?.groupKey }.distinct().forEach { key ->
                busy.getOrPut(key) { ArrayList() } += BusyChannels.Slot(b.start, b.stop, m.id)
            }
        }
        val foundByMatch = HashMap<String, List<FoundSource>>()
        val allKeys = HashSet<String>()
        val unmatched = HashMap<String, Int>()
        val aliases = knowledge.current.stationAliases
        for (m in matches) {
            val epgKeys = m.broadcasts.flatMap { b -> channelsByEpg[b.epgChannelId].orEmpty() }
                .mapNotNull { epgChannels[it]?.groupKey }.toSet()
            val eventKeys = m.eventChannels.mapNotNull { slotChannels[it]?.groupKey }.toSet()
            val dedicatedKeys = DedicatedChannels.seriesOfMatch(m)?.let { scan.dedicated[it] }.orEmpty()
            val found = BusyChannels.adjust(m, finder.find(m, epgKeys, eventKeys, dedicatedKeys), busy)
            foundByMatch[m.id] = found
            found.forEach { allKeys += it.groupKey }
            if (m.status != MatchStatus.FINISHED) {
                for (st in m.stations) {
                    val compact = StationIndex.stationKey(st.name)
                    if (compact !in aliases && index.find(st).isEmpty()) unmatched.merge(st.name, 1, Int::plus)
                }
            }
        }
        lap("surse")
        val variants = allKeys.toList().chunked(IN_CHUNK).flatMap { db.channels().variants(it) }.groupBy { it.groupKey }
        lap("variante")

        val seen = observed.current
        val variantState = variantStore.current
        val entries = matches.map { m ->
            val sources = ArrayList<MatchSource<ChannelWithPlaylist>>()
            for (f in foundByMatch.getValue(m.id)) variants[f.groupKey].orEmpty().forEach { c ->
                // „#### DAZN LALIGA VIP SD ####”: un separator din lista furnizorului, nu un canal.
                if (ChannelNameParser.isSeparator(c.name)) return@forEach
                // Calitatea: corecția ta, altfel rezoluția văzută la redare, altfel eticheta din playlist.
                val quality = qualityView(c.playlistId, c.name, c.quality, seen, variantState).quality
                sources += MatchSource(c, c.groupKey, c.language, quality, f.confidence, f.station, f.rejected, f.origin)
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
        val total = System.currentTimeMillis() - began
        if (total > SLOW_COMPUTE_MS || _snapshot.value.loading) log.log("calcul", "${total}ms: $timing")
        return SportsSnapshot(
            entries, now, loading = false, unmatchedStations = stations,
            events = eventEntries,
            emptyEventChannels = slots.count { it.home == null },
            noEpgChannels = scan.noEpg,
            sportChannelCount = scan.sportChannelCount,
        )
    }

    /**
     * Rezumatele și magazinele sportive din arhivă: emisiunile terminate din EPG-ul canalelor de sport care au
     * catch-up, recunoscute după titlu ([Highlights]). Doar din baza locală: nicio cerere la furnizor.
     * Întâi cele cu echipele sau competițiile tale favorite, apoi cele mai noi.
     */
    suspend fun highlights(now: Long = System.currentTimeMillis()): List<HighlightEntry> = withContext(Dispatchers.IO) {
        val scan = scanCache ?: scanChannels().also { scanCache = it }
        val byEpg = epg.channelsShowing(scan.sportEpg)
        val channels = byEpg.values.flatten().distinct().chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }
            .filter { (it.catchupDays ?: 0) > 0 }.associateBy { it.id }
        if (channels.isEmpty()) return@withContext emptyList()
        val archived = byEpg.mapValues { (_, ids) -> ids.mapNotNull { channels[it] } }.filterValues { it.isNotEmpty() }
        val maxDays = channels.values.maxOf { it.catchupDays ?: 0 }.coerceAtMost(MAX_CATCHUP_DAYS)
        val fav = settings.current.favorites
        val names = fav.teams + fav.competitions
        val seen = HashSet<String>()
        epg.programmesOn(archived.keys, now - TimeUnit.DAYS.toMillis(maxDays.toLong()), now)
            .asSequence()
            .filter { it.stop < now && Highlights.isHighlights(it.title, it.subtitle, it.category) }
            .mapNotNull { p ->
                val channel = archived[p.epgChannelId].orEmpty()
                    .firstOrNull { ro.ak.sportshub.core.playlist.Catchup.isAvailable(it.catchupDays, p.start, now) } ?: return@mapNotNull null
                if (!seen.add("${Sport.fold(p.title)}|${p.start}")) return@mapNotNull null
                HighlightEntry(p.title, p.subtitle, p.start, p.stop, channel, Highlights.mentions(p.title, p.subtitle, names))
            }
            .sortedWith(compareByDescending<HighlightEntry> { it.favorite }.thenByDescending { it.start })
            .take(MAX_HIGHLIGHTS)
            .toList()
    }

    // ---------- jurnalul de diagnostic ----------

    /**
     * Starea Sports Hub pentru jurnalul trimis pe GitHub (Markdown): setările furnizorilor, starea fiecăruia, fișierele
     * salvate cu câte meciuri s-au citit din ele și posturile anunțate fără canal. Nimic din playlisturi în afară de
     * prefixele de țară ale canalelor și numărul lor.
     */
    suspend fun diagnosticSummary(): String = withContext(Dispatchers.IO) {
        val s = settings.current
        val snap = _snapshot.value
        val now = System.currentTimeMillis()
        val utc = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)
        buildString {
            appendLine("### Setări Sports Hub")
            appendLine("- TheSportsDB: ${s.sportsDbEnabled} (cheie ${if (s.sportsDbKey == TheSportsDb.DEFAULT_KEY) "gratuită" else "proprie"}, posturi TV ${s.sportsDbTv}), zile cerute ${s.daysAhead}")
            appendLine("- LiveSoccerTV ${s.liveSoccerTvEnabled} · Sofascore ${s.sofascoreEnabled} (posturi ${s.sofascoreTv}) · FutbolEnLaTV ${s.futbolEnLaTvEnabled} · LiveOnSat ${s.liveOnSatEnabled} · FotMob ${s.fotMobEnabled} · ESPN/NHL/MLB ${s.usLeaguesEnabled} · EuroLeague ${s.euroLeagueEnabled}")
            appendLine("- TheSportsDB cerut doar pentru: ${(if (s.usLeaguesEnabled) ProviderPolicy.sportsDbSports(s.sports) else s.sports).joinToString(", ") { it.name }.ifEmpty { "—" }}")
            appendLine("- Sporturi: ${s.sports.joinToString(", ") { it.name }}")
            appendLine("- Canale EVENT ${s.eventChannels}, fus EVENT „${s.eventZone}”")
            val countries = runCatching { playlistCountries() }.getOrDefault(emptySet())
            appendLine("- Țările canalelor (prefixe): ${countries.sorted().joinToString(", ").ifEmpty { "—" }}; America de Nord: ${countries.any { it in NORTH_AMERICA }}")
            appendLine("- FotMob cere țările: ${FotMob.countriesFor(countries).joinToString(", ").ifEmpty { "—" }}; FutbolEnLaTV: es${FutbolEnLaTv.sitesFor(countries).joinToString("") { ", " + it.code }}")
            val cals = Espn.leaguesFor(s.sports).map { l -> l to runCatching { File(dir, "espncal-${l.key}.txt").readText() }.getOrNull() }
            val today = Espn.today()
            appendLine(
                "- ESPN: ${cals.size} ligi; fără calendar ${cals.count { it.second == null }}, inexistente ${cals.count { it.second == CAL_MISSING }}; " +
                    "cu meciuri azi/mâine: " + cals.filter { (_, t) -> Espn.Calendar.decode(t)?.let { c -> !c.unknown && (c.has(today) || c.has(today.plusDays(1))) } == true }
                    .joinToString(", ") { it.first.key }.ifEmpty { "—" },
            )
            appendLine()
            appendLine("### Lista")
            appendLine("- ${snap.matches.size} meciuri (calculat ${utc.format(java.time.Instant.ofEpochMilli(snap.computedAt))} UTC), cu sursă găsită ${snap.matches.count { it.summary.sure > 0 }}, cu orice sursă ${snap.matches.count { it.summary.count > 0 }}")
            val byProvider = snap.matches.flatMap { it.match.providers }.groupingBy { it }.eachCount()
            appendLine("- Meciuri pe furnizor (după unire): ${byProvider.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }}")
            appendLine("- Canale de sport: ${snap.sportChannelCount}, EVENT/PPV cu eveniment: ${snap.events.size}, goale: ${snap.emptyEventChannels}, fără EPG: ${snap.noEpgChannels.size}")
            if (s.topCompetitionsOnly) {
                val d = droppedCompetitions
                appendLine("- Doar competițiile importante: scoase ${d.values.sum()} meciuri din ${d.size} competiții; cele mai multe: " +
                    d.entries.sortedByDescending { it.value }.take(60).joinToString("; ") { "${it.key} (${it.value})" })
            }
            appendLine()
            appendLine("### Furnizori")
            appendLine("| furnizor | nivel | ok | eșuate | meciuri | ultima reușită | pauză | ultima eroare |")
            appendLine("|---|---|---|---|---|---|---|---|")
            val known = listOf(
                UsLeagues.ESPN, EuroLeague.PROVIDER, UsLeagues.NHL_API, UsLeagues.MLB_API, TheSportsDb.PROVIDER, FutbolEnLaTv.PROVIDER,
                LiveOnSat.PROVIDER, LiveSoccerTv.PROVIDER, FotMob.PROVIDER, Sofascore.PROVIDER,
            )
            val health = _health.value
            (known + health.keys.filter { it !in known }).forEach { p ->
                val h = health[p] ?: ProviderHealth()
                val b = blockOf(p)
                appendLine(
                    "| ${providerLabel(p)} | ${ProviderPolicy.tier(p).name} | ${h.ok} | ${h.failed} | ${h.events} | " +
                        "${h.lastSuccess?.let { utc.format(java.time.Instant.ofEpochMilli(it)) } ?: "—"} | " +
                        "${if (b.active(now)) "până la " + utc.format(java.time.Instant.ofEpochMilli(b.until)) + " (" + b.strikes + ")" else "—"} | " +
                        "${h.lastError?.replace('|', '/') ?: "—"} |",
                )
            }
            appendLine()
            appendLine("### Fișiere salvate")
            appendLine("| fișier | mărime | modificat (UTC) | meciuri citite |")
            appendLine("|---|---|---|---|")
            dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                val count = parsedFiles[f.path]?.second?.size?.toString() ?: parsedTvFiles[f.path]?.second?.values?.sumOf { it.size }?.let { "$it posturi" } ?: "—"
                appendLine("| ${f.name} | ${f.length()} | ${utc.format(java.time.Instant.ofEpochMilli(f.lastModified()))} | $count |")
            }
            if (snap.unmatchedStations.isNotEmpty()) {
                appendLine()
                appendLine("### Posturi anunțate fără canal (primele 40)")
                appendLine(snap.unmatchedStations.take(40).joinToString(", "))
            }
        }
    }

    /**
     * Ultimul fișier salvat al fiecărui furnizor, scurtat ([DiagnosticText.condense]): cei care merg, puțin (pentru
     * comparație), cei cu 0 meciuri, mult (ca să se vadă formatul real al paginii). Titlu → text.
     */
    fun diagnosticFiles(): List<Pair<String, String>> {
        val files = dir.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") && it.length() > 0 }
        val prefixes = listOf("lstv-", "sofa-", "sofatv-", "sofa-tvcountry-", "sofa-tvchannel-", "los-", "fotmob-", "espn-", "nhl-", "mlb-", "fetv-", "events-", "tv-")
        return prefixes.mapNotNull { prefix ->
            val group = files.filter { f ->
                f.name.startsWith(prefix) && prefixes.none { other -> other != prefix && other.startsWith(prefix) && f.name.startsWith(other) }
            }
            // Cel mai nou fișier, de preferat unul din care nu s-a citit niciun meci.
            val pick = group.sortedByDescending { it.lastModified() }
                .let { list -> list.firstOrNull { parsedFiles[it.path]?.second?.isEmpty() == true } ?: list.firstOrNull() }
                ?: return@mapNotNull null
            val events = parsedFiles[pick.path]?.second?.size
            val working = events != null && events > 0
            val text = runCatching { pick.readText() }.getOrDefault("")
            val limit = if (working) 8_000 else DiagnosticText.MAX_CHUNK * 2
            "${pick.name} (${pick.length()}B, ${events ?: "?"} meciuri)" to DiagnosticText.condense(text, limit)
        }
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
    private suspend fun loadBroadcasts(
        sportEpg: Set<Long>,
        from: Long,
        until: Long,
        names: List<String>,
    ): Pair<List<MatchBroadcast>, List<EpgBroadcast>> {
        val rows = (epg.programmesOn(sportEpg, from, until) + epg.sportCategoryProgrammes(from, until, MAX_CATEGORY_ROWS))
            .distinctBy { it.id }
        // Titlurile se repetă (zile, canale): fiecare se analizează o singură dată; cache-ul se golește doar când e foarte mare.
        val cache = extractCache
        if (cache.size > MAX_EXTRACT_CACHE) cache.clear()
        val followed = ArrayList<EpgBroadcast>()
        val list = rows.mapNotNull { r ->
            val sport = r.epgChannelId in sportEpg
            // Prima linie a descrierii contează doar pe canalele de sport (C4).
            val desc = if (sport) r.descriptionHead?.lineSequence()?.firstOrNull()?.takeIf { it.length <= 80 } else null
            val b = EpgBroadcast(r.id, r.epgChannelId, r.start, r.stop, r.title, r.subtitle, desc, r.category)
            // Numele urmărite: și în începutul descrierii (des „Novak Djokovic vs …” e doar acolo).
            if (names.isNotEmpty() && FollowedNames.nameIn(names, b.copy(description = r.descriptionHead)) != null) {
                followed += b.copy(description = r.descriptionHead)
            }
            val key = ExtractKey(r.title, r.subtitle, r.category, sport, desc)
            val x = if (cache.containsKey(key)) {
                cache[key]
            } else {
                (EpgMatchExtractor.extract(b, channelIsSport = sport) ?: EpgMatchExtractor.extractEvent(b, channelIsSport = sport))
                    .also { cache[key] = it }
            }
            x?.let { MatchBroadcast(b, it) }
        }
        return list to followed
    }

    /** Ce contează pentru recunoașterea unui meci într-o emisiune (titlul se repetă pe multe canale și zile). */
    private data class ExtractKey(val title: String, val subtitle: String?, val category: String?, val sport: Boolean, val description: String?)

    /** Folosit doar sub computeMutex (calculul listei), deci fără sincronizare. */
    private val extractCache = HashMap<ExtractKey, ExtractedMatch?>()

    /**
     * O trecere prin numele canalelor active, pe pagini: canalele de sport (și canalele lor EPG, plus cele fără EPG),
     * canalele EVENT / PPV și canalele dedicate unei competiții.
     */
    /** Doar din numele canalelor: canalele de sport, cele EVENT / PPV și cele dedicate unei serii (id-uri). */
    private class NameScan(val key: String, val sportIds: List<Long>, val events: List<ChannelNameRow>, val dedicated: Map<String, List<Long>>)

    /** Amprenta playlisturilor (lista de canale se schimbă doar la un import). */
    private suspend fun playlistsKey(): String =
        NAMES_VERSION.toString() + "|" + db.playlists().all().joinToString(",") { "${it.id}:${it.enabled}:${it.lastUpdated}:${it.channelCount}" }

    /**
     * Numele tuturor canalelor citite și clasificate (zeci de mii de canale: zeci de secunde pe TV), doar când se
     * schimbă playlisturile; rezultatul se păstrează și pe disc, pentru pornirile următoare.
     */
    private suspend fun nameScan(): NameScan {
        val key = playlistsKey()
        nameScanCache?.takeIf { it.key == key }?.let { return it }
        readNameScan()?.takeIf { it.key == key }?.let { nameScanCache = it; return it }
        val sportIds = ArrayList<Long>()
        val events = ArrayList<ChannelNameRow>()
        val dedicatedIds = HashMap<String, MutableList<Long>>()
        // Același grup apare la mii de canale: ce spune grupul singur se află o singură dată.
        val sportGroup = HashMap<String, Boolean>()
        var after = 0L
        while (true) {
            val page = db.channels().channelNames(after, PAGE)
            page.forEach { row ->
                val isEvent = EventChannels.isEventChannel(row.name, row.groupName)
                if (isEvent) events += row
                val sport = sportGroup.getOrPut(row.groupName) { SportChannels.isSport("", row.groupName) } ||
                    SportChannels.isSport(row.name, row.groupName)
                if (sport) {
                    sportIds += row.id
                    if (!isEvent) DedicatedChannels.seriesOfChannel(row.name)?.let { dedicatedIds.getOrPut(it) { ArrayList() } += row.id }
                }
            }
            if (page.size < PAGE) break
            after = page.last().id
        }
        return NameScan(key, sportIds, events, dedicatedIds).also { nameScanCache = it; writeNameScan(it) }
    }

    private fun writeNameScan(n: NameScan) {
        val tmp = File(namesFile.parentFile, namesFile.name + ".tmp")
        runCatching {
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { out ->
                out.writeUTF(n.key.take(20_000))
                out.writeInt(n.sportIds.size)
                n.sportIds.forEach { out.writeLong(it) }
                out.writeInt(n.events.size)
                n.events.forEach { out.writeLong(it.id); out.writeUTF(it.name.take(2_000)); out.writeUTF(it.groupName.take(2_000)) }
                out.writeInt(n.dedicated.size)
                n.dedicated.forEach { (k, ids) -> out.writeUTF(k); out.writeInt(ids.size); ids.forEach { out.writeLong(it) } }
            }
            if (!tmp.renameTo(namesFile)) { namesFile.delete(); tmp.renameTo(namesFile) }
        }.onFailure { tmp.delete() }
    }

    private fun readNameScan(): NameScan? = if (!namesFile.exists()) null else runCatching {
        java.io.DataInputStream(java.io.BufferedInputStream(namesFile.inputStream(), 64 * 1024)).use { input ->
            val key = input.readUTF()
            val sportIds = List(input.readInt()) { input.readLong() }
            val events = List(input.readInt()) { ChannelNameRow(input.readLong(), input.readUTF(), input.readUTF()) }
            val dedicated = (0 until input.readInt()).associate { input.readUTF() to List(input.readInt()) { input.readLong() } }
            NameScan(key, sportIds, events, dedicated)
        }
    }.getOrNull()

    private suspend fun scanChannels(): ChannelScan {
        val names = nameScan()
        val sportIds = names.sportIds
        val events = names.events
        val dedicated = names.dedicated.mapValues { (_, ids) ->
            ids.chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }.map { it.groupKey }.toSet()
        }
        // Canalele de sport și canalele lor EPG, dintr-o singură trecere prin potrivirile EPG.
        val map = sportIds.chunked(IN_CHUNK).flatMap { epg.mapForChannels(it) }
        val mapped = map.mapTo(HashSet()) { it.first }
        // Canalele de sport fără EPG (în afară de cele EVENT / PPV, care oricum n-au program).
        val eventIds = events.map { it.id }.toHashSet()
        val withoutIds = sportIds.filter { it !in eventIds && it !in mapped }
        val noEpg = withoutIds.take(MAX_NO_EPG_SCAN).chunked(IN_CHUNK).flatMap { db.channels().byIds(it) }
            .groupBy { it.groupKey }
            .map { (_, list) -> NoEpgChannel(list.first().name, list.first().groupName, list.first().playlistName, list.size) }
            .sortedWith(compareByDescending<NoEpgChannel> { it.variants }.thenBy { it.name })
            .take(MAX_NO_EPG)
        return ChannelScan(
            sportEpg = map.mapTo(HashSet()) { it.second },
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
        const val RETRY_GAP_MS = 15_000L
        /** După un 429: limita TheSportsDB e pe minut. */
        const val TOO_MANY_GAP_MS = 60_000L
        /** TheSportsDB gratuit: cel mult ~30 de cereri pe minut; aici ~15. */
        const val SPORTSDB_GAP_MS = 4_000L
        /** ESPN: API deschis pe CDN, răspunsuri mici; o cerere pe secundă. */
        const val ESPN_GAP_MS = 1_000L
        /** Calendarul unei ligi ESPN se reîmprospătează cam o dată pe săptămână. */
        val CAL_REFRESH_MS = TimeUnit.DAYS.toMillis(7)
        /** O ligă care nu există la ESPN (404) se mai încearcă abia peste o lună. */
        val CAL_MISSING_MS = TimeUnit.DAYS.toMillis(30)
        const val CAL_MISSING = "missing"
        val ROUND_MS = TimeUnit.MINUTES.toMillis(10)
        val WINDOW_BACK_MS = TimeUnit.HOURS.toMillis(12)
        val BROADCAST_BACK_MS = TimeUnit.HOURS.toMillis(30)
        const val RECOMPUTE_EVERY = 8
        const val ESPN_RECOMPUTE_EVERY = 30
        const val MID_ROUND_GAP_MS = 45_000L
        val NEAR_AHEAD_MS = TimeUnit.HOURS.toMillis(36)
        val NEAR_RELOAD_MS = TimeUnit.HOURS.toMillis(1)
        val FAR_RELOAD_MS = TimeUnit.HOURS.toMillis(6)
        const val SLOW_COMPUTE_MS = 3_000L
        const val HOUR_MS = 3_600_000L

        /** Se mărește când se schimbă calculul: lista salvată se recalculează la prima pornire. */
        const val INPUTS_VERSION = 4

        /** Se mărește când se schimbă clasificarea canalelor după nume. */
        const val NAMES_VERSION = 1
        const val MAX_EXTRACT_CACHE = 300_000
        val WINDOW_AHEAD_MS = TimeUnit.DAYS.toMillis(7)
        const val IN_CHUNK = 500
        const val MAX_CATEGORY_ROWS = 20_000
        const val MAX_UNMATCHED = 150
        const val MAX_NO_EPG = 300
        const val MAX_NO_EPG_SCAN = 5_000
        const val SOFA_TV_PER_ROUND = 25
        const val MAX_HIGHLIGHTS = 300
        const val MAX_CATCHUP_DAYS = 14
        const val SOFA_NAMES_PER_ROUND = 15
        val SOFA_TV_FRESH_MS = TimeUnit.HOURS.toMillis(12)
        val SOFA_NAMES_FRESH_MS = TimeUnit.DAYS.toMillis(7)
        const val MAX_SAMPLE_BYTES = 300_000L
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        val DAY_IN_NAME = Regex("""\d{4}-\d{2}-\d{2}""")
        /** Prefixele de țară ale canalelor din SUA și Canada. */
        val NORTH_AMERICA = setOf("us", "usa", "ca")
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

/** Starea unui furnizor de meciuri: cererile ultimei runde, motivul ultimei erori și câte meciuri are salvate. */
data class ProviderHealth(
    val ok: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
    /** Ultima cerere (ms). */
    val at: Long = 0,
    val lastSuccess: Long? = null,
    /** Meciurile citite acum din fișierele salvate ale furnizorului. */
    val events: Int = 0,
    /** Furnizorul ne-a refuzat și nu-l mai întrebăm până atunci (ms), în afară de „Actualizează acum”. */
    val pausedUntil: Long? = null,
)

/** Furnizorul unei adrese (aceleași chei ca `SportEvent.provider`). */
fun providerOf(url: String): String {
    val u = url.lowercase()
    return when {
        "thesportsdb" in u -> TheSportsDb.PROVIDER
        "livesoccertv" in u -> "livesoccertv"
        "sofascore" in u -> "sofascore"
        FutbolEnLaTv.isSite(u) -> FutbolEnLaTv.PROVIDER
        "euroleague" in u -> EuroLeague.PROVIDER
        "liveonsat" in u -> LiveOnSat.PROVIDER
        "fotmob" in u -> FotMob.PROVIDER
        "espn.com" in u -> UsLeagues.ESPN
        "nhle.com" in u -> UsLeagues.NHL_API
        "mlb.com" in u -> UsLeagues.MLB_API
        else -> "altul"
    }
}

fun providerLabel(provider: String): String = when (provider) {
    TheSportsDb.PROVIDER -> "TheSportsDB"
    "livesoccertv" -> "LiveSoccerTV"
    "sofascore" -> "Sofascore"
    FutbolEnLaTv.PROVIDER -> "FutbolEnLaTV"
    LiveOnSat.PROVIDER -> "LiveOnSat"
    FotMob.PROVIDER -> "FotMob"
    UsLeagues.ESPN -> "ESPN"
    EuroLeague.PROVIDER -> "EuroLeague"
    UsLeagues.NHL_API -> "NHL"
    UsLeagues.MLB_API -> "MLB"
    FollowedNames.PROVIDER -> "Nume urmărit (EPG)"
    else -> provider
}

/**
 * Meciul care rulează pe canalul cu cheia [groupKey] la ora [at] (live sau, la reluare, ora din arhivă):
 * dintre meciurile care au canalul ca sursă (nerespinsă), cel cu sursa cea mai sigură.
 */
fun SportsSnapshot.matchOn(groupKey: String, at: Long): MatchEntry? =
    matches.asSequence()
        .filter { at >= it.match.start - MATCH_LEAD_MS && at <= it.match.end }
        .mapNotNull { e -> e.sources.filter { it.groupKey == groupKey && !it.rejected }.minOfOrNull { it.confidence.ordinal }?.let { e to it } }
        .minByOrNull { it.second }
        ?.first

/** Meciul are canalul cu cheia [groupKey] printre sursele lui (nerespinse). */
fun MatchEntry.hasSource(groupKey: String): Boolean = sources.any { it.groupKey == groupKey && !it.rejected }

/** Meciul a început și o sursă (nerespinsă) are încă arhiva lui: se poate vedea ca reluare. */
fun MatchEntry.hasReplay(now: Long): Boolean =
    match.start < now && match.status != MatchStatus.SCHEDULED &&
        sources.any { !it.rejected && ro.ak.sportshub.core.playlist.Catchup.isAvailable(it.channel.catchupDays, match.start, now) }

/** Un canal e „pe meci” cu câteva minute înainte de start (studioul dinainte). */
private val MATCH_LEAD_MS = TimeUnit.MINUTES.toMillis(10)
