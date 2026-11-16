package ro.ak.sportshub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.common.AppVersions
import ro.ak.sportshub.data.UpdateState
import ro.ak.sportshub.ui.components.rememberReturnFocus
import ro.ak.sportshub.ui.components.TextInputDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.components.LoadsPanel
import ro.ak.sportshub.BuildConfig
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SettingsPane
import ro.ak.sportshub.ui.components.SettingsSection
import ro.ak.sportshub.ui.theme.TextScale
import ro.ak.sportshub.ui.playlists.formatCount

/** Setările aplicației. */
@Composable
fun SettingsScreen(
    onOpenPlaylists: () -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSportsSettings: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenQrSetup: () -> Unit = {},
) {
    val sports by appContainer().sportsSettings.settings.collectAsStateWithLifecycle()
    val playlists by appContainer().playlists.let { r -> remember { r.observePlaylists() } }.collectAsStateWithLifecycle(initialValue = emptyList())
    val mapped by appContainer().epg.let { r -> remember { r.observeMappedCount() } }.collectAsStateWithLifecycle(initialValue = 0)
    val languages = sports.sourcePreferences.preferredLanguages.joinToString(", ") { it.uppercase() }
    val variantStore = appContainer().variantStore
    val variants by variantStore.state.collectAsStateWithLifecycle()
    val updater = appContainer().updater
    val update by updater.state.collectAsStateWithLifecycle()
    var editingToken by remember { mutableStateOf(false) }
    val sportsStore = appContainer().sportsSettings
    var filterDialog by remember { mutableStateOf<FilterDialog?>(null) }
    val scope = rememberCoroutineScope()
    // La Back de pe un ecran de setări, focusul revine pe rândul care l-a deschis.
    val returnFocus = rememberReturnFocus()

    @Composable
    fun row(title: String, subtitle: String, value: String) {
        ListRow(title = title, subtitle = subtitle, onClick = {}, trailing = { Text(text = value, fontSize = 14.sp) })
    }

    SettingsPane(
        sections = listOf(
            SettingsSection("Playlisturi", "Playlisturi, cod QR, EPG") {
                item {
                    ListRow(
                        title = "Playlisturi",
                        subtitle = "M3U și Xtream Codes, o singură conexiune pe playlist. OK pentru adăugare și actualizare",
                        onClick = { returnFocus.clicked("playlists"); onOpenPlaylists() },
                        modifier = returnFocus.on("playlists"),
                        trailing = { Text(text = playlists.size.toString(), fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Adaugă un playlist din telefon (cod QR)",
                        subtitle = "Scanezi codul cu telefonul și scrii adresa și parola Xtream (sau linkul M3U) acolo, nu cu telecomanda",
                        onClick = { returnFocus.clicked("qr"); onOpenQrSetup() },
                        modifier = returnFocus.on("qr"),
                    )
                }
                item {
                    ListRow(
                        title = "EPG (programul TV)",
                        subtitle = "Din playlisturi și externe, actualizare automată, decalaj orar",
                        onClick = { returnFocus.clicked("epg"); onOpenEpg() },
                        modifier = returnFocus.on("epg"),
                        trailing = { Text(text = formatCount(mapped) + " canale", fontSize = 14.sp) },
                    )
                }

            },
            SettingsSection("Canale și variante", "Gruparea, trecerea automată, alegerile tale") {
                item {
                    ListRow(
                        title = "Grupează variantele",
                        subtitle = "Același canal în 4K / FHD / HD / SD apare o singură dată; OK pornește varianta preferată",
                        onClick = { variantStore.update { it.copy(groupVariants = !it.groupVariants) } },
                        trailing = { Text(text = if (variants.groupVariants) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Trecere automată la altă variantă",
                        subtitle = "Dacă o variantă nu pornește, încearcă următoarea, una câte una",
                        onClick = { variantStore.update { it.copy(autoFailover = !it.autoFailover) } },
                        trailing = { Text(text = if (variants.autoFailover) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    val observed = appContainer().observedVideo
                    val seen by observed.state.collectAsStateWithLifecycle()
                    ListRow(
                        title = "Rezoluția văzută la redare",
                        subtitle = "Din ce redă playerul oricum, fără verificări: „✓” dacă bate cu playlistul, altfel eticheta reală. OK uită tot",
                        onClick = { observed.clear() },
                        trailing = { Text(text = "${seen.size} variante", fontSize = 14.sp) },
                    )
                }
                item {
                    row(
                        "Alegerile tale",
                        "Variante preferate și corecții (calitate, limbă, unire, separare). Se schimbă din ecranul canalului",
                        "${variants.preferred.size} preferate · ${variants.corrections.size} corecții",
                    )
                }

            },
            SettingsSection("Redare și afișare", "Imaginea la 4K, frecvența, pauza pe live, mărimea textului") {
                item {
                    ListRow(
                        title = "Potrivește frecvența ecranului (50/60 Hz)",
                        subtitle = "Televizorul trece la frecvența cadrelor stream-ului (25/50p → 50 Hz), pentru mișcare fluidă la sport",
                        onClick = { variantStore.update { it.copy(matchFrameRate = !it.matchFrameRate) } },
                        trailing = { Text(text = if (variants.matchFrameRate) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Imagine fluidă (recomandat la 4K)",
                        subtitle = "Decodorul desenează direct pe ecran (SurfaceView): mai fluid la UHD, iar frecvența se potrivește mai bine. " +
                            "Dacă se aude sunetul dar imaginea e neagră, pune „Nu”",
                        onClick = { variantStore.update { it.copy(smoothVideo = !it.smoothVideo) } },
                        trailing = { Text(text = if (variants.smoothVideo) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Pauză pe live",
                        subtitle = "Playerul ține pe disc ce vezi (aceeași conexiune, niciun stream în plus): pauză, ⏪ ⏩ 10 s, " +
                            "„revezi ultimele 30 s” pe canalele TS. Se șterge la oprirea canalului. OK schimbă spațiul",
                        onClick = { variantStore.update { it.copy(timeshiftMb = ro.ak.sportshub.core.common.player.Timeshift.nextOption(it.timeshiftMb)) } },
                        trailing = { Text(text = ro.ak.sportshub.core.common.player.Timeshift.label(variants.timeshiftMb), fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Mărimea textului",
                        subtitle = "OK schimbă: mic, compact sau normal (listele încap mai bine pe ecran cu textul mai mic)",
                        onClick = { variantStore.update { it.copy(textScale = TextScale.next(it.textScale)) } },
                        trailing = { Text(text = TextScale.label(variants.textScale), fontSize = 14.sp) },
                    )
                }
            },
            SettingsSection("Sports Hub", "Limbi, calitate, furnizori, favorite") {
                item {
                    ListRow(
                        title = "Sports Hub",
                        subtitle = "Limbi preferate, prag de calitate, furnizori de meciuri, sporturi, echipe și competiții favorite",
                        onClick = { returnFocus.clicked("sports"); onOpenSportsSettings() },
                        modifier = returnFocus.on("sports"),
                        trailing = { Text(text = languages + " · " + sports.sourcePreferences.goodQuality.label + "+", fontSize = 14.sp) },
                    )
                }

            },
            SettingsSection("Filtre Sports Hub", "Aceleași filtre ca pe rândul din Sports Hub") {
                val f = sports.filter
                val store = sportsStore
                fun filterRow(key: String, title: String, value: String, onClick: () -> Unit) {
                    item(key = key) {
                        ListRow(title = title, onClick = onClick, trailing = { Text(text = value, fontSize = 14.sp) })
                    }
                }
                filterRow("f-sport", "Sport", f.sport?.label ?: "toate") { filterDialog = FilterDialog.SPORT }
                filterRow("f-comp", "Competiție", f.competition ?: "toate") { filterDialog = FilterDialog.COMPETITION }
                filterRow("f-team", "Echipă", f.team ?: "oricare") { filterDialog = FilterDialog.TEAM }
                filterRow("f-lang", "Limba surselor", f.language?.uppercase() ?: "oricare") { filterDialog = FilterDialog.LANGUAGE }
                filterRow("f-quality", "Calitatea minimă", f.minQuality?.let { it.label + "+" } ?: "oricare") { filterDialog = FilterDialog.QUALITY }
                filterRow("f-sources", "Doar meciurile cu surse", if (f.onlyWithSources) "da" else "nu") {
                    store.update { it.copy(filter = it.filter.copy(onlyWithSources = !it.filter.onlyWithSources)) }
                }
                if (!f.isEmpty) {
                    filterRow("f-clear", "Scoate toate filtrele", "") {
                        store.update { it.copy(filter = ro.ak.sportshub.core.sports.MatchFilter()) }
                    }
                }
            },
            SettingsSection("Zi de zi", "Notificări, rândul de pe ecranul principal, backup") {
                item {
                    val store = appContainer().sportsSettings
                    ListRow(
                        title = "Notificări înainte de meciurile favorite",
                        subtitle = "Echipe, competiții și sporturi favorite; „Pornește” deschide direct sursa recomandată",
                        onClick = { store.update { it.copy(reminders = !it.reminders) } },
                        trailing = { Text(text = if (sports.reminders) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    val store = appContainer().sportsSettings
                    ListRow(
                        title = "Cu câte minute înainte",
                        subtitle = "OK schimbă: 5, 10, 15 sau 30 de minute",
                        onClick = {
                            store.update { s ->
                                val options = listOf(5, 10, 15, 30)
                                s.copy(reminderMinutes = options[(options.indexOf(s.reminderMinutes) + 1) % options.size])
                            }
                        },
                        trailing = { Text(text = "${sports.reminderMinutes} min", fontSize = 14.sp) },
                    )
                }
                item {
                    val store = appContainer().sportsSettings
                    ListRow(
                        title = "Rândul „Meciurile tale de azi”",
                        subtitle = "Pe ecranul principal Android TV (Android 8+): favoritele, apoi meciurile de azi cu surse",
                        onClick = { store.update { it.copy(homeRow = !it.homeRow) } },
                        trailing = { Text(text = if (sports.homeRow) "Da" else "Nu", fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Backup și restaurare",
                        subtitle = "Setări, favorite, alias-uri, reguli și corecții într-un fișier; playlisturile doar dacă vrei",
                        onClick = { returnFocus.clicked("backup"); onOpenBackup() },
                        modifier = returnFocus.on("backup"),
                    )
                }

            },
            SettingsSection("Despre", "Versiunea, actualizarea") {
                item {
                    ListRow(
                        title = "Actualizare aplicație",
                        subtitle = updateSubtitle(update),
                        onClick = {
                            when (update) {
                                is UpdateState.Available -> updater.downloadAndInstall()
                                is UpdateState.Ready -> updater.installAgain()
                                UpdateState.Checking, UpdateState.Downloading -> Unit
                                else -> scope.launch { updater.check() }
                            }
                        },
                        trailing = { Text(text = "v" + BuildConfig.VERSION_NAME, fontSize = 14.sp) },
                    )
                }
                item {
                    ListRow(
                        title = "Cheie GitHub pentru actualizări",
                        subtitle = "Token „fine-grained” pe repo: Contents citire (actualizări) și Issues citire și scriere (jurnalul din Sports Hub). Nu intră în backup.",
                        onClick = { editingToken = true },
                        trailing = { Text(text = if (updater.token != null) "pusă" else "fără", fontSize = 14.sp) },
                    )
                }
            },
        ),
        header = {
            item { LoadsPanel() }
            (update as? UpdateState.Available)?.let { a ->
                item {
                    ListRow(
                        title = "Versiune nouă: v${AppVersions.name(a.latest)}",
                        subtitle = "Ai v${BuildConfig.VERSION_NAME}. OK descarcă și instalează (setările și playlisturile rămân)",
                        onClick = { updater.downloadAndInstall() },
                    )
                }
            }
        },
    )

    filterDialog?.let { d -> SportsFilterDialog(d, onDismiss = { filterDialog = null }) }
    if (editingToken) {
        TextInputDialog(
            title = "Cheie GitHub (token)",
            initial = "",
            hint = "github_pat_… (gol = șterge cheia)",
            password = true,
            onDismiss = { editingToken = false },
            onConfirm = {
                updater.token = it
                editingToken = false
                scope.launch { updater.check() }
            },
        )
    }
}

private fun updateSubtitle(s: UpdateState): String = when (s) {
    UpdateState.Unknown -> "OK caută o versiune nouă"
    UpdateState.Checking -> "Se caută…"
    is UpdateState.UpToDate -> "Ai ultima versiune (v${AppVersions.name(s.latest)}). OK caută din nou"
    is UpdateState.Available -> "Versiune nouă: v${AppVersions.name(s.latest)}. OK descarcă și instalează"
    UpdateState.Downloading -> "Se descarcă APK-ul…"
    is UpdateState.Ready -> "Descărcat v${AppVersions.name(s.latest)}. Confirmă instalarea; dacă nu a apărut fereastra, OK din nou"
    is UpdateState.Failed -> s.reason + " OK încearcă din nou"
}
