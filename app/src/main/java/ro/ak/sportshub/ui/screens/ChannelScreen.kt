package ro.ak.sportshub.ui.screens

import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

/** Ce arată coloana din dreapta. */
private sealed interface Side {
    data object Help : Side
    data class Options(val variant: ChannelWithPlaylist) : Side
    data class PickQuality(val variant: ChannelWithPlaylist) : Side
    data class PickLanguage(val variant: ChannelWithPlaylist) : Side
    data class Merge(val query: String) : Side
}

/** Limbile din lista de corecție (codul ISO 639-1 și numele afișat). */
private val LanguageChoices = listOf(
    "en" to "engleză", "ro" to "română", "de" to "germană", "fr" to "franceză", "it" to "italiană",
    "es" to "spaniolă", "pt" to "portugheză", "nl" to "olandeză", "hu" to "maghiară", "pl" to "poloneză",
    "tr" to "turcă", "el" to "greacă", "ar" to "arabă", "ru" to "rusă", "sr" to "sârbă", "hr" to "croată",
    "bg" to "bulgară", "cs" to "cehă", "sq" to "albaneză", "sv" to "suedeză", "da" to "daneză",
    "no" to "norvegiană", "fi" to "finlandeză",
)

/**
 * Un canal cu toate variantele lui (aceeași denumire normalizată și țară, din toate playlisturile).
 * OK pe o variantă o pornește; OK lung deschide opțiunile ei: varianta preferată, corecția
 * calității sau a limbii, scoaterea din canal. Canalul întreg se poate uni cu altul.
 * Calitatea și limba sunt cele din playlist (sau corecția ta); stream-urile nu se verifică.
 */
