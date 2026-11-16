package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.common.PlaybackHealth
import ro.ak.sportshub.core.common.PlaybackSession
import ro.ak.sportshub.core.common.SourceHealth

/**
 * Cum a mers redarea fiecărei variante (C1) și limbile pistelor audio văzute (C2), pe playlist + numele original,
 * ca rezoluția văzută ([ObservedVideoStore]): doar din ce face playerul oricum, fără nicio cerere în plus.
 * Stau în preferințe (baza de canale se reface la fiecare import), fără adrese de stream.
 */
class PlaybackHealthStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<CorrectionKey, PlaybackHealth>> = _state.asStateFlow()
    val current: Map<CorrectionKey, PlaybackHealth> get() = _state.value

    fun of(playlistId: Long, name: String): PlaybackHealth? = _state.value[CorrectionKey(playlistId, name)]

    /** 0 = stabilă, 1 = nu se știe, 2 = instabilă (pentru ordinea surselor și a variantelor). */
    fun rank(playlistId: Long, name: String): Int = of(playlistId, name)?.rank ?: 1

    /** O sesiune s-a încheiat (alt canal, altă variantă, oprire): media se actualizează. */
    @Synchronized
    fun session(playlistId: Long, name: String, s: PlaybackSession) {
        val key = CorrectionKey(playlistId, name)
        put(key, SourceHealth.after(_state.value[key], s, System.currentTimeMillis()))
    }

    /** Limbile pistelor audio ale stream-ului (doar dacă diferă de ce era notat). */
    @Synchronized
    fun languages(playlistId: Long, name: String, languages: List<String>) {
        if (languages.isEmpty()) return
        val key = CorrectionKey(playlistId, name)
        val prev = _state.value[key] ?: PlaybackHealth()
        if (prev.languages == languages) return
        put(key, prev.copy(languages = languages, at = System.currentTimeMillis()))
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
        _state.value = emptyMap()
    }

    private fun put(key: CorrectionKey, value: PlaybackHealth) {
        val next = (_state.value + (key to value)).entries
            .sortedByDescending { it.value.at }.take(MAX).associate { it.key to it.value }
        prefs.edit().putString(KEY, write(next)).apply()
        _state.value = next
    }

    private fun read(): Map<CorrectionKey, PlaybackHealth> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val langs = o.optJSONArray("l")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                CorrectionKey(o.getLong("p"), o.getString("n")) to PlaybackHealth(
                    plays = o.optInt("ok"),
                    fails = o.optInt("ko"),
                    startMs = o.optInt("s"),
                    stallsPerHour = o.optDouble("r", 0.0).toFloat(),
                    languages = langs,
                    at = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun write(map: Map<CorrectionKey, PlaybackHealth>): String = JSONArray().apply {
        map.forEach { (k, v) ->
            put(
                JSONObject().put("p", k.playlistId).put("n", k.name).put("ok", v.plays).put("ko", v.fails)
                    .put("s", v.startMs).put("r", v.stallsPerHour.toDouble()).put("at", v.at)
                    .apply { if (v.languages.isNotEmpty()) put("l", JSONArray(v.languages)) },
            )
        }
    }.toString()

    companion object {
        const val PREFS = "sanatate_surse"
        private const val KEY = "seen"
        private const val MAX = 3_000
    }
}

/**
 * Limba unei variante (C2): corecția ta are prioritate; altfel limba pistelor audio văzută la redare, dacă diferă
 * de cea din playlist ([SourceHealth.language]); altfel limba din playlist.
 */
fun effectiveLanguage(
    playlistId: Long,
    name: String,
    language: String?,
    health: Map<CorrectionKey, PlaybackHealth>,
    variants: VariantState,
): String? {
    val key = CorrectionKey(playlistId, name)
    if (variants.corrections[key]?.correction?.language != null) return language
    return SourceHealth.language(language, health[key]?.languages.orEmpty())
}
