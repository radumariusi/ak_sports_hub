package ro.ak.sportshub.ui.epg

import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.ak.sportshub.ui.components.rememberDiskUsage
import ro.ak.sportshub.ui.components.describe
import ro.ak.sportshub.ui.components.LoadsPanel
import ro.ak.sportshub.core.common.LoadFormat
import ro.ak.sportshub.core.database.EpgSourceState
import ro.ak.sportshub.core.epg.EpgOverride
import ro.ak.sportshub.core.epg.EpgSource
import ro.ak.sportshub.core.epg.EpgSources
import ro.ak.sportshub.core.epg.EpgUpdatePolicy
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.playlists.formatCount
import ro.ak.sportshub.ui.theme.AkColors

private val dateTime = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ro"))

/** Setările EPG: actualizare, surse din playlisturi, surse externe. */
@Composable
fun EpgSettingsScreen() {
    val container = appContainer()
    val epg = container.epg
    val store = container.epgSettings
    val settings by store.settings.collectAsStateWithLifecycle()
    val sources by remember { epg.observeSources() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val states by remember { epg.observeStates() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val mapped by remember { epg.observeMappedCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val status by epg.status.collectAsStateWithLifecycle()
    val disk = rememberDiskUsage()
    val byKey = states.associateBy { it.sourceKey }
    val policy = settings.policy

    var adding by remember { mutableStateOf(false) }
    var addedUrl by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EpgSource?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    /** Schimbă o sursă și recitește EPG-ul (din fișierul salvat, fără descărcare). */
    fun setSource(s: EpgSource, enabled: Boolean, shift: Int) {
        if (s.playlistId == null) {
            val id = s.key.removePrefix("x")
            store.current.external.firstOrNull { it.id == id }
                ?.let { store.updateExternal(it.copy(enabled = enabled, shiftMinutes = shift)) }
        } else {
            store.setOverride(s.key, EpgOverride(enabled, shift))
        }
        epg.onSourcesChanged()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(text = "EPG (programul TV)", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = FontWeight.Bold)
        Text(
            text = status ?: "Canale cu program: ${formatCount(mapped)}. Se păstrează doar canalele din playlisturile tale.",
            fontSize = 14.sp,
            color = if (status != null) AkColors.Accent else AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LoadsPanel()
        LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionLabel("Actualizare") }
            item {
                ListRow(
                    title = "Actualizează acum",
                    subtitle = "Descarcă din nou toate sursele active, pe rând",
                    onClick = { epg.refreshInBackground() },
                    modifier = Modifier.focusRequester(first),
                )
            }
            item {
                ListRow(
                    title = "Actualizare automată",
                    subtitle = "OK schimbă intervalul",
                    onClick = { store.setPolicy(policy.copy(intervalHours = next(EpgUpdatePolicy.INTERVALS, policy.intervalHours))) },
                    trailing = { Value(if (policy.intervalHours > 0) "la ${policy.intervalHours} h" else "doar manual") },
                )
            }
            item {
                ListRow(
                    title = "Spațiu ocupat",
                    subtitle = disk?.describe() ?: "se calculează…",
                    onClick = {},
                    trailing = { Value(disk?.let { LoadFormat.size(it.total) } ?: "…") },
                )
            }
            item {
                ListRow(
                    title = "Zile de program păstrate",
                    subtitle = "Mai multe zile ocupă mai mult spațiu pe televizor",
                    onClick = {
                        store.setPolicy(policy.copy(daysAhead = next(EpgUpdatePolicy.DAYS, policy.daysAhead)))
                        epg.onSourcesChanged()
                    },
                    trailing = { Value("${policy.daysAhead} " + if (policy.daysAhead == 1) "zi" else "zile") },
                )
            }

            val fromPlaylists = sources.filter { it.playlistId != null }
            val external = sources.filter { it.playlistId == null }

            item { SectionLabel("Din playlisturi", modifier = Modifier.padding(top = 8.dp)) }
            if (fromPlaylists.isEmpty()) {
                item { Hint("Playlisturile active nu declară niciun EPG (url-tvg sau xmltv.php la Xtream). Cele inactive nu contează: canalele lor nu primesc program. Poți adăuga unul extern mai jos.") }
            }
            items(fromPlaylists, key = { it.key }) { s ->
                SourceRow(s, byKey[s.key]) { editing = s }
            }

            item { SectionLabel("Externe", modifier = Modifier.padding(top = 8.dp)) }
            item {
                ListRow(
                    title = "Adaugă un EPG extern",
                    subtitle = "Adresa unui fișier XMLTV (.xml sau .xml.gz)",
                    onClick = { adding = true },
                )
            }
            items(external, key = { it.key }) { s ->
                SourceRow(s, byKey[s.key]) { editing = s }
            }
            item {
                Hint(
                    "Potrivirea canalelor: întâi după tvg-id, apoi după nume (fără calitate și diacritice). " +
                        "La același canal are prioritate EPG-ul playlistului, apoi cele externe.",
                )
            }
        }
    }

    if (adding) {
        TextInputDialog(
            title = "Adresa EPG-ului",
            hint = "De exemplu https://…/epg.xml.gz",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = { url ->
                adding = false
                if (url.isNotBlank()) addedUrl = url
            },
        )
    }
    addedUrl?.let { url ->
        TextInputDialog(
            title = "Numele EPG-ului",
            hint = "Opțional; gol = numele serverului",
            initial = EpgSources.host(url).orEmpty(),
            onDismiss = { addedUrl = null },
            onConfirm = { name ->
                addedUrl = null
                val e = store.addExternal(url, name)
                epg.refreshInBackground(EpgSources.externalKey(e.id))
            },
        )
    }
    editing?.let { s ->
        SourceDialog(
            source = s,
            onDismiss = { editing = null },
            onToggle = {
                setSource(s, enabled = !s.enabled, shift = s.shiftMinutes)
                editing = null
            },
            onShift = { delta ->
                val shift = (s.shiftMinutes + delta).coerceIn(-12 * 60, 12 * 60)
                setSource(s, enabled = s.enabled, shift = shift)
                editing = s.copy(shiftMinutes = shift)
            },
            onRefresh = {
                epg.refreshInBackground(s.key)
                editing = null
            },
            onDelete = if (s.playlistId == null) {
                {
                    store.removeExternal(s.key.removePrefix("x"))
                    epg.onSourcesChanged()
                    editing = null
                }
            } else {
                null
            },
        )
    }
}

private fun next(options: List<Int>, current: Int): Int = options[(options.indexOf(current) + 1) % options.size]

@Composable
private fun Value(text: String) {
    Text(text = text, fontSize = 14.sp)
}

@Composable
private fun Hint(text: String) {
    Text(text = text, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
private fun SourceRow(s: EpgSource, state: EpgSourceState?, onClick: () -> Unit) {
    ListRow(
        title = s.label + if (!s.enabled) "  (oprit)" else "",
        subtitle = sourceStatus(s, state),
        onClick = onClick,
    )
}

private fun sourceStatus(s: EpgSource, state: EpgSourceState?): String = buildList {
    if (!s.enabled) {
        add("nefolosit")
    } else {
        val updated = state?.lastUpdated
        if (state != null && updated != null) {
            add("${formatCount(state.channelCount)} canale, ${formatCount(state.programmeCount)} emisiuni")
            add("actualizat ${dateTime.format(Date(updated))}")
        } else if (state?.lastError == null) {
            add("neîncărcat încă")
        }
        state?.lastError?.let { add(it) }
    }
    if (s.shiftMinutes != 0) add("decalaj ${shiftLabel(s.shiftMinutes)}")
    if (s.gated) add("pe serverul furnizorului: așteaptă când playerul folosește playlistul")
}.joinToString(" · ")

private fun shiftLabel(minutes: Int): String {
    val sign = if (minutes < 0) "−" else "+"
    val m = kotlin.math.abs(minutes)
    return if (m % 60 == 0) "$sign${m / 60} h" else "$sign${m / 60}:${"%02d".format(m % 60)}"
}

@Composable
private fun SourceDialog(
    source: EpgSource,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onShift: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(AkColors.Panel, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = source.label, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            ListRow(
                title = if (source.enabled) "Oprește sursa" else "Folosește sursa",
                subtitle = if (source.enabled) "Programul ei nu mai e folosit" else "Programul ei se încarcă la următoarea actualizare",
                onClick = onToggle,
                modifier = Modifier.focusRequester(focus),
            )
            ListRow(
                title = "Decalaj orar: " + if (source.shiftMinutes == 0) "fără" else shiftLabel(source.shiftMinutes),
                subtitle = "Dacă emisiunile apar mai devreme sau mai târziu decât în realitate",
                onClick = {},
            )
            ListRow(title = "Mai târziu cu o oră (+1 h)", onClick = { onShift(60) })
            ListRow(title = "Mai devreme cu o oră (−1 h)", onClick = { onShift(-60) })
            ListRow(title = "Actualizează doar această sursă", onClick = onRefresh)
            if (onDelete != null) ListRow(title = "Șterge sursa", onClick = onDelete)
        }
    }
}
