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

    val variants = VariantRepository(
        database.channels(),
        variantStore,
        preferences = { sportsSettings.current.sourcePreferences },
        observed = { observedVideo.current },
        suspect = { suspectChannels.keys.value },
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

    /** Imaginile siglelor (pe disc, descărcate o dată; nimic cât rulează playerul). */
    val logos = LogoImages(app, http, playing)

    val sports = SportsRepository(
        context = app,
        db = database,
        epg = epg,
        playlists = playlists,
        variantStore = variantStore,
        observed = observedVideo,
        settings = sportsSettings,
        knowledge = sportsKnowledge,
        http = http,
        scope = scope,
        log = diagnosticLog,
        playing = playing,
        looks = teamLooks,
    )

    /** Pre-meci și Live: cerute doar cât e deschisă fereastra unui meci. */
    val matchDetails = MatchDetailsRepository(app, sportsSettings, http, diagnosticLog, sports::espnEventFor)

    /** Clasamentele (tabul „Clasamente”), cerute doar cât e deschis tabul. */
    val standings = StandingsRepository(app, http, diagnosticLog)

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
}

open class AkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        startBackgroundWork()
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
