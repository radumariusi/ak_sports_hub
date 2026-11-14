package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
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
) {
    val sports by appContainer().sportsSettings.settings.collectAsStateWithLifecycle()
    val playlists by appContainer().playlists.let { r -> remember { r.observePlaylists() } }.collectAsStateWithLifecycle(initialValue = emptyList())
    val mapped by appContainer().epg.let { r -> remember { r.observeMappedCount() } }.collectAsStateWithLifecycle(initialValue = 0)
    val languages = sports.sourcePreferences.preferredLanguages.joinToString(", ") { it.uppercase() }
    val variantStore = appContainer().variantStore
    val variants by variantStore.state.collectAsStateWithLifecycle()

    @Composable
    fun row(title: String, subtitle: String, value: String) {
        ListRow(title = title, subtitle = subtitle, onClick = {}, trailing = { Text(text = value, fontSize = 14.sp) })
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        item { row("Versiune", "AK Sports Hub", BuildConfig.VERSION_NAME) }
    }
}
