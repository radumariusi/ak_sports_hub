package ro.ak.sportshub.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.core.common.player.PlaybackErrors
import ro.ak.sportshub.core.common.player.ReconnectBackoff
import ro.ak.sportshub.core.common.player.StreamLease
import ro.ak.sportshub.core.common.player.TrackLabels
import ro.ak.sportshub.core.common.player.Zapping
import ro.ak.sportshub.core.database.PlayableChannel
import ro.ak.sportshub.core.model.SourcePreferences

/** Ce arată playerul în bara de stare. */
sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data class Waiting(val message: String) : PlaybackStatus
    data object Connecting : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data class Retrying(val attempt: Int, val maxAttempts: Int, val seconds: Int, val reason: String) : PlaybackStatus
    data class Failed(val reason: String) : PlaybackStatus
}

data class PlayerUiState(
    val channel: PlayableChannel? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    /** „12 / 80” în grupul curent. */
    val position: String? = null,
    /** Rezoluția stream-ului care rulează (din decodor, nu dintr-o verificare separată). */
    val videoSize: String? = null,
    val audioTracks: Int = 0,
    val textTracks: Int = 0,
)

/** O pistă audio sau de subtitrare, pentru meniul playerului. */
data class TrackOption(
    val label: String,
    val selected: Boolean,
    val group: Tracks.Group?,
    val index: Int,
)

/**
 * Playerul: un singur ExoPlayer, un singur stream deodată.
 *
 * Reguli pentru „o conexiune pe playlist”:
 * - la schimbarea canalului, stream-ul vechi se oprește complet, apoi urmează o pauză scurtă
 *   ([SWITCH_GAP_MS]) ca serverul să închidă sesiunea, abia apoi pornește cel nou;
 * - cât rulează stream-ul, playerul ține permisul playlistului în `ConnectionGate` ([StreamLease]),
 *   deci actualizarea programată a aceluiași playlist e amânată;
 * - reconectarea încearcă din nou același canal, una câte una, cu pauze crescătoare;
 * - când aplicația trece în fundal sau ieși din player, stream-ul se oprește și permisul se eliberează.
 *
 * Totul rulează pe firul principal (cerința ExoPlayer).
 */
class PlayerController(context: Context, private val container: AppContainer) {

    private val scope = MainScope()
    private val dao = container.database.channels()
    private val history = container.playerHistory
    private val lease = StreamLease(container.gate, container.scope)
    private val backoff = ReconnectBackoff()
    private val preferences = SourcePreferences()

    /** Clientul HTTP al playerului: fără reîncercări ascunse, cu conexiunile închise la oprire. */
    private val http = container.http.newBuilder()
        .connectionPool(ConnectionPool(2, 15, TimeUnit.SECONDS))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val dataSources = OkHttpDataSource.Factory(http).setUserAgent(DEFAULT_USER_AGENT)

