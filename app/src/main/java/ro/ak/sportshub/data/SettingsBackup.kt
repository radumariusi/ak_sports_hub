package ro.ak.sportshub.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType

/** Ce s-a restaurat. */
data class RestoreResult(val settings: Int, val playlists: Int)

/**
 * Backup și restaurare: setările, favoritele, alias-urile, regulile și corecțiile (preferințele aplicației)
 * într-un singur fișier JSON, ca să muți totul pe alt televizor. Playlisturile (cu adresele și parolele lor)
 * intră doar dacă ceri explicit. Baza de canale și EPG-ul nu intră: se refac de pe server.
 */
class SettingsBackup(private val context: Context, private val db: AkDatabase) {

    /** Fișierul JSON al backup-ului. [withPlaylists] adaugă playlisturile, cu datele lor de acces. */
    suspend fun export(withPlaylists: Boolean): String = withContext(Dispatchers.IO) {
        val prefs = JSONObject()
        for (name in PREFS) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = JSONObject()
            all.forEach { (k, v) -> typed(v)?.let { o.put(k, it) } }
            prefs.put(name, o)
        }
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAt", System.currentTimeMillis())
            put("prefs", prefs)
            if (withPlaylists) put("playlists", JSONArray(db.playlists().all().map(::playlistJson)))
        }.toString(2)
    }

    /** Numele implicit al fișierului (cu data). */
    fun fileName(): String = "ak-sports-hub-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    suspend fun writeTo(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: error("Fișierul nu poate fi scris.")
    }

    suspend fun readFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fișierul nu poate fi citit.")
    }

    /**
     * Pentru televizoarele fără selector de fișiere: salvează în Download (Android 10+) sau în folderul
     * aplicației. Întoarce unde a ajuns fișierul.
     */
    suspend fun writeToDownloads(text: String): String = withContext(Dispatchers.IO) {
        val name = fileName()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                return@withContext "Download/$name"
            }
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, name).writeText(text)
        File(dir, name).path
    }

    /** Ultimul backup salvat de aplicație în Download sau în folderul ei (pentru restaurarea fără selector). */
    suspend fun latestLocal(): String? = withContext(Dispatchers.IO) {
        val local = (context.getExternalFilesDir(null) ?: context.filesDir).listFiles()
            .orEmpty().filter { it.name.startsWith("ak-sports-hub-backup") && it.name.endsWith(".json") }
            .maxByOrNull { it.lastModified() }
        if (Build.VERSION.SDK_INT >= 29) {
            val fromDownloads = runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_MODIFIED),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("ak-sports-hub-backup%"),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        val modified = c.getLong(1) * 1000
                        if (local == null || modified >= local.lastModified()) {
                            return@use readFrom(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()))
                        }
                    }
                    null
                }
            }.getOrNull()
            if (fromDownloads != null) return@withContext fromDownloads
        }
        local?.readText()
    }

    /** Backup-ul de la o adresă web (de exemplu un fișier pus pe un server al tău). */
    suspend fun download(http: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url.trim()).build()).execute().use { r ->
            if (!r.isSuccessful) error("Serverul a răspuns ${r.code}.")
            r.body?.string() ?: error("Răspuns gol.")
        }
    }

    /**
     * Restaurează [text]: preferințele din backup le înlocuiesc pe cele actuale; playlisturile din backup
     * (dacă există) se adaugă sau se actualizează și se reîncarcă de pe server. Aplicația trebuie repornită.
     */
    suspend fun restore(text: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT) error("Fișierul nu e un backup AK Sports Hub.")
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        var settings = 0
        for (name in PREFS) {
            val o = prefs.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k -> put(editor, k, o.getJSONObject(k)) }
            editor.commit()
            settings++
        }
        // Rândul din launcher și notificările se refac de la zero pe televizorul nou.
        context.getSharedPreferences("reminders_state", Context.MODE_PRIVATE).edit().clear().commit()

        var playlists = 0
        root.optJSONArray("playlists")?.let { arr ->
            val dao = db.playlists()
            for (i in 0 until arr.length()) {
                val p = playlistFrom(arr.getJSONObject(i)) ?: continue
                // Același id, ca alegerile salvate pe playlist (corecții, variante preferate) să se potrivească.
                if (dao.get(p.id) != null) dao.update(p) else dao.insert(p)
                playlists++
            }
        }
        RestoreResult(settings, playlists)
    }

    /** Repornește aplicația, ca setările restaurate să fie citite din nou. */
    fun restartApp() {
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 7, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
        Runtime.getRuntime().exit(0)
    }

    private fun typed(v: Any?): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.filterIsInstance<String>()))
        else -> null
    }

    private fun put(editor: android.content.SharedPreferences.Editor, key: String, o: JSONObject) {
        when (o.optString("t")) {
            "s" -> editor.putString(key, o.optString("v"))
            "b" -> editor.putBoolean(key, o.optBoolean("v"))
            "i" -> editor.putInt(key, o.optInt("v"))
            "l" -> editor.putLong(key, o.optLong("v"))
            "f" -> editor.putFloat(key, o.optDouble("v").toFloat())
            "set" -> editor.putStringSet(key, o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty())
        }
    }

    private fun playlistJson(p: PlaylistEntity) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("type", p.type.name)
        put("url", p.url)
        putOpt("username", p.username)
        putOpt("password", p.password)
        putOpt("userAgent", p.userAgent)
        put("streamFormat", p.streamFormat)
        put("enabled", p.enabled)
        put("updateIntervalHours", p.updateIntervalHours)
        put("sortOrder", p.sortOrder)
    }

    private fun playlistFrom(o: JSONObject): PlaylistEntity? {
        val type = runCatching { PlaylistType.valueOf(o.getString("type")) }.getOrNull() ?: return null
        fun opt(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        return PlaylistEntity(
            id = o.optLong("id"),
            name = o.optString("name", "Playlist"),
            type = type,
            url = o.optString("url"),
            username = opt("username"),
            password = opt("password"),
            userAgent = opt("userAgent"),
            streamFormat = o.optString("streamFormat", "ts"),
            enabled = o.optBoolean("enabled", true),
            updateIntervalHours = o.optInt("updateIntervalHours", 24),
            sortOrder = o.optInt("sortOrder", 0),
            // Fără data ultimei actualizări: la repornire playlistul se descarcă din nou.
        )
    }

    private companion object {
        const val FORMAT = "ak-sports-hub-backup"
        const val VERSION = 1
        /** Preferințele salvate: Sports Hub, variante, dicționar, EPG, favorite, catch-up, ultimul canal. */
        val PREFS = listOf(
            SportsSettingsStore.PREFS, "variants", "sports_knowledge", "epg",
            ChannelFavoritesStore.PREFS, CatchupStore.PREFS, "player",
        )
    }
}
