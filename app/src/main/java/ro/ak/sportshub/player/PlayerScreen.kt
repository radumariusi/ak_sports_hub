package ro.ak.sportshub.player

import ro.ak.sportshub.ui.components.ListPadding
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import ro.ak.sportshub.core.common.player.DropAdapter
import ro.ak.sportshub.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.playlist.Catchup
import ro.ak.sportshub.core.sports.DetailsSchedule
import ro.ak.sportshub.core.sports.KickoffNotices
import ro.ak.sportshub.core.sports.AutoSwitch
import ro.ak.sportshub.core.sports.BandItem
import ro.ak.sportshub.core.sports.GoalAlerts
import ro.ak.sportshub.core.sports.HalftimeZap
import ro.ak.sportshub.core.sports.ScoreBand
import ro.ak.sportshub.core.sports.Scoreboard
import ro.ak.sportshub.data.orderedSources
import ro.ak.sportshub.core.sports.ArchiveJumps
import ro.ak.sportshub.core.sports.AutoStop
import ro.ak.sportshub.core.common.player.AudioDelay
import ro.ak.sportshub.core.common.player.VideoFit
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchClock
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.PanelView
import ro.ak.sportshub.core.sports.ReplayView
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.data.CatchupStore
import ro.ak.sportshub.data.MatchDetailsState
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.data.StreamDelays
import ro.ak.sportshub.data.bestSource
import ro.ak.sportshub.data.hasSource
import ro.ak.sportshub.data.matchOn
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.core.common.player.NumberInput
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.epg.NowNext
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.ProgressLine
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.epg.ScheduleList
import ro.ak.sportshub.ui.epg.formatRange
import ro.ak.sportshub.ui.epg.formatTime
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

private enum class Panel { None, Channels, Menu, Audio, Subtitles, Schedule, Variants, Match, Quick, Sources }

/** Ferestrele de alegere din panoul meciului. */
private enum class PanelDialog { DELAY, OFFSET, AUDIO_DELAY, FIT, AUTO_STOP }

private val OkKeys = setOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
private const val KICKOFF_NOTICE_MS = 15_000L
private const val KICKOFF_CHECK_MS = 30_000L

private const val INFO_MS = 5_000L
private const val NUMBER_COMMIT_MS = 1_500L
internal val Overlay = Color(0xEB0B0F14)
private val NavigationKeys = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

/**
 * Playerul pe tot ecranul. Telecomanda:
 * ▲ / CH+ canalul următor, ▼ / CH− canalul anterior din grup, ◀ lista canalelor grupului,
 * ▶ / Meniu pistele audio, subtitrările și restul opțiunilor, OK informații (sau reîncearcă după o eroare),
 * Ghid / EPG programul canalului,
 * cifrele numărul canalului, butonul „ultimul canal” comută între ultimele două canale, Back iese.
 * OK lung: meniul rapid (meciurile tale de acum, alte meciuri live, canalul anterior).
 * Când pe canal e un meci ([matchId] sau găsit după sursele lui), meniul are și panoul meciului.
 */
