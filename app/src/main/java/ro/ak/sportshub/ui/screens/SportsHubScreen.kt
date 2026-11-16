package ro.ak.sportshub.ui.screens

import ro.ak.sportshub.ui.components.touchClick
import ro.ak.sportshub.ui.components.FilterIcon
import ro.ak.sportshub.ui.components.FilterPill
import ro.ak.sportshub.ui.components.IconPill
import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.sports.MatchFilter
import ro.ak.sportshub.core.sports.MatchLists
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.MatchTab
import ro.ak.sportshub.core.sports.NowOnChannels
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.SourceWindow
import ro.ak.sportshub.core.sports.TeamCalendar
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.data.hasReplay
import ro.ak.sportshub.data.bestSource
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.QualityBadge
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.TabStrip
import ro.ak.sportshub.ui.components.rememberReturnFocus
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts
import ro.ak.sportshub.ui.components.LocalOnFocus
import ro.ak.sportshub.ui.components.accentText
import ro.ak.sportshub.ui.components.dimText
import ro.ak.sportshub.core.sports.Countdown
import ro.ak.sportshub.core.sports.MatchSources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.LocalContentColor
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import ro.ak.sportshub.core.sports.TeamLookBook
import ro.ak.sportshub.ui.components.CompetitionLogo
import ro.ak.sportshub.ui.components.FocusBorder
import ro.ak.sportshub.ui.components.FormSquares
import ro.ak.sportshub.ui.components.MatchBand
import ro.ak.sportshub.ui.components.TeamLogo
import ro.ak.sportshub.ui.components.rememberTeamLooks

/** Ferestrele filtrelor din Sports Hub (aceleași și în Setări → Filtre Sports Hub). */
internal enum class FilterDialog { SPORT, COMPETITION, TEAM, LANGUAGE, QUALITY }

private val RoLocale = Locale.forLanguageTag("ro")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", RoLocale)
private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", RoLocale)
private val WeekDayFormat = DateTimeFormatter.ofPattern("dd.MM", RoLocale)
private val DayRowHeight = 22.dp

