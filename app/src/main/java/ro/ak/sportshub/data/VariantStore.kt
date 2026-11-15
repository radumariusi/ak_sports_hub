package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.model.ChannelCorrection
import ro.ak.sportshub.core.model.ChannelLabels
import ro.ak.sportshub.core.model.Quality

/** O corecție manuală, cu valorile din playlist de dinainte (ca s-o poți anula pe loc). */
data class CorrectionEntry(val correction: ChannelCorrection, val original: ChannelLabels)

/** Setările și alegerile pentru variantele canalelor. */
data class VariantState(
    /** În Live TV, variantele aceluiași canal apar ca un singur rând. */
    val groupVariants: Boolean = true,
    /** Dacă varianta curentă nu pornește, playerul trece singur la următoarea. */
    val autoFailover: Boolean = true,
    /** Ecranul trece la frecvența potrivită cadrelor stream-ului (50/60 Hz). */
    val matchFrameRate: Boolean = false,
    /**
     * Imaginea în SurfaceView (fluidă: decodorul scrie direct pe ecran, potrivește cadrele; recomandată la 4K),
     * altfel în TextureView (compatibilă: pe unele televizoare SurfaceView rămânea negru).
     */
    val smoothVideo: Boolean = false,
    /** Mărimea textului din toată aplicația (0,78 / 0,85 / 0,92 / 1). */
    val textScale: Float = 0.85f,
    /** Live TV ca ghid în grilă (canale × ore), în loc de listă. */
    val guideGrid: Boolean = false,
    /** Varianta preferată, pe cheia de grupare (aceeași în toate playlisturile). */
    val preferred: Map<String, ChannelRef> = emptyMap(),
    /** Corecțiile manuale, pe playlist și numele original al canalului. */
    val corrections: Map<CorrectionKey, CorrectionEntry> = emptyMap(),
    /** Variantele care n-au pornit ultima oară când le-ai folosit (momentul eșecului). */
    val failures: Map<ChannelRef, Long> = emptyMap(),
)

data class CorrectionKey(val playlistId: Long, val name: String)

/**
 * Alegerile tale despre variante, în preferințele aplicației (nu în baza de canale, care se
 * reface la fiecare import). Nu conțin adrese de stream sau date de acces.
 */
class VariantStore(context: Context) {

    private val prefs = context.getSharedPreferences("variants", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VariantState> = _state.asStateFlow()
    val current: VariantState get() = _state.value

    @Synchronized
    fun update(transform: (VariantState) -> VariantState) {
        val next = transform(_state.value)
        prefs.edit().putString(KEY, write(next)).apply()
        _state.value = next
    }

    /** Corecțiile unui playlist, după numele original (pentru import). */
    fun correctionsFor(playlistId: Long): Map<String, ChannelCorrection> =
        current.corrections.filterKeys { it.playlistId == playlistId }
            .map { (k, v) -> k.name to v.correction }
            .toMap()

    private fun read(): VariantState {
        val json = prefs.getString(KEY, null) ?: return VariantState()
        return runCatching {
            val o = JSONObject(json)
            VariantState(
                groupVariants = o.optBoolean("groupVariants", true),
                autoFailover = o.optBoolean("autoFailover", true),
                matchFrameRate = o.optBoolean("matchFrameRate", false),
                smoothVideo = o.optBoolean("smoothVideo", false),
                textScale = o.optDouble("textScale2", 0.85).toFloat(),
                guideGrid = o.optBoolean("guideGrid", false),
                preferred = o.optJSONArray("preferred").objects().associate { it.getString("key") to it.ref() },
                corrections = o.optJSONArray("corrections").objects().associate { e ->
                    val c = e.getJSONObject("correction")
                    val orig = e.getJSONObject("original")
                    CorrectionKey(e.getLong("playlist"), e.getString("name")) to CorrectionEntry(
                        correction = ChannelCorrection(
                            groupKey = c.optStringOrNull("groupKey"),
                            quality = c.optStringOrNull("quality")?.let(::quality),
                            language = c.optStringOrNull("language"),
                        ),
                        original = ChannelLabels(
                            groupKey = orig.getString("groupKey"),
                            quality = quality(orig.getString("quality")) ?: Quality.UNKNOWN,
                            language = orig.optStringOrNull("language"),
                        ),
                    )
                },
                failures = o.optJSONArray("failures").objects().associate { it.ref() to it.getLong("at") },
            )
        }.getOrDefault(VariantState())
    }

    private fun write(s: VariantState): String = JSONObject().apply {
        put("groupVariants", s.groupVariants)
        put("autoFailover", s.autoFailover)
        put("matchFrameRate", s.matchFrameRate)
        put("smoothVideo", s.smoothVideo)
        put("textScale2", s.textScale.toDouble())
        put("guideGrid", s.guideGrid)
        put("preferred", JSONArray().apply { s.preferred.forEach { (k, r) -> put(r.json().put("key", k)) } })
        put(
            "corrections",
            JSONArray().apply {
                s.corrections.forEach { (k, e) ->
                    put(
                        JSONObject()
                            .put("playlist", k.playlistId)
                            .put("name", k.name)
                            .put(
                                "correction",
                                JSONObject()
                                    .putOpt("groupKey", e.correction.groupKey)
                                    .putOpt("quality", e.correction.quality?.name)
                                    .putOpt("language", e.correction.language),
                            )
                            .put(
                                "original",
                                JSONObject()
                                    .put("groupKey", e.original.groupKey)
                                    .put("quality", e.original.quality.name)
                                    .putOpt("language", e.original.language),
                            ),
                    )
                }
            },
        )
        put("failures", JSONArray().apply { s.failures.forEach { (r, at) -> put(r.json().put("at", at)) } })
    }.toString()

    private companion object {
        const val KEY = "state"

        fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

        fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

        fun JSONObject.ref() = ChannelRef(getLong("playlist"), getString("name"), optString("group"))

        fun ChannelRef.json(): JSONObject =
            JSONObject().put("playlist", playlistId).put("name", name).put("group", groupName)

        fun quality(name: String): Quality? = runCatching { Quality.valueOf(name) }.getOrNull()
    }
}
