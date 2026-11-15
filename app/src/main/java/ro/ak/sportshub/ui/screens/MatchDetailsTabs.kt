package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ro.ak.sportshub.core.sports.Formation
import ro.ak.sportshub.core.sports.LineupPlayer
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchDetails
import ro.ak.sportshub.core.sports.MatchInsights
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.Positions
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.StatGroups
import ro.ak.sportshub.core.sports.StatLine
import ro.ak.sportshub.core.sports.TimelineEntry
import ro.ak.sportshub.core.sports.TimelineKind
import ro.ak.sportshub.data.MatchDetailsState
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.FocusBorder
import ro.ak.sportshub.ui.components.FormSquares
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.LocalOnFocus
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TeamLogo
import ro.ak.sportshub.ui.components.accentText
import ro.ak.sportshub.ui.components.dimText
import ro.ak.sportshub.ui.components.groupedStats
import ro.ak.sportshub.ui.components.rememberTeamLooks
import ro.ak.sportshub.ui.components.statGroups
import ro.ak.sportshub.ui.components.statTeams
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

private val DateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Lățimea cea mai mare a unei coloane: pe ecranul TV rândurile nu se mai întind de la o margine la alta. */
private val ColumnMax = 520.dp

/**
 * Două coloane una lângă alta, fiecare cu derularea ei (săgețile stânga/dreapta trec dintr-una în alta).
 * Conținutul stă grupat la mijloc, nu întins pe tot ecranul.
 */
@Composable
fun TwoColumns(
    modifier: Modifier = Modifier,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
) {
    Row(
        modifier = modifier.padding(top = 10.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
    ) {
        LazyColumn(modifier = Modifier.weight(1f).widthIn(max = ColumnMax), verticalArrangement = Arrangement.spacedBy(2.dp), content = left)
        LazyColumn(modifier = Modifier.weight(1f).widthIn(max = ColumnMax), verticalArrangement = Arrangement.spacedBy(2.dp), content = right)
    }
}

/** Tabul Pre: detaliile meciului, forma, meciurile directe (stânga); clasamentul și statisticile sezonului (dreapta). */
@Composable
fun PreMatchTab(
    match: Match,
    state: MatchDetailsState,
    hideSpoilers: Boolean,
    sportsDbEnabled: Boolean,
    onRefresh: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = state.details
    val zone = ZoneId.systemDefault()
    TwoColumns(
        modifier = modifier,
        left = {
            item(key = "refresh") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    ActionButton(text = if (state.loading) "Se încarcă…" else "↻ Actualizează", onClick = onRefresh, enabled = !state.loading)
                }
            }
            statusItem(state)
            factsSection(match, d)
            formSection(match, d, hideSpoilers, zone)
            headToHeadSection(match, d, zone)
            if (!state.loading && d.isEmpty && sportsDbEnabled) {
                item(key = "nothing") {
                    ListRow(
                        title = "Nicio informație pentru acest meci",
                        subtitle = "Nici ESPN, nici TheSportsDB nu au date despre el. Sursele rămân în primul tab.",
                        onClick = {},
                    )
                }
            }
        },
        right = {
            tableSection(match, d, hideSpoilers, onReveal)
            if (match.away.isNotBlank()) groupedStats(d.seasonStats, "ss", match.home, match.away, title = "Sezonul", compact = true)
            item(key = "foot") {
                Text(
                    text = "ESPN (sau TheSportsDB), cerut doar cât e deschis meciul; nu folosește conexiunea playlisturilor.",
                    fontSize = 12.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(14.dp),
                )
            }
        },
    )
}

