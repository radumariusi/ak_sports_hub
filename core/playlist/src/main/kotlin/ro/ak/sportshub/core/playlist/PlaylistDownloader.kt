package ro.ak.sportshub.core.playlist

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Eroare afișabilă: mesajul e în română și nu conține adrese sau date de acces. */
class PlaylistException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Antetele pentru actualizarea incrementală: dacă fișierul nu s-a schimbat, nu se mai descarcă. */
data class CacheTag(val etag: String? = null, val lastModified: String? = null)

/**
 * Descărcări HTTP simple pentru playlisturi (fișiere, nu stream-uri video).
 * Nu limitează singur conexiunile: [PlaylistImporter] ține permisul din `ConnectionGate`
 * pe toată durata descărcărilor unui playlist.
 */
class PlaylistDownloader(private val client: OkHttpClient) {

    sealed interface Result {
        data object NotModified : Result
        data class Saved(val tag: CacheTag) : Result
    }

    suspend fun download(
        url: String,
        target: File,
        userAgent: String?,
        previous: CacheTag? = null,
        /** Ce se descarcă, pentru mesajul de eroare („playlistul”, „EPG-ul”). */
        what: String = "playlistul",
    ): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            userAgent?.let { header("User-Agent", it) }
            previous?.etag?.let { header("If-None-Match", it) }
            previous?.lastModified?.let { header("If-Modified-Since", it) }
        }.build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@withContext Result.NotModified
                if (!response.isSuccessful) throw httpError(response.code)
                val body = response.body ?: throw PlaylistException("Serverul a trimis un răspuns gol.")
                target.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                Result.Saved(CacheTag(response.header("ETag"), response.header("Last-Modified")))
            }
        } catch (e: PlaylistException) {
            throw e
        } catch (e: IOException) {
            throw PlaylistException("Nu s-a putut descărca $what. Verifică adresa și conexiunea.", e)
        } catch (e: IllegalArgumentException) {
            throw PlaylistException(if (what == "playlistul") "Adresa playlistului nu e validă." else "Adresa nu e validă.", e)
        }
    }

    suspend fun fetchText(url: HttpUrl, userAgent: String?): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            userAgent?.let { header("User-Agent", it) }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code)
                response.body?.string().orEmpty()
            }
        } catch (e: PlaylistException) {
            throw e
        } catch (e: IOException) {
            throw PlaylistException("Nu s-a putut contacta serverul. Verifică adresa și conexiunea.", e)
        }
    }

    private fun httpError(code: Int) = PlaylistException(
        when (code) {
            401, 403 -> "Accesul a fost refuzat (cod $code). Contul poate fi expirat sau datele sunt greșite."
            404 -> "Adresa nu a fost găsită pe server (cod 404)."
            429 -> "Serverul cere o pauză (cod 429). Se reîncearcă mai târziu."
            in 500..599 -> "Serverul are o problemă (cod $code). Se reîncearcă mai târziu."
            else -> "Serverul a răspuns cu eroarea $code."
        },
    )
}
