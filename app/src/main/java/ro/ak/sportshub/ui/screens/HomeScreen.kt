package ro.ak.sportshub.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.components.AkLogo
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.theme.AkFonts
import ro.ak.sportshub.ui.theme.AkColors

private val Sections = listOf("Sports Hub", "Live TV", "Setări")

@Composable
fun HomeScreen(
    onOpenMatch: (String) -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenEpg: () -> Unit,
    onPlayChannel: (channelId: Long, zapGroup: Long) -> Unit,
    onOpenChannel: (Long) -> Unit,
    onOpenSportsSettings: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onPlayArchive: (channelId: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
    onOpenQrSetup: () -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var focused by rememberSaveable { mutableIntStateOf(0) }
    val firstTab = remember { FocusRequester() }
    // ▼ din meniul de sus intră în Sports Hub pe tabul curent (Live, Azi…), nu direct în listă;
    // ▲ din taburile Sports Hub urcă pe secțiunea curentă din meniul de sus.
    val topSelected = remember { FocusRequester() }
    val sportsTabs = remember { FocusRequester() }
    // Fiecare secțiune își păstrează starea (căutarea din Live TV, tabul din Sports Hub) când treci prin celelalte.
    val sectionStates = rememberSaveableStateHolder()
    // Trecerea prin meniul de sus (Sports Hub ↔ Live TV ↔ Setări) nu coboară focusul pe rândul ținut minte;
    // doar întoarcerea de pe alt ecran (player, meci, canal) o face. Se uită la refacerea ecranului principal.
    var switchedSection by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Back pe ecranul principal: ieșirea se confirmă.
    BackHandler { confirmExit = true }

    // E3: primul ecran desenat (o dată pe rulare, în jurnal).
    val container = ro.ak.sportshub.ui.appContainer()
    LaunchedEffect(Unit) { container.startup.mark("primul ecran") }
    // E2: după o cădere, întrebarea „Trimiți jurnalul?” (o singură dată).
    var crashAsk by remember { mutableStateOf(container.diagnosticLog.crashPending()) }
    var crashReport by remember { mutableStateOf(false) }
    val report by container.diagnosticReporter.state.collectAsStateWithLifecycle()

    // Focus inițial pe meniu, o singură dată (nu și la întoarcerea de pe ecranul unui meci).
    LaunchedEffect(Unit) {
        if (focused == 0) {
            focused = 1
            runCatching { firstTab.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AkLogo(size = 48.dp)
            Spacer(Modifier.width(18.dp))
            TabStrip(
                tabs = Sections,
                selectedIndex = section,
                onSelect = { if (it != section) switchedSection = true; section = it },
                firstTabModifier = Modifier.focusRequester(firstTab),
                fontSize = 21.sp,
                selectedTabRequester = topSelected,
                down = if (section == 0) sportsTabs else null,
                // Taburile ocupă tot locul rămas, ca ceasul să stea lipit de marginea din dreapta.
                modifier = Modifier.weight(1f),
            )
            HeaderClock()
        }
        Column(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(ro.ak.sportshub.ui.components.LocalSectionSwitched provides switchedSection) {
            sectionStates.SaveableStateProvider(section) { when (section) {
                0 -> SportsHubScreen(
                    topMenu = topSelected,
                    tabsEntry = sportsTabs,
                    onOpenMatch = onOpenMatch,
                    onOpenSettings = onOpenSportsSettings,
                    onPlay = { id -> onPlayChannel(id, 0L) },
                    onPlayArchive = onPlayArchive,
                )
                1 -> LiveTvScreen(onOpenPlaylists = onOpenPlaylists, onPlayChannel = onPlayChannel, onOpenChannel = onOpenChannel)
                else -> SettingsScreen(
                    onOpenPlaylists = onOpenPlaylists,
                    onOpenEpg = onOpenEpg,
                    onOpenSportsSettings = onOpenSportsSettings,
                    onOpenBackup = onOpenBackup,
                    onOpenQrSetup = onOpenQrSetup,
                )
            } }
            }
        }
    }

    if (confirmExit) {
        ChoiceDialog(
            title = "Ieși din aplicație?",
            options = listOf("Rămân", "Ieși"),
            selectedIndex = 0,
            onDismiss = { confirmExit = false },
            onSelect = { i ->
                confirmExit = false
                if (i == 1) context.findActivity()?.finish()
            },
        )
    }
    if (crashAsk && !confirmExit) {
        ChoiceDialog(
            title = "Aplicația s-a închis neașteptat data trecută",
            hint = "Eroarea e scrisă în jurnal, fără nimic din playlisturi. Trimiți jurnalul? (cu cheia GitHub din Setări → Despre)",
            options = listOf("Trimite jurnalul", "Nu acum"),
            selectedIndex = 0,
            onDismiss = {
                crashAsk = false
                container.diagnosticLog.clearCrashPending()
            },
            onSelect = { i ->
                crashAsk = false
                container.diagnosticLog.clearCrashPending()
                if (i == 0) {
                    container.diagnosticReporter.send()
                    crashReport = true
                }
            },
        )
    }
    val done = report as? ro.ak.sportshub.data.ReportState.Sent
    val failed = report as? ro.ak.sportshub.data.ReportState.Failed
    if (crashReport && (done != null || failed != null)) {
        ChoiceDialog(
            title = if (done != null) "Jurnal trimis (#${done.number})" else "Jurnalul nu s-a trimis",
            hint = failed?.reason ?: "Mulțumesc! Îl văd în repo, la Issues.",
            options = listOf("OK"),
            selectedIndex = 0,
            onDismiss = { crashReport = false },
            onSelect = { crashReport = false },
        )
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private val ClockFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", java.util.Locale.forLanguageTag("ro"))

/** Ziua și ora, în colțul din dreapta sus (ca pe un tabelă de stadion). */
@Composable
private fun HeaderClock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000)
        }
    }
    val text = remember(now / 60_000) {
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).format(ClockFormat)
    }
    Text(text = text, fontSize = 22.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold, color = AkColors.TextDim)
}
