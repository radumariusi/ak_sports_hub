package ro.ak.sportshub.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.sports.MatchSearch
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.ScreensaverBoard
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListPadding
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

private const val CHANNEL_LIMIT = 60

/**
 * F1: căutarea în meciuri și canale, cu vocea (butonul de microfon al telecomenzii sau „Vorbește”) sau scrisă.
 * Meciurile vin din lista Sports Hub deja calculată, canalele din baza locală (FTS); nicio cerere în rețea.
 * Fraza spusă își pierde cuvintele de umplutură („pune meciul lui Arsenal” → „Arsenal”).
 */
@Composable
fun SearchScreen(
    initialQuery: String,
    startVoice: Boolean,
    onOpenMatch: (String) -> Unit,
    onPlayChannel: (Long) -> Unit,
) {
    val container = appContainer()
    val settings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var typing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var voiceStarted by rememberSaveable { mutableStateOf(false) }
    val firstButton = remember { FocusRequester() }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) {
            query = heard
            message = null
        } else if (query.isEmpty()) {
            message = "Nu s-a auzit nimic. Apasă din nou „Vorbește” sau scrie."
        }
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Spune o echipă, o competiție sau un canal")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            message = "Televizorul nu are recunoaștere vocală pentru aplicații. Scrie căutarea."
            typing = true
        }
    }

    LaunchedEffect(Unit) {
        runCatching { firstButton.requestFocus() }
        if (startVoice && !voiceStarted && query.isEmpty()) {
            voiceStarted = true
            listen()
        }
    }

    val matches = remember(snapshot.computedAt, snapshot.matches.size, query) {
        MatchSearch.find(snapshot.matches.map { it.match }, query)
    }
    val sourceCounts = remember(snapshot.computedAt, snapshot.matches.size) {
        snapshot.matches.associate { it.match.id to it.summary.count }
    }
    val fts = SearchText.ftsQuery(MatchSearch.clean(query))
    val channels by remember(fts) {
        if (fts != null) container.database.channels().search(fts, CHANNEL_LIMIT) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (query.isEmpty()) "Caută" else "Caută: $query",
                fontSize = 26.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ActionButton(text = "🎤 Vorbește", onClick = { listen() }, modifier = Modifier.focusRequester(firstButton))
            ActionButton(text = "Scrie", onClick = { typing = true })
            if (query.isNotEmpty()) ActionButton(text = "Șterge", onClick = { query = "" })
        }
        Text(
            text = message ?: "Meciuri din Sports Hub și canale din playlisturi. De exemplu „Arsenal”, „Liga Campionilor”, „Pro TV”.",
            fontSize = 13.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (query.isBlank()) {
            EmptyState(title = "Spune sau scrie ce cauți", hint = "Butonul de microfon al telecomenzii pornește căutarea cu vocea și din alte ecrane.")
            return@Column
        }
        if (matches.isEmpty() && channels.isEmpty()) {
            EmptyState(title = "Nimic găsit", hint = "Încearcă doar numele echipei sau al canalului.")
            return@Column
        }
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(key = "mh") { SectionLabel("Meciuri (${matches.size})") }
                if (matches.isEmpty()) {
                    item(key = "m0") { Text(text = "Niciun meci în listă.", fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp)) }
                }
                items(matches, key = { "m:" + it.id }) { m ->
                    val sources = sourceCounts[m.id] ?: 0
                    ListRow(
                        title = (if (settings.favorites.isFavorite(m)) "★ " else "") + m.title,
                        subtitle = listOfNotNull(
                            when (m.status) {
                                MatchStatus.LIVE -> "LIVE"
                                MatchStatus.FINISHED -> "terminat"
                                MatchStatus.SCHEDULED -> ScreensaverBoard.when_(m.start, now, zone)
                            },
                            m.competition,
                            when (sources) {
                                0 -> "fără surse"
                                1 -> "1 sursă"
                                else -> "$sources surse"
                            },
                        ).joinToString(" · "),
                        onClick = { onOpenMatch(m.id) },
                    )
                }
            }
            LazyColumn(
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(key = "ch") { SectionLabel("Canale (" + (if (channels.size >= CHANNEL_LIMIT) "primele $CHANNEL_LIMIT" else channels.size.toString()) + ")") }
                if (channels.isEmpty()) {
                    item(key = "c0") { Text(text = "Niciun canal.", fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp)) }
                }
                items(channels, key = { "c:" + it.id }) { c ->
                    ListRow(
                        title = c.name,
                        subtitle = listOf(c.groupName, c.playlistName).filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = { onPlayChannel(c.id) },
                        trailing = { VariantQualityBadge(c.playlistId, c.name, c.quality) },
                    )
                }
            }
        }
    }

    if (typing) {
        TextInputDialog(
            title = "Caută",
            hint = "Echipă, competiție sau canal. Fără diacritice e în regulă.",
            initial = query,
            onDismiss = { typing = false },
            onConfirm = {
                query = it
                typing = false
                message = null
            },
        )
    }
}