@Composable
fun SportsHubScreen(
    /** Tabul curent din meniul de sus (▲ din taburile Sports Hub urcă exact acolo). */
    topMenu: FocusRequester? = null,
    /** Pus pe tabul curent din Sports Hub (▼ din meniul de sus coboară exact aici). */
    tabsEntry: FocusRequester? = null,
    onOpenMatch: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (Long) -> Unit = {},
    onPlayArchive: (channelId: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
) {
    val container = appContainer()
    val sports = container.sports
    val store = container.sportsSettings
    val snapshot by sports.snapshot.collectAsStateWithLifecycle()
    val status by sports.status.collectAsStateWithLifecycle()
    // E3: prima listă de meciuri afișată după pornire (o dată pe rulare, în jurnal).
    val hasMatches = snapshot.matches.isNotEmpty()
    LaunchedEffect(hasMatches) { if (hasMatches) container.startup.mark("lista Sports Hub afișată", " (${snapshot.matches.size} meciuri)") }
    val settings by store.settings.collectAsStateWithLifecycle()
    val revealed by container.matchDetails.revealed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // „Toată săptămâna”: ziua aleasă din rândul de zile (0 = poimâine).
    var weekDay by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FilterDialog?>(null) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    // F5: fereastra video (OK lung pe un meci): meciul și canalul pornit; se oprește la Back sau pe alt meci.
    var videoMatchId by remember { mutableStateOf<String?>(null) }
    var videoChannel by remember { mutableStateOf<Long?>(null) }
    var videoNote by remember { mutableStateOf<String?>(null) }
    fun toggleVideo(entry: MatchEntry) {
        if (videoMatchId == entry.match.id) {
            videoMatchId = null
            videoChannel = null
            return
        }
        val best = entry.bestSource(settings)
        if (best == null) {
            videoNote = "${entry.match.title}: nicio sursă în playlisturile tale."
            return
        }
        videoNote = null
        videoMatchId = entry.match.id
        videoChannel = best.id
    }
    androidx.activity.compose.BackHandler(enabled = videoChannel != null) {
        videoMatchId = null
        videoChannel = null
    }
    androidx.compose.runtime.LaunchedEffect(focusedId) {
        if (videoMatchId != null && focusedId != null && focusedId != videoMatchId) {
            videoMatchId = null
            videoChannel = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(tab) {
        videoMatchId = null
        videoChannel = null
    }
    // Scorul și starea meciurilor live, cât e Sports Hub pe ecran (o cerere pe ligă, cel mult o dată pe minut).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            runCatching { sports.refreshLiveScores() }
            kotlinx.coroutines.delay(30_000)
        }
    }
    // La Back de pe meci, focusul revine pe meciul de unde ai plecat.
    val returnFocus = rememberReturnFocus()
    // Navigarea sus/jos între rânduri e explicită (meniul de sus → taburi → zile → filtre → listă și înapoi),
    // ca focusul să nu sară peste un rând când elementele nu stau unul sub altul.
    val tabsReq = tabsEntry ?: remember { FocusRequester() }
    val dayReq = remember { FocusRequester() }
    val filterReqs = remember { List(FILTER_SLOTS) { FocusRequester() } }
    var lastFilter by rememberSaveable { mutableIntStateOf(0) }

    val filter = settings.filter
    val zone = remember { ZoneId.systemDefault() }
    val now = snapshot.computedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    // Tabul „Evenimente” arată canalele EVENT / PPV din playlisturi, nu meciuri; apoi „Acum pe canale” și „Rezumate”.
    val eventsTab = tab == MatchTab.entries.size
    val nowTab = tab == MatchTab.entries.size + 1
    val highlightsTab = tab == MatchTab.entries.size + 2
    val standingsTab = tab == MatchTab.entries.size + 3
    val currentTab = MatchTab.entries[tab.coerceAtMost(MatchTab.entries.lastIndex)]

    val weekDays = remember(now / 3_600_000L) { MatchLists.weekDays(now, zone) }
    val visible: List<MatchEntry> = remember(snapshot, settings, tab, weekDay) {
        val byId = snapshot.matches.associateBy { it.match.id }
        val chosenDay = weekDays.getOrNull(weekDay)
        val inTab = snapshot.matches.filter { e ->
            MatchLists.inTab(e.match, currentTab, now, zone, settings.favorites.isFavorite(e.match)) &&
                (currentTab != MatchTab.WEEK || MatchLists.day(e.match, zone) == chosenDay) &&
                filter.accepts(e.match, e.summary)
        }
        MatchLists.sort(inTab.map { it.match }).mapNotNull { byId[it.id] }
    }

    // Tabul Favorite: calendarul fiecărei echipe favorite (următoarele 5 meciuri), apoi restul favoritelor.
    val calendar: List<Pair<String, List<MatchEntry>>> = remember(snapshot, settings, tab) {
        if (currentTab != MatchTab.FAVORITES || settings.favorites.teams.isEmpty()) {
            emptyList()
        } else {
            val byId = snapshot.matches.associateBy { it.match.id }
            TeamCalendar.build(settings.favorites.teams, snapshot.matches.map { it.match }, now)
                .map { (team, ms) -> team to ms.mapNotNull { byId[it.id] } }
        }
    }
    // Cardul de sus: următorul meci (sau cel live) al echipelor tale favorite, în celelalte taburi.
    val nextFavorite: MatchEntry? = remember(snapshot, settings, tab) {
        if (currentTab == MatchTab.FAVORITES || settings.favorites.teams.isEmpty()) {
            null
        } else {
            val byId = snapshot.matches.associateBy { it.match.id }
            TeamCalendar.build(settings.favorites.teams, snapshot.matches.map { it.match }, now)
                .flatMap { it.second }
                .filter { it.status != MatchStatus.FINISHED }
                .minByOrNull { it.start }
                ?.let { byId[it.id] }
        }
    }
    val looks = rememberTeamLooks()
    val otherFavorites = remember(visible, calendar) {
        val shown = calendar.flatMap { it.second }.map { it.match.id }.toSet()
        if (calendar.isEmpty()) visible else visible.filterNot { it.match.id in shown }
    }

    val setFilter: ((MatchFilter) -> MatchFilter) -> Unit = { transform -> store.update { it.copy(filter = transform(it.filter)) } }
    // Filtrul pe care intră focusul de sus; „Fără filtre” (6) există doar cu filtre puse.
    val filterSlot = lastFilter.coerceIn(0, FILTER_SLOTS - 1).let { if (it == 6 && filter.isEmpty) 0 else it }

    Column(modifier = Modifier.fillMaxSize()) {
        // „Toată săptămâna”: zilele de după mâine (20.11…) într-un rând mărunt, chiar sub tab. Rândul are mereu
        // aceeași înălțime (gol la celelalte taburi), ca lista și panoul să nu se mute când schimbi tabul.
        var weekTabCenter by remember { mutableStateOf(0f) }
        var dayStripWidth by remember { mutableStateOf(0) }
        var dayRowX by remember { mutableStateOf(0f) }
        TabStrip(
            tabs = MatchTab.entries.map { it.label } + EventsTabLabel + NowTabLabel + HighlightsTabLabel + StandingsTabLabel,
            selectedIndex = tab,
            onSelect = {
                // Intrarea în „Toată săptămâna” pornește mereu de la prima zi; cât rămâi pe tab, ziua se păstrează
                // (urci în taburi și cobori înapoi pe ziua de unde ai plecat, prin focusRestorer din DayStrip).
                if (it == MatchTab.WEEK.ordinal && tab != it) weekDay = 0
                tab = it
            },
            onTabX = { i, centerX -> if (i == MatchTab.WEEK.ordinal) weekTabCenter = centerX },
            selectedTabRequester = tabsReq,
            up = topMenu,
            down = when {
                tab >= MatchTab.entries.size -> null
                currentTab == MatchTab.WEEK -> dayReq
                else -> filterReqs[filterSlot]
            },
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(DayRowHeight)
                .onGloballyPositioned { dayRowX = it.positionInWindow().x },
        ) {
            if (currentTab == MatchTab.WEEK && tab < MatchTab.entries.size) {
                // Rândul zilelor e centrat sub tabul „Toată săptămâna”, chiar dacă e mai lat decât tabul.
                val offset = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (weekTabCenter - dayRowX - dayStripWidth / 2f).coerceAtLeast(0f).toDp()
                }
                DayStrip(
                    days = weekDays.map { it.format(WeekDayFormat) },
                    selected = weekDay,
                    onSelect = { weekDay = it },
                    selectedRequester = dayReq,
                    up = tabsReq,
                    down = filterReqs[filterSlot],
                    modifier = Modifier.padding(start = offset).onGloballyPositioned { dayStripWidth = it.size.width },
                )
            }
        }

        if (eventsTab) {
            EventChannelsList(snapshot = snapshot, enabled = settings.eventChannels, zone = zone, onOpenMatch = onOpenMatch, onPlay = onPlay)
            return@Column
        }
        if (nowTab) {
            NowOnChannelsList(
                snapshot = snapshot,
                hideScore = { m -> Spoilers.hide(settings.noSpoilers, m.id in revealed, m.status) },
                onOpenMatch = onOpenMatch,
                onPlay = onPlay,
            )
            return@Column
        }
        if (highlightsTab) {
            HighlightsList(zone = zone, onPlayArchive = onPlayArchive)
            return@Column
        }
        if (standingsTab) {
            StandingsTab(onOpenMatch = onOpenMatch)
            return@Column
        }

        // Rândul de filtre: ▲ urcă pe zile (în „Toată săptămâna”) sau pe tabul curent; din taburi focusul intră pe
        // ultimul filtru folosit. Marginea din jur lasă loc ramei și măririi butonului focusat (altfel ovalul e tăiat).
        val filterUp = if (currentTab == MatchTab.WEEK) dayReq else tabsReq
        fun slotModifier(slot: Int): Modifier = Modifier.focusRequester(filterReqs[slot])
            .onFocusChanged { if (it.isFocused) lastFilter = slot }
            .focusProperties { up = filterUp }
        // ▲ din listă intră pe ultimul filtru folosit (focusRestorer), nu pe cel aflat întâmplător deasupra rândului.
        Row(
            modifier = Modifier.fillMaxWidth().focusRestorer(filterReqs[filterSlot]).focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Filtrele: doar valoarea când e pusă („Fotbal”, „UCL”), numele filtrului când nu; nume lungi tăiate cu „…”.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterPill(FilterIcon.SPORT, "Sport", filter.sport?.label, { dialog = FilterDialog.SPORT }, slotModifier(0), sport = filter.sport)
                FilterPill(FilterIcon.COMPETITION, "Competiție", filter.competition, { dialog = FilterDialog.COMPETITION }, slotModifier(1))
                FilterPill(FilterIcon.TEAM, "Echipă", filter.team, { dialog = FilterDialog.TEAM }, slotModifier(2))
                FilterPill(FilterIcon.LANGUAGE, "Limbă", filter.language?.uppercase(), { dialog = FilterDialog.LANGUAGE }, slotModifier(3))
                FilterPill(FilterIcon.QUALITY, "Calitate", filter.minQuality?.let { it.label + "+" }, { dialog = FilterDialog.QUALITY }, slotModifier(4))
                FilterPill(
                    FilterIcon.SOURCES, "Cu surse", if (filter.onlyWithSources) "Doar cu surse" else null,
                    { setFilter { it.copy(onlyWithSources = !it.onlyWithSources) } }, slotModifier(5),
                )
                if (!filter.isEmpty) {
                    // „Fără filtre”: doar ✕, ca rândul să încapă și cu toate filtrele puse.
                    FilterPill(FilterIcon.CLEAR, "", null, { setFilter { MatchFilter() }; lastFilter = 0 }, slotModifier(6))
                }
            }
            // Actualizează și Setări: butoane rotunde doar cu iconițe, despărțite de filtre.
            Spacer(Modifier.width(10.dp))
            IconPill(FilterIcon.REFRESH, { sports.refreshInBackground(force = true) }, slotModifier(7))
            Spacer(Modifier.width(8.dp))
            IconPill(FilterIcon.SETTINGS, onOpenSettings, slotModifier(8).padding(end = 4.dp))
        }

        Text(
            text = videoNote ?: status ?: summaryLine(visible.size, snapshot.matches.size, snapshot.loading),
            fontSize = 13.sp,
            color = AkColors.TextDim,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        if (visible.isEmpty() && calendar.isEmpty()) {
            EmptyState(
                title = when {
                    snapshot.loading -> "Se caută meciurile…"
                    snapshot.matches.isEmpty() -> "Niciun meci găsit încă"
                    else -> "Niciun meci aici"
                },
                hint = when {
                    snapshot.loading -> "Din EPG-ul tău, ESPN și ceilalți furnizori."
                    snapshot.matches.isEmpty() -> "Meciurile vin din EPG-ul playlisturilor, de la ESPN, EuroLeague și ceilalți furnizori (Setări → Sports Hub)."
                    currentTab == MatchTab.FAVORITES && settings.favorites.teams.isEmpty() && settings.favorites.competitions.isEmpty() &&
                        settings.favorites.names.isEmpty() ->
                        "Marchează echipe sau competiții ca favorite din ecranul unui meci, din Clasamente sau din Setări (și nume urmărite: jucători, piloți)."
                    else -> "Încearcă alt tab sau scoate filtrele."
                },
            )
        } else {
            // Din „Foaie de meci”: panoul din dreapta arată meciul focusat (ora, cât mai e, primele surse), fără OK.
            val all = calendar.flatMap { it.second } + otherFavorites
            val preview = all.firstOrNull { it.match.id == focusedId } ?: all.firstOrNull()
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Back: întâi la primul meci; CH+/CH− (sau ⏪ ⏩): primul / ultimul meci.
                val jump = ro.ak.sportshub.ui.components.rememberListJump()
                val firstKey = if (nextFavorite != null) "next-favorite" else calendar.firstNotNullOfOrNull { (team, es) -> es.firstOrNull()?.let { "team:$team:" + it.match.id } }
                    ?: otherFavorites.firstOrNull()?.match?.id
                val lastKey = otherFavorites.lastOrNull()?.match?.id
                    ?: calendar.lastOrNull { it.second.isNotEmpty() }?.let { (team, es) -> "team:$team:" + es.last().match.id }
                val itemCount = (if (nextFavorite != null) 1 else 0) + calendar.sumOf { 1 + maxOf(1, it.second.size) } +
                    (if (calendar.isNotEmpty() && otherFavorites.isNotEmpty()) 1 else 0) + otherFavorites.size
                fun jumpOn(key: String): Modifier = when (key) {
                    firstKey -> if (key == lastKey) Modifier.focusRequester(jump.first).focusRequester(jump.last) else Modifier.focusRequester(jump.first)
                    lastKey -> Modifier.focusRequester(jump.last)
                    else -> Modifier
                }
                LazyColumn(state = jump.state, contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f).then(jump.modifier(itemCount, backToTop = videoChannel == null))) {
                    nextFavorite?.let { entry ->
                        item(key = "next-favorite") {
                            NextFavoriteCard(
                                entry = entry,
                                looks = looks,
                                hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                                zone = zone,
                                onClick = { returnFocus.clicked("next-favorite"); onOpenMatch(entry.match.id) },
                                onFocused = { focusedId = entry.match.id },
                                modifier = returnFocus.on("next-favorite").then(jumpOn("next-favorite")).padding(bottom = 6.dp),
                            )
                        }
                    }
                    calendar.forEach { (team, entries) ->
                        item(key = "team:$team") { SectionLabel("$team · următoarele meciuri", modifier = Modifier.padding(top = 6.dp)) }
                        if (entries.isEmpty()) {
                            item(key = "team-empty:$team") {
                                Text(
                                    text = "Niciun meci în zilele din program.",
                                    fontSize = 13.sp,
                                    color = AkColors.TextDim,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                )
                            }
                        }
                        items(entries, key = { "team:$team:" + it.match.id }) { entry ->
                            MatchRow(
                                entry = entry,
                                looks = looks,
                                favorite = true,
                                hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                                showDay = true,
                                zone = zone,
                                onClick = { returnFocus.clicked("team:$team:" + entry.match.id); onOpenMatch(entry.match.id) },
                                onFocused = { focusedId = entry.match.id },
                                onLongClick = { toggleVideo(entry) },
                                modifier = returnFocus.on("team:$team:" + entry.match.id).then(jumpOn("team:$team:" + entry.match.id)),
                            )
                        }
                    }
                    if (calendar.isNotEmpty() && otherFavorites.isNotEmpty()) {
                        item(key = "others") { SectionLabel("Alte meciuri favorite", modifier = Modifier.padding(top = 6.dp)) }
                    }
                    items(otherFavorites, key = { it.match.id }) { entry ->
                        MatchRow(
                            entry = entry,
                            looks = looks,
                            favorite = settings.favorites.isFavorite(entry.match),
                            hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                            showDay = currentTab == MatchTab.FAVORITES,
                            zone = zone,
                            onClick = { returnFocus.clicked(entry.match.id); onOpenMatch(entry.match.id) },
                            onFocused = { focusedId = entry.match.id },
                            onLongClick = { toggleVideo(entry) },
                            modifier = returnFocus.on(entry.match.id).then(jumpOn(entry.match.id)),
                        )
                    }
                }
                preview?.let { entry ->
                    Column(modifier = Modifier.width(290.dp).fillMaxHeight().padding(bottom = 8.dp)) {
                        val channel = videoChannel
                        if (channel != null && videoMatchId == entry.match.id) {
                            ro.ak.sportshub.player.VideoPreview(channelId = channel, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        MatchPreviewPanel(
                            entry = entry,
                            looks = looks,
                            favorite = settings.favorites.isFavorite(entry.match),
                            hideScore = Spoilers.hide(settings.noSpoilers, entry.match.id in revealed, entry.match.status),
                            prefs = settings.sourcePreferences,
                            zone = zone,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
        }
    }

    dialog?.let { d -> SportsFilterDialog(d, onDismiss = { dialog = null }) }
}

/**
 * Fereastra unui filtru din Sports Hub. Competițiile și sugestiile de echipe sunt doar ale sportului ales,
 * iar schimbarea sportului scoate competiția și echipa care nu-i aparțin.
 */
@Composable
internal fun SportsFilterDialog(dialog: FilterDialog, onDismiss: () -> Unit) {
    val container = appContainer()
    val store = container.sportsSettings
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val filter = settings.filter
    val sport = filter.sport
    val setFilter: ((MatchFilter) -> MatchFilter) -> Unit = { transform -> store.update { it.copy(filter = transform(it.filter)) } }
    val matches = remember(snapshot) { snapshot.matches.map { it.match } }
    when (dialog) {
        FilterDialog.SPORT -> {
            val options = listOf<Sport?>(null) + Sport.entries
            ChoiceDialog(
                title = "Sport",
                options = options.map { it?.label ?: "Toate" },
                selectedIndex = options.indexOf(filter.sport),
                onDismiss = onDismiss,
                onSelect = { i -> setFilter { MatchLists.withSport(it, options[i], matches) }; onDismiss() },
            )
        }
        FilterDialog.COMPETITION -> {
            val options = listOf<String?>(null) + MatchLists.competitions(matches, sport)
            ChoiceDialog(
                title = if (sport != null) "Competiție (${sport.label})" else "Competiție",
                options = options.map { it ?: "Toate" },
                selectedIndex = options.indexOf(filter.competition).coerceAtLeast(0),
                onDismiss = onDismiss,
                onSelect = { i -> setFilter { it.copy(competition = options[i]) }; onDismiss() },
            )
        }
        FilterDialog.TEAM -> {
            val teams = remember(matches, sport) { MatchLists.teams(matches, sport) }
            TextInputDialog(
                title = if (sport != null) "Echipă (${sport.label})" else "Echipă",
                initial = filter.team.orEmpty(),
                hint = if (sport != null) "O parte din numele echipei; sugestiile sunt doar de la ${sport.label.lowercase()}. Gol = oricare."
                else "O parte din numele echipei. Gol = oricare.",
                suggestions = { typed ->
                    val t = Sport.fold(typed)
                    teams.filter { Sport.fold(it).contains(t) }.take(8).map { it to null }
                },
                onDismiss = onDismiss,
                onConfirm = { text -> setFilter { it.copy(team = text.ifBlank { null }) }; onDismiss() },
            )
        }
        FilterDialog.LANGUAGE -> {
            val known = snapshot.matches.flatMap { it.summary.languages }.toSet()
            val ordered = (settings.sourcePreferences.preferredLanguages.map { it.lowercase() } + known.sorted()).distinct()
            val options = listOf<String?>(null) + ordered
            ChoiceDialog(
                title = "Limba audio a surselor",
                hint = "Din informațiile playlistului (prefixul de țară sau atributele canalului).",
                options = options.map { it?.uppercase() ?: "Oricare" },
                selectedIndex = options.indexOf(filter.language).coerceAtLeast(0),
                onDismiss = onDismiss,
                onSelect = { i -> setFilter { it.copy(language = options[i]) }; onDismiss() },
            )
        }
        FilterDialog.QUALITY -> {
            val options = listOf(null, Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            ChoiceDialog(
                title = "Calitatea minimă a unei surse",
                options = options.map { it?.let { q -> q.label + " sau mai bună" } ?: "Oricare" },
                selectedIndex = options.indexOf(filter.minQuality).coerceAtLeast(0),
                onDismiss = onDismiss,
                onSelect = { i -> setFilter { it.copy(minQuality = options[i]) }; onDismiss() },
            )
        }
    }
}

private const val FILTER_SLOTS = 9

private fun summaryLine(visible: Int, total: Int, loading: Boolean): String = when {
    loading -> "Se încarcă…"
    else -> "$visible meciuri afișate din $total"
}

@Composable
private fun MatchRow(
    entry: MatchEntry,
    looks: TeamLookBook,
    favorite: Boolean,
    hideScore: Boolean,
    showDay: Boolean,
    zone: ZoneId,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    /** OK lung: fereastra video cu sursa recomandată (F5). */
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val m = entry.match
    val start = Instant.ofEpochMilli(m.start).atZone(zone)
    val score = if (!hideScore && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED) {
        "  ${m.homeScore}–${m.awayScore}  "
    } else {
        null
    }
    val title = if (score != null && m.away.isNotBlank()) m.home + score + m.away else m.title
    val count = entry.summary.count
    ListRow(
        title = title,
        titleSize = 17.sp,
        subtitle = listOfNotNull(
            if (showDay) start.format(DayFormat) else null,
            m.sport?.label,
            m.competition,
            if (m.status == MatchStatus.FINISHED) "încheiat" else null,
        ).joinToString(" · "),
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        icon = { MatchIcon(m.home, m.away, m.competition, looks) },
        // Echipele pe două rânduri, scorul în dreptul fiecăreia; numele prea lungi se taie cu „…”.
        titleContent = if (m.away.isBlank()) null else {
            {
                Column {
                    listOf(m.home to m.homeScore, m.away to m.awayScore).forEach { (team, goals) ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = team,
                                fontSize = 17.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // Coloana scorului are mereu aceeași lățime, ca scorurile să stea unul sub altul.
                            Text(
                                text = if (score != null) goals?.toString().orEmpty() else "",
                                fontSize = 17.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = 8.dp).width(34.dp),
                            )
                        }
                    }
                }
            }
        },
        leadingWidth = 84.dp,
        leading = {
            // Ora (sau LIVE), cu cifre înguste, ca pe o tabelă.
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                // Iconița sportului, în dreptul fiecărui meci.
                Text(text = sportIcon(m.sport), fontSize = 16.sp, modifier = Modifier.padding(end = 4.dp).width(22.dp), textAlign = TextAlign.Center)
                if (m.isLive) {
                    Text(
                        text = "LIVE",
                        fontSize = 19.sp,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = FontWeight.Bold,
                        color = if (LocalOnFocus.current) AkColors.LiveOnFocus else AkColors.Live,
                    )
                } else {
                    Text(text = start.format(TimeFormat), fontSize = 19.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        trailing = {
            // Coloane de lățime fixă (steaua, reluarea, calitatea, sursele), ca etichetele să stea una sub alta.
            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (favorite) Text(text = "★", fontSize = 20.sp, color = accentText())
            }
            // Meci terminat cu arhivă pe o sursă: se poate vedea ca reluare (niciodată marcat drept live).
            if (m.status == MatchStatus.FINISHED && entry.hasReplay(System.currentTimeMillis())) Badge(text = "RELUARE", color = AkColors.Focus)
            Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                if (count > 0) QualityBadge(entry.summary.best)
            }
            // Pe scurt, ca titlul să încapă; presupusele se văd în panoul din dreapta.
            Text(
                text = when {
                    count == 0 && !SourceWindow.searches(m, System.currentTimeMillis(), zone) -> "din ajun"
                    count == 0 -> "fără surse"
                    entry.summary.sure == 0 -> "$count presup."
                    count == 1 -> "1 sursă"
                    else -> "$count surse"
                },
                fontSize = 14.sp,
                maxLines = 1,
                color = if (count == 0 || entry.summary.sure == 0) dimText() else LocalContentColor.current,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).width(74.dp),
            )
        },
    )
}

/**
 * Panoul din dreapta listei de meciuri: meciul focusat, ora și cât mai e până la start, primele trei surse
 * (în ordinea preferințelor tale de surse, aceeași ca în ecranul meciului). Doar din lista calculată, fără cereri.
 */
@Composable
private fun MatchPreviewPanel(
    entry: MatchEntry,
    looks: TeamLookBook,
    favorite: Boolean,
    hideScore: Boolean,
    prefs: ro.ak.sportshub.core.model.SourcePreferences,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val m = entry.match
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val best = remember(entry, favorite, prefs) {
        MatchSources.order(entry.sources, isFavorite = favorite, prefs = prefs)
            .map { it.members.first() }
            .filterNot { it.rejected }
            .take(3)
    }
    val start = Instant.ofEpochMilli(m.start).atZone(zone)
    Column(
        modifier = modifier
            .background(AkColors.Panel, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompetitionLogo(m.competition, 24.dp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = listOfNotNull(m.sport?.label, m.competition, m.venue).joinToString(" · "),
                fontSize = 15.sp,
                color = AkColors.TextDim,
                maxLines = 2,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Echipele cu sigla, banda culorii și forma (ultimele 5, din programul ESPN; doar înainte de start).
            val showForm = m.status == MatchStatus.SCHEDULED && !hideScore
            val teamScores = !hideScore && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED && m.away.isNotBlank()
            listOf(m.home to m.homeScore, m.away to m.awayScore).filter { it.first.isNotBlank() }.forEach { (team, goals) ->
                val look = looks.team(team)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MatchBand(look, look, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    TeamLogo(team, 30.dp, look = look, modifier = Modifier.padding(end = 8.dp))
                    // Numele care nu încape se prescurtează („Manchester United” → „Man Utd”), nu se taie.
                    FittingName(
                        name = team,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (teamScores) {
                        Text(
                            text = goals?.toString().orEmpty(),
                            fontSize = 22.sp,
                            lineHeight = 24.sp,
                            fontFamily = AkFonts.Condensed,
                            fontWeight = FontWeight.Bold,
                            color = if (m.isLive) AkColors.Live else AkColors.Text,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp).width(36.dp),
                        )
                    }
                }
                val form = if (showForm && m.away.isNotBlank()) look?.formResults().orEmpty() else emptyList()
                if (form.isNotEmpty()) FormSquares(form, modifier = Modifier.padding(start = 12.dp), square = 16.dp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val score = !hideScore && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED
            // Cu două echipe, scorul e deja în dreptul fiecăreia; aici rămâne ora.
            val bigScore = score && m.away.isBlank()
            Text(
                text = if (bigScore) "${m.homeScore} – ${m.awayScore}" else start.format(TimeFormat),
                fontSize = 30.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = FontWeight.Bold,
                color = if (m.isLive && bigScore) AkColors.Live else AkColors.Text,
            )
            Text(
                text = (if (bigScore) start.format(TimeFormat) + " · " else "") + Countdown.text(m.start, now, m.status),
                fontSize = 15.sp,
                color = AkColors.TextDim,
                maxLines = 2,
                modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AkColors.Divider))
        Text(text = "CELE MAI BUNE SURSE", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = AkColors.TextDim)
        if (best.isEmpty()) {
            Text(
                text = if (SourceWindow.searches(entry.match, System.currentTimeMillis(), java.time.ZoneId.systemDefault())) {
                    "Nicio sursă în playlisturile tale."
                } else {
                    "Sursele se caută din ziua dinainte de meci."
                },
                fontSize = 17.sp,
                color = AkColors.TextDim,
            )
        }
        best.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = s.channel.name, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(text = s.confidence.short(), fontSize = 14.sp, color = AkColors.TextDim, modifier = Modifier.padding(start = 8.dp))
                VariantQualityBadge(s.channel.playlistId, s.channel.name, s.channel.quality)
            }
        }
        val more = entry.summary.count - best.size
        val guesses = entry.summary.count - entry.summary.sure
        if (more > 0) {
            Text(
                text = "+ încă $more " + (if (more == 1) "sursă" else "surse") + (if (guesses > 0) " · $guesses presupuse în total" else ""),
                fontSize = 14.sp,
                color = AkColors.TextDim,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(text = "OK deschide meciul: surse, pre-meci, live · OK lung: video aici (Back oprește)", fontSize = 14.sp, color = AkColors.TextDim)
    }
}

private const val EventsTabLabel = "Evenimente"

/**
 * Tabul „Evenimente”: canalele EVENT / PPV din playlisturi care au acum un eveniment în nume, după oră.
 * OK deschide meciul (cu toate sursele lui) dacă s-a legat de unul, altfel pornește canalul.
 */
@Composable
private fun EventChannelsList(
    snapshot: ro.ak.sportshub.data.SportsSnapshot,
    enabled: Boolean,
    zone: ZoneId,
    onOpenMatch: (String) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val events = snapshot.events
    Text(
        text = when {
            !enabled -> "Canalele EVENT / PPV sunt oprite (Setări → Sports Hub)."
            else -> "${events.size} canale cu eveniment" +
                (if (snapshot.emptyEventChannels > 0) " · ${snapshot.emptyEventChannels} goale acum" else "") +
                " · OK: meciul (dacă e legat) sau canalul"
        },
        fontSize = 13.sp,
        color = AkColors.TextDim,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    if (enabled && events.isEmpty()) {
        EmptyState(
            title = if (snapshot.loading) "Se caută canalele…" else "Niciun canal EVENT / PPV cu eveniment",
            hint = "Canalele furnizorului care au meciul în nume (de exemplu „EVENT 03: UFC 310 20:00”) apar aici și ca surse sigure la meciuri.",
        )
        return
    }
    val returnFocus = rememberReturnFocus()
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(events, key = { "ev:" + it.channel.id }) { e ->
            val rowKey = "ev:" + e.channel.id
            val time = e.slot.start?.let { val z = Instant.ofEpochMilli(it).atZone(zone); "${z.format(DayFormat)} ${z.format(TimeFormat)}" } ?: "oră necunoscută"
            ListRow(
                title = "$time   ${e.slot.title ?: e.channel.name}",
                subtitle = listOfNotNull(
                    e.channel.name,
                    e.channel.playlistName,
                    e.slot.sport?.label,
                    if (e.matchId != null) "legat de un meci" else null,
                ).joinToString(" · "),
                modifier = returnFocus.on(rowKey),
                onClick = { returnFocus.clicked(rowKey); if (e.matchId != null) onOpenMatch(e.matchId) else onPlay(e.channel.id) },
                onLongClick = { returnFocus.clicked(rowKey); onPlay(e.channel.id) },
                trailing = { VariantQualityBadge(e.channel.playlistId, e.channel.name, e.channel.quality) },
            )
        }
    }
}

private const val NowTabLabel = "Acum pe canale"
private const val HighlightsTabLabel = "Rezumate"
private const val StandingsTabLabel = "Clasamente"

/**
 * Tabul „Acum pe canalele tale”: fiecare canal de sport cu meciul care rulează acum pe el (din lista de meciuri,
 * fără cereri în plus). OK pornește canalul (varianta preferată sau cea mai bună), OK lung deschide meciul.
 */
@Composable
private fun NowOnChannelsList(
    snapshot: ro.ak.sportshub.data.SportsSnapshot,
    hideScore: (ro.ak.sportshub.core.sports.Match) -> Boolean,
    onOpenMatch: (String) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val container = appContainer()
    val seen by container.observedVideo.state.collectAsStateWithLifecycle()
    val now = snapshot.computedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    val rows = remember(snapshot) { NowOnChannels.build(snapshot.matches.map { it.match to it.sources }, now) }
    Text(
        text = if (rows.isEmpty()) "Niciun canal cu meci acum." else "${rows.size} canale cu meci acum · OK pornește canalul · OK lung: meciul",
        fontSize = 13.sp,
        color = AkColors.TextDim,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    if (rows.isEmpty()) {
        EmptyState(
            title = if (snapshot.loading) "Se caută meciurile…" else "Nimic acum pe canalele tale",
            hint = "Aici apar canalele de sport pe care rulează un meci (din EPG, posturi anunțate, canale EVENT sau drepturi TV).",
        )
        return
    }
    val returnFocus = rememberReturnFocus()
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows, key = { "now:" + it.groupKey }) { row ->
            val rowKey = "now:" + row.groupKey
            val m = row.match
            // Varianta care pornește: cea preferată sau cea mai bună (cu rezoluția văzută la redare).
            val best = remember(row, seen) { container.variants.order(row.sources.map { it.channel }).first() }
            val score = if (!hideScore(m) && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED) {
                "  ${m.homeScore}–${m.awayScore}"
            } else {
                ""
            }
            ListRow(
                title = best.name,
                subtitle = listOfNotNull(m.title + score, m.competition, row.confidence.label, best.playlistName).joinToString(" · "),
                modifier = returnFocus.on(rowKey),
                onClick = { returnFocus.clicked(rowKey); onPlay(best.id) },
                onLongClick = { returnFocus.clicked(rowKey); onOpenMatch(m.id) },
                trailing = {
                    if (m.isLive) Badge(text = "LIVE", color = AkColors.Live)
                    if (row.sources.size > 1) Text(text = "${row.sources.size} var.", fontSize = 13.sp, color = AkColors.TextDim)
                    VariantQualityBadge(best.playlistId, best.name, best.quality)
                },
            )
        }
    }
}

/**
 * Tabul „Rezumate”: rezumatele și magazinele sportive din arhiva canalelor (catch-up), găsite în EPG.
 * OK pornește emisiunea din arhivă (marcată „RELUARE” în player).
 */
@Composable
private fun HighlightsList(zone: ZoneId, onPlayArchive: (Long, Long, Long) -> Unit) {
    val container = appContainer()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val list by androidx.compose.runtime.produceState<List<ro.ak.sportshub.data.HighlightEntry>?>(null, snapshot.computedAt / 600_000) {
        value = runCatching { container.sports.highlights() }.getOrDefault(emptyList())
    }
    val items = list
    Text(
        text = when {
            items == null -> "Se caută în EPG…"
            items.isEmpty() -> "Niciun rezumat în arhivă."
            else -> "${items.size} rezumate și magazine în arhivă · OK pornește din arhivă"
        },
        fontSize = 13.sp,
        color = AkColors.TextDim,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    if (items == null) return
    if (items.isEmpty()) {
        EmptyState(
            title = "Niciun rezumat găsit",
            hint = "Apar emisiunile terminate cu „Highlights”, „Rezumat”, „Match of the Day”… de pe canalele de sport cu arhivă (catch-up, Xtream).",
        )
        return
    }
    val returnFocus = rememberReturnFocus()
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { "hl:" + it.channel.id + ":" + it.start }) { h ->
            val rowKey = "hl:" + h.channel.id + ":" + h.start
            val start = Instant.ofEpochMilli(h.start).atZone(zone)
            ListRow(
                title = "${start.format(DayFormat)} ${start.format(TimeFormat)}   ${h.title}",
                subtitle = listOfNotNull(h.subtitle, h.channel.name, h.channel.playlistName).joinToString(" · "),
                modifier = returnFocus.on(rowKey),
                onClick = { returnFocus.clicked(rowKey); onPlayArchive(h.channel.id, h.start, h.stop) },
                trailing = {
                    if (h.favorite) Text(text = "★", fontSize = 18.sp, color = accentText())
                    Badge(text = "ARHIVĂ", color = AkColors.Focus)
                },
            )
        }
    }
}

/** Încrederea sursei, pe scurt, pentru panoul de previzualizare. */
private fun ro.ak.sportshub.core.sports.Confidence.short(): String = when (this) {
    ro.ak.sportshub.core.sports.Confidence.CONFIRMED -> "confirmat"
    ro.ak.sportshub.core.sports.Confidence.EPG -> "EPG"
    ro.ak.sportshub.core.sports.Confidence.EVENT -> "eveniment"
    ro.ak.sportshub.core.sports.Confidence.STATION -> "anunțat"
    ro.ak.sportshub.core.sports.Confidence.LEARNED -> "învățat"
    ro.ak.sportshub.core.sports.Confidence.DEDICATED -> "dedicat"
    ro.ak.sportshub.core.sports.Confidence.RIGHTS -> "drepturi TV"
    ro.ak.sportshub.core.sports.Confidence.ELSEWHERE -> "nesigur"
}


/**
 * Sigle și culori în rândul meciului: banda (gazde sus, oaspeți jos) și cele două sigle una sub alta; la evenimentele
 * fără echipe, sigla competiției. Locul are mereu aceeași lățime, ca rândurile să rămână aliniate.
 */
@Composable
private fun MatchIcon(home: String, away: String, competition: String?, looks: TeamLookBook) {
    val hl = looks.team(home)
    val al = if (away.isNotBlank()) looks.team(away) else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        MatchBand(hl, al ?: hl.takeIf { away.isBlank() }, 40.dp)
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.width(22.dp).height(42.dp), contentAlignment = Alignment.Center) {
            if (away.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(20.dp)) { TeamLogo(home, 20.dp, look = hl) }
                    Box(Modifier.size(20.dp)) { TeamLogo(away, 20.dp, look = al) }
                }
            } else {
                CompetitionLogo(competition, 22.dp)
            }
        }
    }
}

