package ro.ak.sportshub.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ro.ak.sportshub.BuildConfig
import ro.ak.sportshub.core.common.AppVersions
import ro.ak.sportshub.core.common.LoadProgress

/** Starea actualizării aplicației, pentru ecranul de setări. */
sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: Int) : UpdateState
    data class Available(val latest: Int, val assetUrl: String, val size: Long) : UpdateState
    data object Downloading : UpdateState
    /** APK-ul e descărcat; instalarea așteaptă confirmarea ta în fereastra sistemului. */
    data class Ready(val latest: Int) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Actualizarea din aplicație: citește release-ul unic „ultima-versiune” din GitHub (titlul are „v1.0.N”),
 * descarcă APK-ul și deschide instalatorul sistemului. Nu folosește conexiunea niciunui playlist.
 * Repo privat: e nevoie de o cheie GitHub (doar citire), pusă în setări; nu intră în backup sau în loguri.
 */
class AppUpdater(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val progress: LoadProgress,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installed: Int get() = BuildConfig.VERSION_CODE

    var token: String?
        get() = prefs.getString("token", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString("token", value?.trim()?.ifEmpty { null }).apply()
        }

    /** Verificarea automată: cel mult o dată la 6 ore. */
    fun checkIfDue() {
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS && _state.value != UpdateState.Unknown) return
        scope.launch { check() }
    }

    suspend fun check(): UpdateState = mutex.withLock {
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { fetchRelease() }
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        _state.value = result
        result
    }

    private fun fetchRelease(): UpdateState = try {
        val request = github(Request.Builder().url(RELEASE_URL).header("Accept", "application/vnd.github+json")).build()
        http.newCall(request).execute().use { r ->
            when {
                r.code == 404 && token == null ->
                    UpdateState.Failed("Repo-ul e privat: pune o cheie GitHub (doar citire) mai jos.")
                r.code == 401 || r.code == 403 -> UpdateState.Failed("GitHub a refuzat cheia (cod ${r.code}).")
                !r.isSuccessful -> UpdateState.Failed("GitHub a răspuns cu ${r.code}.")
                else -> {
                    val json = JSONObject(r.body?.string().orEmpty())
                    val latest = AppVersions.fromTitle(json.optString("name"))
                        ?: return UpdateState.Failed("Release-ul nu are numărul versiunii în titlu.")
                    val assets = json.optJSONArray("assets")
                    val asset = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                        ?: return UpdateState.Failed("Release-ul nu are niciun APK.")
                    if (latest <= installed) {
                        UpdateState.UpToDate(latest)
                    } else {
                        // Cu cheie, APK-ul se ia prin API (merge și la repo privat); fără, prin linkul public.
                        val url = if (token != null) asset.optString("url") else asset.optString("browser_download_url")
                        UpdateState.Available(latest, url, asset.optLong("size", -1))
                    }
                }
            }
        }
    } catch (e: Exception) {
        UpdateState.Failed("Nu s-a putut verifica (${e.javaClass.simpleName}).")
    }

    /** Descarcă versiunea nouă și deschide instalatorul. */
    fun downloadAndInstall() {
        val available = _state.value as? UpdateState.Available ?: return
        scope.launch {
            mutex.withLock {
                _state.value = UpdateState.Downloading
                val file = withContext(Dispatchers.IO) { download(available) }
                _state.value = if (file == null) {
                    UpdateState.Failed("Descărcarea APK-ului a eșuat.")
                } else {
                    install(file)
                    UpdateState.Ready(available.latest)
                }
            }
        }
    }

    private fun download(a: UpdateState.Available): File? = progress.track("app-update", "Actualizare la ${AppVersions.name(a.latest)}", "se descarcă APK-ul") { report ->
        try {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "ak-sports-hub-${a.latest}.apk")
            val request = github(Request.Builder().url(a.assetUrl).header("Accept", "application/octet-stream")).build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@track null
                val body = r.body ?: return@track null
                val total = body.contentLength().takeIf { it > 0 } ?: a.size
                var read = 0L
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            report.bytes(read, total)
                        }
                    }
                }
            }
            target.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** Instalatorul sistemului; dacă aplicația nu are voie să instaleze, deschide întâi setarea. */
    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            pendingFile = file
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private var pendingFile: File? = null

    /** După ce ai dat voie la instalare: deschide instalatorul pentru APK-ul deja descărcat. */
    fun installAgain() {
        val file = pendingFile ?: File(app.cacheDir, "updates").listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return
        install(file)
    }

    private fun github(b: Request.Builder): Request.Builder = b.apply {
        header("User-Agent", "AKSportsHub-updater")
        token?.let { header("Authorization", "Bearer $it") }
    }

    private companion object {
        const val RELEASE_URL = "https://api.github.com/repos/radumariusi/ak_sports_hub/releases/tags/ultima-versiune"
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}
