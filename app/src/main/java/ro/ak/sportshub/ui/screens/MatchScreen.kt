package ro.ak.sportshub.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import ro.ak.sportshub.core.common.ChannelFamily
import ro.ak.sportshub.core.sports.LanguageGroups
import ro.ak.sportshub.ui.components.ReturnFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.sports.Confidence
import ro.ak.sportshub.core.sports.DetailsSchedule
import ro.ak.sportshub.core.sports.MatchSource
import ro.ak.sportshub.core.sports.SourceDiagnosis
import ro.ak.sportshub.core.sports.StationNames
import ro.ak.sportshub.core.sports.StationOutcome
import ro.ak.sportshub.core.sports.Verdict
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.data.MatchDetailsState
import ro.ak.sportshub.core.sports.MatchSources
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.playlist.Catchup
import ro.ak.sportshub.data.archiveSource
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.rememberReturnFocus
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

private val Tabs = listOf("Surse", "Pre", "Formații", "Live", "Statistici")
private val WhenFormat = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale.forLanguageTag("ro"))

/**
 * Fereastra de previzualizare a unui meci: se deschide înainte de player, iar playerul pornește
 * abia când alegi o sursă. Sursele sunt grupate pe canal și apoi pe variante (calitate, limbă,
 * încredere), în ordinea preferințelor tale; nimic nu e ascuns.
 */
