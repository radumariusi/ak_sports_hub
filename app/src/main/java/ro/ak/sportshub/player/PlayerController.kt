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
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.common.ChannelFamily
import ro.ak.sportshub.core.common.FailoverPlan
import ro.ak.sportshub.core.common.ObservedQuality
import ro.ak.sportshub.core.common.ObservedVideo
import ro.ak.sportshub.core.common.player.PlaybackErrors
import ro.ak.sportshub.core.common.player.ReconnectBackoff
import ro.ak.sportshub.core.common.player.StreamLease
import ro.ak.sportshub.core.common.player.TrackLabels
import ro.ak.sportshub.core.common.player.Zapping
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.database.PlayableChannel
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.core.playlist.Catchup

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
    /** Cadrele pe secundă ale stream-ului (din decodor), dacă stream-ul le declară. */
    val frameRate: Float? = null,
    /** Decodorul video folosit (de ex. „c2.amlogic.hevc.decoder”), pentru verificarea redării. */
    val decoder: String? = null,
    /** Cadrele aruncate de decodor de la pornirea stream-ului (sacadările se văd aici). */
    val droppedFrames: Int = 0,
    val audioTracks: Int = 0,
    val textTracks: Int = 0,
    /** Câte variante are canalul (aceeași cheie de grupare, din toate playlisturile active). */
    val variantCount: Int = 0,
    /** Mesaj scurt (de exemplu trecerea automată la altă variantă). */
    val notice: Notice? = null,
    /** Se redă din arhivă (catch-up), de la această oră; null = live. */
    val archiveFrom: Long? = null,
)

/** O porțiune din arhiva canalului (catch-up): de la [start] la [end], în ms UTC. */
data class Archive(val start: Long, val end: Long)