@Composable
fun ChannelScreen(channelId: Long, onPlay: (Long) -> Unit, onOpenSchedule: (Long) -> Unit) {
    val container = appContainer()
    val repo = container.variants
    val dao = container.database.channels()
    val scope = rememberCoroutineScope()
    val state by container.variantStore.state.collectAsStateWithLifecycle()

    var key by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var side by remember { mutableStateOf<Side>(Side.Help) }
    var message by remember { mutableStateOf<String?>(null) }
    var askMerge by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        val c = dao.get(channelId)
        if (c == null) missing = true else key = c.groupKey
    }
    val variants by produceState<List<ChannelWithPlaylist>?>(null, key, reload, state) {
        value = key?.let { repo.variants(it) }
    }
    val listFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(variants) {
        if (!focused && !variants.isNullOrEmpty()) {
            focused = true
            runCatching { listFocus.requestFocus() }
        }
    }

    fun act(text: String, block: suspend () -> Unit) {
        scope.launch {
            block()
            message = text
            reload++
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        val list = variants
        if (missing || (list != null && list.isEmpty())) {
            EmptyState("Canalul nu mai există", "Playlistul s-a actualizat sau e dezactivat.")
            return@Column
        }
        if (list == null) return@Column
        val first = list.first()
        val playlists = list.map { it.playlistId }.distinct().size

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = first.name, fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
            first.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
            VariantQualityBadge(first.playlistId, first.name, first.quality)
        }
        Text(
            text = "${list.size} " + (if (list.size == 1) "variantă" else "variante") +
                (if (playlists > 1) " în $playlists playlisturi" else "") +
                " · OK pornește varianta · OK lung: opțiunile ei",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            ActionButton(text = "Pornește prima variantă", onClick = { onPlay(first.id) })
            ActionButton(text = "Programul canalului", onClick = { onOpenSchedule(first.id) })
            ActionButton(text = "Unește cu alt canal…", onClick = { askMerge = true })
            val favorites by container.channelFavorites.keys.collectAsStateWithLifecycle()
            val favorite = first.groupKey in favorites
            ActionButton(
                text = if (favorite) "★ Favorit" else "☆ Adaugă la favorite",
                onClick = {
                    container.channelFavorites.toggle(first.groupKey)
                    message = if (favorite) "Scos din favorite." else "Adăugat la favorite (toate variantele)."
                },
            )
        }

        Row(modifier = Modifier.weight(1f)) {
            LazyColumn(contentPadding = ListPadding, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, v -> v.id }) { index, v ->
                    val corrected = repo.correctionOf(v) != null
                    ListRow(
                        title = v.name,
                        subtitle = listOfNotNull(
                            v.playlistName,
                            v.groupName,
                            if (repo.isPreferred(v, state)) "preferată" else null,
                            if (repo.failedRecently(v, state)) "n-a pornit ultima oară" else null,
                            if (corrected) "corectată de tine" else null,
                        ).joinToString(" · "),
                        selected = (side as? Side.Options)?.variant?.id == v.id,
                        onClick = { onPlay(v.id) },
                        onLongClick = { side = Side.Options(v) },
                        modifier = if (index == 0) Modifier.focusRequester(listFocus) else Modifier,
                        trailing = {
                            v.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                            VariantQualityBadge(v.playlistId, v.name, v.quality)
                        },
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .background(AkColors.Panel, RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp),
            ) {
                when (val s = side) {
                    Side.Help -> HelpText(
                        "Ordinea: varianta preferată, apoi calitatea (4K, FHD, HD, SD), apoi limbile preferate. " +
                            "Variantele care n-au pornit ultima oară coboară la final.\n\n" +
                            "Dacă o variantă nu pornește, playerul trece singur la următoarea, una câte una.\n\n" +
                            "Ține apăsat OK pe o variantă ca s-o faci preferată, să-i corectezi calitatea sau limba " +
                            "ori s-o scoți din acest canal. Corecțiile rămân și după actualizarea playlistului.",
                    )
                    is Side.Options -> VariantOptions(
                        v = s.variant,
                        preferred = repo.isPreferred(s.variant, state),
                        corrected = repo.correctionOf(s.variant) != null,
                        canSeparate = list.size > 1,
                        onPlay = { onPlay(s.variant.id) },
                        onTogglePreferred = {
                            if (repo.isPreferred(s.variant, state)) {
                                repo.clearPreferred(s.variant.groupKey)
                                message = "Fără variantă preferată: pornește cea mai bună."
                            } else {
                                repo.setPreferred(s.variant)
                                message = "${s.variant.name} pornește prima la acest canal."
                            }
                        },
                        onQuality = { side = Side.PickQuality(s.variant) },
                        onLanguage = { side = Side.PickLanguage(s.variant) },
                        onSeparate = {
                            side = Side.Help
                            act("${s.variant.name} e acum un canal separat. Din ecranul lui poți anula corecția.") {
                                repo.separate(s.variant)
                            }
                        },
                        onRevert = {
                            side = Side.Help
                            act("Corecțiile pentru ${s.variant.name} au fost anulate.") { repo.revert(s.variant) }
                        },
                    )
                    is Side.PickQuality -> {
                        val original = repo.correctionOf(s.variant)?.original?.quality ?: s.variant.quality
                        ChoiceList(
                            title = "Calitatea pentru ${s.variant.name}",
                            choices = listOf<Quality?>(null) + Quality.entries,
                            label = { q -> q?.label?.let { if (q == Quality.UNKNOWN) "Necunoscută" else it } ?: "Din playlist (${original.label})" },
                            selected = { q -> q == s.variant.quality && q != null },
                            onPick = { q ->
                                side = Side.Help
                                act("Calitatea a fost actualizată.") { repo.correct(s.variant) { it.copy(quality = q) } }
                            },
                        )
                    }
                    is Side.PickLanguage -> {
                        val original = repo.correctionOf(s.variant)?.original?.language ?: s.variant.language
                        ChoiceList(
                            title = "Limba pentru ${s.variant.name}",
                            choices = listOf<String?>(null) + LanguageChoices.map { it.first } + "",
                            label = { l ->
                                when (l) {
                                    null -> "Din playlist (${original?.uppercase() ?: "necunoscută"})"
                                    "" -> "Necunoscută"
                                    else -> "${l.uppercase()}  ${LanguageChoices.first { it.first == l }.second}"
                                }
                            },
                            selected = { l -> l != null && l == (s.variant.language ?: "") },
                            onPick = { l ->
                                side = Side.Help
                                act("Limba a fost actualizată.") { repo.correct(s.variant) { it.copy(language = l) } }
                            },
                        )
                    }
                    is Side.Merge -> MergePicker(
                        query = s.query,
                        excludeKey = first.groupKey,
                        onPick = { target ->
                            side = Side.Help
                            val into = target.groupKey
                            act("Unit cu ${target.name}. Corecția rămâne și după actualizări.") {
                                repo.merge(first.groupKey, into)
                                key = into
                            }
                        },
                    )
                }
            }
        }
        Text(
            text = message ?: "Back: înapoi la canale.",
            fontSize = 13.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (askMerge) {
        TextInputDialog(
            title = "Unește cu alt canal",
            hint = "Caută canalul în care se mută toate variantele acestuia (de exemplu „sky sports main”).",
            initial = "",
            onDismiss = { askMerge = false },
            onConfirm = {
                askMerge = false
                if (it.isNotBlank()) side = Side.Merge(it)
            },
        )
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text = text, fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp))
}

