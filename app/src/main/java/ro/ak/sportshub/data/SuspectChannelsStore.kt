package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Variantele semnalate din player ca „canal posibil greșit” (pe playlist + numele original, ca să rămână după
 * fiecare import). Nu se ascund: apar în continuare ca surse și variante, doar cu eticheta lor și după celelalte
 * variante ale aceluiași canal. Semnalarea se scoate tot din player.
 */
class SuspectChannelsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(read())
    val keys: StateFlow<Set<CorrectionKey>> = _keys.asStateFlow()

    fun contains(playlistId: Long, name: String): Boolean = CorrectionKey(playlistId, name) in _keys.value

    /** Pune sau scoate semnalarea; întoarce noua stare (true = semnalat). */
    @Synchronized
    fun toggle(playlistId: Long, name: String): Boolean {
        val key = CorrectionKey(playlistId, name)
        val marked = key !in _keys.value
        val next = if (marked) _keys.value + key else _keys.value - key
        prefs.edit().putString(KEY, write(next)).apply()
        _keys.value = next
        return marked
    }

    private fun read(): Set<CorrectionKey> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptySet())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { CorrectionKey(it.getLong("p"), it.getString("n")) } }.toSet()
    }.getOrDefault(emptySet())

    private fun write(keys: Set<CorrectionKey>): String =
        JSONArray().apply { keys.forEach { put(JSONObject().put("p", it.playlistId).put("n", it.name)) } }.toString()

    companion object {
        const val PREFS = "suspect_channels"
        private const val KEY = "keys"
    }
}
