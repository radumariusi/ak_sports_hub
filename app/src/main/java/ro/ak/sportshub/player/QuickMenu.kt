package ro.ak.sportshub.player

import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.theme.AkColors

/** Meciurile favorite deja anunțate în player (o dată pe meci, cât rulează aplicația). */
internal val AnnouncedKickoffs: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

private val Hour = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Meniul rapid (OK ținut apăsat în player): meciul anunțat, meciurile urmărite (favorite) care se joacă
 * acum, alte meciuri live cu surse și canalul anterior. Fără scoruri (nimic de spoilat). Alegerea unui meci
 * oprește stream-ul curent și pornește sursa recomandată a meciului.
 */
@Composable
fun QuickMenuContent(
    previousName: String?,
    current: MatchEntry?,
    announced: MatchEntry?,
    favorites: List<MatchEntry>,
    others: List<MatchEntry>,
    onPrevious: () -> Unit,
    onMatchPanel: (() -> Unit)?,
    onPlayMatch: (MatchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val zone = ZoneId.systemDefault()
    var focusUsed = false
    fun firstFocus(): Modifier = if (!focusUsed) {
        focusUsed = true
        Modifier.focusRequester(focus)
    } else {
        Modifier
    }
    // Ordinea elementelor decide cine primește focusul: meciul anunțat, apoi panoul meciului curent.
    val announcedModifier = if (announced != null) firstFocus() else Modifier
    val panelModifier = if (onMatchPanel != null && current != null) firstFocus() else Modifier
    val previousModifier = firstFocus()

    SidePanel(modifier.width(560.dp)) {
        LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "title") { SectionLabel("Meniu rapid") }
            if (announced != null) {
                item(key = "announced") {
                    ListRow(
                        title = "★ A început: ${announced.match.title}",
                        subtitle = listOfNotNull(announced.match.competition, "OK: mută aici (stream-ul curent se oprește întâi)").joinToString(" · "),
                        onClick = { onPlayMatch(announced) },
                        modifier = announcedModifier,
                        trailing = { Badge(text = "LIVE", color = AkColors.Live) },
                    )
                }
            }
            if (onMatchPanel != null && current != null) {
                item(key = "panel") {
                    ListRow(
                        title = "Detaliile meciului: ${current.match.title}",
                        subtitle = "Scor, evenimente, statistici, echipe; salt la faze în arhivă",
                        onClick = onMatchPanel,
                        modifier = panelModifier,
                    )
                }
            }
            item(key = "previous") {
                ListRow(
                    title = "Canalul anterior",
                    subtitle = previousName ?: "niciunul încă",
                    onClick = onPrevious,
                    modifier = previousModifier,
                )
            }
            matchSection("Meciurile tale, acum", "fav", favorites, current, zone, onPlayMatch)
            matchSection("Alte meciuri live", "other", others, current, zone, onPlayMatch)
            if (favorites.isEmpty() && others.isEmpty()) {
                item(key = "none") {
                    Text(
                        text = "Niciun meci live cu surse în playlisturile tale.",
                        fontSize = 14.sp,
                        color = AkColors.TextDim,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.matchSection(
    title: String,
    prefix: String,
    list: List<MatchEntry>,
    current: MatchEntry?,
    zone: ZoneId,
    onPlayMatch: (MatchEntry) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "$prefix-h") { SectionLabel(title, modifier = Modifier.padding(top = 6.dp)) }
    items(list, key = { "$prefix:" + it.match.id }) { e ->
        val m = e.match
        val onScreen = current?.match?.id == m.id
        ListRow(
            title = "${Instant.ofEpochMilli(m.start).atZone(zone).format(Hour)}   ${m.title}",
            subtitle = listOfNotNull(
                m.competition,
                if (e.summary.count == 1) "1 sursă" else "${e.summary.count} surse",
                if (onScreen) "pe ecran acum" else null,
            ).joinToString(" · "),
            selected = onScreen,
            onClick = { onPlayMatch(e) },
            trailing = { QualityBadge(e.summary.best) },
        )
    }
}