@Composable
private fun VariantOptions(
    v: ChannelWithPlaylist,
    preferred: Boolean,
    corrected: Boolean,
    canSeparate: Boolean,
    onPlay: () -> Unit,
    onTogglePreferred: () -> Unit,
    onQuality: () -> Unit,
    onLanguage: () -> Unit,
    onSeparate: () -> Unit,
    onRevert: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(v.id) { runCatching { focus.requestFocus() } }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionLabel(v.name) }
        item { ListRow(title = "Pornește această variantă", subtitle = v.playlistName, onClick = onPlay, modifier = Modifier.focusRequester(focus)) }
        item {
            ListRow(
                title = if (preferred) "Nu mai e preferată" else "Setează ca preferată",
                subtitle = if (preferred) "pornește cea mai bună variantă" else "pornește prima la OK pe canal",
                onClick = onTogglePreferred,
            )
        }
        item {
            ListRow(title = "Calitate", subtitle = "dacă eticheta din playlist e greșită", onClick = onQuality, trailing = { VariantQualityBadge(v.playlistId, v.name, v.quality) })
        }
        item {
            ListRow(
                title = "Limbă",
                subtitle = "dacă playlistul n-o spune sau o spune greșit",
                onClick = onLanguage,
                trailing = { Text(text = v.language?.uppercase() ?: "?", fontSize = 14.sp) },
            )
        }
        if (canSeparate) {
            item { ListRow(title = "Scoate din acest canal", subtitle = "e alt canal, grupat greșit", onClick = onSeparate) }
        }
        if (corrected) {
            item { ListRow(title = "Anulează corecțiile", subtitle = "revine la ce spune playlistul", onClick = onRevert) }
        }
    }
}

@Composable
private fun <T> ChoiceList(
    title: String,
    choices: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onPick: (T) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { focus.requestFocus() } }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionLabel(title) }
        itemsIndexed(choices) { index, choice ->
            ListRow(
                title = label(choice),
                selected = selected(choice),
                onClick = { onPick(choice) },
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                trailing = if (selected(choice)) {
                    { Text(text = "✓", fontSize = 16.sp) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun MergePicker(query: String, excludeKey: String, onPick: (ChannelWithPlaylist) -> Unit) {
    val container = appContainer()
    val results by produceState<List<ChannelWithPlaylist>?>(null, query) {
        val fts = SearchText.ftsQuery(query)
        value = if (fts == null) {
            emptyList()
        } else {
            val found = container.database.channels().search(fts, MERGE_LIMIT).first()
            val families = ChannelFamilies.collapse(found) { it.groupKey }.filter { it.key != excludeKey }
            val best = container.variants.families(families.map { it.key })
            families.map { f -> best[f.key]?.firstOrNull() ?: f.members.first() }
        }
    }
    val focus = remember { FocusRequester() }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionLabel("Unește cu: „$query”") }
        val list = results
        if (list != null && list.isEmpty()) {
            item { HelpText("Niciun canal găsit. Încearcă alt cuvânt.") }
        }
        if (list != null) {
            item { LaunchedEffect(list) { runCatching { focus.requestFocus() } } }
            items(list, key = { it.groupKey }) { c ->
                ListRow(
                    title = c.name,
                    subtitle = listOf(c.groupName, c.playlistName).joinToString(" · "),
                    onClick = { onPick(c) },
                    modifier = if (c == list.first()) Modifier.focusRequester(focus) else Modifier,
                    trailing = { VariantQualityBadge(c.playlistId, c.name, c.quality) },
                )
            }
        }
    }
}

private const val MERGE_LIMIT = 300
