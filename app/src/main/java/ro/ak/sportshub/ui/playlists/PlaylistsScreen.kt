package ro.ak.sportshub.ui.playlists

import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.ak.sportshub.ui.components.LoadsPanel
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.theme.AkColors

/** Lista playlisturilor, cu adăugare și actualizare. */
@Composable
fun PlaylistsScreen(onEdit: (Long) -> Unit, onAdd: (PlaylistType) -> Unit) {
    val repo = appContainer().playlists
    val playlists by remember { repo.observePlaylists() }.collectAsStateWithLifecycle(initialValue = null)
    val refreshing by repo.refreshing.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(text = "Playlisturi", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = FontWeight.Bold)
        Text(
            text = "Doar canalele live sunt păstrate. Fiecare playlist folosește o singură conexiune.",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LoadsPanel()
        LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionLabel("Adaugă") }
            item {
                ListRow(
                    title = "Playlist M3U de la o adresă",
                    subtitle = "Linkul primit de la furnizor (http://…/get.php?… sau …m3u)",
                    onClick = { onAdd(PlaylistType.M3U_URL) },
                    modifier = Modifier.focusRequester(first),
                )
            }
            item {
                ListRow(
                    title = "Cont Xtream Codes",
                    subtitle = "Server, utilizator și parolă",
                    onClick = { onAdd(PlaylistType.XTREAM) },
                )
            }
            item {
                ListRow(
                    title = "Fișier M3U de pe dispozitiv",
                    subtitle = "De exemplu de pe un stick USB",
                    onClick = { onAdd(PlaylistType.M3U_FILE) },
                )
            }

            val list = playlists.orEmpty()
            if (list.isNotEmpty()) {
                item { SectionLabel("Playlisturile tale", modifier = Modifier.padding(top = 8.dp)) }
                item {
                    ListRow(
                        title = "Actualizează toate acum",
                        subtitle = "Pe rând, câte unul",
                        onClick = { repo.refreshAllInBackground() },
                    )
                }
                items(list, key = { it.id }) { p ->
                    ListRow(
                        title = p.name + if (!p.enabled) "  (inactiv)" else "",
                        subtitle = (if (!p.enabled) "canale ascunse · " else "") + statusLine(p, p.id in refreshing),
                        onClick = { onEdit(p.id) },
                    )
                }
            }
        }
    }
}

fun typeLabel(type: PlaylistType): String = when (type) {
    PlaylistType.M3U_URL -> "M3U"
    PlaylistType.M3U_FILE -> "Fișier M3U"
    PlaylistType.XTREAM -> "Xtream"
}

private val dateTime = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ro"))
private val dateOnly = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ro"))

fun formatCount(n: Int): String = String.format(Locale.forLanguageTag("ro"), "%,d", n)

fun statusLine(p: PlaylistEntity, refreshing: Boolean): String = buildList {
    add(typeLabel(p.type))
    val updated = p.lastUpdated
    when {
        refreshing -> add("se actualizează…")
        updated == null && p.lastError == null -> add("neîncărcat încă")
        updated != null -> {
            add("${formatCount(p.channelCount)} canale live")
            add("actualizat ${dateTime.format(Date(updated))}")
        }
    }
    if (!refreshing && p.lastError != null) add("eroare: ${p.lastError}")
    add(if (p.updateIntervalHours > 0) "automat la ${p.updateIntervalHours} h" else "doar manual")
    p.expiresAt?.let { add("expiră ${dateOnly.format(Date(it * 1000))}") }
}.joinToString(" · ")
