package ro.ak.sportshub.ui.screens

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
import kotlinx.coroutines.launch
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
        Standings.ordered(settings.favorites, teamLeagues, withMatches)
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = leagues.firstOrNull { it.key == selectedKey } ?: leagues.firstOrNull()
    var card by remember { mutableStateOf<Pair<TableGroup, TableEntry>?>(null) }

    LaunchedEffect(selected?.key) { selected?.let { container.standings.load(it) } }

    Row(modifier = Modifier.fillMaxSize().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LazyColumn(modifier = Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(leagues, key = { it.key }) { l ->
                ListRow(
                    title = l.competition,
                    subtitle = l.sport.label + if (settings.favorites.isCompetition(l.competition)) " · favorită" else "",
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
                ActionButton(text = "Actualizează", onClick = { scope.launch { container.standings.load(league, force = true) } })
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
