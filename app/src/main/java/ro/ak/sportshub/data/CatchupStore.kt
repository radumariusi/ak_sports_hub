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

    companion object {
        const val PREFS = "catchup"
    }
}
