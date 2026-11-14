package ro.ak.sportshub.data

import android.content.Context
import java.time.ZoneId

/**
 * Fusul orar al fiecărui server Xtream (din `server_info.timezone`, citit la actualizarea playlistului),
 * pentru adresele de catch-up. Fără el se folosește fusul televizorului.
 */
class CatchupStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setZone(playlistId: Long, timezone: String?) {
        val valid = timezone?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: return
        if (prefs.getString(key(playlistId), null) != valid) prefs.edit().putString(key(playlistId), valid).apply()
    }

    fun zoneFor(playlistId: Long): ZoneId =
        prefs.getString(key(playlistId), null)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    private fun key(playlistId: Long) = "zone.$playlistId"

    /**
     * Corecția saltului la eveniment pe playlist (secunde): pozitivă dacă arhiva furnizorului e în urmă
     * (saltul ajunge prea devreme), negativă dacă ajunge prea târziu.
     */
    fun offsetSeconds(playlistId: Long): Int = prefs.getInt("offset.$playlistId", 0)

    fun setOffsetSeconds(playlistId: Long, seconds: Int) {
        prefs.edit().putInt("offset.$playlistId", seconds.coerceIn(-MAX_OFFSET, MAX_OFFSET)).apply()
    }

    companion object {
        const val PREFS = "catchup"
        const val MAX_OFFSET = 600

        /** Valorile din care alegi corecția (secunde). */
        val OFFSETS: List<Int> = listOf(-180, -120, -90, -60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60, 90, 120, 180, 300)
    }
}
