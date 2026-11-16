package ro.ak.sportshub

import android.app.Application
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.ak.sportshub.core.common.LoadProgress
import ro.ak.sportshub.core.common.ConnectionGate
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.EpgDatabase
import ro.ak.sportshub.core.playlist.PlaylistDownloader
import ro.ak.sportshub.core.playlist.PlaylistImporter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import ro.ak.sportshub.data.ApiKeys
import ro.ak.sportshub.data.AppUpdater
import ro.ak.sportshub.data.LogoImages
import ro.ak.sportshub.data.TeamLookStore
import ro.ak.sportshub.data.CatchupStore
import ro.ak.sportshub.data.ChannelFavoritesStore
import ro.ak.sportshub.data.SuspectChannelsStore
import ro.ak.sportshub.data.EpgRepository
import ro.ak.sportshub.data.SettingsBackup
import ro.ak.sportshub.home.HomeChannel
import ro.ak.sportshub.notify.MatchReminderScheduler
import ro.ak.sportshub.data.EpgSettingsStore
import ro.ak.sportshub.data.MatchDetailsRepository
import ro.ak.sportshub.data.ObservedVideoStore
import ro.ak.sportshub.data.PlayerHistory
import ro.ak.sportshub.data.PlaylistRepository
import ro.ak.sportshub.data.PlaylistUpdateWorker
import ro.ak.sportshub.data.DiagnosticLog
import ro.ak.sportshub.data.DiagnosticReporter
import ro.ak.sportshub.data.SportsKnowledgeStore
import ro.ak.sportshub.data.SportsRepository
import ro.ak.sportshub.data.SportsSettingsStore
import ro.ak.sportshub.data.VariantRepository
import ro.ak.sportshub.data.VariantStore
import ro.ak.sportshub.data.StandingsRepository
import ro.ak.sportshub.data.ChannelPlaybackStore

/** Dependențele aplicației, create o singură dată (fără bibliotecă de injecție, deocamdată). */
class AppContainer(app: Application) {
    /** Un singur proces, o singură poartă: playerul și actualizările folosesc aceeași. */
    val gate = ConnectionGate()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Playerul e pe ecran: lucrul greu din fundal (calcule, importuri) așteaptă până se oprește redarea. */
    val playing = MutableStateFlow(false)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // fără reîncercări ascunse: o conexiune pe playlist
        .build()

    val database: AkDatabase = AkDatabase.create(app)

    private val downloader = PlaylistDownloader(http)

    /** Încărcările în curs (playlisturi, EPG, potrivire), cu numărătorile lor, pentru ecran. */
    val loads = LoadProgress()

    val variantStore = VariantStore(app)

    val sportsSettings = SportsSettingsStore(app)

    /** Dicționarul de posturi, regulile de drepturi TV și ce s-a învățat din confirmări. */
    val sportsKnowledge = SportsKnowledgeStore(app)

    /** Rezoluția și cadrele văzute la redare, pe variantă. */
    val observedVideo = ObservedVideoStore(app)

    /** Variantele semnalate din player ca „canal posibil greșit”. */
    val suspectChannels = SuspectChannelsStore(app)

    /** Cum a mers redarea fiecărei variante (pornire, opriri, căderi) și limba pistelor audio (C1, C2). */
    val playbackHealth = ro.ak.sportshub.data.PlaybackHealthStore(app).also { h ->
        ro.ak.sportshub.data.sourceStability = { c -> h.rank(c.playlistId, c.name) }
    }

    val variants = VariantRepository(
        database.channels(),
        variantStore,
        preferences = { sportsSettings.current.sourcePreferences },
        observed = { observedVideo.current },
        suspect = { suspectChannels.keys.value },
        health = { playbackHealth.current },
    )

    /** Fusul orar al serverelor Xtream (pentru catch-up). */
    val catchup = CatchupStore(app)

    /** Canalele favorite (pe cheia de grupare: toate variantele). */
    val channelFavorites = ChannelFavoritesStore(app)

    val playlists = PlaylistRepository(
        context = app,
        db = database,
        importer = PlaylistImporter(downloader, gate, File(app.cacheDir, "import"), File(app.noBackupFilesDir, "amprente-playlist")),
        scope = scope,
        corrections = variantStore::correctionsFor,
        onServerZone = catchup::setZone,
        progress = loads,
    )

    val epgSettings = EpgSettingsStore(app)

    val epg = EpgRepository(
        context = app,
        db = database,
        epgDb = EpgDatabase.create(app),
        settings = epgSettings,
        downloader = downloader,
        gate = gate,
        scope = scope,
        progress = loads,
    )

    /** Actualizarea aplicației din release-ul „ultima-versiune”. */
    val updater = AppUpdater(app, http, scope, loads)

    val playerHistory = PlayerHistory(app, database.channels())

    /** Jurnalul de diagnostic al furnizorilor de meciuri și de statistici (trimis pe GitHub doar la cerere). */
    val diagnosticLog = DiagnosticLog(app)

    /** Siglele, culorile și forma echipelor, învățate din programul furnizorilor. */
    val teamLooks = TeamLookStore(app, scope)
    val apiKeys = ApiKeys(app)

    /** Imaginile siglelor (pe disc, descărcate o dată; nimic cât rulează playerul). */
    val logos = LogoImages(app, http, playing)

    val sports = SportsRepository(
        context = app,
        db = database,
        epg = epg,
        playlists = playlists,
        variantStore = variantStore,
        observed = observedVideo,
        playback = playbackHealth,
        settings = sportsSettings,
        knowledge = sportsKnowledge,
        http = http,
        scope = scope,
        log = diagnosticLog,
        playing = playing,
        looks = teamLooks,
    )

