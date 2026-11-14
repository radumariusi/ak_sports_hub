package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import ro.ak.sportshub.core.common.LoadFormat
import ro.ak.sportshub.data.DiskUsage
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.theme.AkColors

/**
 * Încărcările în curs, numărate pe viu: playlisturi (octeți descărcați, canale citite), surse EPG
 * (octeți, canale, emisiuni) și potrivirea canalelor cu programul. Nu ocupă loc când nu se încarcă nimic.
 */
@Composable
fun LoadsPanel(modifier: Modifier = Modifier) {
    val tasks by appContainer().loads.tasks.collectAsStateWithLifecycle()
    if (tasks.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(AkColors.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tasks.forEach { t ->
            Column {
                Text(text = t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AkColors.Accent)
                Text(text = t.describe(), fontSize = 14.sp, color = AkColors.Text)
                t.fraction?.let { ProgressLine(it, Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
        }
    }
}

/** Spațiul ocupat, recitit la câteva secunde (crește pe viu în timpul unei încărcări). */
@Composable
fun rememberDiskUsage(): DiskUsage? {
    val epg = appContainer().epg
    val usage by produceState<DiskUsage?>(null) {
        while (true) {
            value = epg.diskUsage()
            delay(3_000)
        }
    }
    return usage
}

fun DiskUsage.describe(): String =
    "${LoadFormat.size(total)} · baza canalelor ${LoadFormat.size(channelsDatabase)} · program ${LoadFormat.size(epgDatabase)} · " +
        "fișiere EPG ${LoadFormat.size(epgFiles)}"
