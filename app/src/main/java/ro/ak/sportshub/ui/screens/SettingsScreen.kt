package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.common.AppVersions
import ro.ak.sportshub.data.UpdateState
import ro.ak.sportshub.ui.components.TextInputDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.components.LoadsPanel
import ro.ak.sportshub.BuildConfig
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
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
    val scope = rememberCoroutineScope()

    @Composable
    fun row(title: String, subtitle: String, value: String) {
        ListRow(title = title, subtitle = subtitle, onClick = {}, trailing = { Text(text = value, fontSize = 14.sp) })
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        item { SectionLabel("Playlisturi") }
        item {
            ListRow(
                title = "Playlisturi",
                subtitle = "M3U și Xtream Codes, o singură conexiune pe playlist. OK pentru adăugare și actualizare",
                onClick = onOpenPlaylists,
                trailing = { Text(text = playlists.size.toString(), fontSize = 14.sp) },
            )
        }
        item {
            ListRow(
                title = "Adaugă un playlist din telefon (cod QR)",
                subtitle = "Scanezi codul cu telefonul și scrii adresa și parola Xtream (sau linkul M3U) acolo, nu cu telecomanda",
                onClick = onOpenQrSetup,
            )
        }
        item {
            ListRow(
                title = "EPG (programul TV)",
                subtitle = "Din playlisturi și externe, actualizare automată, decalaj orar",
                onClick = onOpenEpg,
                trailing = { Text(text = formatCount(mapped) + " canale", fontSize = 14.sp) },
            )
        }

        item { SectionLabel("Canale și variante") }
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
            ListRow(
                title = "Potrivește frecvența ecranului (50/60 Hz)",
                subtitle = "Televizorul trece la frecvența cadrelor stream-ului (25/50p → 50 Hz), pentru mișcare fluidă la sport",
                onClick = { variantStore.update { it.copy(matchFrameRate = !it.matchFrameRate) } },
                trailing = { Text(text = if (variants.matchFrameRate) "Da" else "Nu", fontSize = 14.sp) },
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

        item { SectionLabel("Sports Hub") }
        item {
            ListRow(
                title = "Sports Hub",
                subtitle = "Limbi preferate, prag de calitate, TheSportsDB, sporturi, echipe și competiții favorite",
                onClick = onOpenSportsSettings,
                trailing = { Text(text = languages + " · " + sports.sourcePreferences.goodQuality.label + "+", fontSize = 14.sp) },
            )
        }

        item { SectionLabel("Zi de zi") }
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
                onClick = onOpenBackup,
            )
        }

        item { SectionLabel("Despre") }
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
                subtitle = "Doar dacă repo-ul e privat: un token „fine-grained” cu acces de citire la conținut. Nu intră în backup.",
                onClick = { editingToken = true },
                trailing = { Text(text = if (updater.token != null) "pusă" else "fără", fontSize = 14.sp) },
            )
        }
    }

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
