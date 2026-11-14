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
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.playlists.formatCount

/** Setările planificate, cu valorile implicite. Devin editabile pe măsură ce apar funcțiile. */
@Composable
fun SettingsScreen(onOpenPlaylists: () -> Unit, onOpenEpg: () -> Unit) {
    val prefs = remember { SourcePreferences() }
    val playlists by appContainer().playlists.let { r -> remember { r.observePlaylists() } }.collectAsStateWithLifecycle(initialValue = emptyList())
    val mapped by appContainer().epg.let { r -> remember { r.observeMappedCount() } }.collectAsStateWithLifecycle(initialValue = 0)
    val languages = prefs.preferredLanguages.joinToString(", ") { it.uppercase() }

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

        item { SectionLabel("Surse pentru meciuri") }
        item { row("Limbi preferate", "Au prioritate la aceeași calitate", languages) }
        item { row("Calitate bună", "Pragul de la care contează limba", prefs.goodQuality.label + " sau mai mult") }
        item {
            row(
                "Meciuri favorite",
                "Limbi preferate la calitate bună, apoi alte limbi la calitate bună, apoi restul",
                "",
            )
        }
        item { row("Alte meciuri", "Orice limbă, cea mai bună rezoluție întâi", "") }

        item { SectionLabel("Despre") }
        item { row("Versiune", "AK Sports Hub", BuildConfig.VERSION_NAME) }
    }
}
