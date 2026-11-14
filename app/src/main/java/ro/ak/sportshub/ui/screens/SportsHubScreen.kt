package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.sports.MatchFilter
import ro.ak.sportshub.core.sports.MatchLists
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.MatchTab
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.TeamCalendar
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

private enum class FilterDialog { SPORT, COMPETITION, TEAM, LANGUAGE, QUALITY }

private val RoLocale = Locale.forLanguageTag("ro")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", RoLocale)
private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", RoLocale)

@Composable
fun SportsHubScreen(onOpenMatch: (String) -> Unit, onOpenSettings: () -> Unit, onPlay: (Long) -> Unit = {}) {
    val container = appContainer()
    val sports = container.sports
    val store = container.sportsSettings
    val snapshot by sports.snapshot.collectAsStateWithLifecycle()
    val status by sports.status.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val revealed by container.matchDetails.revealed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FilterDialog?>(null) }

    val filter = settings.filter
    val zone = remember { ZoneId.systemDefault() }
    val now = snapshot.computedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    // Ultimul tab („Evenimente”) arată canalele EVENT / PPV din playlisturi, nu meciuri.
    val eventsTab = tab == MatchTab.entries.size
    val currentTab = MatchTab.entries[tab.coerceAtMost(MatchTab.entries.lastIndex)]

    val visible: List<MatchEntry> = remember(snapshot, settings, tab) {
        val byId = snapshot.matches.associateBy { it.match.id }
        val inTab = snapshot.matches.filter { e ->
            MatchLists.inTab(e.match, currentTab, now, zone, settings.favorites.isFavorite(e.match)) &&
                filter.accepts(e.match, e.summary)
        }
        MatchLists.sort(inTab.map { it.match }).mapNotNull { byId[it.id] }
    }

    // Tabul Favorite: calendarul fiecărei echipe favorite (următoarele 5 meciuri), apoi restul favoritelor.
    val calendar: List<Pair<String, List<MatchEntry>>> = remember(snapshot, settings, tab) {
        if (currentTab != MatchTab.FAVORITES || settings.favorites.teams.isEmpty()) {
            emptyList()
        } else {
            val byId = snapshot.matches.associateBy { it.match.id }
            TeamCalendar.build(settings.favorites.teams, snapshot.matches.map { it.match }, now)
                .map { (team, ms) -> team to ms.mapNotNull { byId[it.id] } }
        }
    }
    val otherFavorites = remember(visible, calendar) {
        val shown = calendar.flatMap { it.second }.map { it.match.id }.toSet()
        if (calendar.isEmpty()) visible else visible.filterNot { it.match.id in shown }
    }

    val setFilter: ((MatchFilter) -> MatchFilter) -> Unit = { transform -> store.update { it.copy(filter = transform(it.filter)) } }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = MatchTab.entries.map { it.label } + EventsTabLabel, selectedIndex = tab, onSelect = { tab = it })

        if (eventsTab) {
            EventChannelsList(snapshot = snapshot, enabled = settings.eventChannels, zone = zone, onOpenMatch = onOpenMatch, onPlay = onPlay)
            return@Column
        }

        LazyRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ActionButton(text = "Sport: " + (filter.sport?.label ?: "toate"), onClick = { dialog = FilterDialog.SPORT }) }
            item { ActionButton(text = "Competiție: " + (filter.competition ?: "toate"), onClick = { dialog = FilterDialog.COMPETITION }) }
            item { ActionButton(text = "Echipă: " + (filter.team ?: "oricare"), onClick = { dialog = FilterDialog.TEAM }) }
            item { ActionButton(text = "Limbă: " + (filter.language?.uppercase() ?: "oricare"), onClick = { dialog = FilterDialog.LANGUAGE }) }
            item { ActionButton(text = "Calitate: " + (filter.minQuality?.let { it.label + "+" } ?: "oricare"), onClick = { dialog = FilterDialog.QUALITY }) }
            item {
                ActionButton(
                    text = if (filter.onlyWithSources) "Doar cu surse: da" else "Doar cu surse: nu",
                    onClick = { setFilter { it.copy(onlyWithSources = !it.onlyWithSources) } },
                )
            }
            if (!filter.isEmpty) item { ActionButton(text = "Fără filtre", onClick = { setFilter { MatchFilter() } }) }
            item { ActionButton(text = "Actualizează", onClick = { sports.refreshInBackground(force = true) }) }
            item { ActionButton(text = "Setări", onClick = onOpenSettings) }
        }

        Text(
            text = status ?: summaryLine(visible.size, snapshot.matches.size, snapshot.loading),
            fontSize = 13.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        if (visible.isEmpty() && calendar.isEmpty()) {
            EmptyState(
                title = when {
                    snapshot.loading -> "Se caută meciurile…"
                    snapshot.matches.isEmpty() -> "Niciun meci găsit încă"
                    else -> "Niciun meci aici"
                },
                hint = when {
                    snapshot.loading -> "Din EPG-ul tău și din TheSportsDB."
                    snapshot.matches.isEmpty() -> "Meciurile vin din EPG-ul playlisturilor și din TheSportsDB (Setări → Sports Hub)."
                    currentTab == MatchTab.FAVORITES && settings.favorites.teams.isEmpty() && settings.favorites.competitions.isEmpty() ->
                        "Marchează echipe sau competiții ca favorite din ecranul unui meci sau din Setări."
                    else -> "Încearcă alt tab sau scoate filtrele."
                },
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                calendar.forEach { (team, entries) ->
                    item(key = "team:$team") { SectionLabel("$team · următoarele meciuri", modifier = Modifier.padding(top = 6.dp)) }
                    if (entries.isEmpty()) {
                        item(key = "team-empty:$team") {
                            Text(
                                text = "Niciun meci în zilele din program.",
                                fontSize = 13.sp,
                                color = AkColors.TextDim,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(entries, key = { "team:$team:" + it.match.id }) { entry ->
                        MatchRow(
                            entry = entry,
                            favorite = true,
                            hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                            showDay = true,
                            zone = zone,
                            onClick = { onOpenMatch(entry.match.id) },
                        )
                    }
                }
                if (calendar.isNotEmpty() && otherFavorites.isNotEmpty()) {
                    item(key = "others") { SectionLabel("Alte meciuri favorite", modifier = Modifier.padding(top = 6.dp)) }
                }
                items(otherFavorites, key = { it.match.id }) { entry ->
                    MatchRow(
                        entry = entry,
                        favorite = settings.favorites.isFavorite(entry.match),
                        hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                        showDay = currentTab == MatchTab.WEEK || currentTab == MatchTab.FAVORITES,
                        zone = zone,
                        onClick = { onOpenMatch(entry.match.id) },
                    )
                }
            }
        }
    }

    when (dialog) {
        FilterDialog.SPORT -> {
            val options = listOf<Sport?>(null) + Sport.entries
            ChoiceDialog(
                title = "Sport",
                options = options.map { it?.label ?: "Toate" },
                selectedIndex = options.indexOf(filter.sport),
                onDismiss = { dialog = null },
                onSelect = { i -> setFilter { it.copy(sport = options[i]) }; dialog = null },
            )
        }
        FilterDialog.COMPETITION -> {
            val options = listOf<String?>(null) + MatchLists.competitions(snapshot.matches.map { it.match })
            ChoiceDialog(
                title = "Competiție",
                options = options.map { it ?: "Toate" },
                selectedIndex = options.indexOf(filter.competition).coerceAtLeast(0),
                onDismiss = { dialog = null },
                onSelect = { i -> setFilter { it.copy(competition = options[i]) }; dialog = null },
            )
        }
        FilterDialog.TEAM -> TextInputDialog(
            title = "Echipă",
            initial = filter.team.orEmpty(),
            hint = "O parte din numele echipei. Gol = oricare.",
            onDismiss = { dialog = null },
            onConfirm = { text -> setFilter { it.copy(team = text.ifBlank { null }) }; dialog = null },
        )
        FilterDialog.LANGUAGE -> {
            val known = snapshot.matches.flatMap { it.summary.languages }.toSet()
            val ordered = (settings.sourcePreferences.preferredLanguages.map { it.lowercase() } + known.sorted()).distinct()
            val options = listOf<String?>(null) + ordered
            ChoiceDialog(
                title = "Limba audio a surselor",
                hint = "Din informațiile playlistului (prefixul de țară sau atributele canalului).",
                options = options.map { it?.uppercase() ?: "Oricare" },
                selectedIndex = options.indexOf(filter.language).coerceAtLeast(0),
                onDismiss = { dialog = null },
                onSelect = { i -> setFilter { it.copy(language = options[i]) }; dialog = null },
            )
        }
        FilterDialog.QUALITY -> {
            val options = listOf(null, Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            ChoiceDialog(
                title = "Calitatea minimă a unei surse",
                options = options.map { it?.let { q -> q.label + " sau mai bună" } ?: "Oricare" },
                selectedIndex = options.indexOf(filter.minQuality).coerceAtLeast(0),
                onDismiss = { dialog = null },
                onSelect = { i -> setFilter { it.copy(minQuality = options[i]) }; dialog = null },
            )
        }
        null -> Unit
    }
}

private fun summaryLine(visible: Int, total: Int, loading: Boolean): String = when {
    loading -> "Se încarcă…"
    else -> "$visible meciuri afișate din $total"
}

@Composable
private fun MatchRow(entry: MatchEntry, favorite: Boolean, hideScore: Boolean, showDay: Boolean, zone: ZoneId, onClick: () -> Unit) {
    val m = entry.match
    val start = Instant.ofEpochMilli(m.start).atZone(zone)
    val time = if (showDay) "${start.format(DayFormat)} ${start.format(TimeFormat)}" else start.format(TimeFormat)
    val score = if (!hideScore && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED) {
        "  ${m.homeScore}–${m.awayScore}"
    } else {
        ""
    }
    val count = entry.summary.count
    ListRow(
        title = "$time   ${m.title}$score",
        subtitle = listOfNotNull(m.sport?.label, m.competition, if (m.status == MatchStatus.FINISHED) "încheiat" else null)
            .joinToString(" · "),
        onClick = onClick,
        trailing = {
            if (favorite) Text(text = "★", fontSize = 16.sp, color = AkColors.Accent)
            if (m.isLive) Badge(text = "LIVE", color = AkColors.Live)
            if (count > 0) QualityBadge(entry.summary.best)
            Text(
                text = when (count) {
                    0 -> "fără surse"
                    1 -> "1 sursă"
                    else -> "$count surse"
                } + when (val guesses = count - entry.summary.sure) {
                    0 -> ""
                    count -> if (count == 1) " presupusă" else " presupuse"
                    else -> " ($guesses presupuse)"
                },
                fontSize = 13.sp,
                color = if (count == 0) AkColors.TextDim else AkColors.Text,
                modifier = Modifier.padding(start = 12.dp),
            )
        },
    )
}

private const val EventsTabLabel = "Evenimente"

/**
 * Tabul „Evenimente”: canalele EVENT / PPV din playlisturi care au acum un eveniment în nume, după oră.
 * OK deschide meciul (cu toate sursele lui) dacă s-a legat de unul, altfel pornește canalul.
 */
@Composable
private fun EventChannelsList(
    snapshot: ro.ak.sportshub.data.SportsSnapshot,
    enabled: Boolean,
    zone: ZoneId,
    onOpenMatch: (String) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val events = snapshot.events
    Text(
        text = when {
            !enabled -> "Canalele EVENT / PPV sunt oprite (Setări → Sports Hub)."
            else -> "${events.size} canale cu eveniment" +
                (if (snapshot.emptyEventChannels > 0) " · ${snapshot.emptyEventChannels} goale acum" else "") +
                " · OK: meciul (dacă e legat) sau canalul"
        },
        fontSize = 13.sp,
        color = AkColors.TextDim,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    if (enabled && events.isEmpty()) {
        EmptyState(
            title = if (snapshot.loading) "Se caută canalele…" else "Niciun canal EVENT / PPV cu eveniment",
            hint = "Canalele furnizorului care au meciul în nume (de exemplu „EVENT 03: UFC 310 20:00”) apar aici și ca surse sigure la meciuri.",
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(events, key = { "ev:" + it.channel.id }) { e ->
            val time = e.slot.start?.let { val z = Instant.ofEpochMilli(it).atZone(zone); "${z.format(DayFormat)} ${z.format(TimeFormat)}" } ?: "oră necunoscută"
            ListRow(
                title = "$time   ${e.slot.title ?: e.channel.name}",
                subtitle = listOfNotNull(
                    e.channel.name,
                    e.channel.playlistName,
                    e.slot.sport?.label,
                    if (e.matchId != null) "legat de un meci" else null,
                ).joinToString(" · "),
                onClick = { if (e.matchId != null) onOpenMatch(e.matchId) else onPlay(e.channel.id) },
                onLongClick = { onPlay(e.channel.id) },
                trailing = { QualityBadge(e.channel.quality) },
            )
        }
    }
}
