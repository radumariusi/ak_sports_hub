package ro.ak.sportshub.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.sportshub.core.sports.TeamLooks

/**
 * Siglele echipelor și ale competițiilor: descărcate o singură dată, mici (ESPN și TheSportsDB le dau redimensionate,
 * câțiva KB), păstrate în `noBackupFilesDir/sigle` și ținute în memorie cât se văd.
 *
 * - Nu trec prin `ConnectionGate`: sunt servere de imagini, nu furnizorul IPTV.
 * - Cât rulează playerul nu se descarcă nimic: tabela de scor arată doar ce e deja pe disc (altfel codul echipei).
 * - Cel mult două descărcări odată; o adresă care a dat greș nu se mai cere până la repornirea aplicației.
 */
class LogoImages(context: Context, private val http: OkHttpClient, private val playing: StateFlow<Boolean>) {

    private val dir = File(context.noBackupFilesDir, "sigle")
    private val memory = LruCache<String, ImageBitmap>(MEMORY_ITEMS)
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val downloads = Semaphore(2)
    @Volatile private var trimmed = false

    /** Sigla deja în memorie (pentru primul cadru, fără să aștepte). */
    fun cached(url: String?): ImageBitmap? = url?.let { memory.get(it) }

    /** Sigla de pe disc sau, dacă lipsește și playerul nu rulează, din rețea; null dacă nu se poate. */
    suspend fun load(url: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        if (!trimmed) trim()
        val file = File(dir, sha1(url) + ".img")
        if (!file.exists()) {
            if (playing.value || url in failed) return@withContext null
            val ok = downloads.withPermit { file.exists() || download(url, file) }
            if (!ok) {
                failed += url
                return@withContext null
            }
        }
        val bmp = runCatching { decode(file, px) }.getOrNull()
        if (bmp == null) {
            file.delete()
            failed += url
            return@withContext null
        }
        memory.put(url, bmp)
        bmp
    }

    private fun download(url: String, file: File): Boolean {
        dir.mkdirs()
        val small = TeamLooks.smallLogo(url)
        val tries = if (small != url) listOf(small, url) else listOf(url)
        for (u in tries) {
            val ok = runCatching {
                http.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AKSportsHub").build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) return@use false
                    val type = body.contentType()?.type
                    if (type != null && type != "image") return@use false
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@use false
                    val tmp = File(file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun decode(file: File, px: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    }

    /** O dată pe rulare: dacă s-au strâns prea multe sigle, le șterge pe cele mai vechi. */
    @Synchronized
    private fun trim() {
        if (trimmed) return
        trimmed = true
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val images = files.filter { it.name.endsWith(".img") }
        if (images.size <= MAX_FILES) return
        images.sortedBy { it.lastModified() }.take(images.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MEMORY_ITEMS = 300
        const val MAX_FILES = 3000
        const val MAX_BYTES = 512 * 1024
    }
}