/** Tabul Formații: terenul cu așezarea în stânga, titularii și rezervele celor două echipe în dreapta. */
@Composable
fun LineupsTab(match: Match, state: MatchDetailsState, modifier: Modifier = Modifier) {
    val d = state.details
    val e = d.event
    if (d.lineup.isEmpty()) {
        LazyColumn(modifier = modifier.padding(top = 10.dp)) {
            item(key = "lineup-later") {
                ListRow(
                    title = if (match.status == MatchStatus.SCHEDULED) "Echipele de start nu sunt încă anunțate" else "Fără echipe de start",
                    subtitle = "Apar de obicei cu o oră înainte de meci; în ultimele două ore datele se reiau la 20 de minute.",
                    onClick = {},
                )
            }
        }
        return
    }
    val homeF = Formation.of(d.lineup.filter { it.home })
    val awayF = Formation.of(d.lineup.filter { !it.home })
    Row(modifier = modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (homeF != null && awayF != null) {
            Column(modifier = Modifier.weight(2f)) {
                Text(
                    text = "${match.home} ${homeF.label}  ·  ${match.away} ${awayF.label}",
                    fontSize = 15.sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                PitchView(homeF, awayF, height = 350.dp)
                (e?.venue ?: match.venue)?.let { v ->
                    Text(text = v + (e?.city?.let { ", $it" } ?: ""), fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(4.dp))
                }
            }
        }
        // Listele înguste și mărunte (număr, nume, postul prescurtat), ca terenul să aibă loc și rezervele să se vadă.
        LazyColumn(modifier = Modifier.weight(0.8f)) {
            lineupList(match.home, d.lineup.filter { it.home }, "h")
        }
        LazyColumn(modifier = Modifier.weight(0.8f)) {
            lineupList(match.away, d.lineup.filter { !it.home }, "a")
        }
    }
}

/** Tabul Live: scorul și evenimentele pe o axă (gazdele în stânga, minutul la mijloc, oaspeții în dreapta). */
@Composable
fun LiveTab(
    match: Match,
    state: MatchDetailsState,
    hideSpoilers: Boolean,
    sportsDbEnabled: Boolean,
    onRefresh: () -> Unit,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    noSpoilersOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val d = state.details
    val e = d.event
    val status = listOfNotNull(e?.status, match.status).maxBy { it.ordinal }
    val homeScore = e?.homeScore ?: match.homeScore
    val awayScore = e?.awayScore ?: match.awayScore
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.padding(top = 10.dp).widthIn(max = 880.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (notStartedItem(match, status, onRefresh)) return@LazyColumn
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    ActionButton(text = if (state.loading) "Se încarcă…" else "↻ Actualizează", onClick = onRefresh, enabled = !state.loading)
                    if (noSpoilersOn && !hideSpoilers) ActionButton(text = "Ascunde din nou", onClick = onHide)
                }
            }
            statusItem(state)
            val label = MatchInsights.liveLabel(status, e?.statusCode, e?.progress)
            if (hideSpoilers) {
                hiddenItem(label, onReveal)
                return@LazyColumn
            }
            item(key = "score") {
                ListRow(
                    title = if (homeScore != null && awayScore != null) "${match.home}  $homeScore – $awayScore  ${match.away}" else match.title,
                    subtitle = label.ifEmpty { if (status == MatchStatus.LIVE) "În desfășurare" else "Încheiat" },
                    titleSize = 20.sp,
                    onClick = onRefresh,
                    trailing = { if (status == MatchStatus.LIVE) Badge(text = "LIVE", color = AkColors.Live) },
                )
            }
            if (d.timeline.isNotEmpty()) {
                item { SectionLabel("Evenimente") }
                timelineAxis(match, d.timeline, newestFirst = status == MatchStatus.LIVE)
            } else if (!state.loading) {
                item(key = "no-live") {
                    ListRow(
                        title = "Niciun eveniment încă",
                        subtitle = if (sportsDbEnabled) "ESPN și TheSportsDB nu au (încă) evenimente pentru acest meci." else "Evenimentele vin de la ESPN.",
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** Tabul Statistici: în timpul și după meci statisticile meciului, înainte cele ale sezonului; grupurile pe două coloane. */
@Composable
fun StatsTab(
    match: Match,
    state: MatchDetailsState,
    hideSpoilers: Boolean,
    onRefresh: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = state.details
    val status = listOfNotNull(d.event?.status, match.status).maxBy { it.ordinal }
    val live = d.stats.isNotEmpty()
    val stats = if (live) d.stats else d.seasonStats
    if (stats.isEmpty() || (live && hideSpoilers)) {
        LazyColumn(modifier = modifier.padding(top = 10.dp)) {
            if (live && hideSpoilers) {
                hiddenItem(MatchInsights.liveLabel(status, d.event?.statusCode, d.event?.progress), onReveal)
            } else {
                item(key = "none") {
                    ListRow(
                        title = if (state.loading) "Se încarcă…" else "Fără statistici încă",
                        subtitle = if (status == MatchStatus.SCHEDULED) "Înainte de meci apar statisticile sezonului, dacă ESPN le are; în timpul meciului, cele live." else "ESPN nu le are (încă) pentru acest meci. OK: actualizează.",
                        onClick = onRefresh,
                    )
                }
            }
        }
        return
    }
    // Grupurile se împart pe două coloane cât mai egale (după numărul de rânduri).
    val groups = StatGroups.group(stats)
    val left = ArrayList<StatGroups.Group>()
    val right = ArrayList<StatGroups.Group>()
    var l = 0
    var r = 0
    groups.forEach { g -> if (l <= r) { left += g; l += g.rows.size + 1 } else { right += g; r += g.rows.size + 1 } }
    TwoColumns(
        modifier = modifier,
        left = {
            item(key = "title") { SectionLabel(if (live) "Statisticile meciului" else "Statisticile sezonului") }
            statTeams(match.home, match.away, "sl")
            statGroups(left, "sl", compact = true)
        },
        right = {
            item(key = "title") { SectionLabel(" ") }
            if (right.isNotEmpty()) statTeams(match.home, match.away, "sr")
            statGroups(right, "sr", compact = true)
        },
    )
}

// ---------- bucăți folosite și de panoul meciului din player ----------

internal fun LazyListScope.factsSection(match: Match, d: MatchDetails) {
    val e = d.event
    val facts = listOfNotNull(
        e?.league?.let { it + (e?.round?.let { r -> ", etapa $r" } ?: "") },
        (e?.venue ?: match.venue)?.let { it + (e?.city?.let { c -> ", $c" } ?: "") },
        e?.referee?.let { "Arbitru: $it" },
        e?.spectators?.let { "Spectatori: " + "%,d".format(it).replace(',', '.') },
    )
    if (facts.isEmpty()) return
    item(key = "facts") {
        Text(
            text = facts.joinToString("\n"),
            fontSize = 14.sp,
            color = AkColors.TextDim,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

/** Forma: din ultimele meciuri cerute, altfel cea din programul ESPN (ascunsă la „fără spoilere”). */
internal fun LazyListScope.formSection(match: Match, d: MatchDetails, hideSpoilers: Boolean, zone: ZoneId) {
    if (match.away.isBlank()) return
    item(key = "form-h") { SectionLabel("Forma") }
    item(key = "form-home") { FormRow(match.home, d.homeRecent, zone, lookForm = !hideSpoilers) }
    item(key = "form-away") { FormRow(match.away, d.awayRecent, zone, lookForm = !hideSpoilers) }
}

/** Meciurile directe: bilanțul, apoi rezultatele, câte unul pe rând scurt. */
internal fun LazyListScope.headToHeadSection(match: Match, d: MatchDetails, zone: ZoneId, max: Int = 10) {
    if (d.headToHead.isEmpty()) return
    val (w, dr, l) = MatchInsights.headToHeadSummary(d.headToHead, match.home)
    item(key = "h2h-h") { SectionLabel("Meciuri directe (H2H) · ${match.home}: ${w}V ${dr}E ${l}Î") }
    items(d.headToHead.take(max), key = { "h2h:" + it.providerId }) { r -> ResultRow(r, zone) }
}

/**
 * Clasamentul, cu rânduri scurte; [around] > 0 arată doar locurile din jurul celor două echipe (panoul din player).
 */
internal fun LazyListScope.tableSection(match: Match, d: MatchDetails, hideSpoilers: Boolean, onReveal: () -> Unit, around: Int = 0) {
    if (d.table.isEmpty()) return
    item(key = "table-h") { SectionLabel("Clasament") }
    if (hideSpoilers) {
        item(key = "table-hidden") {
            ListRow(title = "Clasament ascuns (fără spoilere)", subtitle = "OK îl arată.", onClick = onReveal)
        }
        return
    }
    val mine = MatchInsights.tableRowsFor(d.table, d.event, match.home, match.away)
    val rows = if (around <= 0 || mine.isEmpty()) d.table else d.table.filter { row -> mine.any { kotlin.math.abs(it - row.rank) <= around } }
    // Rânduri mărunte, cu coloane fixe (meciuri, V-E-Î, goluri, golaveraj, puncte), ca tot clasamentul să încapă.
    item(key = "t-head") {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.width(22.dp))
            Spacer(Modifier.weight(1f))
            listOf("M" to 22.dp, "V-E-Î" to 52.dp, "Gol." to 44.dp, "GV" to 30.dp, "P" to 28.dp).forEach { (t, w) ->
                Text(text = t, fontSize = 11.sp, color = AkColors.TextDim, textAlign = TextAlign.End, modifier = Modifier.width(w))
            }
        }
    }
    items(rows, key = { "t:" + it.rank + it.team }) { row ->
        FocusLine(selected = row.rank in mine, verticalPadding = 0.dp, horizontalPadding = 8.dp, spacing = 6.dp) {
            Text(text = "${row.rank}.", fontSize = 12.sp, fontFamily = AkFonts.Condensed, color = dimText(), modifier = Modifier.width(22.dp))
            Text(text = row.team, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val gd = if (row.goalsFor != null && row.goalsAgainst != null) row.goalsFor!! - row.goalsAgainst!! else null
            listOf(
                (row.played?.toString() ?: "") to 22.dp,
                (if (row.won != null && row.drawn != null && row.lost != null) "${row.won}-${row.drawn}-${row.lost}" else "") to 52.dp,
                (if (row.goalsFor != null && row.goalsAgainst != null) "${row.goalsFor}:${row.goalsAgainst}" else "") to 44.dp,
                (gd?.let { if (it > 0) "+$it" else it.toString() } ?: "") to 30.dp,
            ).forEach { (t, w) ->
                Text(text = t, fontSize = 12.sp, fontFamily = AkFonts.Condensed, color = dimText(), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(w))
            }
            Text(
                text = row.points?.toString() ?: "",
                fontSize = 13.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
        }
    }
}

/** Titularii, apoi rezervele unei echipe, pe rânduri scurte (număr, nume, post). */
internal fun LazyListScope.lineupList(team: String, players: List<LineupPlayer>, prefix: String) {
    if (players.isEmpty()) return
    val formation = Formation.of(players)?.label
    item(key = "lu-h:$prefix") {
        Text(
            text = team + (formation?.let { " · $it" } ?: ""),
            fontSize = 14.sp,
            fontFamily = AkFonts.Condensed,
            fontWeight = FontWeight.Bold,
            color = AkColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
        )
    }
    val (subs, starters) = players.partition { it.substitute }
    items(starters.withIndex().toList(), key = { "lu:$prefix:" + it.index }) { (_, p) -> PlayerLine(p) }
    if (subs.isNotEmpty()) {
        item(key = "lu-s:$prefix") {
            Text(text = "REZERVE", fontSize = 11.sp, letterSpacing = 1.5.sp, color = AkColors.TextDim, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 1.dp))
        }
        items(subs.withIndex().toList(), key = { "lus:$prefix:" + it.index }) { (_, p) -> PlayerLine(p) }
    }
}

/**
 * Evenimentele pe o axă: gazdele în stânga (aliniate spre mijloc), minutul la mijloc, oaspeții în dreapta.
 * [onClick] (panoul din player): saltul în arhivă.
 */
internal fun LazyListScope.timelineAxis(
    match: Match,
    timeline: List<TimelineEntry>,
    newestFirst: Boolean,
    onClick: ((TimelineEntry) -> Unit)? = null,
) {
    val list = timeline.withIndex().toList().let { if (newestFirst) it.reversed() else it }
    items(list, key = { "ev:" + it.index }) { (_, t) ->
        val text = listOfNotNull(timelineIcon(t.kind), t.player ?: t.kind.label).joinToString(" ") +
            (t.other?.let { if (t.kind == TimelineKind.SUBSTITUTION) " ↔ $it" else " ($it)" } ?: "")
        FocusLine(onClick = { onClick?.invoke(t) }) {
            Text(
                text = if (t.home) text else "",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                fontWeight = if (t.kind.isGoal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = t.minute?.let { "$it′" } ?: "·",
                fontSize = 16.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = FontWeight.Bold,
                color = if (t.kind.isGoal) accentText() else dimText(),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp),
            )
            Text(
                text = if (!t.home) text else "",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (t.kind.isGoal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TimelineKind.isGoal: Boolean
    get() = this == TimelineKind.GOAL || this == TimelineKind.PENALTY || this == TimelineKind.OWN_GOAL

/** Simbolul unui eveniment din meci (și în panoul din player). */
fun timelineIcon(kind: TimelineKind): String = when (kind) {
    TimelineKind.GOAL, TimelineKind.PENALTY -> "⚽"
    TimelineKind.OWN_GOAL -> "⚽ (autogol)"
    TimelineKind.YELLOW -> "🟨"
    TimelineKind.RED -> "🟥"
    TimelineKind.SUBSTITUTION -> "⇄"
    else -> "•"
}

/** Un rând scurt care primește focus (lista se derulează cu telecomanda), cu conținut pe orizontală. */
@Composable
internal fun FocusLine(
    selected: Boolean = false,
    onClick: () -> Unit = {},
    verticalPadding: Dp = 5.dp,
    horizontalPadding: Dp = 14.dp,
    spacing: Dp = 8.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AkColors.Panel else Color.Transparent,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        CompositionLocalProvider(LocalOnFocus provides focused) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

@Composable
private fun PlayerLine(p: LineupPlayer) {
    FocusLine(verticalPadding = 0.dp, horizontalPadding = 8.dp, spacing = 6.dp) {
        Text(text = p.number ?: "", fontSize = 12.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold, color = accentText(), modifier = Modifier.width(20.dp))
        Text(text = p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Positions.short(p.position)?.let {
            Text(text = it, fontSize = 12.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold, color = dimText(), maxLines = 1)
        }
    }
}

private fun LazyListScope.statusItem(state: MatchDetailsState) {
    val text = state.status ?: return
    item(key = "status") {
        Text(text = text, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

/** Meciul n-a început: un singur rând. Întoarce true dacă l-a pus (restul tabului nu mai are sens). */
private fun LazyListScope.notStartedItem(match: Match, status: MatchStatus, onRefresh: () -> Unit): Boolean {
    if (status != MatchStatus.SCHEDULED) return false
    val at = Instant.ofEpochMilli(match.start).atZone(ZoneId.systemDefault()).format(HourFormat)
    item(key = "not-started") {
        ListRow(
            title = "Meciul începe la $at",
            subtitle = "Scorul și evenimentele apar aici în timpul meciului (actualizate la un minut cât stai pe acest tab).",
            onClick = onRefresh,
        )
    }
    return true
}

private fun LazyListScope.hiddenItem(label: String, onReveal: () -> Unit) {
    item(key = "hidden") {
        ListRow(
            title = "Ascuns (fără spoilere)",
            subtitle = listOf(label.ifEmpty { "Meci început" }, "OK arată scorul, evenimentele și statisticile acestui meci").joinToString(" · "),
            onClick = onReveal,
        )
    }
}

@Composable
private fun FormRow(team: String, recent: List<SportEvent>, zone: ZoneId, lookForm: Boolean) {
    val look = rememberTeamLooks().team(team)
    val form = if (recent.isEmpty()) (if (lookForm) look?.formResults().orEmpty() else emptyList()) else MatchInsights.form(recent, team)
    ListRow(
        title = team,
        icon = { TeamLogo(team, 30.dp, codeFallback = true, look = look) },
        subtitle = if (recent.isEmpty()) {
            if (form.isNotEmpty()) "Forma din programul ESPN" else "Fără meciuri recente"
        } else {
            recent.take(3).joinToString("  ·  ") { r ->
                "${Instant.ofEpochMilli(r.start).atZone(zone).format(DateFormat).substring(0, 5)} ${r.homeScore}–${r.awayScore} " +
                    (if (ro.ak.sportshub.core.sports.TeamNames.same(r.home, team)) r.away else r.home)
            }
        },
        titleSize = 16.sp,
        onClick = {},
        trailing = { FormSquares(form, square = 22.dp) },
    )
}

@Composable
private fun ResultRow(r: SportEvent, zone: ZoneId) {
    FocusLine(verticalPadding = 4.dp) {
        Text(
            text = Instant.ofEpochMilli(r.start).atZone(zone).format(DateFormat),
            fontSize = 13.sp,
            fontFamily = AkFonts.Condensed,
            color = dimText(),
            modifier = Modifier.width(76.dp),
        )
        Text(text = r.home, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(
            text = "${r.homeScore ?: "-"} – ${r.awayScore ?: "-"}",
            fontSize = 16.sp,
            fontFamily = AkFonts.Condensed,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(60.dp),
        )
        Text(text = r.away, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Statisticile pe o singură coloană (panoul din player). */
internal fun LazyListScope.statsColumn(stats: List<StatLine>, match: Match, prefix: String, title: String) =
    groupedStats(stats, prefix, match.home, match.away, title = title)
