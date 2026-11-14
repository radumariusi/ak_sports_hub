package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Canalele favorite, pe cheia de grupare (aceeași în toate playlisturile și după fiecare import),
 * deci un favorit cuprinde toate variantele canalului. Ordinea e cea în care le-ai adăugat.
 */
class ChannelFavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(read())
    val keys: StateFlow<List<String>> = _keys.asStateFlow()

    fun contains(groupKey: String): Boolean = groupKey in _keys.value

    @Synchronized
    fun toggle(groupKey: String) {
        val next = if (groupKey in _keys.value) _keys.value - groupKey else _keys.value + groupKey
        prefs.edit().putString(KEY, JSONArray(next).toString()).apply()
        _keys.value = next
    }

    private fun read(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }.distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val PREFS = "channel_favorites"
        private const val KEY = "keys"
    }
}