/**
 * Cardul „următorul meci al echipelor tale”, sus în Sports Hub: siglele mari, ora sau LIVE, cât mai e și sursele.
 * Doar din lista calculată, fără cereri.
 */
@Composable
private fun NextFavoriteCard(
    entry: MatchEntry,
    looks: TeamLookBook,
    hideScore: Boolean,
    zone: ZoneId,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = entry.match
    var focused by remember { mutableStateOf(false) }
    val start = Instant.ofEpochMilli(m.start).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    val day = when (start.toLocalDate()) {
        today -> "azi"
        today.plusDays(1) -> "mâine"
        else -> start.format(DayFormat)
    }
    val hl = looks.team(m.home)
    val al = if (m.away.isNotBlank()) looks.team(m.away) else null
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .then(Modifier.touchClick(onClick = onClick)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AkColors.Panel,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        CompositionLocalProvider(LocalOnFocus provides focused) {
            Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MatchBand(hl, al ?: hl, 56.dp)
                Spacer(Modifier.width(14.dp))
                TeamLogo(m.home, 52.dp, look = hl, codeFallback = true)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(text = "URMĂTORUL MECI AL ECHIPELOR TALE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = dimText())
                    val score = !hideScore && m.homeScore != null && m.awayScore != null && m.status != MatchStatus.SCHEDULED
                    Text(
                        text = if (m.away.isBlank()) m.title else m.home + (if (score) "  ${m.homeScore} – ${m.awayScore}  " else "  –  ") + m.away,
                        fontSize = 25.sp,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = listOfNotNull(m.competition, Countdown.text(m.start, System.currentTimeMillis(), m.status)).joinToString(" · "),
                        fontSize = 14.sp,
                        color = dimText(),
                        maxLines = 1,
                    )
                }
                if (m.away.isNotBlank()) TeamLogo(m.away, 52.dp, look = al, codeFallback = true)
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 16.dp).width(96.dp)) {
                    if (m.isLive) {
                        Text(
                            text = "LIVE",
                            fontSize = 28.sp,
                            fontFamily = AkFonts.Condensed,
                            fontWeight = FontWeight.Bold,
                            color = if (focused) AkColors.LiveOnFocus else AkColors.Live,
                        )
                    } else {
                        Text(text = start.format(TimeFormat), fontSize = 28.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold)
                        Text(text = day, fontSize = 14.sp, color = dimText())
                    }
                    val count = entry.summary.count
                    Text(
                        text = when (count) {
                            0 -> "fără surse"
                            1 -> "1 sursă"
                            else -> "$count surse"
                        },
                        fontSize = 14.sp,
                        color = dimText(),
                    )
                }
            }
        }
    }
}

