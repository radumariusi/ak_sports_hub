package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
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
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors

private val Tabs = listOf("Surse", "Pre-meci", "Live")
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
    /** Sursa pe care ai ținut OK apăsat (meniul „e meciul / nu e”). */
    var menuFor by remember { mutableStateOf<MatchSource<ChannelWithPlaylist>?>(null) }
    /** Căutarea unui canal de adăugat ca sursă: null = închisă, "" = se cere textul. */
    var addQuery by remember { mutableStateOf<String?>(null) }
    /** „De ce nu are sursă?”: în locul listei de surse apar posturile anunțate și ce s-a găsit pentru ele. */
    var showWhy by rememberSaveable { mutableStateOf(false) }
    /** Postul anunțat pe care îl legi de un canal: (post, căutare); căutarea null = se cere textul. */
    var linkStation by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val firstTab = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

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

    // Pre-meci: se cere o dată la deschidere (doar ce e vechi). Live: la un minut, doar cât stai pe tabul Live.
    LaunchedEffect(matchId) { details.refreshPreMatch(currentMatch) }
    LaunchedEffect(matchId, tab == 2) {
        if (tab != 2) return@LaunchedEffect
        while (isActive) {
            details.refreshLive(currentMatch)
            delay(DetailsSchedule.LIVE_REFRESH_MS)
        }
    }

    val favorite = settings.favorites.isFavorite(m)
    val families = remember(entry, favorite, settings.sourcePreferences) {
        MatchSources.order(entry.sources, isFavorite = favorite, prefs = settings.sourcePreferences)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
        Row {
            val hs = event?.homeScore ?: m.homeScore
            val aw = event?.awayScore ?: m.awayScore
            val score = if (!hideSpoilers && liveStatus != MatchStatus.SCHEDULED && hs != null && aw != null) "   $hs–$aw" else ""
            Text(text = m.title + score, fontSize = 34.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = FontWeight.Bold)
            if (liveStatus == MatchStatus.LIVE) Badge(text = "LIVE", color = AkColors.Live, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            if (favorite) Text(text = "  ★", fontSize = 22.sp, color = AkColors.Accent)
        }
        val start = Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault()).format(WhenFormat)
        Text(
            text = listOfNotNull(start, m.sport?.label, m.competition, m.venue).joinToString(" · "),
            fontSize = 14.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        TabStrip(
            tabs = Tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            firstTabModifier = Modifier.focusRequester(firstTab),
        )
        when (tab) {
            0 -> if (showWhy) {
                WhyNoSource(
                    entry = entry,
                    onBack = { showWhy = false },
                    onLink = { station -> linkStation = station to null },
                    modifier = Modifier.weight(1f),
                )
            } else LazyColumn(
                modifier = Modifier.padding(top = 10.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        families.firstOrNull { !it.members.first().rejected }?.members?.firstOrNull()?.let { best ->
                            ActionButton(text = "▶ Pornește sursa recomandată", onClick = { onPlay(best.channel.id) })
                        }
                        // Meci început, pe un canal cu arhivă (Xtream): de la fluierul de start.
                        val now = System.currentTimeMillis()
                        if (m.start < now && m.status != MatchStatus.SCHEDULED) {
                            entry.archiveSource(settings)?.let { src ->
                                if (Catchup.isAvailable(src.catchupDays, m.start, now)) {
                                    ActionButton(
                                        // Meci terminat: reluare, marcată ca atare (playerul arată „RELUARE”, nu „LIVE”).
                                        text = if (m.status == MatchStatus.FINISHED) "⏮ Vezi reluarea" else "⏮ De la început",
                                        onClick = { onPlayArchive(src.id, m.start - ARCHIVE_LEAD_MS, m.end) },
                                    )
                                }
                            }
                        }
                        ActionButton(text = "+ Adaugă un canal", onClick = { addQuery = "" })
                        ActionButton(
                            text = if (entry.summary.sure == 0) "? De ce nu are sursă" else "? De unde vin sursele",
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
                }
                if (families.isEmpty()) {
                    item {
                        Text(
                            text = "Nicio sursă găsită în playlisturile tale. Sursele apar când EPG-ul unui canal conține meciul, " +
                                "când un canal EVENT / PPV are meciul în nume, când un post anunțat are același nume cu un canal al tău " +
                                "(sau e în dicționarul de posturi) ori după regulile de drepturi TV. „? De ce nu are sursă” arată posturile " +
                                "anunțate care lipsesc; poți adăuga și un canal de mână cu „+ Adaugă un canal”.",
                            fontSize = 14.sp,
                            color = AkColors.TextDim,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                var guessesShown = false
                var rejectedShown = false
                families.forEach { family ->
                    val head = family.members.first()
                    if (head.rejected && !rejectedShown) {
                        rejectedShown = true
                        item(key = "t:rejected") { SectionLabel("Respinse de tine (nu e meciul)", modifier = Modifier.padding(top = 14.dp)) }
                    } else if (!head.rejected && head.confidence.guess && !guessesShown) {
                        guessesShown = true
                        item(key = "t:guess") {
                            SectionLabel(
                                "Presupuse (competiții învățate, canale dedicate, drepturi TV)",
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                    item(key = "h:" + family.key) {
                        SectionLabel(head.channel.name, modifier = Modifier.padding(top = 6.dp))
                    }
                    items(family.members, key = { "s:" + it.channel.id }) { s ->
                        val c = s.channel
                        val preferred = variantState.preferred[c.groupKey]?.let { it.playlistId == c.playlistId && it.name == c.name } == true
                        ListRow(
                            title = c.name,
                            subtitle = listOfNotNull(
                                c.playlistName,
                                if (s.rejected) "respinsă de tine" else s.confidence.label + (s.station?.let { ": $it" } ?: ""),
                                if (preferred) "varianta preferată" else null,
                            ).joinToString(" · "),
                            onClick = { onPlay(c.id) },
                            onLongClick = { menuFor = s },
                            trailing = {
                                c.language?.let { Badge(text = it.uppercase(), color = AkColors.Focus) }
                                VariantQualityBadge(c.playlistId, c.name, c.quality)
                            },
                        )
                    }
                }
                item {
                    Text(
                        text = "Calitatea și limba sunt cele declarate de playlist. Ordinea urmează preferințele din Setări → Sports Hub" +
                            (if (favorite) " (meci favorit: limbile preferate întâi)." else " (rezoluția întâi).") +
                            " OK ținut apăsat pe o sursă: „e meciul” / „nu e meciul”; aplicația ține minte postul sau competiția.",
                        fontSize = 13.sp,
                        color = AkColors.TextDim,
                        modifier = Modifier.padding(14.dp),
                    )
                }
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
            else -> LiveTab(
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
        }
    }

    menuFor?.let { src ->
        val verdict = when {
            src.rejected -> Verdict.REJECTED
            src.confidence == Confidence.CONFIRMED -> Verdict.CONFIRMED
            else -> null
        }
        val options = buildList<Pair<String, Verdict?>> {
            add("Da, e meciul" to Verdict.CONFIRMED)
            add("Nu e meciul" to Verdict.REJECTED)
            if (verdict != null) add("Anulează alegerea" to null)
        }
        ChoiceDialog(
            title = src.channel.name,
            hint = when (src.origin) {
                Confidence.STATION -> "Postul anunțat „${src.station}” se leagă (sau nu) de canalul ăsta și la meciurile viitoare."
                Confidence.RIGHTS, Confidence.DEDICATED, Confidence.LEARNED, Confidence.CONFIRMED ->
                    "Se ține minte pentru meciurile din ${m.competition ?: "aceeași competiție"}."
                Confidence.EPG -> "Doar pentru meciul ăsta (EPG-ul canalului l-a arătat)."
                Confidence.EVENT -> "Doar pentru meciul ăsta (numele canalului EVENT / PPV îl arată)."
            },
            options = options.map { it.first },
            selectedIndex = options.indexOfFirst { verdict != null && it.second == verdict },
            onDismiss = { menuFor = null },
            onSelect = { i ->
                container.sports.decide(m, src.groupKey, options[i].second, src)
                menuFor = null
            },
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
    "thesportsdb" to "TheSportsDB", "livesoccertv" to "LiveSoccerTV", "sofascore" to "Sofascore",
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
                        "FutbolEnLaTV (Spania, multe sporturi), Sofascore (pe țară), ESPN / NHL / MLB (SUA, Canada) și TheSportsDB; le pornești din Setări → Sports Hub.",
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
