package ro.ak.sportshub.ui.playlists

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.core.playlist.PlaylistException
import ro.ak.sportshub.core.playlist.XtreamEndpoints
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

private enum class Field { NAME, URL, USER, PASS, USER_AGENT }

private val Intervals = listOf(0, 6, 12, 24, 48)

/**
 * Adăugarea sau modificarea unui playlist. [playlistId] 0 = playlist nou de tipul [newType].
 * Fiecare câmp se modifică într-o fereastră separată, deschisă cu OK.
 */
@Composable
fun PlaylistEditScreen(playlistId: Long, newType: PlaylistType, onClose: () -> Unit) {
    val repo = appContainer().playlists
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var savedId by rememberSaveable { mutableStateOf(playlistId) }
    var draft by remember {
        mutableStateOf(
            PlaylistEntity(
                name = "",
                type = newType,
                url = "",
                updateIntervalHours = if (newType == PlaylistType.M3U_FILE) 0 else 24,
            ),
        )
    }
    var loaded by remember { mutableStateOf(playlistId == 0L) }
    var editing by remember { mutableStateOf<Field?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val stored by remember(savedId) {
        if (savedId > 0) repo.observePlaylist(savedId) else flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val refreshing by repo.refreshing.collectAsStateWithLifecycle()

    LaunchedEffect(playlistId) {
        if (playlistId > 0) {
            repo.get(playlistId)?.let { draft = it }
            loaded = true
        }
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(loaded) { if (loaded) runCatching { first.requestFocus() } }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            draft = draft.copy(url = uri.toString(), name = draft.name.ifEmpty { fileName?.substringBeforeLast('.').orEmpty() })
        }
    }

    fun validate(): String? = when {
        draft.url.isBlank() -> when (draft.type) {
            PlaylistType.M3U_URL -> "Completează adresa playlistului."
            PlaylistType.M3U_FILE -> "Alege fișierul."
            PlaylistType.XTREAM -> "Completează adresa serverului."
        }
        draft.type == PlaylistType.M3U_URL && !draft.url.contains("://") -> "Adresa trebuie să înceapă cu http:// sau https://."
        draft.type == PlaylistType.XTREAM && XtreamEndpoints.normalizeServer(draft.url) == null -> "Adresa serverului nu e validă."
        draft.type == PlaylistType.XTREAM && (draft.username.isNullOrBlank() || draft.password.isNullOrBlank()) ->
            "Completează utilizatorul și parola."
        else -> null
    }

    fun defaultName(): String = when (draft.type) {
        PlaylistType.M3U_FILE -> "Fișier M3U"
        else -> runCatching { Uri.parse(if (draft.url.contains("://")) draft.url else "http://${draft.url}").host }
            .getOrNull()?.ifEmpty { null } ?: "Playlist"
    }

    fun save() {
        validate()?.let { message = it; return }
        scope.launch {
            val toSave = draft.copy(name = draft.name.ifBlank { defaultName() })
            val id = repo.save(toSave.copy(id = savedId))
            savedId = id
            draft = toSave.copy(id = id)
            message = "Salvat. Se încarcă playlistul; poți ieși, continuă în fundal."
            repo.refreshInBackground(id, force = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text(
            text = if (savedId > 0) draft.name.ifEmpty { "Playlist" } else "Playlist nou · ${typeLabel(draft.type)}",
            fontSize = 32.sp,
            fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed,
            fontWeight = FontWeight.Bold,
        )
        val live = stored
        Text(
            text = message ?: live?.let { statusLine(it, it.id in refreshing) }
                ?: "Datele de acces rămân doar pe televizor.",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (!loaded) return@Column

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionLabel("Sursa") }
            when (draft.type) {
                PlaylistType.M3U_URL -> item {
                    ListRow(
                        title = "Adresa playlistului",
                        subtitle = draft.url.ifEmpty { "Apasă OK ca s-o scrii" }.let(::maskSecrets),
                        onClick = { editing = Field.URL },
                        modifier = Modifier.focusRequester(first),
                    )
                }
                PlaylistType.M3U_FILE -> item {
                    ListRow(
                        title = "Fișierul",
                        subtitle = if (draft.url.isEmpty()) "Apasă OK ca să-l alegi" else Uri.parse(draft.url).lastPathSegment.orEmpty(),
                        onClick = {
                            try {
                                pickFile.launch(arrayOf("*/*"))
                            } catch (e: ActivityNotFoundException) {
                                message = "Televizorul nu are un selector de fișiere. Folosește un playlist de la o adresă."
                            }
                        },
                        modifier = Modifier.focusRequester(first),
                    )
                }
                PlaylistType.XTREAM -> {
                    item {
                        ListRow(
                            title = "Server",
                            subtitle = draft.url.ifEmpty { "De exemplu http://server.com:8080" },
                            onClick = { editing = Field.URL },
                            modifier = Modifier.focusRequester(first),
                        )
                    }
                    item {
                        ListRow(
                            title = "Utilizator",
                            subtitle = draft.username?.ifEmpty { null } ?: "—",
                            onClick = { editing = Field.USER },
                        )
                    }
                    item {
                        ListRow(
                            title = "Parolă",
                            subtitle = if (draft.password.isNullOrEmpty()) "—" else "••••••••",
                            onClick = { editing = Field.PASS },
                        )
                    }
                    item {
                        ListRow(
                            title = "Formatul stream-ului",
                            subtitle = "TS merge pe aproape orice server; HLS (m3u8) e util dacă TS se blochează",
                            onClick = { draft = draft.copy(streamFormat = if (draft.streamFormat == "ts") "m3u8" else "ts") },
                            trailing = { Text(text = if (draft.streamFormat == "ts") "TS" else "HLS", fontSize = 14.sp) },
                        )
                    }
                }
            }

            item { SectionLabel("Setări", modifier = Modifier.padding(top = 8.dp)) }
            item {
                ListRow(
                    title = "Nume",
                    subtitle = draft.name.ifEmpty { "Opțional" },
                    onClick = { editing = Field.NAME },
                )
            }
            item {
                ListRow(
                    title = "Actualizare automată",
                    subtitle = "OK schimbă intervalul",
                    onClick = {
                        val i = Intervals.indexOf(draft.updateIntervalHours)
                        draft = draft.copy(updateIntervalHours = Intervals[(i + 1) % Intervals.size])
                    },
                    trailing = {
                        Text(
                            text = if (draft.updateIntervalHours == 0) "Doar manual" else "La ${draft.updateIntervalHours} ore",
                            fontSize = 14.sp,
                        )
                    },
                )
            }
            item {
                ListRow(
                    title = "Activ",
                    subtitle = "Canalele playlisturilor inactive nu apar în liste",
                    onClick = {
                        draft = draft.copy(enabled = !draft.enabled)
                        message = if (draft.enabled) "Activ: canalele lui apar după salvare."
                        else "Atenție: un playlist inactiv își ascunde canalele din Live TV, EPG și Sports Hub. Salvează ca să aplici."
                    },
                    trailing = { Text(text = if (draft.enabled) "Da" else "Nu", fontSize = 14.sp) },
                )
            }
            if (draft.type != PlaylistType.M3U_FILE) {
                item {
                    ListRow(
                        title = "User-agent",
                        subtitle = draft.userAgent ?: "Implicit (lasă gol dacă furnizorul nu cere altceva)",
                        onClick = { editing = Field.USER_AGENT },
                    )
                }
            }
            item {
                ListRow(
                    title = "Conexiuni simultane",
                    subtitle = "Descărcarea listei și redarea nu rulează niciodată în paralel",
                    onClick = {},
                    trailing = { Text(text = "1", fontSize = 14.sp) },
                )
            }
            live?.let { p ->
                if (p.lastUpdated != null) {
                    item {
                        ListRow(
                            title = "Ultima încărcare",
                            subtitle = buildString {
                                append("${formatCount(p.channelCount)} canale live")
                                if (p.skippedCount > 0) append(" · ${formatCount(p.skippedCount)} filme/seriale ignorate")
                            },
                            onClick = {},
                            trailing = {
                                Text(
                                    text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ro"))
                                        .format(Date(p.lastUpdated!!)),
                                    fontSize = 14.sp,
                                )
                            },
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = if (savedId > 0) "Salvează și reîncarcă" else "Salvează și încarcă",
                enabled = !busy && savedId !in refreshing,
                onClick = { save() },
            )
            if (draft.type == PlaylistType.XTREAM) {
                ActionButton(
                    text = "Verifică contul",
                    enabled = !busy,
                    onClick = {
                        validate()?.let { message = it; return@ActionButton }
                        busy = true
                        message = "Se verifică…"
                        scope.launch {
                            message = try {
                                val a = repo.checkXtream(savedId, draft.url, draft.username.orEmpty(), draft.password.orEmpty(), draft.userAgent)
                                buildList {
                                    add("Cont valid")
                                    a.status?.let { add("stare: $it") }
                                    a.expiresAt?.let {
                                        add("expiră " + SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ro")).format(Date(it * 1000)))
                                    }
                                    a.maxConnections?.let { add("conexiuni permise: $it") }
                                    a.activeConnections?.let { add("active acum: $it") }
                                }.joinToString(" · ")
                            } catch (e: PlaylistException) {
                                e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            if (savedId > 0) {
                ActionButton(
                    text = if (confirmDelete) "Apasă din nou ca să ștergi" else "Șterge",
                    danger = true,
                    onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            scope.launch {
                                repo.delete(savedId)
                                onClose()
                            }
                        }
                    },
                )
            }
        }
    }

    editing?.let { f ->
        TextInputDialog(
            title = when (f) {
                Field.NAME -> "Numele playlistului"
                Field.URL -> if (draft.type == PlaylistType.XTREAM) "Serverul Xtream" else "Adresa playlistului M3U"
                Field.USER -> "Utilizator"
                Field.PASS -> "Parolă"
                Field.USER_AGENT -> "User-agent"
            },
            hint = when (f) {
                Field.URL -> if (draft.type == PlaylistType.XTREAM) "De exemplu http://server.com:8080" else "De exemplu http://server.com/get.php?username=…&type=m3u_plus"
                Field.USER_AGENT -> "Lasă gol pentru valoarea implicită"
                else -> null
            },
            initial = when (f) {
                Field.NAME -> draft.name
                Field.URL -> draft.url
                Field.USER -> draft.username.orEmpty()
                Field.PASS -> draft.password.orEmpty()
                Field.USER_AGENT -> draft.userAgent.orEmpty()
            },
            password = f == Field.PASS,
            keyboardType = if (f == Field.URL) KeyboardType.Uri else KeyboardType.Text,
            onDismiss = { editing = null },
            onConfirm = { value ->
                draft = when (f) {
                    Field.NAME -> draft.copy(name = value)
                    Field.URL -> draft.copy(url = value)
                    Field.USER -> draft.copy(username = value)
                    Field.PASS -> draft.copy(password = value)
                    Field.USER_AGENT -> draft.copy(userAgent = value.ifEmpty { null })
                }
                confirmDelete = false
                editing = null
            },
        )
    }
}

/** Ascunde parolele din adresele M3U afișate (`password=…`, `token=…`). */
fun maskSecrets(url: String): String =
    url.replace(Regex("""(?i)(password|pass|pwd|token)=([^&]+)"""), "$1=•••")
