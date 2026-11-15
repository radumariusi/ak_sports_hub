package ro.ak.sportshub.ui.epg

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.core.database.PlayableChannel
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.theme.AkColors

private sealed interface ScheduleState {
    data object Loading : ScheduleState
    data class Ready(val channel: PlayableChannel?, val programmes: List<Programme>?) : ScheduleState
}

/** Programul unui canal, deschis din Live TV (OK ținut apăsat pe canal). */
@Composable
fun ScheduleScreen(channelId: Long, onPlay: (Long) -> Unit, onPlayArchive: (channelId: Long, start: Long, end: Long) -> Unit = { _, _, _ -> }) {
    val container = appContainer()
    val state by produceState<ScheduleState>(ScheduleState.Loading, channelId) {
        value = ScheduleState.Ready(container.database.channels().playable(channelId), container.epg.schedule(channelId))
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        val ready = state as? ScheduleState.Ready
        val channel = ready?.channel
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = channel?.name ?: "Program", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            channel?.let { VariantQualityBadge(it.playlistId, it.name, it.quality) }
            Spacer(Modifier.width(20.dp))
            if (channel != null) ActionButton(text = "Pornește canalul", onClick = { onPlay(channel.id) })
        }
        Text(
            text = listOfNotNull(
                channel?.groupName,
                channel?.playlistName,
                channel?.catchupDays?.takeIf { it > 0 }?.let { "arhivă $it zile: OK pe o emisiune începută o pornește de la început" },
            ).joinToString(" · "),
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        val programmes = ready?.programmes
        when {
            ready == null -> Unit
            channel == null -> EmptyState("Canalul nu mai există", "Playlistul s-a actualizat între timp.")
            programmes == null -> EmptyState(
                "Fără program",
                "Niciun EPG nu are acest canal. Adaugă un EPG extern din Setări → EPG.",
            )
            programmes.isEmpty() -> EmptyState("Fără emisiuni", "EPG-ul nu are emisiuni pentru zilele următoare.")
            else -> ScheduleList(
                programmes,
                modifier = Modifier.weight(1f),
                archiveDays = channel.catchupDays,
                onPlayArchive = { p -> onPlayArchive(channel.id, p.start, p.stop) },
            )
        }
    }
}
