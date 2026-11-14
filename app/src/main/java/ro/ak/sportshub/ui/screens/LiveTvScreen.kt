package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.database.GroupWithPlaylist
import ro.ak.sportshub.core.epg.NowNext
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.epg.nowNextLine
import ro.ak.sportshub.ui.epg.rememberMinuteClock
import ro.ak.sportshub.ui.playlists.formatCount
import ro.ak.sportshub.ui.theme.AkColors

/** Unde se caută. */
private enum class SearchMode(val label: String, val groups: Boolean, val channels: Boolean) {
    BOTH("Grupuri și canale", true, true),
    CHANNELS("Doar canale", false, true),
    GROUPS("Doar grupuri", true, false),
}

private const val SEARCH_LIMIT = 500

/**
 * Live TV: grupuri în stânga, canale în dreapta, totul ca liste de text.
 * Căutarea are un singur câmp și un comutator: grupuri și canale, doar canale sau doar grupuri.
 */
@Composable
fun LiveTvScreen(
    onOpenPlaylists: () -> Unit,
    onPlayChannel: (channelId: Long, zapGroup: Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
) {
    val container = appContainer()
    val dao = container.database.channels()

    val playlists by remember { container.playlists.observePlaylists() }.collectAsStateWithLifecycle(initialValue = null)
    val refreshing by container.playlists.refreshing.collectAsStateWithLifecycle()
    val groups by remember { dao.observeGroups() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastChannel by container.playerHistory.last.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedGroup by rememberSaveable { mutableLongStateOf(0L) }
    var query by rememberSaveable { mutableStateOf("") }
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingQuery by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val mode = SearchMode.entries[modeIndex]

    val ftsQuery = SearchText.ftsQuery(query)
    val searching = ftsQuery != null

    val groupChannels by remember(selectedGroup) {
        if (selectedGroup > 0) dao.observeChannelsInGroup(selectedGroup) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchResults by remember(ftsQuery, mode.channels) {
        if (ftsQuery != null && mode.channels) dao.search(ftsQuery, SEARCH_LIMIT) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val list = playlists
    if (list != null && list.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                title = "Niciun playlist",
                hint = "Adaugă un playlist M3U sau un cont Xtream ca să-ți vezi canalele.",
                modifier = Modifier.weight(1f),
            )
            ActionButton(text = "Adaugă un playlist", onClick = onOpenPlaylists, modifier = Modifier.padding(bottom = 40.dp))
        }
        return
    }

    val visibleGroups: List<GroupWithPlaylist> = remember(groups, query, mode) {
        if (searching && mode.groups) groups.filter { SearchText.matches(it.name, query) } else groups
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Stânga: căutare + grupuri
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .background(AkColors.Panel, RoundedCornerShape(8.dp))
                .padding(vertical = 8.dp),
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lastChannel?.let { ref ->
                    item {
                        ListRow(
                            title = "Ultimul canal",
                            subtitle = ref.name,
                            onClick = {
                                scope.launch {
                                    val id = container.playerHistory.resolve(ref)
                                    if (id != null) {
                                        onPlayChannel(id, 0L)
                                    } else {
                                        container.playerHistory.forget(ref)
                                        message = "${ref.name} nu mai există în playlist."
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    ListRow(
                        title = if (query.isEmpty()) "Caută" else "Caută: $query",
                        subtitle = mode.label,
                        onClick = { editingQuery = true },
                    )
                }
                item {
                    ListRow(
                        title = "Caută în",
                        onClick = { modeIndex = (modeIndex + 1) % SearchMode.entries.size },
                        trailing = { Text(text = mode.label, fontSize = 13.sp) },
                    )
                }
                if (query.isNotEmpty()) {
                    item { ListRow(title = "Șterge căutarea", onClick = { query = "" }) }
                }
                item {
                    SectionLabel(
                        if (searching && mode.groups) "Grupuri găsite: ${visibleGroups.size}"
                        else "Grupuri (${visibleGroups.size})",
                    )
                }
                if (visibleGroups.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                refreshing.isNotEmpty() -> "Se încarcă playlisturile…"
                                searching -> "Niciun grup găsit."
                                else -> "Niciun canal încă. Verifică playlisturile din Setări."
                            },
                            fontSize = 14.sp,
                            color = AkColors.TextDim,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                items(visibleGroups, key = { it.id }) { g ->
                    val multiplePlaylists = (list?.count { it.enabled } ?: 0) > 1
                    val countTrailing: (@Composable () -> Unit)? = if (multiplePlaylists) {
                        null
                    } else {
                        { Text(text = formatCount(g.channelCount), fontSize = 13.sp, color = AkColors.TextDim) }
                    }
                    ListRow(
                        title = g.name,
                        subtitle = if (multiplePlaylists) "${g.playlistName} · ${formatCount(g.channelCount)}" else null,
                        selected = g.id == selectedGroup && !(searching && mode.channels),
                        onClick = {
                            selectedGroup = g.id
                            // Alegerea unui grup arată canalele lui, chiar dacă era o căutare de canale.
                            if (searching && mode.channels) query = ""
                        },
                        trailing = countTrailing,
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Dreapta: canalele grupului sau rezultatele căutării
        Column(modifier = Modifier.fillMaxSize()) {
            val showingSearch = searching && mode.channels
            val channels = if (showingSearch) searchResults else groupChannels
            val title = when {
                showingSearch -> "Canale găsite pentru „$query”: " +
                    (if (channels.size >= SEARCH_LIMIT) "primele $SEARCH_LIMIT" else formatCount(channels.size))
                selectedGroup > 0 -> groups.firstOrNull { it.id == selectedGroup }?.name ?: "Canale"
                else -> "Canale"
            }
            SectionLabel(title)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    channels.isNotEmpty() -> ChannelList(
                        channels,
                        showGroup = showingSearch,
                        // Din căutare, CH+/CH− rămân în grupul canalului; din grup, în grupul ales.
                        onClick = { c -> onPlayChannel(c.id, if (showingSearch) 0L else selectedGroup) },
                        onLongClick = { c -> onOpenChannel(c.id) },
                    )
                    showingSearch -> EmptyState(title = "Niciun canal găsit", hint = "Încearcă alt cuvânt sau doar începutul lui.")
                    selectedGroup == 0L -> EmptyState(title = "Alege un grup", hint = "Sau caută un canal după nume.")
                    else -> EmptyState(title = "Grupul e gol", hint = "Actualizează playlistul din Setări.")
                }
            }
            Text(
                text = message ?: "Ține apăsat OK pe un canal pentru variante, varianta preferată, corecții și program. " +
                    "Calitatea și limba sunt cele declarate de playlist.",
                fontSize = 13.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (editingQuery) {
        TextInputDialog(
            title = "Caută",
            hint = "${mode.label}. Fără diacritice e în regulă: „romania” găsește „România”.",
            initial = query,
            onDismiss = { editingQuery = false },
            onConfirm = {
                query = it
                editingQuery = false
            },
        )
    }
}

/** Un rând din listă: un canal, cu toate variantele lui (dacă gruparea e activă). */
private data class ChannelRow(
    val key: String,
    /** Ce pornește OK: varianta preferată sau cea mai bună (din toate playlisturile). */
    val shown: ChannelWithPlaylist,
    val variants: List<ChannelWithPlaylist>,
)

@Composable
private fun ChannelList(
    channels: List<ChannelWithPlaylist>,
    showGroup: Boolean,
    onClick: (ChannelWithPlaylist) -> Unit,
    onLongClick: (ChannelWithPlaylist) -> Unit,
) {
    val container = appContainer()
    val epg = container.epg
    val variantState by container.variantStore.state.collectAsStateWithLifecycle()
    val grouped = variantState.groupVariants
    val now = rememberMinuteClock()

    // Variantele din toate playlisturile pentru canalele din listă (din baza locală, fără rețea).
    val families by produceState(emptyMap<String, List<ChannelWithPlaylist>>(), channels, grouped, variantState) {
        value = if (grouped) container.variants.families(channels.map { it.groupKey }) else emptyMap()
    }
    val rows = remember(channels, grouped, families) {
        if (grouped) {
            ChannelFamilies.collapse(channels) { it.groupKey }.map { f ->
                val all = families[f.key] ?: f.members
                ChannelRow(f.key, all.first(), all)
            }
        } else {
            channels.map { ChannelRow(it.id.toString(), it, listOf(it)) }
        }
    }
    // Acum / urmează, reîmprospătat în fiecare minut; la un canal grupat, din prima variantă care are EPG.
    val nowNext by produceState(emptyMap<Long, NowNext>(), rows, now) {
        value = epg.nowNext(rows.flatMap { r -> r.variants.take(EPG_VARIANTS).map { it.id } }, now)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows, key = { it.key }) { row ->
            val c = row.shown
            val nn = row.variants.take(EPG_VARIANTS).firstNotNullOfOrNull { nowNext[it.id] }
            val meta = buildList {
                if (showGroup) add(c.groupName)
                add(c.playlistName)
                if (row.variants.size > 1) add(variantSummary(row.variants))
                c.catchupDays?.let { add("înapoi în timp: $it zile") }
            }.joinToString(" · ")
            ListRow(
                title = (c.number?.let { "$it  " } ?: "") + c.name,
                subtitle = nowNextLine(nn)?.let { if (showGroup) "$it · ${c.groupName}" else it } ?: meta,
                progress = nn?.now?.progress(now),
                onClick = { onClick(c) },
                onLongClick = { onLongClick(c) },
                trailing = {
                    if (row.variants.size > 1) {
                        Text(text = "${row.variants.size} var.", fontSize = 13.sp, color = AkColors.TextDim)
                    }
                    c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                    QualityBadge(c.quality)
                },
            )
        }
    }
}

private const val EPG_VARIANTS = 6

/** „4K · FHD · HD” – calitățile disponibile ale unui canal grupat. */
private fun variantSummary(variants: List<ChannelWithPlaylist>): String =
    variants.map { it.quality }.distinct().sortedByDescending { it.rank }.joinToString(" · ") { it.label }
