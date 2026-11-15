package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.DefaultRights
import ro.ak.sportshub.core.sports.PresetRights
import ro.ak.sportshub.core.sports.RightsRule
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

private val KnowledgeTabs = listOf("Posturi", "Drepturi TV", "Echipe", "Învățat", "Fără EPG")

/** Ce fereastră e deschisă pe ecranul dicționarului. */
private sealed interface KDialog {
    /** Numele unui post nou, de legat de un canal. */
    data object NewStation : KDialog
    /** Caută canalul (după [query]) pentru postul [station]. */
    data class PickChannel(val station: String, val query: String?) : KDialog
    data class StationEntry(val station: String, val groupKey: String, val label: String) : KDialog
    data class RuleMenu(val index: Int) : KDialog
    /** Editează regula [index] (sau una nouă, cu -1). */
    data class RuleEdit(val index: Int) : KDialog
    data object NewTeam : KDialog
    data class TeamEntry(val alias: String) : KDialog
    data class LearnedEntry(val competition: String, val groupKey: String) : KDialog
    data object Forget : KDialog
}

/**
 * Dicționarul Sports Hub: posturile anunțate fără canal (le legi de un canal), regulile de drepturi TV
 * („competiție → posturi”), alias-urile de echipe și ce a învățat aplicația din confirmările tale.
 */
