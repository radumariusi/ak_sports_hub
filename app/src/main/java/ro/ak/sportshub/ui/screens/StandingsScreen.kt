package ro.ak.sportshub.ui.screens

import ro.ak.sportshub.ui.components.ListPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.sports.Brackets
import ro.ak.sportshub.core.sports.Espn
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.core.sports.Standings
import ro.ak.sportshub.core.sports.TableEntry
import ro.ak.sportshub.core.sports.TableGroup
import ro.ak.sportshub.core.sports.TeamCalendar
import ro.ak.sportshub.core.sports.TeamLook
import ro.ak.sportshub.core.sports.TeamNames
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.FormSquares
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.components.TeamLogo
import ro.ak.sportshub.ui.components.accentText
import ro.ak.sportshub.ui.components.dimText
import ro.ak.sportshub.ui.components.rememberTeamLooks
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

private val Up = Color(0xFF3DDC84)
private val RoLocale = Locale.forLanguageTag("ro")
private val NextFormat = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", RoLocale)

/**
 * Tabul „Clasamente” (F3): ligile în stânga (întâi cele ale favoritelor și cele cu meciuri în listă), clasamentul
 * ligii alese în dreapta: locul, ▲/▼, zona (calificare, retrogradare) ca bandă colorată, sigla, meciurile, golaverajul,
 * punctele și forma (V/E/Î). OK pe o echipă: cardul ei (forma, următoarele meciuri, „Urmărește”).
 * Clasamentul se cere doar când alegi liga (cel mult o dată la 6 ore); cu „Fără spoilere” forma nu se arată.
 */