/** Iconița unui sport (emoji, desenate de sistem). */
internal fun sportIcon(sport: ro.ak.sportshub.core.sports.Sport?): String = when (sport) {
    ro.ak.sportshub.core.sports.Sport.FOOTBALL -> "⚽"
    ro.ak.sportshub.core.sports.Sport.BASKETBALL -> "🏀"
    ro.ak.sportshub.core.sports.Sport.TENNIS -> "🎾"
    ro.ak.sportshub.core.sports.Sport.ICE_HOCKEY -> "🏒"
    ro.ak.sportshub.core.sports.Sport.HANDBALL -> "🤾"
    ro.ak.sportshub.core.sports.Sport.VOLLEYBALL -> "🏐"
    ro.ak.sportshub.core.sports.Sport.RUGBY -> "🏉"
    ro.ak.sportshub.core.sports.Sport.AMERICAN_FOOTBALL -> "🏈"
    ro.ak.sportshub.core.sports.Sport.BASEBALL -> "⚾"
    ro.ak.sportshub.core.sports.Sport.WINTER -> "⛷"
    ro.ak.sportshub.core.sports.Sport.MOTORSPORT -> "🏎"
    ro.ak.sportshub.core.sports.Sport.FIGHTING -> "🥊"
    ro.ak.sportshub.core.sports.Sport.DARTS -> "🎯"
    ro.ak.sportshub.core.sports.Sport.SNOOKER -> "🎱"
    ro.ak.sportshub.core.sports.Sport.CYCLING -> "🚴"
    ro.ak.sportshub.core.sports.Sport.GOLF -> "⛳"
    ro.ak.sportshub.core.sports.Sport.CRICKET -> "🏏"
    null -> "🏟"
}

