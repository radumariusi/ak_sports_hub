package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.common.ObservedQuality
import ro.ak.sportshub.core.common.ObservedVideo
import ro.ak.sportshub.core.common.QualityView
import ro.ak.sportshub.core.model.Quality

/**
 * Rezoluția și cadrele văzute la ultima redare a fiecărei variante (playlist + numele original), din decodorul
 * playerului: stream-ul se reda oricum, deci nicio cerere în plus și nicio verificare separată.
 * Stau în preferințe (baza de canale se reface la fiecare import), fără adrese de stream.
 */
class ObservedVideoStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<CorrectionKey, ObservedVideo>> = _state.asStateFlow()
    val current: Map<CorrectionKey, ObservedVideo> get() = _state.value

    fun of(playlistId: Long, name: String): ObservedVideo? = _state.value[CorrectionKey(playlistId, name)]

    /** Notează ce s-a văzut la redare (doar dacă diferă de ce era notat). */
    @Synchronized
    fun record(playlistId: Long, name: String, video: ObservedVideo) {
        val key = CorrectionKey(playlistId, name)
        if (ObservedQuality.same(_state.value[key], video)) return
        val next = (_state.value + (key to video)).entries
            .sortedByDescending { it.value.at }.take(MAX).associate { it.key to it.value }
        prefs.edit().putString(KEY, write(next)).apply()
        _state.value = next
    }

    /** Uită ce s-a văzut la o variantă (de exemplu după ce furnizorul a schimbat stream-ul). */
    @Synchronized
    fun forget(playlistId: Long, name: String) {
        val key = CorrectionKey(playlistId, name)
        if (key !in _state.value) return
        val next = _state.value - key
        prefs.edit().putString(KEY, write(next)).apply()
        _state.value = next
    }

    /** Uită tot ce s-a văzut (etichetele revin la cele din playlist). */
    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
        _state.value = emptyMap()
    }

    private fun read(): Map<CorrectionKey, ObservedVideo> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                CorrectionKey(o.getLong("p"), o.getString("n")) to ObservedVideo(
                    width = o.getInt("w"),
                    height = o.getInt("h"),
                    fps = if (o.has("f")) o.getDouble("f").toFloat() else null,
                    at = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun write(map: Map<CorrectionKey, ObservedVideo>): String = JSONArray().apply {
        map.forEach { (k, v) ->
            put(
                JSONObject().put("p", k.playlistId).put("n", k.name).put("w", v.width).put("h", v.height).put("at", v.at)
                    .apply { v.fps?.let { put("f", it.toDouble()) } },
            )
        }
    }.toString()

    companion object {
        const val PREFS = "observed_video"
        private const val KEY = "seen"
        private const val MAX = 3_000
    }
}

/** Eticheta de calitate a unei variante: corecția ta, altfel ce s-a văzut la redare, altfel playlistul. */
fun qualityView(
    playlistId: Long,
    name: String,
    quality: Quality,
    observed: Map<CorrectionKey, ObservedVideo>,
    variants: VariantState,
): QualityView {
    val key = CorrectionKey(playlistId, name)
    val manual = variants.corrections[key]?.correction?.quality != null
    return ObservedQuality.view(quality, manual, observed[key])
}
