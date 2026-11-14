package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.theme.AkColors

private val Sections = listOf("Sports Hub", "Live TV", "Setări")

@Composable
fun HomeScreen(onOpenMatch: (String) -> Unit, onOpenPlaylists: () -> Unit, onPlayChannel: (Long) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var focused by rememberSaveable { mutableIntStateOf(0) }
    val firstTab = remember { FocusRequester() }

    // Focus inițial pe meniu, o singură dată (nu și la întoarcerea de pe ecranul unui meci).
    LaunchedEffect(Unit) {
        if (focused == 0) {
            focused = 1
            runCatching { firstTab.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "AK", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AkColors.Accent)
            Spacer(Modifier.width(20.dp))
            TabStrip(
                tabs = Sections,
                selectedIndex = section,
                onSelect = { section = it },
                firstTabModifier = Modifier.focusRequester(firstTab),
            )
        }
        Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            when (section) {
                0 -> SportsHubScreen(onOpenMatch = onOpenMatch)
                1 -> LiveTvScreen(onOpenPlaylists = onOpenPlaylists, onPlayChannel = onPlayChannel)
                else -> SettingsScreen(onOpenPlaylists = onOpenPlaylists)
            }
        }
    }
}
