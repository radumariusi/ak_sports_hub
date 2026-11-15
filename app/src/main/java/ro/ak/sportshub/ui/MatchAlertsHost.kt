package ro.ak.sportshub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.sports.AutoSwitch
import ro.ak.sportshub.data.GoalWatcher
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.data.bestSource
import ro.ak.sportshub.ui.theme.AkColors

private val Panel = Color(0xEB0B0F14)
private const val CHECK_MS = 5_000L
private const val NOTICE_MS = 5_000L

/**
 * Peste toate ecranele (în afară de player, care le are pe ale lui):
 * - F1: cu un minut înainte de un meci favorit, numărătoarea „Trec pe meci în 10 s” (Back anulează), apoi
 *   pornește sursa recomandată ([onPlayMatch]);
 * - F2: alertele de gol și „Final” ([GoalWatcher]) și celelalte anunțuri din aplicație, sus, câteva secunde.
 * Totul rulează doar cât aplicația e pe ecran.
 */
@Composable
fun MatchAlertsHost(onPlayMatch: (channelId: Long, matchId: String) -> Unit, content: @Composable BoxScope.() -> Unit) {
    val container = appContainer()
    val playing by container.playing.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<MatchEntry?>(null) }
    var pendingSince by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val watcher = remember {
        GoalWatcher(container.sports, container.matchDetails, container.sportsSettings, container.watchingMatch, container.inAppNotices)
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { watcher.run() }
            // Anunțurile apar aici doar în afara playerului (playerul le arată în colțul lui).
            launch { container.inAppNotices.collect { if (!container.playing.value) notice = it } }
            while (true) {
                now = System.currentTimeMillis()
                val s = container.sportsSettings.current
                if (pending == null && s.autoSwitch && !container.playing.value) {
                    val snap = container.sports.snapshot.value
                    val due = AutoSwitch.due(snap.matches.map { it.match }, s.favorites, now, container.handledKickoffs, null)
                    val entry = due?.let { m -> snap.matches.firstOrNull { it.match.id == m.id && it.bestSource(s) != null } }
                    if (due != null) container.handledKickoffs += due.id
                    if (entry != null) {
                        pending = entry
                        pendingSince = now
                    }
                }
                val p = pending
                if (p != null) {
                    if (container.playing.value) {
                        // Ai pornit singur playerul: comutarea trece acolo (meciul e deja „tratat”, deci nu se repetă).
                        pending = null
                    } else if (AutoSwitch.secondsLeft(pendingSince, now) == 0) {
                        pending = null
                        p.bestSource(container.sportsSettings.current)?.let { onPlayMatch(it.id, p.match.id) }
                    }
                }
                delay(if (pending != null) 250 else CHECK_MS)
            }
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    BackHandler(enabled = pending != null) { pending = null }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (!playing) {
            pending?.let { p ->
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 32.dp)
                        .width(440.dp)
                        .background(Panel, RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(text = "★ Începe meciul favorit", fontSize = 13.sp, color = AkColors.Accent)
                    Text(text = p.match.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        text = "Pornesc meciul în ${AutoSwitch.secondsLeft(pendingSince, now)} s · Back: anulează",
                        fontSize = 14.sp,
                        color = AkColors.TextDim,
                    )
                }
            }
            notice?.let {
                Text(
                    text = it,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