    /** Pre-meci și Live: cerute doar cât e deschisă fereastra unui meci. */
    /** Clasamentele (tabul „Clasamente” și Pre-meci), cerute doar cât e deschis tabul sau meciul. */
    val standings = StandingsRepository(app, http, diagnosticLog)

    val matchDetails = MatchDetailsRepository(app, sportsSettings, http, diagnosticLog, sports::espnEventFor, standings, sports::fotmobEventFor, sports::euroleagueEventFor, teamLooks, { apiKeys.goalApi })

    /** Meciul la care te uiți acum în player (pentru alertele de gol: nu pentru meciul de pe ecran). */
    val watchingMatch = MutableStateFlow<String?>(null)

    /** Meciurile deja comutate, anulate sau anunțate (o singură dată pe meci, cât rulează aplicația). */
    val handledKickoffs: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Trimite jurnalul ca issue în repo-ul aplicației, cu cheia GitHub din Setări → Despre. */
    val diagnosticReporter = DiagnosticReporter(http, scope, diagnosticLog, sports) { updater.token }

    /** Notificările din aplicație (de exemplu „Arsenal – Chelsea începe în 10 minute”), arătate și în player. */
    val inAppNotices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Backup și restaurare pentru setări (și, la cerere, playlisturi). */
    val backup = SettingsBackup(app, database)

    /** Reglajele playerului pe canal (întârzierea sunetului, pista audio, încadrarea) și modul de noapte. */
    val channelPlayback = ChannelPlaybackStore(app)

    /** Timpii pornirii (E3), scriși o dată în jurnal. */
    val startup = StartupClock(diagnosticLog)

    /** F4: ce rulează acum în player (pentru telefon); null = playerul nu e pe ecran. */
    val onTv = MutableStateFlow<OnTv?>(null)

    /** F4: meciurile trimise din telefon („Pornește pe televizor”), deschise de navigare. */
    val phonePlay = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Playerul de pe ecran pornește singur meciul trimis (pe firul principal); null = nu rulează. */
    @Volatile var playerRemote: ((String) -> Boolean)? = null

    /** F4: telefonul ca al doilea ecran (serverul local, doar cât e aplicația pe ecran și setarea pornită). */
    val secondScreen = ro.ak.sportshub.setup.SecondScreenHost(app, this)
}

/** Canalul din player și meciul legat de el. */
data class OnTv(val channel: String, val matchId: String?)

/**
 * Cât durează pornirea (E3): de la pornirea procesului (și de la deschiderea ecranului) până la primul ecran și până
 * la lista Sports Hub afișată. Fiecare pas se scrie o singură dată pe rulare, cu versiunea, ca să se vadă în jurnal
 * dacă o versiune nouă a încetinit ceva.
 */
class StartupClock(private val log: DiagnosticLog) {
    private val appStart = android.os.SystemClock.elapsedRealtime()
    private val processStart: Long =
        if (android.os.Build.VERSION.SDK_INT >= 24) android.os.Process.getStartElapsedRealtime() else appStart
    @Volatile private var screenStart: Long? = null
    private val marked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Ecranul aplicației s-a deschis (prima dată în proces). */
    fun screenOpened() {
        if (screenStart == null) screenStart = android.os.SystemClock.elapsedRealtime()
    }

    fun mark(step: String, detail: String = "") {
        val screen = screenStart ?: return
        if (!marked.add(step)) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Procesul pornit mai devreme pentru altceva (notificare, actualizare programată): contează doar ecranul.
        val cold = screen - processStart < COLD_LIMIT_MS
        val fromProcess = if (cold) "${now - processStart} ms de la pornirea procesului · " else ""
        log.log("pornire", "$step$detail: $fromProcess${now - screen} ms de la deschiderea ecranului · v${BuildConfig.VERSION_NAME}")
    }

    private companion object {
        const val COLD_LIMIT_MS = 5_000L
    }
}

open class AkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        catchCrashes()
        startBackgroundWork()
    }

    /**
     * E2: o cădere a aplicației se scrie în jurnal (fără adrese de stream) înainte ca sistemul s-o închidă; la
     * pornirea următoare ecranul principal întreabă dacă trimiți jurnalul.
     */
    private fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { container.diagnosticLog.crash(ro.ak.sportshub.core.common.DiagnosticText.crash(error, thread.name)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Actualizările, notificările și rândul de pe ecranul principal (în testele de capturi nu pornesc). */
    protected open fun startBackgroundWork() {
        PlaylistUpdateWorker.schedule(this)
        // La pornire (și după o actualizare a aplicației) nu se reîncarcă nimic din ce există deja: doar playlisturile
        // și sursele EPG încărcate niciodată. Restul rămâne pentru actualizarea programată (la 2 ore, după intervale).
        container.scope.launch {
            container.playlists.refreshMissing()
            container.epg.refreshMissing()
        }
        // Sports Hub: lista se recalculează la orice schimbare; programul meciurilor se cere doar dacă e vechi.
        container.sports.start()
        container.scope.launch { container.sports.refreshDue() }
        // Canale noi sau playlisturi șterse: EPG-ul se recitește din fișierele salvate și se refac potrivirile.
        container.scope.launch { container.playlists.changes.collect { container.epg.onPlaylistsChanged() } }
        // Notificări înainte de meciurile favorite și rândul „Meciurile tale de azi” din launcher.
        MatchReminderScheduler(this, container).start()
        HomeChannel(this, container).start()
    }
}