@Composable
fun StandingsTab(onOpenMatch: (String) -> Unit) {
    val container = appContainer()
    val settings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val states by container.standings.states.collectAsStateWithLifecycle()
    val looks = rememberTeamLooks()
    val scope = rememberCoroutineScope()

    val leagues = remember(settings.favorites, snapshot.computedAt) {
        val matches = snapshot.matches.map { it.match }
        val withMatches = Standings.leagues.filter { l -> matches.any { it.competition == l.competition } }.map { it.key }.toSet()
        val teamLeagues = Standings.leagues.filter { l ->
            matches.any { m -> m.competition == l.competition && (settings.favorites.isTeam(m.home) || (m.away.isNotBlank() && settings.favorites.isTeam(m.away))) }
        }.map { it.key }.toSet()
        // A6: și tabloul Grand Slam-ului în curs (ATP / WTA), la sfârșit.
        Standings.ordered(settings.favorites, teamLeagues, withMatches) + Espn.leagues.filter { it.key in Brackets.TENNIS }
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = leagues.firstOrNull { it.key == selectedKey } ?: leagues.firstOrNull()
    var card by remember { mutableStateOf<Pair<TableGroup, TableEntry>?>(null) }

    val brackets by container.standings.brackets.collectAsStateWithLifecycle()
    // A6: la cupe, tabloul fazei eliminatorii în locul clasamentului (implicit la cupele fără grupe și la tenis).
    var bracketChoice by rememberSaveable { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val showBracket = selected?.let { l ->
        l.key in Brackets.TENNIS || (Brackets.has(l) && (bracketChoice[l.key] ?: (l.key in Brackets.KNOCKOUT_ONLY)))
    } ?: false

    LaunchedEffect(selected?.key, showBracket) {
        selected?.let { if (showBracket) container.standings.loadBracket(it) else container.standings.load(it) }
    }

    Row(modifier = Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LazyColumn(contentPadding = ListPadding, modifier = Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(leagues, key = { it.key }) { l ->
                ListRow(
                    title = when (l.key) {
                        "ATP" -> "Grand Slam · simplu masculin"
                        "WTA" -> "Grand Slam · simplu feminin"
                        else -> l.competition
                    },
                    subtitle = l.sport.label + (if (Brackets.has(l)) " · tablou" else "") +
                        if (settings.favorites.isCompetition(l.competition)) " · favorită" else "",
                    selected = l.key == selected?.key,
                    titleSize = 15.sp,
                    onClick = { selectedKey = l.key },
                )
            }
        }
        val league = selected
        val state = league?.let { states[it.key] }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (league == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = league.competition,
                    fontSize = 24.sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (Brackets.has(league) && league.key !in Brackets.TENNIS) {
                    ActionButton(
                        text = if (showBracket) "Clasament" else "Tablou",
                        onClick = { bracketChoice = bracketChoice + (league.key to !showBracket) },
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                ActionButton(
                    text = "Actualizează",
                    onClick = {
                        scope.launch {
                            if (showBracket) container.standings.loadBracket(league, force = true) else container.standings.load(league, force = true)
                        }
                    },
                )
            }
            if (showBracket) {
                BracketView(state = brackets[league.key], onOpenMatch = onOpenMatch)
                return@Column
            }
            Text(
                text = state?.status ?: state?.fetchedAt?.takeIf { it > 0 }?.let { "Clasament ESPN · actualizat " + ago(it) } ?: "Se încarcă…",
                fontSize = 13.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val groups = state?.groups.orEmpty()
            if (groups.isEmpty()) {
                EmptyState(
                    title = if (state?.loading != false) "Se cere clasamentul…" else "Fără clasament",
                    hint = "Clasamentele vin de la ESPN, pentru ligile cu echipe din catalog.",
                )
                return@Column
            }
            LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { g ->
                    if (groups.size > 1) item(key = "g:" + g.name) { SectionLabel(g.name, modifier = Modifier.padding(top = 6.dp)) }
                    items(g.rows, key = { "r:" + g.name + ":" + (it.teamId ?: it.team) }) { r ->
                        TableRow(
                            row = r,
                            look = TeamLook(logo = r.logo).over(looks.team(r.team)),
                            favorite = settings.favorites.isTeam(r.team),
                            showForm = !settings.noSpoilers,
                            onClick = { card = g to r },
                        )
                    }
                    val zones = g.rows.mapNotNull { it.zone }.distinctBy { it.description }
                    if (zones.isNotEmpty()) {
                        item(key = "z:" + g.name) {
                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                zones.forEach { z ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).background(z.color?.let { Color(it) } ?: AkColors.TextDim, RoundedCornerShape(2.dp)))
                                        Text(text = " " + zoneLabel(z.description), fontSize = 12.sp, color = AkColors.TextDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    card?.let { (group, row) ->
        TeamCard(
            row = row,
            group = group,
            look = TeamLook(logo = row.logo).over(looks.team(row.team)),
            onOpenMatch = { id -> card = null; onOpenMatch(id) },
            onDismiss = { card = null },
        )
    }
}

@Composable
private fun TableRow(row: TableEntry, look: TeamLook, favorite: Boolean, showForm: Boolean, onClick: () -> Unit) {
    ListRow(
        title = (if (favorite) "★ " else "") + row.team,
        subtitle = stats(row),
        onClick = onClick,
        titleSize = 16.sp,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(30.dp).background(row.zone?.color?.let { Color(it) } ?: Color.Transparent, RoundedCornerShape(2.dp)))
                Text(
                    text = row.rank.toString(),
                    fontSize = 20.sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
                Spacer(Modifier.width(6.dp))
                row.change?.let { c ->
                    Text(
                        text = if (c > 0) "▲$c" else "▼${-c}",
                        fontSize = 12.sp,
                        color = if (c > 0) Up else AkColors.Live,
                    )
                }
            }
        },
        icon = { TeamLogo(team = row.team, size = 30.dp, codeFallback = true, look = look) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showForm) {
                    val form = look.formResults()
                    if (form.isNotEmpty()) FormSquares(form, modifier = Modifier.padding(end = 14.dp), square = 16.dp)
                }
                Text(
                    text = row.points ?: "–",
                    fontSize = 22.sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.Bold,
                    color = accentText(),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        },
    )
}

/** „10 meciuri · 9-0-1 · golaveraj +10” (cât dă ESPN). */
private fun stats(r: TableEntry): String = listOfNotNull(
    r.played?.let { "$it meciuri" },
    if (r.won != null && r.lost != null) listOfNotNull(r.won, r.drawn, r.lost).joinToString("-") else null,
    if (r.goalsFor != null && r.goalsAgainst != null) {
        val d = r.goalsFor!! - r.goalsAgainst!!
        "${r.goalsFor}:${r.goalsAgainst} (" + (if (d > 0) "+$d" else "$d") + ")"
    } else {
        null
    },
).joinToString(" · ")

/** Zonele ESPN, pe românește când le știm. */
private fun zoneLabel(d: String): String {
    val l = d.lowercase()
    return when {
        "relegation" in l && "playoff" in l -> "Baraj de retrogradare"
        "relegation" in l -> "Retrogradare"
        "champions league" in l && "qual" in l -> "Preliminarii Liga Campionilor"
        "champions league" in l -> "Liga Campionilor"
        "europa league" in l -> "Europa League"
        "conference league" in l -> "Conference League"
        "promotion" in l && "playoff" in l -> "Baraj de promovare"
        "promotion" in l -> "Promovare"
        "playoff" in l -> "Play-off"
        "play-in" in l -> "Play-in"
        else -> d
    }
}

private fun ago(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "acum"
        min < 60 -> "acum $min min"
        else -> "acum ${min / 60} h"
    }
}

/** Cardul echipei: sigla, locul și punctele, forma, următoarele meciuri (din lista Sports Hub) și „Urmărește”. */
@Composable
private fun TeamCard(row: TableEntry, group: TableGroup, look: TeamLook, onOpenMatch: (String) -> Unit, onDismiss: () -> Unit) {
    val container = appContainer()
    val settings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val revealed by container.matchDetails.revealed.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val next = remember(snapshot, row.team) {
        TeamCalendar.build(listOf(row.team), snapshot.matches.map { it.match }, now, perTeam = 3).firstOrNull()?.second.orEmpty()
    }
    val following = settings.favorites.isTeam(row.team)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val zone = ZoneId.systemDefault()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(600.dp)
                .background(AkColors.Panel, RoundedCornerShape(10.dp))
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(team = row.team, size = 56.dp, codeFallback = true, look = look)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.team, fontSize = 24.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        text = listOfNotNull("Locul ${row.rank}", row.points?.let { "$it pct" }, group.name.takeIf { it.isNotBlank() }, row.zone?.description?.let(::zoneLabel))
                            .joinToString(" · "),
                        fontSize = 14.sp,
                        color = AkColors.TextDim,
                    )
                }
            }
            val form = look.formResults()
            if (form.isNotEmpty() && !settings.noSpoilers) {
                Row(modifier = Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Forma  ", fontSize = 14.sp, color = AkColors.TextDim)
                    FormSquares(form, square = 20.dp)
                }
            }
            SectionLabel("Următoarele meciuri", modifier = Modifier.padding(top = 14.dp))
            LazyColumn(contentPadding = ListPadding, modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "follow") {
                    ListRow(
                        title = if (following) "★ Urmărești echipa (OK: nu mai urmări)" else "☆ Urmărește echipa",
                        subtitle = "Meciurile ei apar la Favorite, cu notificări, comutare automată și alerte de gol.",
                        onClick = { container.sportsSettings.toggleFavoriteTeam(row.team) },
                        modifier = Modifier.focusRequester(focus),
                    )
                }
                if (next.isEmpty()) {
                    item(key = "none") {
                        Text(text = "Niciun meci în zilele din program.", fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(14.dp))
                    }
                }
                items(next, key = { it.id }) { m ->
                    val hide = Spoilers.hide(settings.noSpoilers, m.id in revealed, m.status)
                    ListRow(
                        title = m.title,
                        subtitle = listOfNotNull(
                            Instant.ofEpochMilli(m.start).atZone(zone).format(NextFormat),
                            m.competition,
                            if (m.status == MatchStatus.LIVE) "LIVE" else null,
                            if (!hide && m.status == MatchStatus.LIVE && m.homeScore != null) "${m.homeScore}–${m.awayScore}" else null,
                        ).joinToString(" · "),
                        onClick = { onOpenMatch(m.id) },
                        trailing = { Text(text = if (TeamNames.same(row.team, m.home)) "acasă" else "deplasare", fontSize = 12.sp, color = dimText()) },
                    )
                }
            }
        }
    }
}

