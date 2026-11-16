package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ro.ak.sportshub.core.common.player.AudioChoice
import ro.ak.sportshub.core.common.player.AudioDelay
import ro.ak.sportshub.core.common.player.VideoFit

/** Reglajele playerului ținute minte pe un canal (playlist + numele original, ca să rămână după importuri). */
data class ChannelPlayback(
    val audioDelayMs: Int = 0,
    val audio: AudioChoice? = null,
    val fit: VideoFit = VideoFit.FIT,
) {
    val isDefault: Boolean get() = audioDelayMs == 0 && audio == null && fit == VideoFit.FIT
}

/** Reglajele generale ale playerului, schimbate din meniul lui. */
data class PlayerPrefs(
    /** Modul de noapte (B4): compresia sunetului. */
    val night: Boolean = false,
    /** A5: playerul se închide singur la 5 minute după finalul meciului de pe ecran. */
    val stopAfterFinal: Boolean = false,
)

/**
 * Ce ții minte în player: întârzierea sunetului (B2), pista audio aleasă (B3) și încadrarea imaginii (B5) pe
 * canal, plus modul de noapte și oprirea la final. Fără adrese de stream: doar playlistul și numele canalului.
 */
class ChannelPlaybackStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _general = MutableStateFlow(PlayerPrefs(prefs.getBoolean(NIGHT, false), prefs.getBoolean(STOP_AFTER_FINAL, false)))
    val general: StateFlow<PlayerPrefs> = _general.asStateFlow()

    fun of(playlistId: Long, name: String): ChannelPlayback = runCatching {
        val o = JSONObject(prefs.getString(key(playlistId, name), null) ?: return ChannelPlayback())
        ChannelPlayback(
            audioDelayMs = AudioDelay.clamp(o.optInt("d", 0)),
            audio = AudioChoice.decode(o.optString("a", "")),
            fit = VideoFit.of(o.optString("z", "")),
        )
    }.getOrDefault(ChannelPlayback())

    @Synchronized
    fun update(playlistId: Long, name: String, transform: (ChannelPlayback) -> ChannelPlayback): ChannelPlayback {
        val next = transform(of(playlistId, name))
        val editor = prefs.edit()
        if (next.isDefault) {
            editor.remove(key(playlistId, name))
        } else {
            val o = JSONObject().put("d", next.audioDelayMs).put("z", next.fit.name)
            next.audio?.let { o.put("a", it.encode()) }
            editor.putString(key(playlistId, name), o.toString())
        }
        editor.apply()
        return next
    }

    @Synchronized
    fun updateGeneral(transform: (PlayerPrefs) -> PlayerPrefs) {
        val next = transform(_general.value)
        prefs.edit().putBoolean(NIGHT, next.night).putBoolean(STOP_AFTER_FINAL, next.stopAfterFinal).apply()
        _general.value = next
    }

    private fun key(playlistId: Long, name: String) = "c:$playlistId:$name"

    companion object {
        const val PREFS = "player_canale"
        private const val NIGHT = "night"
        private const val STOP_AFTER_FINAL = "stopAfterFinal"
    }
}
