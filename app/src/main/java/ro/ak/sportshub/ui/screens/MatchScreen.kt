package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.data.DemoData
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.theme.AkColors

private val Tabs = listOf("Surse", "Pre-meci", "Live")

/**
 * Fereastra de previzualizare a unui meci: se deschide înainte de player.
 * Taburi: sursele găsite, statistici dinaintea meciului, date live.
 */
@Composable
fun MatchScreen(matchId: String) {
    val match = remember(matchId) { DemoData.match(matchId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val firstTab = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    if (match == null) {
        EmptyState(title = "Meciul nu a fost găsit", hint = "Apasă Înapoi.")
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(text = match.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "${match.time} · ${match.sport} · ${match.competition}",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        TabStrip(
            tabs = Tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            firstTabModifier = Modifier.focusRequester(firstTab),
        )
        when (tab) {
            0 -> {
                val sources = remember(match) { match.sources }
                LazyColumn(
                    modifier = Modifier.padding(top = 10.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(sources) { index, source ->
                        ListRow(
                            title = source.channelName,
                            subtitle = source.playlist + if (index == 0) " · sursa preferată" else "",
                            onClick = { message = "Meciurile sunt exemple; sursele reale apar în etapa 6: ${source.channelName}" },
                            trailing = {
                                Badge(text = source.language.uppercase(), color = AkColors.Focus)
                                QualityBadge(source.quality)
                            },
                        )
                    }
                }
                Text(
                    text = message
                        ?: "Calitatea și limba sunt cele declarate de playlist. Sunt ordonate după preferințele din Setări.",
                    fontSize = 13.sp,
                    color = AkColors.TextDim,
                )
            }
            1 -> EmptyState(
                title = "Pre-meci",
                hint = "Formă, clasament, meciuri directe și echipe probabile. Vine odată cu sursele de meciuri.",
            )
            else -> EmptyState(
                title = "Live",
                hint = "Scor, minut, evenimente și statistici live, cu opțiunea „fără spoilere”.",
            )
        }
    }
}
