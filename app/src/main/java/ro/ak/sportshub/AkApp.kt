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
import ro.ak.sportshub.data.EpgRepository
import ro.ak.sportshub.data.EpgSettingsStore
import ro.ak.sportshub.data.MatchDetailsRepository
import ro.ak.sportshub.data.PlayerHistory
import ro.ak.sportshub.data.PlaylistRepository
import ro.ak.sportshub.data.PlaylistUpdateWorker
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

    val variants = VariantRepository(database.channels(), variantStore) { sportsSettings.current.sourcePreferences }

    val playlists = PlaylistRepository(
        context = app,
        db = database,
        importer = PlaylistImporter(downloader, gate, File(app.cacheDir, "import")),
        scope = scope,
        corrections = variantStore::correctionsFor,
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
        http = http,
        scope = scope,
    )

    /** Pre-meci și Live: cerute doar cât e deschisă fereastra unui meci. */
    val matchDetails = MatchDetailsRepository(app, sportsSettings, http)
}

class AkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
    }
}