@Composable
fun SportsKnowledgeScreen() {
    val container = appContainer()
    val store = container.sportsKnowledge
    val k by store.state.collectAsStateWithLifecycle()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<KDialog?>(null) }
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    // Numele afișat al canalelor din dicționar (în preferințe stă doar cheia de grupare).
    val keys = remember(k) { (k.stationAliases.values.flatten() + k.learnedRights.values.flatten()).distinct() }
    val names by produceState(emptyMap<String, String>(), keys) {
        value = keys.chunked(500).flatMap { container.database.channels().variants(it) }
            .groupBy { it.groupKey }.mapValues { (_, list) -> list.first().name }
    }
    fun nameOf(groupKey: String) = names[groupKey] ?: groupKey.substringAfter(':')

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(text = "Dicționar Sports Hub", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
        TabStrip(tabs = KnowledgeTabs, selectedIndex = tab, onSelect = { tab = it }, firstTabModifier = Modifier.focusRequester(firstTab))
        LazyColumn(modifier = Modifier.padding(top = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (tab) {
                0 -> {
                    item {
                        ListRow(
                            title = "+ Leagă un post de un canal",
                            subtitle = "De exemplu „Sky Sp ME” → canalul tău Sky Sports Main Event",
                            onClick = { dialog = KDialog.NewStation },
                        )
                    }
                    item { SectionLabel("Posturi anunțate fără canal (OK: alege canalul)") }
                    if (snapshot.unmatchedStations.isEmpty()) {
                        item { Hint("Toate posturile anunțate pentru meciurile din listă au fost găsite (sau niciun furnizor nu a anunțat posturi).") }
                    }
                    snapshot.unmatchedStations.forEach { st ->
                        item(key = "u:$st") { ListRow(title = st, onClick = { dialog = KDialog.PickChannel(st, null) }) }
                    }
                    item { SectionLabel("Dicționarul tău (OK: șterge)") }
                    if (k.stationAliases.isEmpty()) item { Hint("Gol. Se completează și singur când confirmi o sursă găsită după postul anunțat.") }
                    k.stationAliases.entries.sortedBy { it.key }.forEach { (station, groupKeys) ->
                        groupKeys.sorted().forEach { gk ->
                            item(key = "a:$station|$gk") {
                                ListRow(
                                    title = station,
                                    subtitle = "→ " + nameOf(gk),
                                    onClick = { dialog = KDialog.StationEntry(station, gk, nameOf(gk)) },
                                )
                            }
                        }
                    }
                    item {
                        Hint(
                            "Dicționarul încorporat știe deja nume vechi și prescurtări (BT Sport = TNT Sports, Look Sport = Prima Sport, " +
                                "Sky Sp ME = Sky Sports Main Event…). Țara de la finalul numelui („Digi Sport 1 Romania”) se folosește ca țară.",
                        )
                    }
                }
                1 -> {
                    item { ListRow(title = "+ Adaugă o regulă", subtitle = "Competiție → posturile care o transmit", onClick = { dialog = KDialog.RuleEdit(-1) }) }
                    k.rules.forEachIndexed { i, r ->
                        item(key = "r:$i") {
                            ListRow(
                                title = r.label,
                                subtitle = (if (r.enabled) "" else "oprită · ") + "se potrivește cu: " + r.competitions.joinToString(", "),
                                onClick = { dialog = KDialog.RuleMenu(i) },
                                trailing = { Text(text = if (r.enabled) "Da" else "Nu", fontSize = 14.sp) },
                            )
                        }
                    }
                    item { SectionLabel("Drepturi TV preumplute (livrate cu aplicația)") }
                    item {
                        ListRow(
                            title = "Folosește drepturile TV preumplute",
                            subtitle = "${PresetRights.rules.size} reguli pe țări (UK, RO, SUA, Canada, DE, ES, IT, FR, PT, NL), sezonul 2026/27; presupuneri",
                            onClick = { store.update { it.copy(presetRights = !it.presetRights) } },
                            trailing = { Text(text = if (k.presetRights) "Da" else "Nu", fontSize = 14.sp) },
                        )
                    }
                    if (k.presetRights) {
                        PresetRights.rules.forEach { r ->
                            item(key = "p:${r.id}") {
                                val on = r.id !in k.disabledPresets
                                ListRow(
                                    title = r.label,
                                    subtitle = "OK pornește / oprește regula",
                                    onClick = {
                                        store.update { st ->
                                            st.copy(disabledPresets = if (on) st.disabledPresets + r.id else st.disabledPresets - r.id)
                                        }
                                    },
                                    trailing = { Text(text = if (on) "Da" else "Nu", fontSize = 14.sp) },
                                )
                            }
                        }
                    }
                    item {
                        ListRow(
                            title = "Reface regulile implicite",
                            subtitle = "Înlocuiește regulile de mai sus cu exemplele cu care pornește aplicația",
                            onClick = { store.update { it.copy(rules = DefaultRights.rules) } },
                        )
                    }
                    item {
                        Hint(
                            "Sursele din reguli sunt presupuneri: apar după cele găsite în EPG sau după postul anunțat, marcate „Presupus”. " +
                                "O competiție se potrivește dacă numele ei conține cuvintele regulii, în aceeași ordine.",
                        )
                    }
                }
                2 -> {
                    item { ListRow(title = "+ Adaugă un alias", subtitle = "De exemplu „Sepsi = Sepsi OSK”", onClick = { dialog = KDialog.NewTeam }) }
                    if (k.teamAliases.isEmpty()) item { Hint("Niciun alias al tău. Aplicația știe deja câteva (Man Utd, PSG, Spurs, Steaua = FCSB…).") }
                    k.teamAliases.entries.sortedBy { it.key.lowercase() }.forEach { (alias, full) ->
                        item(key = "t:$alias") { ListRow(title = "$alias = $full", onClick = { dialog = KDialog.TeamEntry(alias) }) }
                    }
                    item { Hint("Alias-urile ajută la unirea aceluiași meci din surse diferite și la găsirea lui în EPG.") }
                }
                4 -> {
                    val list = snapshot.noEpgChannels
                    item {
                        Hint(
                            "EPG-ul e singura sursă sigură pentru orice sport, deci fiecare canal de sport fără program e o gaură: meciurile de pe el " +
                                "apar doar după postul anunțat sau după drepturile TV. " +
                                (if (list.isEmpty()) "Toate cele ${snapshot.sportChannelCount} canale de sport au EPG." else "${list.size} canale de sport fără EPG (cele cu mai multe variante întâi)."),
                        )
                    }
                    list.forEachIndexed { i, c ->
                        item(key = "n:$i") {
                            ListRow(
                                title = c.name,
                                subtitle = listOf(c.group, c.playlist, if (c.variants > 1) "${c.variants} variante" else null).filterNotNull().joinToString(" · "),
                                onClick = {},
                            )
                        }
                    }
                    item {
                        Hint(
                            "Ce poți face: adaugă un EPG extern care are aceste canale (Setări → EPG), de exemplu fișierele XMLTV pe țări de la " +
                                "EPGShare01 sau un fișier generat cu iptv-org/epg; aplicația le potrivește după tvg-id și după nume. " +
                                "Canalele EVENT / PPV nu apar aici: meciul lor se citește din nume.",
                        )
                    }
                }
                else -> {
                    item { SectionLabel("Competiții → canale confirmate (OK: uită)") }
                    if (k.learnedRights.isEmpty()) item { Hint("Nimic încă. Când confirmi o sursă presupusă sau adaugi un canal la un meci, apare aici.") }
                    k.learnedRights.entries.sortedBy { it.key }.forEach { (comp, groupKeys) ->
                        groupKeys.sorted().forEach { gk ->
                            item(key = "l:$comp|$gk") {
                                ListRow(title = comp, subtitle = "→ " + nameOf(gk), onClick = { dialog = KDialog.LearnedEntry(comp, gk) })
                            }
                        }
                    }
                    item {
                        ListRow(
                            title = "Uită tot ce s-a învățat",
                            subtitle = "${k.stationBlocks.size + k.rightsBlocks.size} respingeri, ${k.decisions.size} meciuri cu alegeri, " +
                                "${k.stationAliases.size} posturi în dicționar",
                            onClick = { dialog = KDialog.Forget },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        KDialog.NewStation -> TextInputDialog(
            title = "Numele postului",
            initial = "",
            hint = "Cum îl anunță site-urile (de exemplu: Sky Sp ME)",
            onDismiss = { dialog = null },
            onConfirm = { text -> dialog = text.trim().takeIf { it.isNotEmpty() }?.let { KDialog.PickChannel(it, null) } },
        )
        is KDialog.PickChannel -> if (d.query == null) {
            TextInputDialog(
                title = "Canalul pentru „${d.station}”",
                initial = d.station,
                hint = "Caută canalul tău după nume",
                onDismiss = { dialog = null },
                onConfirm = { text -> dialog = text.trim().takeIf { it.isNotEmpty() }?.let { d.copy(query = it) } },
            )
        } else {
            ChannelPickerDialog(
                query = d.query,
                onDismiss = { dialog = null },
                onPick = { c ->
                    store.update { it.addStationAlias(d.station, c.groupKey) }
                    dialog = null
                },
            )
        }
        is KDialog.StationEntry -> ChoiceDialog(
            title = "${d.station} → ${d.label}",
            options = listOf("Șterge din dicționar", "Renunță"),
            selectedIndex = -1,
            onDismiss = { dialog = null },
            onSelect = { i ->
                if (i == 0) store.update { it.removeStationAlias(d.station, d.groupKey) }
                dialog = null
            },
        )
        is KDialog.RuleMenu -> {
            val rule = k.rules.getOrNull(d.index)
            if (rule == null) {
                dialog = null
            } else {
                ChoiceDialog(
                    title = rule.label,
                    options = listOf(if (rule.enabled) "Oprește regula" else "Pornește regula", "Editează", "Șterge"),
                    selectedIndex = -1,
                    onDismiss = { dialog = null },
                    onSelect = { i ->
                        when (i) {
                            0 -> store.update { st -> st.copy(rules = st.rules.mapIndexed { j, r -> if (j == d.index) r.copy(enabled = !r.enabled) else r }) }
                            1 -> dialog = KDialog.RuleEdit(d.index)
                            else -> store.update { st -> st.copy(rules = st.rules.filterIndexed { j, _ -> j != d.index }) }
                        }
                        if (i != 1) dialog = null
                    },
                )
            }
        }
        is KDialog.RuleEdit -> {
            val rule = k.rules.getOrNull(d.index)
            TextInputDialog(
                title = if (rule == null) "Regulă nouă" else "Editează regula",
                initial = rule?.let { RuleText.write(it) } ?: "",
                hint = "Competiții | posturi | țară, de exemplu: Premier League, English Premier League | Sky Sports Main Event, TNT Sports 1 | UK",
                onDismiss = { dialog = null },
                onConfirm = { text ->
                    RuleText.read(text, enabled = rule?.enabled ?: true)?.let { r ->
                        store.update { st ->
                            st.copy(rules = if (rule == null) st.rules + r else st.rules.mapIndexed { j, old -> if (j == d.index) r else old })
                        }
                    }
                    dialog = null
                },
            )
        }
        KDialog.NewTeam -> TextInputDialog(
            title = "Alias de echipă",
            initial = "",
            hint = "Alias = nume complet (de exemplu: Sepsi = Sepsi OSK)",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                val parts = text.split('=', limit = 2).map { it.trim() }
                if (parts.size == 2 && parts.all { it.isNotEmpty() }) {
                    store.update { it.copy(teamAliases = it.teamAliases + (parts[0] to parts[1])) }
                }
                dialog = null
            },
        )
        is KDialog.TeamEntry -> ChoiceDialog(
            title = "${d.alias} = ${k.teamAliases[d.alias].orEmpty()}",
            options = listOf("Șterge alias-ul", "Renunță"),
            selectedIndex = -1,
            onDismiss = { dialog = null },
            onSelect = { i ->
                if (i == 0) store.update { it.copy(teamAliases = it.teamAliases - d.alias) }
                dialog = null
            },
        )
        is KDialog.LearnedEntry -> ChoiceDialog(
            title = "${d.competition} → ${nameOf(d.groupKey)}",
            options = listOf("Uită legătura", "Renunță"),
            selectedIndex = -1,
            onDismiss = { dialog = null },
            onSelect = { i ->
                if (i == 0) store.update { it.removeLearned(d.competition, d.groupKey) }
                dialog = null
            },
        )
        KDialog.Forget -> ChoiceDialog(
            title = "Uiți tot ce s-a învățat?",
            hint = "Dicționarul tău de posturi, competițiile confirmate, respingerile și alegerile pe meciuri. Regulile și alias-urile de echipe rămân.",
            options = listOf("Da, uită tot", "Renunță"),
            selectedIndex = -1,
            onDismiss = { dialog = null },
            onSelect = { i ->
                if (i == 0) store.update { it.forgetLearning() }
                dialog = null
            },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp))
}

/** O regulă scrisă pe un rând: „competiții | posturi | țară”. */
private object RuleText {
    fun write(r: RightsRule): String =
        r.competitions.joinToString(", ") + " | " + r.stations.joinToString(", ") + (r.country?.let { " | $it" } ?: "")

    fun read(text: String, enabled: Boolean): RightsRule? {
        val parts = text.split('|').map { it.trim() }
        if (parts.size < 2) return null
        val comps = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val stations = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (comps.isEmpty() || stations.isEmpty()) return null
        return RightsRule(comps, stations, parts.getOrNull(2)?.ifBlank { null }, enabled)
    }
}