// ---------- A6: tabloul cupei ----------

private val GameFormat = DateTimeFormatter.ofPattern("d MMM · HH:mm", RoLocale)

/**
 * Tabloul: rundele una lângă alta (◀ ▶ trec de la o rundă la alta, ▲ ▼ prin confruntări), pornind de la runda în curs.
 * O confruntare arată echipele, scorul general (sau seturile), câștigătoarea îngroșată și data manșelor; cu „Fără
 * spoilere”, scorurile și câștigătorii meciurilor începute nu se arată. OK pe o confruntare din lista Sports Hub
 * deschide meciul.
 */
@Composable
private fun BracketView(state: ro.ak.sportshub.data.BracketState?, onOpenMatch: (String) -> Unit) {
    val container = appContainer()
    val settings by container.sportsSettings.settings.collectAsStateWithLifecycle()
    val snapshot by container.sports.snapshot.collectAsStateWithLifecycle()
    val looks = rememberTeamLooks()
    val bracket = state?.bracket
    Text(
        text = state?.status ?: listOfNotNull(
            bracket?.title?.takeIf { it.isNotBlank() },
            bracket?.season,
            state?.fetchedAt?.takeIf { it > 0 }?.let { "ESPN · actualizat " + ago(it) },
            if (settings.noSpoilers) "fără spoilere: scorurile sunt ascunse" else null,
        ).joinToString(" · ").ifEmpty { "Se încarcă…" },
        fontSize = 13.sp,
        color = AkColors.TextDim,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    if (bracket == null || bracket.rounds.isEmpty()) {
        EmptyState(
            title = if (state?.loading != false) "Se cere tabloul…" else "Fără tablou",
            hint = "Tabloul vine din programul ESPN al competiției: doar fazele eliminatorii.",
        )
        return
    }
    val zone = ZoneId.systemDefault()
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = bracket.currentRound())
    androidx.compose.foundation.lazy.LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(bracket.rounds, key = { "round:" + it.key }) { round ->
            Column(modifier = Modifier.width(290.dp).fillMaxHeight()) {
                SectionLabel(round.name + " (" + round.ties.size + ")", modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(round.ties, key = { round.key + ":" + it.first + ":" + it.second }) { tie ->
                        val hide = settings.noSpoilers && tie.started
                        val entry = remember(snapshot.computedAt, tie) {
                            snapshot.matches.firstOrNull { e ->
                                val m = e.match
                                tie.games.any { g ->
                                    kotlin.math.abs(m.start - g.start) < TimeUnit.HOURS.toMillis(3) &&
                                        (TeamNames.same(m.home, g.home) && TeamNames.same(m.away, g.away) ||
                                            TeamNames.same(m.home, g.away) && TeamNames.same(m.away, g.home))
                                }
                            }
                        }
                        val next = tie.games.firstOrNull { it.status != MatchStatus.FINISHED } ?: tie.games.last()
                        ListRow(
                            title = tie.first + " – " + tie.second,
                            onClick = { entry?.let { onOpenMatch(it.match.id) } },
                            subtitle = listOfNotNull(
                                when {
                                    tie.live -> "LIVE"
                                    tie.finished && tie.games.size > 1 -> "${tie.games.size} manșe"
                                    tie.finished -> Instant.ofEpochMilli(next.start).atZone(zone).format(GameFormat)
                                    else -> Instant.ofEpochMilli(next.start).atZone(zone).format(GameFormat) +
                                        if (tie.games.size > 1) " · manșa ${tie.games.indexOf(next) + 1}" else ""
                                },
                                if (hide) null else tie.note,
                                if (entry != null) "OK: meciul" else null,
                            ).joinToString(" · "),
                            titleContent = {
                                Column {
                                    TieLine(tie.first, if (hide) null else tie.firstScore, !hide && tie.winner == 0, settings.favorites.isTeam(tie.first), looks.team(tie.first))
                                    TieLine(tie.second, if (hide) null else tie.secondScore, !hide && tie.winner == 1, settings.favorites.isTeam(tie.second), looks.team(tie.second))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TieLine(name: String, score: String?, winner: Boolean, favorite: Boolean, look: TeamLook?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        TeamLogo(team = name, size = 20.dp, codeFallback = false, look = look)
        Spacer(Modifier.width(6.dp))
        Text(
            text = (if (favorite) "★ " else "") + name,
            fontSize = 15.sp,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null) {
            Text(
                text = score,
                fontSize = 16.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium,
                color = if (winner) accentText() else androidx.compose.ui.graphics.Color.Unspecified,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
