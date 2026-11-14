package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.BuildConfig
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel

/** Setările planificate, cu valorile implicite. Devin editabile pe măsură ce apar funcțiile. */
@Composable
fun SettingsScreen() {
    val prefs = remember { SourcePreferences() }
    val languages = prefs.preferredLanguages.joinToString(", ") { it.uppercase() }

    @Composable
    fun row(title: String, subtitle: String, value: String) {
        ListRow(title = title, subtitle = subtitle, onClick = {}, trailing = { Text(text = value, fontSize = 14.sp) })
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionLabel("Playlisturi") }
        item { row("Playlisturi", "M3U și Xtream Codes, o singură conexiune pe playlist", "0") }
        item { row("EPG extern", "Surse XMLTV suplimentare", "0") }

        item { SectionLabel("Surse pentru meciuri") }
        item { row("Limbi preferate", "Ordinea în care sunt alese sursele", languages) }
        item {
            row(
                "Meciuri favorite",
                "Doar limbile preferate",
                if (prefs.favoritesPreferredOnly) "Da" else "Nu",
            )
        }
        item {
            row(
                "Alte meciuri",
                "Alte limbi doar dacă lipsește o sursă preferată de cel puțin această calitate",
                prefs.minQualityBeforeOtherLanguages.label,
            )
        }

        item { SectionLabel("Despre") }
        item { row("Versiune", "AK Sports Hub", BuildConfig.VERSION_NAME) }
    }
}
