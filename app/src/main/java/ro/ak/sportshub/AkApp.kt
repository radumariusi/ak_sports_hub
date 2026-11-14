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
import ro.ak.sportshub.core.playlist.PlaylistDownloader
import ro.ak.sportshub.core.playlist.PlaylistImporter
import ro.ak.sportshub.data.PlayerHistory
import ro.ak.sportshub.data.PlaylistRepository
import ro.ak.sportshub.data.PlaylistUpdateWorker

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

    val playlists = PlaylistRepository(
        context = app,
        db = database,
        importer = PlaylistImporter(PlaylistDownloader(http), gate, File(app.cacheDir, "import")),
        scope = scope,
    )

    val playerHistory = PlayerHistory(app, database.channels())
}

class AkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PlaylistUpdateWorker.schedule(this)
        // La pornire, ce a rămas în urmă (de exemplu televizorul a stat oprit).
        container.scope.launch { container.playlists.refreshDue() }
    }
}