@Composable
fun MatchScreen(
    matchId: String,
    onPlay: (Long) -> Unit,
    onPlayArchive: (channelId: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
) {
    val container = appContainer()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val settings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val variantState by container.variantStore.state.collectAsStateWithLifecycle()
    val details = container.matchDetails
    val detailsStates by details.states.collectAsStateWithLifecycle()
    val revealed by details.revealed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    /** Sursa previzualizată în fereastra din dreapta (OK pe o sursă; încă un OK = tot ecranul); un singur stream odată. */
    var previewOf by remember { mutableStateOf<MatchSource<ChannelWithPlaylist>?>(null) }
    /** Sursa pe care s-a ținut OK apăsat: alegerile ei (e meciul, nu e, alt canal în stream). */
    var actionsFor by remember { mutableStateOf<MatchSource<ChannelWithPlaylist>?>(null) }
    /** ◀ ▶ schimbă tabul de oriunde din fereastră, în afară de rândul de taburi și de rândul de butoane. */
    var stripFocused by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    var tabFocusSignal by remember { mutableIntStateOf(0) }
    /** Căutarea unui canal de adăugat ca sursă: null = închisă, "" = se cere textul. */
    var addQuery by remember { mutableStateOf<String?>(null) }
    /** „De ce nu are sursă?”: în locul listei de surse apar posturile anunțate și ce s-a găsit pentru ele. */
    var showWhy by rememberSaveable { mutableStateOf(false) }
    /** Postul anunțat pe care îl legi de un canal: (post, căutare); căutarea null = se cere textul. */
    var linkStation by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val firstTab = remember { FocusRequester() }

    // La Back din player, focusul revine pe sursa pornită; altfel pe primul tab.
    val returnFocus = rememberReturnFocus()
    LaunchedEffect(Unit) { if (!returnFocus.returning) runCatching { firstTab.requestFocus() } }

    val entry = remember(snapshot, matchId) { snapshot.matches.firstOrNull { it.match.id == matchId } }
    if (entry == null) {
        EmptyState(
            title = if (snapshot.loading) "Se încarcă meciul…" else "Meciul nu mai e în listă",
            hint = "Apasă Înapoi.",
        )
        return
    }
    val m = entry.match
    val currentMatch by rememberUpdatedState(m)
    val detailsState = detailsStates[m.id] ?: MatchDetailsState()
    val event = detailsState.details.event
    val liveStatus = listOfNotNull(event?.status, m.status).maxBy { it.ordinal }
    val hideSpoilers = Spoilers.hide(settings.noSpoilers, m.id in revealed, liveStatus)

    // Pre-meci: se cere o dată la deschidere (doar ce e vechi). Live: la un minut, doar cât stai pe Live sau Statistici.
    // Posturile pe țări (pagina LiveSoccerTV a meciului), dacă sunt vechi.
    LaunchedEffect(matchId) {
        details.refreshPreMatch(currentMatch)
        container.sports.refreshStationsFor(currentMatch)
    }
    val liveTab = tab == 3 || tab == 4
    LaunchedEffect(matchId, liveTab) {
        if (!liveTab) return@LaunchedEffect
        while (isActive) {
            details.refreshLive(currentMatch)
            delay(DetailsSchedule.LIVE_REFRESH_MS)
        }
    }

    val favorite = settings.favorites.isFavorite(m)
    val families = remember(entry, favorite, settings.sourcePreferences) {
        MatchSources.order(entry.sources, isFavorite = favorite, prefs = settings.sourcePreferences)
    }

    // Previzualizarea se oprește când pleci de pe tabul Surse.
    LaunchedEffect(tab) { if (tab != 0) previewOf = null }
    BackHandler(enabled = previewOf != null) { previewOf = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val arrow = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                if (!arrow || stripFocused || headerFocused) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) {
                    val next = (tab + if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, Tabs.size - 1)
                    if (next != tab) {
                        tab = next
                        showWhy = false
                    }
                    tabFocusSignal++
                }
                true
            }
            .padding(horizontal = 36.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val hs = event?.homeScore ?: m.homeScore
            val aw = event?.awayScore ?: m.awayScore
            val score = if (!hideSpoilers && liveStatus != MatchStatus.SCHEDULED && hs != null && aw != null) "   $hs–$aw" else ""
            // Siglele echipelor (sau a competiției la evenimentele fără echipe), doar dacă se cunosc.
            if (m.away.isNotBlank()) {
                ro.ak.sportshub.ui.components.TeamLogo(m.home, 44.dp, modifier = Modifier.padding(end = 12.dp))
            } else {
                ro.ak.sportshub.ui.components.CompetitionLogo(m.competition, 44.dp, modifier = Modifier.padding(end = 12.dp))
            }
            Text(text = m.title + score, fontSize = 30.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = FontWeight.Bold)
            if (m.away.isNotBlank()) ro.ak.sportshub.ui.components.TeamLogo(m.away, 44.dp, modifier = Modifier.padding(start = 12.dp))
            if (liveStatus == MatchStatus.LIVE) Badge(text = "LIVE", color = AkColors.Live, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            if (favorite) Text(text = "  ★", fontSize = 22.sp, color = AkColors.Accent)
        }
        val start = Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault()).format(WhenFormat)
        Text(
            text = listOfNotNull(start, m.sport?.label, m.competition, m.venue).joinToString(" · "),
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        TabStrip(
            tabs = Tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            firstTabModifier = Modifier.focusRequester(firstTab),
            modifier = Modifier.onFocusChanged { stripFocused = it.hasFocus },
            focusSignal = tabFocusSignal,
        )
        when (tab) {
            0 -> if (showWhy) {
                WhyNoSource(
                    entry = entry,
                    onBack = { showWhy = false },
                    onLink = { station -> linkStation = station to null },
                    modifier = Modifier.weight(1f),
                )
            } else Row(modifier = Modifier.weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SourcesList(
                    entry = entry,
                    families = families,
                    favorite = favorite,
                    preferred = settings.sourcePreferences.preferredLanguages,
                    previewId = previewOf?.channel?.id,
                    returnFocus = returnFocus,
                    header = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .onFocusChanged { headerFocused = it.hasFocus }
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            families.firstOrNull { it.members.first().let { h -> !h.rejected && h.confidence != Confidence.ELSEWHERE } }?.members?.firstOrNull()?.let { best ->
                                ActionButton(
                                    text = "▶ Sursa recomandată",
                                    onClick = { returnFocus.clicked("best"); onPlay(best.channel.id) },
                                    modifier = returnFocus.on("best"),
                                )
                            }
                            // Meci început, pe un canal cu arhivă (Xtream): de la fluierul de start.
                            val now = System.currentTimeMillis()
                            if (m.start < now && m.status != MatchStatus.SCHEDULED) {
                                entry.archiveSource(settings)?.let { src ->
                                    if (Catchup.isAvailable(src.catchupDays, m.start, now)) {
                                        ActionButton(
                                            // Meci terminat: reluare, marcată ca atare (playerul arată „RELUARE”, nu „LIVE”).
                                            text = if (m.status == MatchStatus.FINISHED) "⏮ Reluarea" else "⏮ De la început",
                                            onClick = { returnFocus.clicked("archive"); onPlayArchive(src.id, m.start - ARCHIVE_LEAD_MS, m.end) },
                                            modifier = returnFocus.on("archive"),
                                        )
                                    }
                                }
                            }
                            ActionButton(text = "+ Canal", onClick = { addQuery = "" })
                            ActionButton(
                                text = if (entry.summary.sure == 0) "? De ce nu are sursă" else "? De unde vin",
                                onClick = { showWhy = true },
                            )
                            val store = container.sportsSettings
                            // La o cursă sau o gală „echipa” e numele evenimentului: nu se poate marca drept echipă favorită.
                            if (!m.isSingleEvent) {
                                ActionButton(
                                    text = (if (settings.favorites.isTeam(m.home)) "★ " else "☆ ") + m.home,
                                    onClick = { store.toggleFavoriteTeam(m.home) },
                                )
                            }
                            if (m.away.isNotBlank()) {
                                ActionButton(
                                    text = (if (settings.favorites.isTeam(m.away)) "★ " else "☆ ") + m.away,
                                    onClick = { store.toggleFavoriteTeam(m.away) },
                                )
                            }
                            m.competition?.let { c ->
                                ActionButton(
                                    text = (if (settings.favorites.isCompetition(c)) "★ " else "☆ ") + c,
                                    onClick = { store.toggleFavoriteCompetition(c) },
                                )
                            }
                        }
                    },
                    // OK: previzualizare în dreapta; OK pe aceeași sursă: pe tot ecranul. OK ținut: alegerile sursei.
                    onClick = { src ->
                        if (previewOf?.channel?.id == src.channel.id) {
                            returnFocus.clicked("s:" + src.channel.id)
                            previewOf = null
                            onPlay(src.channel.id)
                        } else {
                            previewOf = src
                        }
                    },
                    onLongClick = { src -> actionsFor = src },
                    modifier = Modifier.weight(1f),
                )
                SourcePreviewPane(
                    source = previewOf?.let { p -> entry.sources.firstOrNull { it.channel.id == p.channel.id } ?: p },
                    modifier = Modifier.width(420.dp),
                )
            }
            1 -> PreMatchTab(
                match = m,
                state = detailsState,
                hideSpoilers = hideSpoilers,
                sportsDbEnabled = settings.sportsDbEnabled,
                onRefresh = { scope.launch { details.refreshPreMatch(m, force = true) } },
                onReveal = { details.reveal(m.id) },
                modifier = Modifier.weight(1f),
            )
            2 -> LineupsTab(match = m, state = detailsState, modifier = Modifier.weight(1f))
            3 -> LiveTab(
                match = m,
                state = detailsState,
                hideSpoilers = hideSpoilers,
                sportsDbEnabled = settings.sportsDbEnabled,
                onRefresh = { scope.launch { details.refreshLive(m, force = true) } },
                onReveal = { details.reveal(m.id) },
                onHide = { details.hide(m.id) },
                noSpoilersOn = settings.noSpoilers,
                modifier = Modifier.weight(1f),
            )
            else -> StatsTab(
                match = m,
                state = detailsState,
                hideSpoilers = hideSpoilers,
                onRefresh = { scope.launch { details.refreshLive(m, force = true) } },
                onReveal = { details.reveal(m.id) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    actionsFor?.let { held ->
        val src = entry.sources.firstOrNull { it.channel.id == held.channel.id } ?: held
        SourceActions(
            match = m,
            source = src,
            previewing = previewOf?.channel?.id == src.channel.id,
            onDismiss = { actionsFor = null },
            onFull = { returnFocus.clicked("s:" + src.channel.id); actionsFor = null; previewOf = null; onPlay(src.channel.id) },
            onPreview = { actionsFor = null; previewOf = if (previewOf?.channel?.id == src.channel.id) null else src },
        )
    }

    linkStation?.let { (station, query) ->
        if (query == null) {
            TextInputDialog(
                title = "Leagă „$station” de un canal",
                initial = StationNames.splitCountry(station).first,
                hint = "Numele canalului tău care e postul ăsta. Se ține minte în dicționar, pentru toate meciurile.",
                onDismiss = { linkStation = null },
                onConfirm = { text -> linkStation = text.trim().takeIf { it.isNotEmpty() }?.let { station to it } },
            )
        } else {
            ChannelPickerDialog(
                query = query,
                onDismiss = { linkStation = null },
                onPick = { c ->
                    container.sports.linkStation(station, c.groupKey)
                    linkStation = null
                },
            )
        }
    }

    when (val q = addQuery) {
        null -> Unit
        "" -> TextInputDialog(
            title = "Adaugă un canal ca sursă",
            initial = "",
            hint = "Numele canalului (de exemplu: digi sport 1). Se ține minte pentru competiția meciului.",
            onDismiss = { addQuery = null },
            onConfirm = { text -> addQuery = text.trim().ifEmpty { null } },
        )
        else -> ChannelPickerDialog(
            query = q,
            onDismiss = { addQuery = null },
            onPick = { c ->
                container.sports.decide(m, c.groupKey, Verdict.CONFIRMED, null)
                addQuery = null
            },
        )
    }
}

/**
 * Alege un canal după numele căutat [query]: câte un rând pe canal (variantele sunt aceleași),
 * cu numele original, grupul și playlistul.
 */
@Composable
fun ChannelPickerDialog(query: String, onDismiss: () -> Unit, onPick: (ChannelWithPlaylist) -> Unit) {
    val container = appContainer()
    val results by produceState<List<ChannelWithPlaylist>?>(null, query) {
        val fts = SearchText.ftsQuery(query)
        value = if (fts == null) {
            emptyList()
        } else {
            val found = container.database.channels().search(fts, PICKER_LIMIT).first()
            ChannelFamilies.collapse(found) { it.groupKey }.map { it.members.first() }
        }
    }
    val list = results ?: return
    if (list.isEmpty()) {
        ChoiceDialog(
            title = "Niciun canal pentru „$query”",
            options = listOf("Închide"),
            selectedIndex = -1,
            onDismiss = onDismiss,
            onSelect = { onDismiss() },
        )
        return
    }
    ChoiceDialog(
        title = "Canale pentru „$query”",
        options = list.map { c -> "${c.name}  ·  ${c.groupName}  ·  ${c.playlistName}" },
        selectedIndex = -1,
        onDismiss = onDismiss,
        onSelect = { i -> onPick(list[i]) },
    )
}

private const val PICKER_LIMIT = 200

/** „De la început” pornește arhiva cu două minute înainte de ora de start. */
private const val ARCHIVE_LEAD_MS = 120_000L

/** Numele afișate ale furnizorilor de meciuri. */
private val ProviderNames = mapOf(
    "thesportsdb" to "TheSportsDB", "euroleague" to "EuroLeague", "livesoccertv" to "LiveSoccerTV", "sofascore" to "Sofascore",
    "futbolenlatv" to "FutbolEnLaTV", "epg" to "EPG-ul tău", "playlist" to "canale EVENT / PPV",
    "liveonsat" to "LiveOnSat", "fotmob" to "FotMob", "espn" to "ESPN", "nhl" to "NHL", "mlb" to "MLB",
)

/**
 * „De ce nu are sursă?”: de unde vine meciul, posturile anunțate (găsite, din altă țară, respinse, lipsă),
 * EPG-ul, canalele EVENT / PPV și regulile de drepturi TV. OK pe un post lipsă îl leagă de un canal.
 */
@Composable
private fun WhyNoSource(
    entry: ro.ak.sportshub.data.MatchEntry,
    onBack: () -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val knowledge by container.sportsKnowledge.state.collectAsStateWithLifecycle()
    val diagnosis by produceState<SourceDiagnosis?>(null, entry, knowledge) { value = container.sports.diagnose(entry.match.id) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
    LazyColumn(modifier = modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { ListRow(title = "← Înapoi la surse", onClick = onBack, modifier = Modifier.focusRequester(firstRow)) }
        val d = diagnosis
        if (d == null) {
            item { Text(text = "Se verifică…", fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp)) }
            return@LazyColumn
        }
        item {
            Text(
                text = "Meciul vine de la: " + d.providers.map { ProviderNames[it] ?: it }.sorted().joinToString(", ") + ". " +
                    "Surse găsite: ${entry.summary.sure} sigure sau probabile, ${entry.summary.count - entry.summary.sure} presupuse.",
                fontSize = 14.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        item { SectionLabel("Posturi anunțate (OK pe unul lipsă: leagă-l de canalul tău)", modifier = Modifier.padding(top = 6.dp)) }
        if (d.stations.isEmpty()) {
            item {
                Text(
                    text = "Niciun furnizor nu a anunțat posturi pentru meciul ăsta. Posturile vin de la LiveSoccerTV, LiveOnSat și FotMob (fotbal), " +
                        "FutbolEnLaTV (Spania și site-urile surori: Marea Britanie, SUA, Germania…), Sofascore (pe țară), ESPN / NHL / MLB și TheSportsDB; le pornești din Setări → Sports Hub.",
                    fontSize = 13.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
        items(d.stations, key = { "st:" + it.station.name + "|" + it.station.country }) { c ->
            val linkable = c.outcome == StationOutcome.MISSING || c.outcome == StationOutcome.OTHER_COUNTRY
            ListRow(
                title = c.station.name + (c.station.country?.let { " ($it)" } ?: ""),
                subtitle = c.outcome.label + if (c.groupKeys.isNotEmpty() && c.outcome != StationOutcome.MISSING) {
                    ": " + c.groupKeys.take(3).joinToString(", ") { it.substringAfter(':') } + if (c.groupKeys.size > 3) "…" else ""
                } else {
                    ""
                },
                onClick = { if (linkable) onLink(c.station.name) },
                trailing = {
                    Text(
                        text = when (c.outcome) {
                            StationOutcome.FOUND, StationOutcome.FROM_DICTIONARY -> "✓"
                            StationOutcome.BLOCKED -> "✕"
                            else -> "lipsă"
                        },
                        fontSize = 14.sp,
                        color = if (linkable) AkColors.Live else AkColors.TextDim,
                    )
                },
            )
        }
        item { SectionLabel("Celelalte căi", modifier = Modifier.padding(top = 10.dp)) }
        item {
            Text(
                text = listOf(
                    if (d.epgBroadcasts > 0) "EPG: ${d.epgBroadcasts} emisiuni cu meciul pe canalele tale." else
                        "EPG: nicio emisiune cu meciul (canalul care îl transmite poate să nu aibă EPG; vezi Dicționar → Fără EPG).",
                    if (d.eventChannels > 0) "Canale EVENT / PPV: ${d.eventChannels} cu meciul în nume." else
                        "Canale EVENT / PPV: niciunul cu meciul în nume.",
                    if (d.rules.isEmpty()) "Drepturi TV: nicio regulă pentru competiția meciului." else
                        "Drepturi TV: " + d.rules.joinToString("; ") { r ->
                            r.rule.label + " → " + (if (r.groupKeys.isEmpty()) "niciun canal găsit" else "${r.groupKeys.size} canale") +
                                (if (r.missing.isNotEmpty()) " (lipsă: " + r.missing.joinToString(", ") + ")" else "")
                        },
                    if (d.learned > 0) "Învățat: ${d.learned} canale confirmate la alte meciuri din competiție." else null,
                    if (d.rejected > 0) "Respinse de tine la meciul ăsta: ${d.rejected}." else null,
                ).filterNotNull().joinToString("\n"),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}


/**
 * Lista surselor, pe grupuri de limbi (limbile tale, apoi spaniolă / italiană / germană / franceză, apoi restul), în
 * fiecare grup în ordinea preferințelor (găsite, presupuse, „EPG-ul arată altceva”); cele respinse la final.
 * OK o previzualizează în dreapta, încă un OK pe ea o pornește pe tot ecranul; OK ținut apăsat: alegerile sursei.
 */
@Composable
private fun SourcesList(
    entry: ro.ak.sportshub.data.MatchEntry,
    families: List<ChannelFamily<MatchSource<ChannelWithPlaylist>>>,
    favorite: Boolean,
    preferred: List<String>,
    previewId: Long?,
    returnFocus: ReturnFocus,
    header: @Composable () -> Unit,
    onClick: (MatchSource<ChannelWithPlaylist>) -> Unit,
    onLongClick: (MatchSource<ChannelWithPlaylist>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = appContainer()
    val variantState by container.variantStore.state.collectAsStateWithLifecycle()
    val (groups, rejected) = remember(families, preferred) { LanguageGroups.split(families, preferred) }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item(key = "header") { header() }
        if (families.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Nicio sursă găsită în playlisturile tale. Sursele apar când EPG-ul unui canal conține meciul, " +
                        "când un canal EVENT / PPV are meciul în nume, când un post anunțat are același nume cu un canal al tău " +
                        "(sau e în dicționarul de posturi) ori după regulile de drepturi TV. „? De ce nu are sursă” arată posturile " +
                        "anunțate care lipsesc; poți adăuga și un canal de mână cu „+ Canal”.",
                    fontSize = 14.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        fun family(f: ChannelFamily<MatchSource<ChannelWithPlaylist>>) {
            if (f.members.size > 1) {
                item(key = "h:" + f.key) {
                    Text(
                        text = f.members.first().channel.name,
                        fontSize = 13.sp,
                        color = AkColors.TextDim,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                    )
                }
            }
            items(f.members, key = { "s:" + it.channel.id }) { s ->
                val c = s.channel
                val pref = variantState.preferred[c.groupKey]?.let { it.playlistId == c.playlistId && it.name == c.name } == true
                ListRow(
                    title = c.name,
                    titleSize = 16.sp,
                    subtitle = listOfNotNull(c.playlistName, sourceReason(s), if (pref) "varianta preferată" else null).joinToString(" · "),
                    selected = c.id == previewId,
                    modifier = returnFocus.on("s:" + c.id),
                    onClick = { onClick(s) },
                    onLongClick = { onLongClick(s) },
                    trailing = {
                        if (c.id == previewId) Badge(text = "▶ PREVIZUALIZARE", color = AkColors.Accent)
                        c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                        VariantQualityBadge(c.playlistId, c.name, c.quality)
                    },
                )
            }
        }
        groups.forEach { (g, list) ->
            item(key = "g:" + g.name) {
                GroupHeader(LanguageGroups.label(g, preferred), list.size)
            }
            list.forEach { family(it) }
        }
        if (rejected.isNotEmpty()) {
            item(key = "g:rejected") { GroupHeader("Respinse de tine (nu e meciul)", rejected.size, dim = true) }
            rejected.forEach { family(it) }
        }
        item(key = "foot") {
            Text(
                text = "Calitatea și limba vin din playlist (✓ = rezoluția văzută la redare). În fiecare grup: întâi sursele găsite, apoi cele " +
                    "presupuse, apoi cele al căror EPG arată altceva" + (if (favorite) " (meci favorit: limbile preferate întâi)." else ".") +
                    " OK: previzualizare în dreapta, încă un OK: tot ecranul. OK ținut: e meciul / nu e / alt canal. ◀ ▶: taburile.",
                fontSize = 12.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** Titlul unui grup de limbi: mare, chihlimbar, cu o linie dedesubt, ca grupurile să se vadă de departe. */
@Composable
private fun GroupHeader(title: String, count: Int, dim: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed,
                fontWeight = FontWeight.Bold,
                color = if (dim) AkColors.TextDim else AkColors.Accent,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "  " + count + (if (count == 1) " canal" else " canale"),
                fontSize = 14.sp,
                color = AkColors.TextDim,
            )
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(if (dim) AkColors.TextDim.copy(alpha = 0.3f) else AkColors.Accent.copy(alpha = 0.5f)),
        )
    }
}

/** De ce e sursa aici: „Sigur (EPG)”, „Probabil (post anunțat): Sky Sports Main Event”, „EPG-ul arată altceva: Snooker”. */
private fun sourceReason(s: MatchSource<*>): String = when {
    s.rejected -> "respinsă de tine"
    s.confidence == Confidence.ELSEWHERE -> "EPG-ul arată altceva" + (s.airing?.let { ": $it" } ?: "") + (s.station?.let { " (anunțat: $it)" } ?: "")
    else -> s.confidence.label + (s.station?.let { ": $it" } ?: "")
}

/**
 * Previzualizarea unei surse, în dreapta listei: același player (un singur stream, permisul playlistului), mic.
 * Nu ia focusul: OK pe aceeași sursă din listă o trece pe tot ecranul, OK ținut deschide alegerile ei.
 */
@Composable
private fun SourcePreviewPane(
    source: MatchSource<ChannelWithPlaylist>?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (source == null) {
            SectionLabel("Previzualizare")
            Text(
                text = "OK pe o sursă o pornește aici, mică: vezi dacă merge, ce e pe ea și ce rezoluție are (se notează singură " +
                    "pe etichetă). Încă un OK pe aceeași sursă: pe tot ecranul. OK ținut apăsat: e meciul / nu e / alt canal în " +
                    "stream. Un singur stream odată (o conexiune pe playlist).",
                fontSize = 14.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            return@Column
        }
        val c = source.channel
        ro.ak.sportshub.player.VideoPreview(channelId = c.id)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            VariantQualityBadge(c.playlistId, c.name, c.quality)
        }
        Text(text = listOfNotNull(c.playlistName, sourceReason(source)).joinToString(" · "), fontSize = 13.sp, color = AkColors.TextDim, maxLines = 2)
        Text(
            text = "OK din nou: pe tot ecranul · OK ținut: e meciul / nu e / alt canal · Back: oprește",
            fontSize = 13.sp,
            color = AkColors.Accent,
        )
    }
}

/** Alegerile unei surse (OK ținut apăsat pe ea): tot ecranul, previzualizare, e meciul, nu e, alt canal în stream. */
@Composable
private fun SourceActions(
    match: ro.ak.sportshub.core.sports.Match,
    source: MatchSource<ChannelWithPlaylist>,
    previewing: Boolean,
    onDismiss: () -> Unit,
    onFull: () -> Unit,
    onPreview: () -> Unit,
) {
    val container = appContainer()
    val suspect by container.suspectChannels.keys.collectAsStateWithLifecycle()
    val c = source.channel
    val verdict = when {
        source.rejected -> Verdict.REJECTED
        source.confidence == Confidence.CONFIRMED -> Verdict.CONFIRMED
        else -> null
    }
    val isSuspect = ro.ak.sportshub.data.CorrectionKey(c.playlistId, c.name) in suspect
    val decide = { v: Verdict? -> container.sports.decide(match, source.groupKey, v, source) }
    val actions = buildList<Pair<String, () -> Unit>> {
        add("▶ Pe tot ecranul" to onFull)
        add((if (previewing) "■ Oprește previzualizarea" else "Previzualizează în dreapta") to onPreview)
        add(((if (verdict == Verdict.CONFIRMED) "✓ " else "") + "E meciul") to { decide(Verdict.CONFIRMED); onDismiss() })
        add(((if (verdict == Verdict.REJECTED) "✓ " else "") + "Nu e meciul") to { decide(Verdict.REJECTED); onDismiss() })
        if (verdict != null) add("Anulează alegerea" to { decide(null); onDismiss() })
        add((if (isSuspect) "✓ Alt canal în stream (scoate semnul)" else "Alt canal în stream") to {
            container.suspectChannels.toggle(c.playlistId, c.name)
            onDismiss()
        })
    }
    ChoiceDialog(
        title = c.name,
        options = actions.map { it.first },
        selectedIndex = 0,
        onDismiss = onDismiss,
        onSelect = { i -> actions[i].second() },
        hint = when (source.origin) {
            Confidence.STATION, Confidence.ELSEWHERE -> "„E meciul” / „Nu e” leagă (sau nu) postul anunțat de canalul ăsta și la meciurile viitoare."
            Confidence.RIGHTS, Confidence.DEDICATED, Confidence.LEARNED, Confidence.CONFIRMED ->
                "Alegerea se ține minte pentru meciurile din ${match.competition ?: "aceeași competiție"}."
            Confidence.EPG -> "Alegerea e doar pentru meciul ăsta (EPG-ul canalului l-a arătat)."
            Confidence.EVENT -> "Alegerea e doar pentru meciul ăsta (numele canalului EVENT / PPV îl arată)."
        } + " „Alt canal în stream”: rămâne sursă, după celelalte variante.",
    )
}
