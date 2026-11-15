package ro.ak.sportshub.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

/**
 * Backup și restaurare. Fișierul se alege cu selectorul de fișiere al televizorului; dacă televizorul
 * nu are unul, backup-ul merge în Download (sau în folderul aplicației) și se citește de acolo.
 */
@Composable
fun BackupScreen() {
    val container = appContainer()
    val backup = container.backup
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    /** Textul de salvat după ce alegi fișierul. */
    var pending by remember { mutableStateOf<String?>(null) }
    /** Backup-ul citit, în așteptarea confirmării. */
    var toRestore by remember { mutableStateOf<String?>(null) }
    var askUrl by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Nu a mers: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) {
            message = "Salvare anulată."
        } else {
            run {
                backup.writeTo(uri, text)
                message = "Backup salvat."
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { toRestore = backup.readFrom(uri) }
    }

    fun save(withPlaylists: Boolean) = run {
        val text = backup.export(withPlaylists)
        try {
            pending = text
            createDoc.launch(backup.fileName())
        } catch (e: ActivityNotFoundException) {
            // Televizor fără selector de fișiere.
            pending = null
            message = "Backup salvat în " + backup.writeToDownloads(text)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(text = "Backup și restaurare", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(
            text = message ?: "Mută setările pe alt televizor: salvezi aici, apoi restaurezi acolo. Canalele și programul TV se descarcă din nou.",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionLabel("Salvează") }
            item {
                ListRow(
                    title = "Salvează setările",
                    subtitle = "Sports Hub, favorite, variante preferate, corecții, dicționar de posturi, reguli, EPG-uri externe",
                    onClick = { save(withPlaylists = false) },
                    modifier = Modifier.focusRequester(focus),
                )
            }
            item {
                ListRow(
                    title = "Salvează setările și playlisturile",
                    subtitle = "Atenție: fișierul conține adresele, utilizatorii și parolele playlisturilor. Păstrează-l doar la tine.",
                    onClick = { save(withPlaylists = true) },
                )
            }
            item { SectionLabel("Restaurează") }
            item {
                ListRow(
                    title = "Dintr-un fișier",
                    subtitle = "Alegi fișierul de backup (de pe stick USB sau din Download)",
                    onClick = {
                        try {
                            openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (e: ActivityNotFoundException) {
                            run {
                                toRestore = backup.latestLocal() ?: error("televizorul nu are selector de fișiere și nu am găsit un backup în Download.")
                            }
                        }
                    },
                )
            }
            item {
                ListRow(
                    title = "Ultimul backup salvat pe acest televizor",
                    subtitle = "Din Download sau din folderul aplicației",
                    onClick = { run { toRestore = backup.latestLocal() ?: error("nu am găsit niciun backup.") } },
                )
            }
            item {
                ListRow(
                    title = "De la o adresă web",
                    subtitle = "Un link direct către fișierul de backup (de exemplu de pe serverul tău)",
                    onClick = { askUrl = true },
                )
            }
        }
    }

    if (askUrl) {
        TextInputDialog(
            title = "Adresa fișierului de backup",
            initial = "",
            hint = "http://… sau https://…",
            onDismiss = { askUrl = false },
            onConfirm = { url ->
                askUrl = false
                if (url.isNotBlank()) run { toRestore = backup.download(container.http, url) }
            },
        )
    }

    toRestore?.let { text ->
        ChoiceDialog(
            title = "Restaurezi backup-ul?",
            hint = "Setările de acum se înlocuiesc cu cele din fișier. Playlisturile din backup (dacă are) se adaugă și se reîncarcă. Aplicația repornește.",
            options = listOf("Da, restaurează", "Nu"),
            selectedIndex = -1,
            onDismiss = { toRestore = null },
            onSelect = { i ->
                toRestore = null
                if (i == 0) {
                    run {
                        val r = backup.restore(text)
                        message = "Restaurat: ${r.settings} grupuri de setări, ${r.playlists} playlisturi. Aplicația repornește…"
                        delay(1_500)
                        backup.restartApp()
                    }
                }
            },
        )
    }
}
