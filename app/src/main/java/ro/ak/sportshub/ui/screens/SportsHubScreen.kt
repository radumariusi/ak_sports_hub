package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.data.DemoData
import ro.ak.sportshub.data.DemoMatch
import ro.ak.sportshub.data.MatchDay
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.theme.AkColors

private val Tabs = listOf("LIVE", "Azi", "Mâine", "Săptămâna asta", "Favorite")

@Composable
fun SportsHubScreen(onOpenMatch: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val matches: List<DemoMatch> = when (tab) {
        0 -> DemoData.matches.filter { it.day == MatchDay.LIVE }
        1 -> DemoData.matches.filter { it.day == MatchDay.LIVE || it.day == MatchDay.TODAY }
        2 -> DemoData.matches.filter { it.day == MatchDay.TOMORROW }
        3 -> DemoData.matches
        else -> DemoData.matches.filter { it.favorite }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = Tabs, selectedIndex = tab, onSelect = { tab = it })
        if (matches.isEmpty()) {
            EmptyState(
                title = "Niciun meci aici",
                hint = "Meciurile reale apar după ce adaugi playlisturi și surse de program.",
            )
        } else {
            SectionLabel("Date demonstrative", modifier = Modifier.padding(top = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(matches, key = { it.id }) { match ->
                    val count = match.sources.size
                    ListRow(
                        title = "${match.time}   ${match.title}",
                        subtitle = "${match.sport} · ${match.competition}",
                        onClick = { onOpenMatch(match.id) },
                        trailing = {
                            if (match.favorite) Text(text = "★", fontSize = 16.sp, color = AkColors.Accent)
                            if (match.day == MatchDay.LIVE) Badge(text = "LIVE", color = AkColors.Live)
                            Text(
                                text = if (count == 1) "1 sursă" else "$count surse",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
