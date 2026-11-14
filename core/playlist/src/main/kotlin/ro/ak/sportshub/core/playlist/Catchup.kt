package ro.ak.sportshub.core.playlist

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Catch-up („înapoi în timp”) pe serverele Xtream: arhiva unui canal se cere la
 * `/timeshift/{utilizator}/{parolă}/{minute}/{AAAA-LL-ZZ:HH-mm}/{id}.{ext}`.
 *
 * Adresa se deduce din adresa live a canalului (`/live/u/p/123.ts` sau `/u/p/123`), deci merge și la
 * playlisturile M3U generate de un panou Xtream. Ora de start e în fusul orar al serverului.
 * Adresele conțin parola: nu se loghează.
 */
object Catchup {

    private val StartFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    /** Bucățile unei adrese live Xtream. */
    data class XtreamLive(val base: String, val user: String, val pass: String, val streamId: String, val ext: String)

    fun parse(liveUrl: String): XtreamLive? {
        val url = liveUrl.trim().toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 3) return null
        val file = segments.last()
        val id = file.substringBefore('.')
        if (id.isEmpty() || !id.all(Char::isDigit)) return null
        val ext = file.substringAfter('.', "").ifEmpty { "ts" }
        val pass = segments[segments.size - 2]
        val user = segments[segments.size - 3]
        var prefix = segments.dropLast(3)
        if (prefix.lastOrNull() == "live") prefix = prefix.dropLast(1)
        // Alte tipuri de adrese (filme, seriale, timeshift deja) nu sunt canale live.
        if (prefix.lastOrNull() in setOf("movie", "series", "timeshift")) return null
        val base = url.newBuilder().query(null).fragment(null).encodedPath("/")
            .apply { prefix.forEach { addPathSegment(it) } }
            .build().toString().trimEnd('/')
        return XtreamLive(base, user, pass, id, ext)
    }

    /**
     * Adresa arhivei pentru emisiunea care începe la [start] (ms UTC) și ține [durationMinutes],
     * cu ora scrisă în fusul serverului [serverZone]. Null dacă adresa live nu e de tip Xtream.
     */
    fun archiveUrl(liveUrl: String, start: Long, durationMinutes: Int, serverZone: ZoneId): String? {
        val p = parse(liveUrl) ?: return null
        val at = Instant.ofEpochMilli(start).atZone(serverZone).format(StartFormat)
        val minutes = durationMinutes.coerceIn(1, MAX_MINUTES)
        return "${p.base}/timeshift/${p.user}/${p.pass}/$minutes/$at/${p.streamId}.${p.ext}"
    }

    /** Emisiunea care a început la [start] e încă în arhiva de [catchupDays] zile și a început deja. */
    fun isAvailable(catchupDays: Int?, start: Long, now: Long): Boolean {
        if (catchupDays == null || catchupDays <= 0) return false
        return start < now && start >= now - TimeUnit.DAYS.toMillis(catchupDays.toLong())
    }

    /** Durata cerută serverului: până la [end], cu câteva minute în plus; cel puțin un minut. */
    fun minutes(start: Long, end: Long): Int =
        (TimeUnit.MILLISECONDS.toMinutes((end - start).coerceAtLeast(0)) + EXTRA_MINUTES).toInt().coerceIn(1, MAX_MINUTES)

    private const val EXTRA_MINUTES = 5
    private const val MAX_MINUTES = 600
}