@Composable
fun PlayerScreen(
    channelId: Long,
    zapGroup: Long = 0L,
    archive: Archive? = null,
    matchId: String? = null,
    onExit: () -> Unit,
) {
    val container = appContainer()
    val context = LocalContext.current
    val controller = remember { PlayerController(context.applicationContext, container) }
    // Cât e playerul pe ecran, calculele grele din fundal (Sports Hub, actualizări EPG / playlist) așteaptă:
    // toată puterea televizorului merge la redare (altfel 4K-ul sacadează).
    DisposableEffect(controller) {
        container.playing.value = true
        onDispose {
            container.playing.value = false
            controller.release()
        }
    }
    LaunchedEffect(channelId, archive) { controller.play(channelId, zapGroup, archive) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        container.playing.value = false
        controller.onBackground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        container.playing.value = true
        controller.onForeground()
    }

    val state by controller.state.collectAsStateWithLifecycle()
    // Frecvența ecranului după cadrele stream-ului (setare, oprită implicit).
    val variantSettings by container.variantStore.state.collectAsStateWithLifecycle()
    // Adaptarea la sacadare: dacă stream-ul pierde cadre mereu (nu doar la pornire), playerul încearcă singur
    // potrivirea frecvenței și imaginea fluidă, păstrează ce ajută și ține minte pentru canal (cât rulează aplicația).
    val adaptKey = state.channel?.let { it.playlistId.toString() + "\u0000" + it.name }
    val hostView = androidx.compose.ui.platform.LocalView.current
    fun displayHz(): Float? = hostView.display?.refreshRate
    fun displayModes(): String = hostView.display?.supportedModes.orEmpty()
        .joinToString(" ") { "${it.physicalWidth}x${it.physicalHeight}@${"%.2f".format(java.util.Locale.US, it.refreshRate)}" }
    val adapter = remember(adaptKey) { DropAdapter() }
    var adaptActive by remember(adaptKey) { mutableStateOf(adaptKey?.let { AdaptMemory.steps[it] }.orEmpty()) }
    var adaptNote by remember { mutableStateOf<String?>(null) }
    val currentSettings by rememberUpdatedState(variantSettings)
    LaunchedEffect(adaptKey) {
        if (adaptKey == null) return@LaunchedEffect
        val remembered = AdaptMemory.steps[adaptKey].orEmpty()
        while (true) {
            delay(2_500)
            val s = controller.state.value
            if (s.videoSize == null || s.status != PlaybackStatus.Playing) continue
            val v = currentSettings
            val surfaceNow = (v.smoothVideo || DropAdapter.Step.SURFACE_VIEW in adaptActive) && DropAdapter.Step.TEXTURE_VIEW !in adaptActive
            val available = buildSet {
                if (s.frameRate != null && !v.matchFrameRate) add(DropAdapter.Step.MATCH_RATE)
                if (!v.smoothVideo) add(DropAdapter.Step.SURFACE_VIEW)
                if (surfaceNow) add(DropAdapter.Step.TUNNELING)
                if (v.matchFrameRate) add(DropAdapter.Step.NO_RATE_MATCH)
                if (v.smoothVideo && DropAdapter.Step.TUNNELING !in adaptActive) add(DropAdapter.Step.TEXTURE_VIEW)
            } - remembered
            val hz = displayHz()
            val judder = s.frameRate != null && hz != null && PlaybackCheck.mismatch(s.frameRate, hz)
            if (adapter.sample(s.droppedFrames, available, judder)) adaptActive = remembered + adapter.active
            if (adapter.kept.isNotEmpty()) AdaptMemory.steps[adaptKey] = remembered + adapter.kept
            adapter.takeNote()?.let { note ->
                adaptNote = note
                container.diagnosticLog.log("player", "adaptare: $note")
            }
        }
    }
    // Jurnalul playerului (fără numele canalului sau adrese): la 10 s, ce se vede în informații plus ce s-a schimbat.
    LaunchedEffect(adaptKey) {
        if (adaptKey == null) return@LaunchedEffect
        var lastDropped = 0
        var lastRebuffers = 0
        val modes = displayModes()
        container.diagnosticLog.log("player", "pornire · ecran ${displayHz()?.let { "%.2f".format(java.util.Locale.US, it) } ?: "?"} Hz · moduri: $modes")
        while (true) {
            delay(10_000)
            val s = controller.state.value
            if (s.videoSize == null) continue
            val v = currentSettings
            val dropped = s.droppedFrames - lastDropped
            val rebuffers = s.rebuffers - lastRebuffers
            lastDropped = s.droppedFrames
            lastRebuffers = s.rebuffers
            container.diagnosticLog.log(
                "player",
                listOf(
                    "${s.videoSize} ${s.frameRate?.let { "%.2f".format(java.util.Locale.US, it) } ?: "?"} fps",
                    s.videoCodec ?: "?",
                    "video ${s.decoder ?: "?"}",
                    "audio ${s.audioDecoder ?: "?"}",
                    "ecran ${displayHz()?.let { "%.2f".format(java.util.Locale.US, it) } ?: "?"} Hz",
                    "+$dropped cadre pierdute (total ${s.droppedFrames})",
                    "+$rebuffers încărcări",
                    "stare ${s.status.javaClass.simpleName}",
                    "setări fluidă=${v.smoothVideo} frecvență=${v.matchFrameRate}",
                    "adaptare=${adaptActive.joinToString(",") { it.name }.ifEmpty { "-" }}",
                ).joinToString(" · "),
            )
        }
    }
    LaunchedEffect(adaptNote) {
        if (adaptNote != null) {
            delay(6_000)
            adaptNote = null
        }
    }
    val rateOn = (variantSettings.matchFrameRate || DropAdapter.Step.MATCH_RATE in adaptActive) && DropAdapter.Step.NO_RATE_MATCH !in adaptActive
    val surfaceOn = (variantSettings.smoothVideo || DropAdapter.Step.SURFACE_VIEW in adaptActive) && DropAdapter.Step.TEXTURE_VIEW !in adaptActive
    FrameRateMatch(state.frameRate, rateOn)
    LaunchedEffect(surfaceOn, DropAdapter.Step.TUNNELING in adaptActive) {
        controller.setTunneling(surfaceOn && DropAdapter.Step.TUNNELING in adaptActive)
    }
    var panel by remember { mutableStateOf(Panel.None) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoToken by remember { mutableIntStateOf(0) }
    val numberInput = remember { NumberInput() }
    var numberText by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // ---------- meciul de pe ecran ----------
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val sportsSettings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val details = container.matchDetails
    val detailsStates by details.states.collectAsStateWithLifecycle()
    val revealed by details.revealed.collectAsStateWithLifecycle()
    /** Meciul ales (din fereastra meciului sau din meniul rapid); rămâne cât canalul e una din sursele lui. */
    var requestedMatch by remember { mutableStateOf(matchId) }
    var panelDialog by remember { mutableStateOf<PanelDialog?>(null) }
    var kickoff by remember { mutableStateOf<MatchEntry?>(null) }
    /** OK apăsat pe player (fără panou): scurt = informații, ținut = meniul rapid. */
    var okHeld by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(2_000)
        }
    }
    val minute = now / 60_000
    // Ce arată acum EPG-ul canalului: dacă e un meci din listă, canalul se leagă de el (sursa „pleacă” la meciul real).
    val onAir by produceState<Programme?>(null, state.channel?.id, minute) {
        value = state.channel?.id?.let { id -> runCatching { container.epg.nowNext(listOf(id), System.currentTimeMillis())[id]?.now }.getOrNull() }
    }
    val linked: MatchEntry? = remember(snapshot, state.channel?.groupKey, state.channel?.id, requestedMatch, state.archiveFrom, minute, onAir) {
        val key = state.channel?.groupKey ?: return@remember null
        val ch = state.channel?.id
        if (state.archiveFrom == null) {
            onAir?.let { p ->
                ro.ak.sportshub.core.sports.OnAirMatch.find(p.title, p.subtitle, p.category, System.currentTimeMillis(), snapshot.matches.map { it.match })
            }?.let { m -> snapshot.matches.firstOrNull { it.match.id == m.id } }?.let { return@remember it }
        }
        requestedMatch?.let { id -> snapshot.matches.firstOrNull { it.match.id == id && it.hasSource(key, ch) } }
            ?: snapshot.matchOn(key, state.archiveFrom ?: System.currentTimeMillis(), ch)
    }
    val linkedMatch = linked?.match
    val replay = state.archiveFrom != null
    val detailsState = linkedMatch?.let { detailsStates[it.id] } ?: MatchDetailsState()
    val spoilerHidden = linkedMatch != null &&
        Spoilers.hide(sportsSettings.noSpoilers, linkedMatch.id in revealed, if (replay) MatchStatus.LIVE else linkedMatch.status)
    val panelView: PanelView? = remember(linkedMatch, detailsState, now, replay, sportsSettings.streamDelaySeconds) {
        val m = linkedMatch ?: return@remember null
        if (replay) {
            ReplayView.at(m, detailsState.details, controller.watchedAt() ?: state.archiveFrom ?: m.start)
        } else {
            details.delayedView(m, now, sportsSettings.streamDelaySeconds * 1_000L)
        }
    }

    // Datele meciului: pre-meci cât e deschis panoul; live la un minut cât se joacă meciul de pe ecran, ca tabela de
    // scor să nu rămână la scorul și minutul primei cereri (o cerere ESPN pe minut, fără conexiunea playlistului).
    LaunchedEffect(linkedMatch?.id, panel == Panel.Match, replay) {
        val m = linkedMatch ?: return@LaunchedEffect
        if (replay && panel != Panel.Match) return@LaunchedEffect
        if (panel == Panel.Match) details.refreshPreMatch(m)
        while (true) {
            val current = container.sports.snapshot.value.matches.firstOrNull { it.match.id == m.id }?.match ?: m
            if (current.status == MatchStatus.SCHEDULED) {
                // Meciul n-a început: se așteaptă startul (lista își schimbă starea singură), fără cereri.
                delay(DetailsSchedule.LIVE_REFRESH_MS)
                continue
            }
            details.refreshLive(current)
            if (current.status != MatchStatus.LIVE) break
            delay(DetailsSchedule.LIVE_REFRESH_MS)
        }
    }

    // ---------- A5: oprirea automată (la 5 min după final sau cronometrul de somn) ----------
    val playerPrefs by container.channelPlayback.general.collectAsStateWithLifecycle()
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var finishedSeenAt by remember(state.channel?.id) { mutableStateOf<Long?>(null) }
    var finalDismissed by remember(state.channel?.id) { mutableStateOf(false) }
    val finishedNow = !replay && (panelView?.status ?: linkedMatch?.status) == MatchStatus.FINISHED
    LaunchedEffect(finishedNow, state.channel?.id) { if (finishedNow && finishedSeenAt == null) finishedSeenAt = System.currentTimeMillis() }
    val stopAt = AutoStop.stopAt(sleepAt, playerPrefs.stopAfterFinal && !finalDismissed, finishedSeenAt)
    var stopLeft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(stopAt) {
        stopLeft = null
        val at = stopAt ?: return@LaunchedEffect
        while (true) {
            val t = System.currentTimeMillis()
            if (t >= at) {
                container.diagnosticLog.log("player", "oprire automată (${if (sleepAt != null && at == sleepAt) "cronometru" else "după final"})")
                onExit()
                break
            }
            stopLeft = AutoStop.warningSeconds(at, t)
            delay(500)
        }
    }
    /** Orice tastă (sau atingere) în numărătoare: playerul rămâne pornit. */
    fun cancelStop() {
        // Ora se recalculează aici: atingerea păstrează funcția din prima compunere.
        val at = AutoStop.stopAt(sleepAt, playerPrefs.stopAfterFinal && !finalDismissed, finishedSeenAt)
        if (sleepAt != null && at == sleepAt) sleepAt = null else finalDismissed = true
        stopLeft = null
        toast = "Rămâi pe canal: oprirea automată anulată."
    }

    // Meciul de pe ecran: alertele de gol nu se dau pentru el (îl vezi).
    LaunchedEffect(linkedMatch?.id) { container.watchingMatch.value = linkedMatch?.id }
    DisposableEffect(Unit) { onDispose { container.watchingMatch.value = null } }

    // F1: cu un minut înainte de un meci favorit, numărătoarea de 10 s (Back anulează), apoi sursa lui.
    var autoSwitch by remember { mutableStateOf<MatchEntry?>(null) }
    var autoSwitchSince by remember { mutableLongStateOf(0L) }

    // „A început meciul favorit X”: o dată pe meci, doar dacă are o sursă de pornit.
    val linkedId by rememberUpdatedState(linkedMatch?.id)
    LaunchedEffect(Unit) {
        while (true) {
            val snap = container.sports.snapshot.value
            val s = container.sportsSettings.current
            val t = System.currentTimeMillis()
            if (s.autoSwitch && autoSwitch == null) {
                val next = AutoSwitch.due(snap.matches.map { it.match }, s.favorites, t, container.handledKickoffs, linkedId)
                if (next != null) {
                    container.handledKickoffs += next.id
                    snap.matches.firstOrNull { it.match.id == next.id && it.bestSource(s) != null }?.let {
                        autoSwitchSince = System.currentTimeMillis()
                        autoSwitch = it
                    }
                }
            }
            if (autoSwitch != null) {
                delay(KICKOFF_CHECK_MS)
                continue
            }
            val due = KickoffNotices.due(snap.matches.map { it.match }, s.favorites, t, AnnouncedKickoffs, linkedId)
            val entry = due.firstNotNullOfOrNull { m -> snap.matches.firstOrNull { it.match.id == m.id && it.bestSource(s) != null } }
            due.forEach { AnnouncedKickoffs += it.id }
            if (entry != null) {
                kickoff = entry
                delay(KICKOFF_NOTICE_MS)
                kickoff = null
            }
            delay(KICKOFF_CHECK_MS)
        }
    }

    /** Pornește sursa recomandată a unui meci (stream-ul curent se oprește întâi, ca la orice schimbare). */
    fun playMatch(entry: MatchEntry) {
        val best = entry.bestSource(container.sportsSettings.current)
        panel = Panel.None
        if (best == null) {
            toast = "Meciul nu are surse în playlisturile tale."
            return
        }
        requestedMatch = entry.match.id
        kickoff = null
        if (best.id != state.channel?.id || replay) controller.play(best.id, zapGroup = 0) else Unit
    }

    // Numărătoarea comutării automate: la zero pornește meciul (stream-ul curent se oprește întâi).
    LaunchedEffect(autoSwitch) {
        val target = autoSwitch ?: return@LaunchedEffect
        while (AutoSwitch.secondsLeft(autoSwitchSince, System.currentTimeMillis()) > 0) delay(250)
        autoSwitch = null
        AnnouncedKickoffs += target.match.id
        playMatch(target)
    }

    // ---------- A1: zapping la pauză ----------
    /** Meciul propus la pauză (OK: treci pe el) și de unde pleci. */
    var zapOffer by remember { mutableStateOf<MatchEntry?>(null) }
    var zapOfferFrom by remember { mutableStateOf<ZapReturn?>(null) }
    /** Meciul tău, la care te întorci la reluarea jocului. */
    var zapBack by remember { mutableStateOf<ZapReturn?>(null) }
    var zapCountdownSince by remember { mutableStateOf<Long?>(null) }
    val atBreak = !replay && linkedMatch != null && HalftimeZap.supports(linkedMatch.sport) &&
        HalftimeZap.isBreak(panelView?.label, linkedMatch.sport)
    LaunchedEffect(atBreak, linkedMatch?.id) {
        zapOffer = null
        if (!atBreak) return@LaunchedEffect
        val m = linkedMatch ?: return@LaunchedEffect
        val ch = state.channel ?: return@LaunchedEffect
        val s = container.sportsSettings.current
        if (!s.halftimeZap || zapBack != null || autoSwitch != null) return@LaunchedEffect
        val snap = container.sports.snapshot.value
        val pick = HalftimeZap.pick(
            m, snap.matches, { it.match }, s.favorites::isFavorite, { it.summary.sure }, { it.bestSource(s) != null },
        ) ?: return@LaunchedEffect
        zapOfferFrom = ZapReturn(m.id, ch.id, System.currentTimeMillis(), m.sport, m.title)
        zapOffer = pick
        delay(HalftimeZap.OFFER_MS)
        zapOffer = null
    }

    /** OK pe propunere: pornește celălalt meci (pe calea schimbării canalului) și ține minte drumul înapoi. */
    fun acceptZap() {
        val target = zapOffer ?: return
        val from = zapOfferFrom ?: return
        zapOffer = null
        zapBack = from
        playMatch(target)
        toast = "Pauză la ${from.title}: te întorc la reluarea jocului."
    }

    // Cât ești pe celălalt meci: starea meciului tău se cere la un minut (calea tabului Live, fără conexiunea
    // playlistului); la reluare (sau după pauza obișnuită a sportului) numărătoare de 10 s și înapoi.
    LaunchedEffect(zapBack) {
        val back = zapBack ?: return@LaunchedEffect
        var refreshedAt = 0L
        while (true) {
            val t = System.currentTimeMillis()
            val entry = container.sports.snapshot.value.matches.firstOrNull { it.match.id == back.matchId }
            if (entry == null || entry.match.status == MatchStatus.FINISHED) {
                zapBack = null
                return@LaunchedEffect
            }
            if (t - refreshedAt >= DetailsSchedule.LIVE_REFRESH_MS) {
                refreshedAt = t
                runCatching { details.refreshLive(entry.match) }
            }
            val view = details.delayedView(entry.match, System.currentTimeMillis(), container.sportsSettings.current.streamDelaySeconds * 1_000L)
            if (view?.status == MatchStatus.FINISHED) {
                zapBack = null
                return@LaunchedEffect
            }
            val resumed = view != null && view.status == MatchStatus.LIVE && !HalftimeZap.isBreak(view.label, back.sport)
            if (HalftimeZap.returnDue(back.sport, back.breakSeenAt, System.currentTimeMillis(), resumed)) break
            delay(5_000)
        }
        zapCountdownSince = System.currentTimeMillis()
        while (HalftimeZap.secondsLeft(zapCountdownSince ?: 0L, System.currentTimeMillis()) > 0) delay(250)
        zapCountdownSince = null
        zapBack = null
        requestedMatch = back.matchId
        if (back.channelId != state.channel?.id || replay) controller.play(back.channelId, zapGroup = 0)
    }

    // ---------- A3: banda de scoruri (tasta galbenă) ----------
    var band by remember { mutableStateOf(false) }
    val delayMs = sportsSettings.streamDelaySeconds * 1_000L
    val bandItems: List<BandItem> = remember(band, snapshot, sportsSettings, now, linkedMatch?.id, revealed, detailsStates) {
        if (!band) return@remember emptyList()
        ScoreBand.pick(snapshot.matches.map { it.match }, sportsSettings.favorites::isFavorite, linkedMatch?.id).map { m ->
            val hidden = Spoilers.hide(sportsSettings.noSpoilers, m.id in revealed, m.status)
            val ev = detailsStates[m.id]?.details?.let { details.delayedDetails(m, now, delayMs, it).event }
            val clock = Scoreboard.clock(details.delayedView(m, now, delayMs)?.label)
            BandItem(m.id, ScoreBand.text(m, ev?.homeScore ?: m.homeScore, ev?.awayScore ?: m.awayScore, clock, hidden))
        }
    }
    // Cât e banda pe ecran, scorurile se cer ca la alertele de gol: câte un `summary` ESPN pe minut, cel mult 3 meciuri,
    // niciunul cu scorul ascuns („Fără spoilere”).
    LaunchedEffect(band) {
        if (!band) return@LaunchedEffect
        while (true) {
            val s = container.sportsSettings.current
            val shown = ScoreBand.pick(container.sports.snapshot.value.matches.map { it.match }, s.favorites::isFavorite, linkedId)
                .filter { !Spoilers.hide(s.noSpoilers, it.id in details.revealed.value, it.status) && container.sports.espnEventFor(it) != null }
                .take(GoalAlerts.MAX_WATCHED)
            shown.forEach { runCatching { details.refreshLive(it) } }
            delay(DetailsSchedule.LIVE_REFRESH_MS)
        }
    }

    // ---------- A4: ⏪ ⏩ la faza importantă anterioară / următoare, în arhivă ----------
    fun jumpPhase(forward: Boolean) {
        val m = linkedMatch ?: run { toast = "Canalul nu e legat de un meci."; return }
        val channel = state.channel ?: return
        if (spoilerHidden) {
            toast = "Fazele sunt ascunse („Fără spoilere”). Le dezvălui din panoul meciului."
            return
        }
        val nowMs = System.currentTimeMillis()
        val kickoffAt = detailsState.details.event?.start ?: m.start
        val hasArchive = Catchup.isAvailable(channel.catchupDays, kickoffAt, nowMs) && Catchup.parse(channel.streamUrl) != null
        if (!hasArchive || !MatchClock.supports(m.sport)) {
            toast = "Saltul la faze merge la fotbal, pe canalele cu arhivă."
            return
        }
        val offset = container.catchup.offsetSeconds(channel.playlistId)
        // Live: doar fazele care au apărut deja în stream (întârzierea aplicată); la reluare, toate.
        val timeline = if (replay) detailsState.details.timeline else panelView?.timeline ?: detailsState.details.timeline
        val watched = controller.watchedAt() ?: (nowMs - sportsSettings.streamDelaySeconds * 1_000L)
        val jump = ArchiveJumps.next(timeline, kickoffAt, m.sport, offset, watched, forward)
        when {
            jump == null -> toast = if (forward) "Nicio fază importantă mai departe." else "Nicio fază importantă înainte."
            jump.start > nowMs - ARCHIVE_READY_MS -> toast = "Faza e prea recentă: arhiva n-o are încă."
            else -> {
                toast = "${ArchiveJumps.describe(jump.entry)} (cu un minut înainte)…"
                controller.jumpTo(jump.start, maxOf(m.end, nowMs))
            }
        }
    }

    fun showInfo() {
        infoVisible = true
        infoToken++
    }

    fun commitNumber() {
        val n = numberInput.take()
        numberText = ""
        if (n != null) controller.playNumber(n) { toast = it }
    }

    LaunchedEffect(infoToken) {
        delay(INFO_MS)
        infoVisible = false
    }
    LaunchedEffect(state.channel?.id) { if (state.channel != null) showInfo() }
    LaunchedEffect(numberText) {
        if (numberText.isNotEmpty()) {
            delay(NUMBER_COMMIT_MS)
            commitNumber()
        }
    }
    LaunchedEffect(state.notice?.id) { state.notice?.let { toast = it.text } }
    // Notificările meciurilor favorite apar și peste player.
    LaunchedEffect(Unit) { container.inAppNotices.collect { toast = it } }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4_000)
            toast = null
        }
    }
    LaunchedEffect(panel) { if (panel == Panel.None) runCatching { rootFocus.requestFocus() } }

    BackHandler(enabled = panel != Panel.None || numberText.isNotEmpty()) {
        panelDialog = null
        if (numberText.isNotEmpty()) {
            numberInput.clear()
            numberText = ""
        } else {
            panel = if (panel == Panel.Audio || panel == Panel.Subtitles || panel == Panel.Schedule || panel == Panel.Variants) {
                Panel.Menu
            } else {
                Panel.None
            }
        }
    }

    // A1: Back pe propunere o închide; Back în numărătoarea întoarcerii: rămâi pe meciul celălalt.
    BackHandler(enabled = zapOffer != null) { zapOffer = null }
    BackHandler(enabled = zapCountdownSince != null) {
        zapBack = null
        zapCountdownSince = null
        toast = "Rămâi pe meciul ăsta."
    }

    // Back în timpul numărătorii: comutarea se anulează (rămâi pe canal).
    BackHandler(enabled = autoSwitch != null) {
        autoSwitch?.let { AnnouncedKickoffs += it.match.id }
        autoSwitch = null
        toast = "Comutarea automată anulată."
    }

    fun onKey(code: Int): Boolean {
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                controller.zap(1)
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                controller.zap(-1)
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> panel = Panel.Channels
            // Pe un meci, săgeata dreapta deschide direct panoul meciului (live, statistici, formații, pre); meniul e în el.
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> panel = if (linkedMatch != null) Panel.Match else Panel.Menu
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> panel = Panel.Menu
            AndroidKeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> panel = Panel.Audio
            AndroidKeyEvent.KEYCODE_CAPTIONS -> panel = Panel.Subtitles
            AndroidKeyEvent.KEYCODE_PROG_RED -> panel = Panel.Quick
            AndroidKeyEvent.KEYCODE_PROG_YELLOW -> {
                band = !band
                if (band && ScoreBand.pick(snapshot.matches.map { it.match }, sportsSettings.favorites::isFavorite, linkedMatch?.id).isEmpty()) {
                    toast = "Niciun meci favorit live acum (banda rămâne, apare când începe unul)."
                }
            }
            AndroidKeyEvent.KEYCODE_PROG_BLUE -> if (linkedMatch != null) panel = Panel.Match
            AndroidKeyEvent.KEYCODE_INFO -> showInfo()
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> jumpPhase(forward = true)
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> jumpPhase(forward = false)
            AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_DATA_SERVICE -> panel = Panel.Schedule
            AndroidKeyEvent.KEYCODE_LAST_CHANNEL, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                if (!controller.playPrevious()) toast = "Nu există un canal anterior."
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                numberText = numberInput.type(code - AndroidKeyEvent.KEYCODE_0)
            in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                numberText = numberInput.type(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
            else -> return false
        }
        return true
    }

    fun shortOk() {
        when {
            zapOffer != null && panel == Panel.None -> acceptZap()
            numberInput.isActive -> commitNumber()
            state.status is PlaybackStatus.Failed -> controller.retry()
            infoVisible -> infoVisible = false
            else -> showInfo()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // OK scurt / ținut: decis înaintea panourilor, ca ridicarea tastei să nu apese rândul abia focalizat.
            .onPreviewKeyEvent { event ->
                val code = event.key.nativeKeyCode
                if (stopLeft != null) {
                    if (event.type == KeyEventType.KeyDown) cancelStop()
                    return@onPreviewKeyEvent true
                }
                when {
                    code !in OkKeys -> false
                    event.type == KeyEventType.KeyDown && panel == Panel.None && !okHeld -> {
                        okHeld = true
                        okLong = false
                        true
                    }
                    !okHeld -> false
                    event.type == KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount > 0 && !okLong) {
                            okLong = true
                            panel = Panel.Quick
                        }
                        true
                    }
                    else -> {
                        val wasLong = okLong
                        okHeld = false
                        okLong = false
                        if (!wasLong) shortOk()
                        true
                    }
                }
            }
            .onKeyEvent { event ->
                val code = event.key.nativeKeyCode
                when {
                    // Panourile au focusul lor; săgeata spre margine le închide.
                    panel == Panel.Channels -> if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (event.type == KeyEventType.KeyDown) panel = Panel.None
                        true
                    } else {
                        false
                    }
                    panel != Panel.None -> if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                        if (event.type == KeyEventType.KeyDown) panel = Panel.None
                        true
                    } else {
                        false
                    }
                    event.type == KeyEventType.KeyDown -> onKey(code)
                    // Ridicarea tastelor de navigare nu mai ajunge la panoul abia deschis.
                    else -> code in NavigationKeys
                }
            },
    ) {
        // TextureView (implicit): imaginea apare sigur sub straturile Compose; SurfaceView („Imagine fluidă”):
        // decodorul scrie direct pe ecran, mai fluid la 4K. Vezi player_view.xml și player_view_surface.xml.
        val layout = if (surfaceOn) R.layout.player_view_surface else R.layout.player_view
        androidx.compose.runtime.key(layout) { AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = controller.player
                }
            },
            onRelease = { it.player = null },
            // B5: încadrarea ținută minte pe canal.
            update = { it.resizeMode = state.fit.resizeMode() },
            modifier = Modifier.fillMaxSize(),
        ) }

        // Pe telefon, gesturile în locul telecomenzii (pe televizor nu vine nicio atingere): atingere în mijloc = OK
        // (informațiile, reîncercarea), în treimea stângă = lista canalelor, în cea dreaptă = panoul meciului sau
        // meniul, apăsare lungă = meniul rapid, glisare sus / jos = canalul următor / anterior. Cu un panou deschis,
        // atingerea în afara lui îl închide. Panourile sunt deasupra, deci rândurile lor își primesc singure atingerea.
        val touchHasMatch by rememberUpdatedState(linkedMatch != null)
        val touchOk by rememberUpdatedState({ shortOk() })
        val touchCancelStop by rememberUpdatedState({ cancelStop() })
        val touchStopShown by rememberUpdatedState(stopLeft != null)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            val third = size.width / 3f
                            when {
                                touchStopShown -> touchCancelStop()
                                panel != Panel.None -> {
                                    panelDialog = null
                                    panel = Panel.None
                                }
                                pos.x < third -> panel = Panel.Channels
                                pos.x > 2 * third -> panel = if (touchHasMatch) Panel.Match else Panel.Menu
                                else -> touchOk()
                            }
                        },
                        onLongPress = { if (panel == Panel.None) panel = Panel.Quick },
                    )
                }
                .pointerInput(Unit) {
                    var drag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (panel == Panel.None && kotlin.math.abs(drag) > 80.dp.toPx()) {
                                // Ca pe telefon: tragi în sus = canalul următor (ca săgeata sus).
                                controller.zap(if (drag < 0) 1 else -1)
                            }
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        },
                    )
                },
        )

        StatusOverlay(state.status, Modifier.align(Alignment.Center))

        adaptNote?.let { note ->
            Text(
                text = note,
                fontSize = 15.sp,
                color = AkColors.Accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(Overlay, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Tabela de scor din stânga sus (cu informațiile sau cu panoul meciului): coduri, scor (după întârzierea
        // stream-ului, ascuns la „fără spoilere”), minutul; la reluare marcată „RELUARE”, niciodată „LIVE”.
        linkedMatch?.let { m ->
            if ((infoVisible && panel == Panel.None) || panel == Panel.Match) {
                MatchScoreboard(
                    match = m,
                    view = panelView,
                    hidden = spoilerHidden,
                    replay = replay,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 32.dp),
                )
            }
        }

        if (numberText.isNotEmpty()) {
            Text(
                text = numberText,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Overlay, RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        autoSwitch?.let { a ->
            val left = produceState(AutoSwitch.secondsLeft(autoSwitchSince, System.currentTimeMillis()), a) {
                while (true) {
                    value = AutoSwitch.secondsLeft(autoSwitchSince, System.currentTimeMillis())
                    delay(250)
                }
            }.value
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 32.dp)
                    .width(440.dp)
                    .background(Overlay, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "★ Începe meciul favorit", fontSize = 13.sp, color = AkColors.Accent)
                Text(text = a.match.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(text = "Trec pe meci în $left s · Back: anulează", fontSize = 14.sp, color = AkColors.TextDim)
            }
        }

        if (autoSwitch == null) zapOffer?.let { z ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 32.dp)
                    .width(440.dp)
                    .background(Overlay, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "Pauză" + (zapOfferFrom?.title?.let { " la $it" } ?: ""), fontSize = 13.sp, color = AkColors.Accent, maxLines = 1)
                Text(text = "Treci la ${z.match.title}?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(text = "OK: da, te întorc la reluarea jocului · Back: nu", fontSize = 14.sp, color = AkColors.TextDim)
            }
        }

        zapCountdownSince?.let { since ->
            val left = produceState(HalftimeZap.secondsLeft(since, System.currentTimeMillis()), since) {
                while (true) {
                    value = HalftimeZap.secondsLeft(since, System.currentTimeMillis())
                    delay(250)
                }
            }.value
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 32.dp)
                    .width(440.dp)
                    .background(Overlay, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "Se reia jocul", fontSize = 13.sp, color = AkColors.Accent)
                Text(text = zapBack?.title.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(text = "Mă întorc în $left s · Back: rămâi aici", fontSize = 14.sp, color = AkColors.TextDim)
            }
        }

        if (autoSwitch == null) kickoff?.let { k ->
            if (panel != Panel.Quick) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 32.dp)
                        .width(420.dp)
                        .background(Overlay, RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(text = "★ A început meciul favorit", fontSize = 13.sp, color = AkColors.Accent)
                    Text(text = k.match.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(text = "OK lung: mută pe meci", fontSize = 13.sp, color = AkColors.TextDim)
                }
            }
        }

        stopLeft?.let { left ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(460.dp)
                    .background(Overlay, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Text(text = "Oprire automată", fontSize = 13.sp, color = AkColors.Accent)
                Text(text = "Playerul se închide în $left s", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "Orice tastă: rămâi pe canal", fontSize = 14.sp, color = AkColors.TextDim)
            }
        }

        toast?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(Overlay, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val infoShown = (infoVisible || state.status !is PlaybackStatus.Playing) && panel == Panel.None
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // A3: banda de scoruri, o linie discretă deasupra informațiilor (tasta galbenă o pornește / oprește).
            if (band && panel == Panel.None) ScoreBandLine(bandItems)
            if (infoShown) {
                InfoBar(
                    state = state,
                    matchLine = linkedMatch?.let { m -> matchLine(m, panelView, spoilerHidden) },
                    rateMatchOn = rateOn,
                    smoothOn = surfaceOn,
                    modifier = Modifier,
                )
            }
        }

        when (panel) {
            Panel.Channels -> ChannelsPanel(
                controller = controller,
                currentId = state.channel?.id,
                onPick = { id ->
                    panel = Panel.None
                    if (id != state.channel?.id) controller.play(id)
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Panel.Menu -> MenuPanel(
                state = state,
                matchTitle = linkedMatch?.title,
                onMatch = { panel = Panel.Match },
                sourceCount = linked?.sources?.size ?: 0,
                onSources = { panel = Panel.Sources },
                onQuick = { panel = Panel.Quick },
                previousName = controller.previousName,
                onAudio = { panel = Panel.Audio },
                onSubtitles = { panel = Panel.Subtitles },
                onPrevious = {
                    panel = Panel.None
                    if (!controller.playPrevious()) toast = "Nu există un canal anterior."
                },
                onReconnect = {
                    panel = Panel.None
                    controller.retry()
                },
                onChannels = { panel = Panel.Channels },
                onSchedule = { panel = Panel.Schedule },
                onVariants = { panel = Panel.Variants },
                onPrefer = {
                    panel = Panel.None
                    controller.preferCurrent()?.let { toast = it }
                },
                suspect = state.channel?.let { c -> container.suspectChannels.contains(c.playlistId, c.name) } == true,
                onSuspect = {
                    panel = Panel.None
                    state.channel?.let { c ->
                        val marked = container.suspectChannels.toggle(c.playlistId, c.name)
                        toast = if (marked) "${c.name}: semnalat „canal posibil greșit”. Rămâne în surse, cu eticheta asta."
                        else "${c.name}: semnalarea a fost scoasă."
                    }
                },
                audioDelayMs = state.audioDelayMs,
                onAudioDelay = { panelDialog = PanelDialog.AUDIO_DELAY },
                night = playerPrefs.night,
                onNight = {
                    val on = !playerPrefs.night
                    controller.setNight(on)
                    toast = if (on) "Mod de noapte pornit: sunetul tare coboară, cel încet urcă." else "Mod de noapte oprit."
                },
                fit = state.fit,
                onFit = { panelDialog = PanelDialog.FIT },
                autoStop = autoStopText(sleepAt, playerPrefs.stopAfterFinal),
                onAutoStop = { panelDialog = PanelDialog.AUTO_STOP },
                band = band,
                onBand = {
                    panel = Panel.None
                    band = !band
                },
                onExit = onExit,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Audio -> TrackPanel(
                title = "Pistă audio",
                options = remember(state.audioTracks) { controller.audioOptions() },
                empty = "Stream-ul are o singură pistă audio sau încă se încarcă.",
                onPick = {
                    controller.select(it, C.TRACK_TYPE_AUDIO)
                    panel = Panel.None
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Subtitles -> TrackPanel(
                title = "Subtitrări",
                options = remember(state.textTracks) { controller.textOptions() },
                empty = "",
                onPick = {
                    controller.select(it, C.TRACK_TYPE_TEXT)
                    panel = Panel.None
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Schedule -> SchedulePanel(
                channelId = state.channel?.id,
                channelName = state.channel?.name.orEmpty(),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Sources -> linked?.let { entry ->
                MatchSourcesPanel(
                    entry = entry,
                    favorite = sportsSettings.favorites.isFavorite(entry.match),
                    prefs = sportsSettings.sourcePreferences,
                    currentId = state.channel?.id,
                    onPick = { id ->
                        panel = Panel.None
                        // Aceeași cale ca schimbarea canalului: stream-ul vechi se oprește, apoi pornește sursa.
                        if (id != state.channel?.id) controller.play(id)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            Panel.Variants -> VariantsPanel(
                controller = controller,
                currentId = state.channel?.id,
                onPick = { id ->
                    panel = Panel.None
                    if (id != state.channel?.id) controller.play(id)
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.Match -> linked?.let { entry ->
                val m = entry.match
                val channel = state.channel
                val nowMs = System.currentTimeMillis()
                val kickoffAt = detailsState.details.event?.start ?: m.start
                val hasArchive = channel != null && Catchup.isAvailable(channel.catchupDays, kickoffAt, nowMs) &&
                    Catchup.parse(channel.streamUrl) != null
                val offset = channel?.let { container.catchup.offsetSeconds(it.playlistId) } ?: 0
                val end = maxOf(m.end, nowMs)
                MatchPanelContent(
                    match = m,
                    view = panelView,
                    hidden = spoilerHidden,
                    replay = replay,
                    lineup = detailsState.details.lineup,
                    loading = detailsState.loading,
                    status = detailsState.status,
                    delaySeconds = sportsSettings.streamDelaySeconds,
                    offsetSeconds = offset,
                    details = detailsState.details,
                    onMenu = { panel = Panel.Menu },
                    actions = MatchPanelActions(
                        onJump = if (hasArchive && MatchClock.supports(m.sport)) {
                            { t ->
                                MatchClock.jumpStart(kickoffAt, t.minute, m.sport, offset)?.let { start ->
                                    panel = Panel.None
                                    toast = "Arhiva de la ${t.minute}' (cu un minut înainte)…"
                                    controller.jumpTo(start, end)
                                }
                            }
                        } else {
                            null
                        },
                        onFromStart = if (hasArchive) {
                            {
                                panel = Panel.None
                                controller.jumpTo(kickoffAt - FROM_START_LEAD_MS, end)
                            }
                        } else {
                            null
                        },
                        onLive = if (replay && m.status != MatchStatus.FINISHED) {
                            {
                                panel = Panel.None
                                controller.goLive()
                            }
                        } else {
                            null
                        },
                        onReveal = { details.reveal(m.id) },
                        onRefresh = { scope.launch { if (replay) details.refreshLive(m, force = true) else details.refreshLive(m) } },
                        onDelay = if (!replay) ({ panelDialog = PanelDialog.DELAY }) else null,
                        onOffset = if (hasArchive && channel != null) ({ panelDialog = PanelDialog.OFFSET }) else null,
                    ),
                    modifier = Modifier.align(Alignment.CenterEnd).width(600.dp),
                )
            } ?: LaunchedEffect(Unit) { panel = Panel.None }
            Panel.Quick -> {
                val s = sportsSettings
                val t = System.currentTimeMillis()
                val live = remember(snapshot, minute) {
                    snapshot.matches.filter { e ->
                        e.match.status != MatchStatus.FINISHED && e.match.start <= t && t <= e.match.end && e.bestSource(s) != null
                    }.sortedBy { it.match.start }
                }
                val (fav, other) = live.partition { s.favorites.isFavorite(it.match) }
                QuickMenuContent(
                    previousName = controller.previousName,
                    current = linked,
                    announced = kickoff,
                    favorites = fav,
                    others = other.take(QUICK_OTHERS),
                    onPrevious = {
                        panel = Panel.None
                        if (!controller.playPrevious()) toast = "Nu există un canal anterior."
                    },
                    onMatchPanel = if (linked != null) ({ panel = Panel.Match }) else null,
                    onPlayMatch = { playMatch(it) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    sourceCount = linked?.sources?.size ?: 0,
                    onSources = { panel = Panel.Sources },
                    onVariants = { panel = Panel.Variants },
                    onSchedule = { panel = Panel.Schedule },
                    suspect = state.channel?.let { c -> container.suspectChannels.contains(c.playlistId, c.name) } == true,
                    onSuspect = {
                        panel = Panel.None
                        state.channel?.let { c ->
                            val marked = container.suspectChannels.toggle(c.playlistId, c.name)
                            toast = if (marked) "${c.name}: semnalat „canal posibil greșit”. Rămâne în surse, cu eticheta asta."
                            else "${c.name}: semnalarea a fost scoasă."
                        }
                    },
                )
            }
            Panel.None -> Unit
        }
    }

    when (panelDialog) {
        PanelDialog.DELAY -> ChoiceDialog(
            title = "Întârzierea stream-ului live",
            hint = "Cât e în urmă imaginea față de realitate. Dacă afli golul din panou înainte să-l vezi, mărește valoarea.",
            options = StreamDelays.map { if (it == 0) "Fără întârziere" else "$it secunde" },
            selectedIndex = StreamDelays.indexOf(sportsSettings.streamDelaySeconds),
            onDismiss = { panelDialog = null },
            onSelect = { i ->
                container.sportsSettings.update { it.copy(streamDelaySeconds = StreamDelays[i]) }
                panelDialog = null
            },
        )
        PanelDialog.OFFSET -> state.channel?.let { c ->
            val current = container.catchup.offsetSeconds(c.playlistId)
            val options = (CatchupStore.OFFSETS + current).distinct().sorted()
            ChoiceDialog(
                title = "Corecția saltului · ${c.playlistName}",
                hint = "Saltul la o fază pornește cu un minut înainte. Dacă ajunge constant prea devreme, alege o valoare pozitivă; " +
                    "dacă ratezi faza, una negativă. Se ține minte pentru playlist.",
                options = options.map { if (it == 0) "Fără corecție" else (if (it > 0) "+" else "") + "$it secunde" },
                selectedIndex = options.indexOf(current),
                onDismiss = { panelDialog = null },
                onSelect = { i ->
                    container.catchup.setOffsetSeconds(c.playlistId, options[i])
                    panelDialog = null
                },
            )
        }
        PanelDialog.AUDIO_DELAY -> state.channel?.let { c ->
            ChoiceDialog(
                title = "Întârzierea sunetului · ${c.name}",
                hint = "Dacă vorbele vin după buze, alege o valoare negativă; dacă vin înainte, una pozitivă. " +
                    "Se ține minte pentru canal. (Nu se aplică la redarea tunelată.)",
                options = AudioDelay.options.map { AudioDelay.label(it) },
                selectedIndex = AudioDelay.options.indexOf(state.audioDelayMs).coerceAtLeast(0),
                onDismiss = { panelDialog = null },
                onSelect = { i ->
                    controller.setAudioDelay(AudioDelay.options[i])
                    panelDialog = null
                },
            )
        }
        PanelDialog.FIT -> state.channel?.let { c ->
            ChoiceDialog(
                title = "Imaginea · ${c.name}",
                hint = "Se ține minte pentru canal.",
                options = VideoFit.entries.map { "${it.label}: ${it.hint}" },
                selectedIndex = state.fit.ordinal,
                onDismiss = { panelDialog = null },
                onSelect = { i ->
                    controller.setFit(VideoFit.entries[i])
                    panelDialog = null
                },
            )
        }
        PanelDialog.AUTO_STOP -> {
            val options = listOf("Oprită", "La 5 minute după finalul meciului (ține minte)") +
                AutoStop.SLEEP_MINUTES.map { "Peste $it de minute" }
            val selected = when {
                sleepAt != null -> -1
                playerPrefs.stopAfterFinal -> 1
                else -> 0
            }
            ChoiceDialog(
                title = "Oprire automată",
                hint = "Playerul se închide singur (conexiunea playlistului se eliberează). Cu 30 s înainte apare o numărătoare; orice tastă o anulează.",
                options = options,
                selectedIndex = selected.coerceAtLeast(0),
                onDismiss = { panelDialog = null },
                onSelect = { i ->
                    panelDialog = null
                    when (i) {
                        0 -> {
                            sleepAt = null
                            container.channelPlayback.updateGeneral { it.copy(stopAfterFinal = false) }
                            toast = "Oprirea automată e oprită."
                        }
                        1 -> {
                            sleepAt = null
                            finalDismissed = false
                            container.channelPlayback.updateGeneral { it.copy(stopAfterFinal = true) }
                            toast = "Playerul se închide la 5 minute după finalul meciului de pe ecran."
                        }
                        else -> {
                            val minutes = AutoStop.SLEEP_MINUTES[i - 2]
                            sleepAt = System.currentTimeMillis() + minutes * 60_000L
                            toast = "Playerul se închide peste $minutes de minute."
                        }
                    }
                },
            )
        }
        null -> Unit
    }
}

/** B5: încadrarea aleasă → modul ferestrei video. */
private fun VideoFit.resizeMode(): Int = when (this) {
    VideoFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFit.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoFit.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

/** Rândul „Oprire automată” din meniu. */
private fun autoStopText(sleepAt: Long?, afterFinal: Boolean): String {
    val sleep = sleepAt?.let {
        "la " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()))
    }
    return listOfNotNull(sleep, if (afterFinal) "la 5 min după finalul meciului" else null).joinToString(" · ").ifEmpty { "oprită" }
}

/** Arhiva unei faze e gata la câteva minute după ea (Xtream scrie pe minute întregi). */
private const val ARCHIVE_READY_MS = 120_000L

/** „De la început” în panou: arhiva pornește cu două minute înainte de start. */
private const val FROM_START_LEAD_MS = 120_000L
private const val QUICK_OTHERS = 40

/** Rândul meciului din bara de informații: scorul (cu întârzierea stream-ului sau până unde ai ajuns în reluare). */
private fun matchLine(m: Match, view: PanelView?, hidden: Boolean): String = when {
    hidden -> "${m.title} · scor ascuns"
    view?.homeScore != null && view.awayScore != null ->
        "${m.home} ${view.homeScore}–${view.awayScore} ${m.away}" + view.label.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    else -> m.title
}

/** A1: unde se întoarce zapping-ul la pauză: meciul tău, canalul lui, când a început pauza. */
private data class ZapReturn(val matchId: String, val channelId: Long, val breakSeenAt: Long, val sport: ro.ak.sportshub.core.sports.Sport?, val title: String)

/** A3: banda de scoruri, o linie jos cu meciurile favorite live (scorul întârziat, ascuns la „Fără spoilere”). */
@Composable
private fun ScoreBandLine(items: List<BandItem>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 48.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "★ LIVE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AkColors.Accent, modifier = Modifier.padding(end = 14.dp))
        Text(
            text = if (items.isEmpty()) "Niciun meci favorit live acum" else items.joinToString("   ·   ") { it.text },
            fontSize = 15.sp,
            color = if (items.isEmpty()) AkColors.TextDim else AkColors.Text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusOverlay(status: PlaybackStatus, modifier: Modifier) {
    val (title, hint) = when (status) {
        PlaybackStatus.Idle, PlaybackStatus.Playing -> return
        PlaybackStatus.Connecting -> "Se conectează…" to null
        PlaybackStatus.Buffering -> "Se încarcă…" to null
        is PlaybackStatus.Waiting -> status.message to null
        is PlaybackStatus.Retrying ->
            status.reason to "Reîncerc în ${status.seconds} s (încercarea ${status.attempt} din ${status.maxAttempts})"
        is PlaybackStatus.Failed -> status.reason to "OK: încearcă din nou · ▲▼: alt canal · Back: ieșire"
    }
    Column(
        modifier = modifier
            .background(Overlay, RoundedCornerShape(10.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, fontSize = 20.sp, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(text = hint, fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun InfoBar(state: PlayerUiState, matchLine: String?, rateMatchOn: Boolean, smoothOn: Boolean, modifier: Modifier) {
    val channel = state.channel ?: return
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val clock = remember(now / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val epg = appContainer().epg
    val nowNext by produceState<NowNext?>(null, channel.id, now / 60_000) {
        value = epg.nowNext(listOf(channel.id), now)[channel.id]
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000))))
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            channel.number?.let {
                Text(text = "$it", fontSize = 40.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold, color = AkColors.Accent)
                Spacer(Modifier.width(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        fontSize = 30.sp,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        // Numele lung nu mai împinge etichetele (scrise altfel literă cu literă, pe verticală).
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // O reluare nu apare niciodată drept live.
                    if (state.archiveFrom != null) Badge(text = "RELUARE", color = AkColors.Focus)
                    channel.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                    VariantQualityBadge(channel.playlistId, channel.name, channel.quality)
                }
                Text(
                    text = listOfNotNull(
                        state.archiveFrom?.let { "Din arhivă, de la ${formatTime(it)}" },
                        channel.groupName,
                        channel.playlistName,
                        state.position,
                        if (state.variantCount > 1) "${state.variantCount} variante" else null,
                    ).joinToString(" · "),
                    fontSize = 15.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
                matchLine?.let {
                    Text(text = it, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AkColors.Accent, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
                nowNext?.now?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text(text = formatRange(p), fontSize = 15.sp, color = AkColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(text = p.title, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        ProgressLine(p.progress(now), Modifier.width(140.dp))
                    }
                }
                nowNext?.next?.let { p ->
                    Text(
                        text = "Urmează ${formatTime(p.start)}  ${p.title}",
                        fontSize = 15.sp,
                        color = AkColors.TextDim,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    // Pe un meci, ▶ deschide panoul meciului; variantele, audio și subtitrările sunt în meniu.
                    text = if (matchLine != null) {
                        "▲▼ canale · ◀ lista grupului · ▶ meciul: Live, Statistici, Formații, Pre · Meniu: variante, audio, subtitrări · OK ascunde · OK lung: meniu rapid"
                    } else {
                        "▲▼ canale · ◀ lista grupului · ▶ variante, audio, subtitrări, program · OK ascunde · OK lung: meniu rapid"
                    },
                    fontSize = 13.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 560.dp)) {
                Text(text = clock, fontSize = 30.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
                val details = listOfNotNull(
                    listOfNotNull(state.videoSize, state.frameRate?.let { "${"%.2f".format(Locale.US, it).trimEnd('0').trimEnd('.')} fps" }).joinToString(" ").ifEmpty { null },
                    if (state.audioTracks > 1) "${state.audioTracks} piste audio" else null,
                    if (state.textTracks > 0) "subtitrări" else null,
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(text = details, fontSize = 13.sp, color = AkColors.TextDim)
                // Verificarea redării: decodorul, cadrele aruncate și frecvența ecranului (sacadările se văd aici).
                val hz = androidx.compose.ui.platform.LocalView.current.display?.refreshRate
                val check = listOfNotNull(
                    state.decoder?.let { "decodor ${it.removePrefix("c2.").removePrefix("OMX.").removeSuffix(".decoder")}" },
                    if (state.videoSize != null) "${state.droppedFrames} cadre pierdute" else null,
                    hz?.let { "ecran ${Math.round(it)} Hz" },
                ).joinToString(" · ")
                if (check.isNotEmpty()) Text(text = check, fontSize = 12.sp, color = AkColors.TextDim)
                val fps = state.frameRate
                // Sfatul apare doar pentru ce nu e deja pornit (din Setări sau de adaptarea automată).
                val mismatch = fps != null && hz != null && PlaybackCheck.mismatch(fps, hz)
                val manyDropped = state.droppedFrames >= PlaybackCheck.MANY_DROPPED
                val advice = when {
                    mismatch && !rateMatchOn -> "Cadrele nu se potrivesc cu ecranul: pornește „Potrivește frecvența” în Setări"
                    mismatch && fps != null -> "Televizorul nu are un mod potrivit pentru ${Math.round(fps)} fps"
                    manyDropped && !smoothOn && !rateMatchOn -> "Multe cadre pierdute: pornește „Imagine fluidă” și „Potrivește frecvența” în Setări"
                    manyDropped && !smoothOn -> "Multe cadre pierdute: pornește „Imagine fluidă” în Setări"
                    manyDropped && !rateMatchOn && fps != null -> "Multe cadre pierdute: pornește „Potrivește frecvența” în Setări"
                    else -> null
                }
                advice?.let { Text(text = it, fontSize = 12.sp, color = if (it.startsWith("Televizorul")) AkColors.TextDim else AkColors.Accent, textAlign = TextAlign.End) }
            }
        }
    }
}

@Composable
internal fun SidePanel(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Overlay)
            .padding(vertical = 20.dp, horizontal = 12.dp),
    ) { content() }
}

@Composable
private fun ChannelsPanel(
    controller: PlayerController,
    currentId: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier,
) {
    val rows by produceState<Pair<List<ChannelWithPlaylist>, Int>?>(null) { value = controller.groupRows() }
    SidePanel(modifier) {
        Column {
            SectionLabel(rows?.first?.getOrNull(rows?.second ?: -1)?.groupName ?: "Canale")
            val list = rows?.first ?: return@Column
            val current = (rows?.second ?: 0).coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
            val focus = remember { FocusRequester() }
            LaunchedEffect(list) { runCatching { focus.requestFocus() } }
            LazyColumn(contentPadding = ListPadding, state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                    ListRow(
                        title = (c.number?.let { "$it  " } ?: "") + c.name,
                        subtitle = c.playlistName,
                        selected = index == current,
                        onClick = { onPick(c.id) },
                        modifier = if (index == current) Modifier.focusRequester(focus) else Modifier,
                        trailing = {
                            c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                            VariantQualityBadge(c.playlistId, c.name, c.quality)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuPanel(
    state: PlayerUiState,
    matchTitle: String?,
    onMatch: () -> Unit,
    sourceCount: Int,
    onSources: () -> Unit,
    onQuick: () -> Unit,
    previousName: String?,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onPrevious: () -> Unit,
    onReconnect: () -> Unit,
    onChannels: () -> Unit,
    onSchedule: () -> Unit,
    onVariants: () -> Unit,
    onPrefer: () -> Unit,
    suspect: Boolean,
    onSuspect: () -> Unit,
    audioDelayMs: Int,
    onAudioDelay: () -> Unit,
    night: Boolean,
    onNight: () -> Unit,
    fit: VideoFit,
    onFit: () -> Unit,
    autoStop: String,
    onAutoStop: () -> Unit,
    band: Boolean,
    onBand: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SidePanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(state.channel?.name ?: "Player")
            if (matchTitle != null) {
                ListRow(
                    title = "Meciul: $matchTitle",
                    subtitle = "Scor, evenimente, statistici, echipe; salt la faze în arhivă",
                    onClick = onMatch,
                )
                if (sourceCount > 1) {
                    ListRow(title = "Alte surse pentru meci", subtitle = "$sourceCount surse, în ordinea din Sports Hub", onClick = onSources)
                }
            }
            ListRow(title = "Meniu rapid", subtitle = "Meciurile de acum (și cu OK lung)", onClick = onQuick)
            ListRow(
                title = "Pistă audio",
                subtitle = if (state.audioTracks > 1) "${state.audioTracks} piste" else "o singură pistă",
                onClick = onAudio,
                modifier = Modifier.focusRequester(focus),
            )
            ListRow(
                title = "Subtitrări",
                subtitle = if (state.textTracks > 0) "${state.textTracks} disponibile" else "niciuna în stream",
                onClick = onSubtitles,
            )
            ListRow(title = "Întârzierea sunetului", subtitle = "${AudioDelay.short(audioDelayMs)} pe acest canal", onClick = onAudioDelay)
            ListRow(
                title = "Mod de noapte",
                subtitle = if (night) "pornit: dinamica sunetului comprimată" else "oprit",
                onClick = onNight,
                trailing = if (night) {
                    { Text(text = "✓", fontSize = 16.sp) }
                } else {
                    null
                },
            )
            ListRow(title = "Imaginea", subtitle = "${fit.label} pe acest canal", onClick = onFit)
            ListRow(title = "Oprire automată", subtitle = autoStop, onClick = onAutoStop)
            ListRow(
                title = "Banda cu scoruri",
                subtitle = (if (band) "pornită" else "oprită") + ": meciurile favorite live, jos (tasta galbenă)",
                onClick = onBand,
            )
            if (state.variantCount > 1) {
                ListRow(
                    title = "Variantele canalului",
                    subtitle = "${state.variantCount} variante: calitate, limbă, playlist",
                    onClick = onVariants,
                )
                ListRow(title = "Setează ca variantă preferată", subtitle = "pornește prima la acest canal", onClick = onPrefer)
            }
            ListRow(
                title = if (suspect) "Scoate semnalarea „canal posibil greșit”" else "Semnalează: canal posibil greșit",
                subtitle = if (suspect) "eticheta dispare din surse și variante" else "nu se ascunde: rămâne sursă, cu eticheta asta și după celelalte variante",
                onClick = onSuspect,
            )
            ListRow(title = "Canalul anterior", subtitle = previousName ?: "niciunul încă", onClick = onPrevious)
            ListRow(title = "Programul canalului", subtitle = "EPG: ce rulează acum și mai târziu", onClick = onSchedule)
            ListRow(title = "Lista canalelor din grup", onClick = onChannels)
            ListRow(title = "Reconectează", subtitle = "oprește și pornește din nou stream-ul", onClick = onReconnect)
            ListRow(title = "Ieșire din player", onClick = onExit)
        }
    }
}

@Composable
private fun TrackPanel(
    title: String,
    options: List<TrackOption>,
    empty: String,
    onPick: (TrackOption) -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(options) { runCatching { focus.requestFocus() } }
    SidePanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(title)
            if (options.isEmpty()) {
                Text(text = empty, fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp))
            }
            val selected = options.indexOfFirst { it.selected }.coerceAtLeast(0)
            options.forEachIndexed { index, option ->
                ListRow(
                    title = option.label,
                    selected = option.selected,
                    onClick = { onPick(option) },
                    modifier = if (index == selected) Modifier.focusRequester(focus) else Modifier,
                    trailing = if (option.selected) {
                        { Text(text = "✓", fontSize = 16.sp) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SchedulePanel(channelId: Long?, channelName: String, modifier: Modifier) {
    val epg = appContainer().epg
    val programmes by produceState<List<Programme>?>(null, channelId) {
        value = channelId?.let { epg.schedule(it) } ?: emptyList()
    }
    val fallbackFocus = remember { FocusRequester() }
    SidePanel(modifier.width(560.dp)) {
        Column {
            SectionLabel("Program · $channelName")
            val list = programmes
            when {
                list == null -> Unit
                list.isEmpty() -> {
                    LaunchedEffect(Unit) { runCatching { fallbackFocus.requestFocus() } }
                    Text(
                        text = "Fără program pentru acest canal. Adaugă un EPG din Setări → EPG.",
                        fontSize = 14.sp,
                        color = AkColors.TextDim,
                        modifier = Modifier
                            .padding(14.dp)
                            .focusRequester(fallbackFocus)
                            .focusable(),
                    )
                }
                else -> ScheduleList(list, modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
private fun VariantsPanel(controller: PlayerController, currentId: Long?, onPick: (Long) -> Unit, modifier: Modifier) {
    val variants by produceState<List<ChannelWithPlaylist>?>(null) { value = controller.currentVariants() }
    val repo = appContainer().variants
    SidePanel(modifier.width(560.dp)) {
        Column {
            SectionLabel("Variante · una câte una, oprind-o pe cea curentă")
            val list = variants ?: return@Column
            val current = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
            val focus = remember { FocusRequester() }
            LaunchedEffect(list) { runCatching { focus.requestFocus() } }
            LazyColumn(contentPadding = ListPadding, state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                    ListRow(
                        title = c.name,
                        subtitle = listOfNotNull(
                            c.playlistName,
                            c.groupName,
                            if (repo.isPreferred(c)) "preferată" else null,
                            if (repo.failedRecently(c)) "n-a pornit ultima oară" else null,
                        ).joinToString(" · "),
                        selected = c.id == currentId,
                        onClick = { onPick(c.id) },
                        modifier = if (index == current) Modifier.focusRequester(focus) else Modifier,
                        trailing = {
                            c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                            VariantQualityBadge(c.playlistId, c.name, c.quality)
                        },
                    )
                }
            }
        }
    }
}

/** Sursele meciului de pe ecran, în ordinea din Sports Hub (aceleași etichete: încredere, limbă, calitate). */
@Composable
private fun MatchSourcesPanel(
    entry: MatchEntry,
    favorite: Boolean,
    prefs: ro.ak.sportshub.core.model.SourcePreferences,
    currentId: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier,
) {
    val list = remember(entry, favorite, prefs) {
        entry.orderedSources(isFavorite = favorite, prefs = prefs).flatMap { it.members }
    }
    SidePanel(modifier.width(600.dp)) {
        Column {
            SectionLabel("Surse · ${entry.match.title}")
            val current = list.indexOfFirst { it.channel.id == currentId }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
            val focus = remember { FocusRequester() }
            LaunchedEffect(list) { runCatching { focus.requestFocus() } }
            LazyColumn(contentPadding = ListPadding, state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { i, src -> "${src.channel.id}:$i" }) { index, src ->
                    val c = src.channel
                    ListRow(
                        title = c.name,
                        subtitle = listOfNotNull(
                            if (src.rejected) "respinsă de tine" else src.confidence.label,
                            src.station,
                            c.playlistName,
                        ).joinToString(" · "),
                        selected = c.id == currentId,
                        onClick = { onPick(c.id) },
                        modifier = if (index == current) Modifier.focusRequester(focus) else Modifier,
                        trailing = {
                            src.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                            VariantQualityBadge(c.playlistId, c.name, c.quality)
                        },
                    )
                }
            }
        }
    }
}

/** Pragurile verificării redării din informațiile playerului. */
internal object PlaybackCheck {
    /** De la câte cadre aruncate sugerăm „Imagine fluidă”. */
    const val MANY_DROPPED = 30

    /** 25/50 fps pe un ecran de 60 Hz (sau 30/60 pe 50 Hz): mișcarea sacadează regulat (judder). */
    fun mismatch(fps: Float, hz: Float): Boolean {
        if (fps <= 0f || hz <= 0f) return false
        val ratio = hz / fps
        return kotlin.math.abs(ratio - Math.round(ratio)) > 0.05f
    }
}

/** Ce a ajutat la sacadare, pe canal (playlist + nume), cât rulează aplicația: la revenire se aplică din prima. */
private object AdaptMemory {
    val steps = java.util.concurrent.ConcurrentHashMap<String, Set<DropAdapter.Step>>()
}