    val player: ExoPlayer = buildPlayer(context)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var zapIds: List<Long> = emptyList()
    private var playJob: Job? = null
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var stableJob: Job? = null
    private var lastStopAt = 0L
    private var forceHls = false
    private var stoppedInBackground = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = onState(playbackState)
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val text = if (videoSize.width > 0) "${videoSize.width}×${videoSize.height}" else null
                _state.update { it.copy(videoSize = text) }
            }
            override fun onTracksChanged(tracks: Tracks) {
                _state.update {
                    it.copy(
                        audioTracks = tracks.groups.filter { g -> g.type == C.TRACK_TYPE_AUDIO }.sumOf { g -> g.length },
                        textTracks = tracks.groups.filter { g -> g.type == C.TRACK_TYPE_TEXT }.sumOf { g -> g.length },
                    )
                }
            }
        })
    }

    // ---------- comenzi ----------

    fun play(channelId: Long) {
        cancelJobs()
        playJob = scope.launch { start(channelId) }
    }

    /** Reîncearcă canalul curent de la zero (după o eroare sau la cerere). */
    fun retry() {
        _state.value.channel?.let { play(it.id) }
    }

    /** CH+ (step = 1) sau CH− (step = −1) în grupul canalului curent. */
    fun zap(step: Int) {
        val current = _state.value.channel ?: return
        Zapping.neighbor(zapIds, current.id, step)?.takeIf { it != current.id }?.let(::play)
    }

    /** Canalul anterior; întoarce false dacă nu există. */
    fun playPrevious(): Boolean {
        val ref = history.previous ?: return false
        scope.launch {
            val id = history.resolve(ref)
            if (id == null) history.forget(ref) else play(id)
        }
        return true
    }

    val previousName: String? get() = history.previous?.name

    /** Canalul cu numărul tastat; dacă playlistul nu are numere, poziția din grupul curent. */
    fun playNumber(number: Int, onMissing: (String) -> Unit) {
        val current = _state.value.channel
        scope.launch {
            val id = dao.findByNumber(number, current?.playlistId ?: 0L)
                // Playlist fără numere de canal: numărul e poziția în grupul curent.
                ?: if (current?.number == null) zapIds.getOrNull(number - 1) else null
            if (id == null) onMissing("Nu există canalul $number.") else if (id != current?.id) play(id)
        }
    }

    /** Lista pentru panoul din stânga: canalele grupului curent. */
    suspend fun groupChannels() = _state.value.channel?.let { dao.channelsInGroup(it.groupId) }.orEmpty()

    /** Aplicația trece în fundal: oprim stream-ul și eliberăm conexiunea. */
    fun onBackground() {
        if (_state.value.channel == null) return
        stoppedInBackground = true
        cancelJobs()
        stopStream()
        _state.update { it.copy(status = PlaybackStatus.Idle) }
        container.scope.launch { lease.release() }
    }

    fun onForeground() {
        if (!stoppedInBackground) return
        stoppedInBackground = false
        retry()
    }

    fun release() {
        cancelJobs()
        scope.cancel()
        player.release()
        http.connectionPool.evictAll()
        container.scope.launch { lease.release() }
    }

    // ---------- piste ----------

    fun audioOptions(): List<TrackOption> = options(C.TRACK_TYPE_AUDIO) { f ->
        TrackLabels.audio(f.label, f.language, f.channelCount, f.sampleMimeType)
    }

    fun textOptions(): List<TrackOption> {
        val tracks = options(C.TRACK_TYPE_TEXT) { f -> TrackLabels.text(f.label, f.language) }
        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
            tracks.none { it.selected }
        return listOf(TrackOption("Fără subtitrări", off, null, -1)) + tracks.map { it.copy(selected = it.selected && !off) }
    }

    fun select(option: TrackOption, type: Int) {
        val builder = player.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun options(type: Int, label: (androidx.media3.common.Format) -> String): List<TrackOption> =
        player.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it, true) }.map { i ->
                TrackOption(label(g.getTrackFormat(i)), g.isTrackSelected(i), g, i)
            }
        }

    // ---------- interne ----------

    private suspend fun start(channelId: Long) {
        stopStream()
        backoff.reset()
        forceHls = false
        val channel = dao.playable(channelId)
        if (channel == null) {
            _state.update { it.copy(status = PlaybackStatus.Failed("Canalul nu mai există (playlistul s-a actualizat).")) }
            return
        }
        zapIds = dao.channelIdsInGroup(channel.groupId)
        _state.update {
            it.copy(
                channel = channel,
                status = PlaybackStatus.Connecting,
                position = Zapping.position(zapIds, channel.id),
                videoSize = null,
                audioTracks = 0,
                textTracks = 0,
            )
        }
        history.visit(channel)
        // Alegerile de piste ale canalului vechi nu se aplică noului stream.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        val pid = channel.playlistId.toString()
        if (lease.heldFor != pid) {
            _state.update { it.copy(status = PlaybackStatus.Waiting("Se așteaptă conexiunea playlistului…")) }
        }
        if (!lease.acquire(pid, LEASE_TIMEOUT_MS)) {
            _state.update {
                it.copy(status = PlaybackStatus.Failed("Playlistul e ocupat (se actualizează). Apasă OK peste câteva secunde."))
            }
            return
        }
        waitSwitchGap()
        prepare(channel)
    }

    private suspend fun waitSwitchGap() {
        val since = SystemClock.elapsedRealtime() - lastStopAt
        if (since < SWITCH_GAP_MS) delay(SWITCH_GAP_MS - since)
    }

    private fun prepare(channel: PlayableChannel) {
        dataSources.setUserAgent(channel.userAgent ?: DEFAULT_USER_AGENT)
        val item = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .apply { if (forceHls || isHls(channel.streamUrl)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        _state.update { it.copy(status = PlaybackStatus.Connecting) }
        player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()
    }

    private fun stopStream() {
        stallJob?.cancel()
        stableJob?.cancel()
        if (player.playbackState != Player.STATE_IDLE || player.mediaItemCount > 0) {
            player.stop()
            player.clearMediaItems()
            lastStopAt = SystemClock.elapsedRealtime()
        }
        // Conexiunile HTTP rămase deschise (HLS) se închid, ca serverul să vadă sesiunea încheiată.
        http.connectionPool.evictAll()
    }

    private fun onState(state: Int) {
        stallJob?.cancel()
        stableJob?.cancel()
        when (state) {
            Player.STATE_BUFFERING -> {
                if (_state.value.status == PlaybackStatus.Playing) {
                    _state.update { it.copy(status = PlaybackStatus.Buffering) }
                }
                stallJob = scope.launch {
                    delay(STALL_TIMEOUT_MS)
                    scheduleRetry("Stream-ul s-a blocat.")
                }
            }
            Player.STATE_READY -> {
                _state.update { it.copy(status = PlaybackStatus.Playing) }
                stableJob = scope.launch {
                    delay(STABLE_AFTER_MS)
                    backoff.reset()
                }
            }
            // Un stream live care „se termină” înseamnă că serverul a închis conexiunea.
            Player.STATE_ENDED -> scheduleRetry("Serverul a închis stream-ul.")
            else -> Unit
        }
    }

    private fun onError(error: PlaybackException) {
        if (_state.value.channel == null) return
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> if (!forceHls) {
                // Unele adrese HLS nu se termină în .m3u8: încercăm o dată ca HLS.
                forceHls = true
                retryNow()
                return
            }
        }
        val http = httpStatus(error)
        val reason = when {
            http != null -> PlaybackErrors.forHttpStatus(http)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Serverul nu răspunde."
            error.errorCode in 4000..4999 -> "Formatul stream-ului nu e suportat de acest televizor."
            else -> "Stream-ul s-a întrerupt."
        }
        if ((http != null && PlaybackErrors.isPermanent(http)) || error.errorCode in 4000..4999) {
            fail(reason)
        } else {
            scheduleRetry(reason)
        }
    }

    private fun retryNow() {
        val channel = _state.value.channel ?: return
        retryJob?.cancel()
        retryJob = scope.launch {
            stopStream()
            waitSwitchGap()
            prepare(channel)
        }
    }

    private fun scheduleRetry(reason: String) {
        val channel = _state.value.channel ?: return
        if (retryJob?.isActive == true) return
        val wait = backoff.next() ?: return fail(reason)
        retryJob = scope.launch {
            stopStream()
            var left = wait
            while (left > 0) {
                val seconds = ((left + 999) / 1000).toInt()
                _state.update {
                    it.copy(status = PlaybackStatus.Retrying(backoff.attempt, backoff.maxAttempts, seconds, reason))
                }
                val step = minOf(1_000L, left)
                delay(step)
                left -= step
            }
            waitSwitchGap()
            prepare(channel)
        }
    }

    private fun fail(reason: String) {
        cancelJobs()
        stopStream()
        _state.update { it.copy(status = PlaybackStatus.Failed(reason)) }
        // După renunțare, conexiunea rămâne liberă pentru actualizări.
        container.scope.launch { lease.release() }
    }

    private fun cancelJobs() {
        listOf(playJob, retryJob, stallJob, stableJob).forEach { it?.cancel() }
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        // Stream-urile TS de IPTV au des cadre-cheie lipsă sau marcate greșit.
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        // FFmpeg doar când televizorul nu are decodor (AC3, E-AC3, DTS…).
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources, extractors))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages(*preferences.preferredLanguages.toTypedArray())
                    .build()
            }
    }

    private companion object {
        const val SWITCH_GAP_MS = 700L
        const val LEASE_TIMEOUT_MS = 20_000L
        const val STALL_TIMEOUT_MS = 20_000L
        const val STABLE_AFTER_MS = 30_000L
        const val DEFAULT_USER_AGENT = "AKSportsHub/1.0 (Android TV)"

        fun isHls(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            return path.endsWith(".m3u8") || path.endsWith(".m3u")
        }

        fun httpStatus(error: PlaybackException): Int? {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
                cause = cause.cause
            }
            return null
        }
    }
}
