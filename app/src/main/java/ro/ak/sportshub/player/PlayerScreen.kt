package ro.ak.sportshub.player

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import ro.ak.sportshub.core.common.player.NumberInput
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.epg.NowNext
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.ProgressLine
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.epg.ScheduleList
import ro.ak.sportshub.ui.epg.formatRange
import ro.ak.sportshub.ui.epg.formatTime
import ro.ak.sportshub.ui.theme.AkColors

private enum class Panel { None, Channels, Menu, Audio, Subtitles, Schedule, Variants }

private const val INFO_MS = 5_000L
private const val NUMBER_COMMIT_MS = 1_500L
private val Overlay = Color(0xE6101318)
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
 */
@Composable
fun PlayerScreen(channelId: Long, zapGroup: Long = 0L, onExit: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val controller = remember { PlayerController(context.applicationContext, container) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(channelId) { controller.play(channelId, zapGroup) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.onForeground() }

    val state by controller.state.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.None) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoToken by remember { mutableIntStateOf(0) }
    val numberInput = remember { NumberInput() }
    var numberText by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }

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
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4_000)
            toast = null
        }
    }
    LaunchedEffect(panel) { if (panel == Panel.None) runCatching { rootFocus.requestFocus() } }

    BackHandler(enabled = panel != Panel.None || numberText.isNotEmpty()) {
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

    fun onKey(code: Int): Boolean {
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                controller.zap(1)
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                controller.zap(-1)
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> panel = Panel.Channels
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS ->
                panel = Panel.Menu
            AndroidKeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> panel = Panel.Audio
            AndroidKeyEvent.KEYCODE_CAPTIONS -> panel = Panel.Subtitles
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                numberInput.isActive -> commitNumber()
                state.status is PlaybackStatus.Failed -> controller.retry()
                infoVisible -> infoVisible = false
                else -> showInfo()
            }
            AndroidKeyEvent.KEYCODE_INFO -> showInfo()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
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
            modifier = Modifier.fillMaxSize(),
        )

        StatusOverlay(state.status, Modifier.align(Alignment.Center))

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

        if ((infoVisible || state.status !is PlaybackStatus.Playing) && panel == Panel.None) {
            InfoBar(state, Modifier.align(Alignment.BottomCenter))
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
            Panel.Variants -> VariantsPanel(
                controller = controller,
                currentId = state.channel?.id,
                onPick = { id ->
                    panel = Panel.None
                    if (id != state.channel?.id) controller.play(id)
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Panel.None -> Unit
        }
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
private fun InfoBar(state: PlayerUiState, modifier: Modifier) {
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
                Text(text = "$it", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AkColors.Accent)
                Spacer(Modifier.width(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = channel.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    channel.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                    QualityBadge(channel.quality)
                }
                Text(
                    text = listOfNotNull(
                        channel.groupName,
                        channel.playlistName,
                        state.position,
                        if (state.variantCount > 1) "${state.variantCount} variante" else null,
                    ).joinToString(" · "),
                    fontSize = 15.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
                    text = "▲▼ canale · ◀ lista grupului · ▶ variante, audio, subtitrări, program · OK ascunde",
                    fontSize = 13.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = clock, fontSize = 26.sp)
                val details = listOfNotNull(
                    state.videoSize,
                    if (state.audioTracks > 1) "${state.audioTracks} piste audio" else null,
                    if (state.textTracks > 0) "subtitrări" else null,
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(text = details, fontSize = 13.sp, color = AkColors.TextDim)
            }
        }
    }
}

@Composable
private fun SidePanel(modifier: Modifier, content: @Composable () -> Unit) {
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
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                    ListRow(
                        title = (c.number?.let { "$it  " } ?: "") + c.name,
                        subtitle = c.playlistName,
                        selected = index == current,
                        onClick = { onPick(c.id) },
                        modifier = if (index == current) Modifier.focusRequester(focus) else Modifier,
                        trailing = {
                            c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                            QualityBadge(c.quality)
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
    previousName: String?,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onPrevious: () -> Unit,
    onReconnect: () -> Unit,
    onChannels: () -> Unit,
    onSchedule: () -> Unit,
    onVariants: () -> Unit,
    onPrefer: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SidePanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(state.channel?.name ?: "Player")
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
            if (state.variantCount > 1) {
                ListRow(
                    title = "Variantele canalului",
                    subtitle = "${state.variantCount} variante: calitate, limbă, playlist",
                    onClick = onVariants,
                )
                ListRow(title = "Setează ca variantă preferată", subtitle = "pornește prima la acest canal", onClick = onPrefer)
            }
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
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            QualityBadge(c.quality)
                        },
                    )
                }
            }
        }
    }
}
