package ro.ak.sportshub.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import ro.ak.sportshub.R
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.theme.AkColors

/**
 * Fereastra video din Sports Hub (F5, OK lung pe un meci): același player ca pe tot ecranul (un singur ExoPlayer,
 * permisul playlistului prin `StreamLease`, failover-ul variantelor), doar mai mic. Cât e pe ecran, lucrul greu din
 * fundal așteaptă (`AppContainer.playing`); la ieșire (Back, alt meci, alt ecran) stream-ul se oprește și permisul se
 * eliberează. Nu e o preîncărcare: pornește doar la cererea ta.
 */
@Composable
fun VideoPreview(channelId: Long, modifier: Modifier = Modifier) {
    val container = appContainer()
    val context = LocalContext.current
    val controller = remember { PlayerController(context.applicationContext, container) }
    DisposableEffect(controller) {
        container.playing.value = true
        onDispose {
            container.playing.value = false
            controller.release()
        }
    }
    LaunchedEffect(channelId) { controller.play(channelId, zapGroup = 0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        container.playing.value = false
        controller.onBackground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        container.playing.value = true
        controller.onForeground()
    }
    val state by controller.state.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = controller.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        val text = when (val s = state.status) {
            PlaybackStatus.Playing -> null
            PlaybackStatus.Idle, PlaybackStatus.Connecting -> "Se pornește…"
            PlaybackStatus.Buffering -> "Se încarcă…"
            is PlaybackStatus.Waiting -> s.message
            is PlaybackStatus.Retrying -> "Reîncerc (${s.attempt}/${s.maxAttempts})…"
            is PlaybackStatus.Failed -> s.reason
        }
        if (text != null) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = AkColors.TextDim,
                maxLines = 2,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
        }
        state.channel?.let { c ->
            Text(
                text = c.name,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0xB30B0F14), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
