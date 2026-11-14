package ro.ak.sportshub

import android.app.Application
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.ak.sportshub.core.common.ConnectionGate
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.EpgDatabase
import ro.ak.sportshub.core.playlist.PlaylistDownloader
import ro.ak.sportshub.core.playlist.PlaylistImporter
import kotlinx.coroutines.flow.MutableSharedFlow
import ro.ak.sportshub.data.CatchupStore
import ro.ak.sportshub.data.ChannelFavoritesStore
import ro.ak.sportshub.data.EpgRepository
import ro.ak.sportshub.data.SettingsBackup
import ro.ak.sportshub.home.HomeChannel
import ro.ak.sportshub.notify.MatchReminderScheduler
import ro.ak.sportshub.data.EpgSettingsStore
import ro.ak.sportshub.data.MatchDetailsRepository
import ro.ak.sportshub.data.PlayerHistory
import ro.ak.sportshub.data.PlaylistRepository
import ro.ak.sportshub.data.PlaylistUpdateWorker
import ro.ak.sportshub.data.SportsKnowledgeStore
import ro.ak.sportshub.data.SportsRepository
import ro.ak.sportshub.data.SportsSettingsStore
import ro.ak.sportshub.data.VariantRepository
import ro.ak.sportshub.data.VariantStore

/** Dependențele aplicației, create o singură dată (fără bibliotecă de injecție, deocamdată). */
class AppContainer(app: Application) {
    /** Un singur proces, o singură poartă: playerul și actualizările folosesc aceeași. */
    val gate = ConnectionGate()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // fără reîncercări ascunse: o conexiune pe playlist
        .build()

    val database: AkDatabase = AkDatabase.create(app)

    private val downloader = PlaylistDownloader(http)

    val variantStore = VariantStore(app)

    val sportsSettings = SportsSettingsStore(app)

    /** Dicționarul de posturi, regulile de drepturi TV și ce s-a învățat din confirmări. */
    val sportsKnowledge = SportsKnowledgeStore(app)

    val variants = VariantRepository(database.channels(), variantStore) { sportsSettings.current.sourcePreferences }

    /** Fusul orar al serverelor Xtream (pentru catch-up). */
    val catchup = CatchupStore(app)

    /** Canalele favorite (pe cheia de grupare: toate variantele). */
    val channelFavorites = ChannelFavoritesStore(app)

    val playlists = PlaylistRepository(
        context = app,
        db = database,
        importer = PlaylistImporter(downloader, gate, File(app.cacheDir, "import")),
        scope = scope,
        corrections = variantStore::correctionsFor,
        onServerZone = catchup::setZone,
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
    )

    val playerHistory = PlayerHistory(app, database.channels())

    val sports = SportsRepository(
        context = app,
        db = database,
        epg = epg,
        playlists = playlists,
        variantStore = variantStore,
        settings = sportsSettings,
        knowledge = sportsKnowledge,
        http = http,
        scope = scope,
    )

    /** Pre-meci și Live: cerute doar cât e deschisă fereastra unui meci. */
    val matchDetails = MatchDetailsRepository(app, sportsSettings, http)

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
        // La pornire, ce a rămas în urmă (de exemplu televizorul a stat oprit).
        container.scope.launch {
            container.playlists.refreshDue()
            container.epg.refreshDue()
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