/** Un mesaj de afișat o dată; [id] deosebește două mesaje cu același text. */
data class Notice(val text: String, val id: Long = System.nanoTime())

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
 * - failover: după [FailoverPlan.ATTEMPTS_BEFORE_SWITCH] reîncercări eșuate (sau o eroare definitivă),
 *   trece la următoarea variantă a aceluiași canal, tot una câte una, cu stream-ul vechi oprit întâi;
 *   fiecare variantă se încearcă o singură dată, apoi playerul se oprește și eliberează conexiunea;
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
    private val variants = container.variants
    private val variantStore = container.variantStore

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

    /** Grupul din care ai pornit (CH+/CH− rămân în el, chiar dacă varianta e din alt grup). */
    private var zapGroupId = 0L
    /** Rândurile grupului: variantele aceluiași canal împreună (dacă gruparea e activă) sau câte unul. */
    private var zapFamilies: List<ChannelFamily<ChannelWithPlaylist>> = emptyList()
    private var zapGrouped = true
    private var failover: FailoverPlan<Long>? = null
    private var variantOrder: List<ChannelWithPlaylist> = emptyList()
    private var playJob: Job? = null
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var stableJob: Job? = null
    private var lastStopAt = 0L
    private var forceHls = false
    private var stoppedInBackground = false
    /** Arhiva cerută (catch-up) sau null pentru live. Zapping-ul și canalul anterior revin la live. */
    private var archive: Archive? = null
    /**
     * Arhiva Xtream pornește la minut fix: secundele rămase până la ora cerută se sar după pornire
     * (doar dacă stream-ul permite derularea; altfel pornește de la minutul întreg).
     */
    private var pendingSeekMs = 0L

    init {
        // Cadrele pe secundă măsurate din orele cadrelor redate, când stream-ul nu le declară (TS de IPTV):
        // fără ele, potrivirea frecvenței ecranului nu avea ce potrivi, iar 50 fps pe 60 Hz sacadează.
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            fpsMeter.add(presentationTimeUs)?.let { measured ->
                _state.update { if (it.frameRate == null) it.copy(frameRate = measured) else it }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = onState(playbackState)
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val text = if (videoSize.width > 0) "${videoSize.width}×${videoSize.height}" else null
                _state.update { it.copy(videoSize = text) }
                observeVideo()
            }
            override fun onTracksChanged(tracks: Tracks) {
                observeVideo()
                _state.update {
                    it.copy(
                        audioTracks = tracks.groups.filter { g -> g.type == C.TRACK_TYPE_AUDIO }.sumOf { g -> g.length },
                        textTracks = tracks.groups.filter { g -> g.type == C.TRACK_TYPE_TEXT }.sumOf { g -> g.length },
                    )
                }
            }
        })
        // Verificarea redării (informațiile din player): decodorul și cadrele aruncate, din ce raportează playerul oricum.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                _state.update { it.copy(decoder = decoderName) }
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                _state.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
            }
        })
    }

    // ---------- comenzi ----------

    /**
     * Pornește un canal ales de tine. [zapGroup] e grupul listei din care l-ai ales
     * (0 = grupul canalului); fără argument rămâne grupul curent.
     */
    fun play(channelId: Long, zapGroup: Long = zapGroupId, archive: Archive? = null) {
        this.archive = archive
        cancelJobs()
        playJob = scope.launch { start(channelId, zapGroup, failoverStep = false) }
    }

    /**
     * Salt în arhiva canalului curent: oprește stream-ul (live sau arhiva veche), apoi pornește arhiva
     * de la [start] la [end]. Tot o singură conexiune, ca la schimbarea canalului.
     */
    fun jumpTo(start: Long, end: Long) {
        val c = _state.value.channel ?: return
        play(c.id, archive = Archive(start, end.coerceAtLeast(start + MIN_ARCHIVE_MS)))
    }

    /** Înapoi la live pe canalul curent (din reluare). */
    fun goLive() {
        val c = _state.value.channel ?: return
        play(c.id, archive = null)
    }

    /** Arhiva care rulează acum (null = live). */
    val currentArchive: Archive? get() = archive

    /**
     * Ora (ms UTC) care se vede acum în arhivă: începutul cererii (minutul întreg) plus poziția redată.
     * Null la live sau înainte de pornire.
     */
    fun watchedAt(): Long? {
        val a = archive ?: return null
        if (player.playbackState == Player.STATE_IDLE) return null
        return floorMinute(a.start) + player.currentPosition.coerceAtLeast(0)
    }

    /** Reîncearcă canalul curent de la zero (după o eroare sau la cerere). */
    fun retry() {
        _state.value.channel?.let { play(it.id, archive = archive) }
    }

    /** CH+ (step = 1) sau CH− (step = −1) în grupul canalului curent. */
    fun zap(step: Int) {
        val current = _state.value.channel ?: return
        val keys = zapFamilies.map { it.key }
        val key = Zapping.neighbor(keys, zapKey(current), step)?.takeIf { it != zapKey(current) } ?: return
        val family = zapFamilies.first { it.key == key }
        scope.launch {
            // Canal grupat: pornește varianta preferată sau cea mai bună dintre toate playlisturile.
            val id = if (zapGrouped) variants.representative(key)?.id ?: family.members.first().id else family.members.first().id
            play(id)
        }
    }

    /** Canalul anterior; întoarce false dacă nu există. */
    fun playPrevious(): Boolean {
        val ref = history.previous ?: return false
        scope.launch {
            val id = history.resolve(ref)
            if (id == null) history.forget(ref) else play(id, zapGroup = 0)
        }
        return true
    }

    val previousName: String? get() = history.previous?.name

    /** Canalul cu numărul tastat; dacă playlistul nu are numere, poziția din grupul curent. */
    fun playNumber(number: Int, onMissing: (String) -> Unit) {
        val current = _state.value.channel
        scope.launch {
            val byNumber = dao.findByNumber(number, current?.playlistId ?: 0L)
            // Playlist fără numere de canal: numărul e poziția în grupul curent.
            val byPosition = if (byNumber == null && current?.number == null) {
                zapFamilies.getOrNull(number - 1)?.let { f ->
                    if (zapGrouped) variants.representative(f.key)?.id ?: f.members.first().id else f.members.first().id
                }
            } else {
                null
            }
            val id = byNumber ?: byPosition
            when {
                id == null -> onMissing("Nu există canalul $number.")
                id == current?.id -> Unit
                byNumber != null -> play(id, zapGroup = 0)
                else -> play(id)
            }
        }
    }

    /**
     * Lista pentru panoul din stânga: rândurile grupului curent (la canalele grupate, varianta care
     * pornește la OK) și poziția canalului curent în ea.
     */
    suspend fun groupRows(): Pair<List<ChannelWithPlaylist>, Int> {
        val current = _state.value.channel ?: return emptyList<ChannelWithPlaylist>() to -1
        val families = zapFamilies
        val best = if (zapGrouped) variants.families(families.map { it.key }) else emptyMap()
        val rows = families.map { f -> best[f.key]?.firstOrNull() ?: f.members.first() }
        return rows to families.indexOfFirst { it.key == zapKey(current) }
    }

    /** Variantele canalului curent, în ordinea de pornire. */
    suspend fun currentVariants(): List<ChannelWithPlaylist> =
        _state.value.channel?.let { variants.variants(it.groupKey) }.orEmpty()

    /** Varianta curentă devine cea preferată a canalului. */
    fun preferCurrent(): String? {
        val c = _state.value.channel ?: return null
        val row = variantOrder.firstOrNull { it.id == c.id } ?: return null
        variants.setPreferred(row)
        return "${c.name} e acum varianta preferată."
    }

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

    // ---------- rezoluția văzută la redare ----------

    private val fpsMeter = ro.ak.sportshub.core.common.FpsMeter()

    /** Cea mai bună rezoluție văzută în sesiunea curentă (la HLS adaptiv, primele secunde pot fi mai mici). */
    private var sessionBest: ObservedVideo? = null

    /**
     * Notează rezoluția și cadrele pe care le redă playerul oricum (din formatul decodat), pe variantă.
     * Nicio cerere în plus: eticheta variantei primește „verificat” sau eticheta reală, iar ordinea o folosește.
     */
    private fun observeVideo() {
        val c = _state.value.channel ?: return
        val f = player.videoFormat ?: return
        val size = player.videoSize
        val w = if (f.width > 0) f.width else size.width
        val h = if (f.height > 0) f.height else size.height
        if (w <= 0 || h <= 0) return
        val fps = ObservedQuality.cleanFps(f.frameRate) ?: fpsMeter.fps
        if (fps != null && fps != _state.value.frameRate) _state.update { it.copy(frameRate = fps) }
        val seen = ObservedVideo(w, h, fps, System.currentTimeMillis())
        val best = sessionBest
        if (best != null && best.height * best.width > w * h) return
        sessionBest = seen
        val stored = container.observedVideo.of(c.playlistId, c.name)
        downgradeJob?.cancel()
        if (stored != null && seen.quality.rank < stored.quality.rank) {
            // O calitate mai mică decât cea notată se scrie doar dacă rămâne așa un timp: la HLS adaptiv primele
            // secunde vin adesea mai mici, iar un canal schimbat repede nu trebuie să-și piardă eticheta verificată.
            downgradeJob = scope.launch {
                kotlinx.coroutines.delay(DOWNGRADE_CONFIRM_MS)
                if (sessionBest === seen && _state.value.channel?.id == c.id) container.observedVideo.record(c.playlistId, c.name, seen)
            }
            return
        }
        container.observedVideo.record(c.playlistId, c.name, seen)
    }

    private var downgradeJob: kotlinx.coroutines.Job? = null

    // ---------- interne ----------

    private fun zapKey(c: PlayableChannel): String = if (zapGrouped) c.groupKey else c.id.toString()

    private suspend fun loadZapList(channel: PlayableChannel, zapGroup: Long) {
        zapGrouped = variantStore.current.groupVariants
        zapGroupId = if (zapGroup > 0) zapGroup else channel.groupId
        var rows = dao.channelsInGroup(zapGroupId)
        if (rows.isEmpty() && zapGroupId != channel.groupId) {
            // Grupul de pornire a dispărut (playlistul s-a actualizat): rămânem în grupul canalului.
            zapGroupId = channel.groupId
            rows = dao.channelsInGroup(zapGroupId)
        }
        zapFamilies = ChannelFamilies.collapse(rows) { if (zapGrouped) it.groupKey else it.id.toString() }
    }

    private suspend fun start(channelId: Long, zapGroup: Long, failoverStep: Boolean) {
        stopStream()
        backoff.reset()
        forceHls = false
        val channel = dao.playable(channelId)
        if (channel == null) {
            _state.update { it.copy(status = PlaybackStatus.Failed("Canalul nu mai există (playlistul s-a actualizat).")) }
            return
        }
        if (failoverStep) {
            failover?.moveTo(channel.id)
        } else {
            loadZapList(channel, zapGroup)
            variantOrder = variants.variants(channel.groupKey)
            failover = FailoverPlan(variantOrder.map { it.id }, channel.id)
        }
        sessionBest = null
        fpsMeter.reset()
        _state.update {
            it.copy(
                channel = channel,
                status = PlaybackStatus.Connecting,
                position = Zapping.position(zapFamilies.map { f -> f.key }, zapKey(channel)),
                videoSize = null,
                frameRate = null,
                decoder = null,
                droppedFrames = 0,
                audioTracks = 0,
                textTracks = 0,
                variantCount = variantOrder.size,
                archiveFrom = archive?.start,
            )
        }
        if (archive != null && streamUrl(channel) == null) {
            fail("Canalul nu are arhivă (catch-up merge doar pe serverele Xtream care o oferă).")
            return
        }
        history.visit(channel)
        // Alegerile de piste ale canalului vechi nu se aplică noului stream.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        val pid = channel.playlistId.toString()
        if (lease.heldFor != pid) {
            // O descărcare EPG de pe serverul furnizorului cedează locul playerului (se reia mai târziu).
            container.epg.yieldConnection(channel.playlistId)
            _state.update { it.copy(status = PlaybackStatus.Waiting("Se așteaptă conexiunea playlistului…")) }
        }
        if (!lease.acquire(pid, LEASE_TIMEOUT_MS)) {
            // Altă variantă poate fi pe un playlist liber.
            failOrSwitch("Playlistul e ocupat (se actualizează). Apasă OK peste câteva secunde.", streamFailed = false)
            return
        }
        waitSwitchGap()
        prepare(channel)
    }

    private suspend fun waitSwitchGap() {
        val since = SystemClock.elapsedRealtime() - lastStopAt
        if (since < SWITCH_GAP_MS) delay(SWITCH_GAP_MS - since)
    }

    /** Adresa de redat: live sau arhiva cerută (în fusul orar al serverului). Conține parola: nu se loghează. */
    private fun streamUrl(channel: PlayableChannel): String? {
        val a = archive ?: return channel.streamUrl
        if ((channel.catchupDays ?: 0) <= 0) return null
        return Catchup.archiveUrl(channel.streamUrl, a.start, Catchup.minutes(a.start, a.end), container.catchup.zoneFor(channel.playlistId))
    }

    private fun prepare(channel: PlayableChannel) {
        val url = streamUrl(channel) ?: return fail("Canalul nu are arhivă.")
        dataSources.setUserAgent(channel.userAgent ?: DEFAULT_USER_AGENT)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (forceHls || isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        _state.update { it.copy(status = PlaybackStatus.Connecting) }
        pendingSeekMs = archive?.let { it.start - floorMinute(it.start) } ?: 0L
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
                _state.value.channel?.let(variants::markWorked)
                if (pendingSeekMs > 0) {
                    if (player.isCurrentMediaItemSeekable) player.seekTo(pendingSeekMs)
                    pendingSeekMs = 0
                }
                stableJob = scope.launch {
                    delay(STABLE_AFTER_MS)
                    backoff.reset()
                }
            }
            // Un stream live care „se termină” înseamnă că serverul a închis conexiunea.
            Player.STATE_ENDED -> if (archive != null) fail("Înregistrarea s-a terminat.") else scheduleRetry("Serverul a închis stream-ul.")
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
            failOrSwitch(reason)
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
        if (backoff.attempt >= FailoverPlan.ATTEMPTS_BEFORE_SWITCH && nextVariant() != null) return failOrSwitch(reason)
        val wait = backoff.next() ?: return failOrSwitch(reason)
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

    /** Din arhivă nu se trece la alte variante (pot să nu aibă aceeași arhivă). */
    private fun nextVariant(): Long? = if (variantStore.current.autoFailover && archive == null) failover?.next() else null

    /**
     * Varianta curentă nu merge: trece la următoarea variantă neîncercată a canalului (una câte una),
     * sau se oprește dacă nu mai există. [streamFailed] = false când doar playlistul era ocupat.
     */
    private fun failOrSwitch(reason: String, streamFailed: Boolean = true) {
        val channel = _state.value.channel ?: return
        if (streamFailed) variants.markFailed(channel)
        val next = nextVariant() ?: return fail(reason)
        cancelJobs()
        stopStream()
        val target = variantOrder.firstOrNull { it.id == next }
        val label = target?.let { "${it.name} (${variants.effectiveQuality(it).label}${it.language?.let { l -> ", " + l.uppercase() }.orEmpty()})" }
        _state.update {
            it.copy(
                status = PlaybackStatus.Waiting("Trec la altă variantă…"),
                notice = Notice("$reason Încerc altă variantă: ${label ?: "următoarea"}."),
            )
        }
        playJob = scope.launch { start(next, zapGroupId, failoverStep = true) }
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
        const val DOWNGRADE_CONFIRM_MS = 20_000L
        const val DEFAULT_USER_AGENT = "AKSportsHub/1.0 (Android TV)"
        const val MIN_ARCHIVE_MS = 10 * 60_000L

        fun floorMinute(ms: Long): Long = ms - Math.floorMod(ms, 60_000L)

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