/** Numele echipei în cea mai lungă formă care încape pe un rând (`TeamCodes.shorter`). */
@Composable
private fun FittingName(name: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val forms = remember(name) { ro.ak.sportshub.core.sports.TeamCodes.shorter(name) }
    var index by remember(name) { androidx.compose.runtime.mutableIntStateOf(0) }
    Text(
        text = forms[index.coerceIn(0, forms.size - 1)],
        fontSize = fontSize,
        lineHeight = fontSize * 1.1f,
        fontFamily = AkFonts.Condensed,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { r -> if (r.hasVisualOverflow && index < forms.size - 1) index++ },
        modifier = modifier,
    )
}

/** Zilele din „Toată săptămâna”, mărunte, sub tab: focusul pe o zi o alege (linia chihlimbar sub ziua aleasă). */
@Composable
private fun DayStrip(
    days: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    selectedRequester: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val requesters = remember(days.size) { List(days.size) { androidx.compose.ui.focus.FocusRequester() } }
    Row(
        modifier = modifier
            .focusRestorer(requesters.getOrElse(selected) { requesters.firstOrNull() ?: androidx.compose.ui.focus.FocusRequester.Default })
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        days.forEachIndexed { i, day ->
            androidx.tv.material3.Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.focusRequester(requesters[i])
                    .then(if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier)
                    // ▲ urcă pe „Toată săptămâna” de pe oricare zi (nu în meniul de sus), ▼ coboară pe filtre.
                    .focusProperties { this.up = up; this.down = down }
                    .onFocusChanged { if (it.isFocused) onSelect(i) }
                    .then(Modifier.touchClick { onSelect(i) }),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (i == selected) AkColors.Text else AkColors.TextDim,
                    focusedContainerColor = AkColors.Focus,
                    focusedContentColor = AkColors.OnFocus,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = day, fontSize = 13.sp, lineHeight = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier.padding(top = 1.dp).height(2.dp).width(22.dp)
                            .background(if (i == selected) AkColors.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
    }
}
