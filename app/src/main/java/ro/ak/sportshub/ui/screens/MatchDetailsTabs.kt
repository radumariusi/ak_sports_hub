package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ro.ak.sportshub.core.sports.FormResult
import ro.ak.sportshub.core.sports.Formation
import ro.ak.sportshub.core.sports.LineupPlayer
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchInsights
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.TimelineKind
import ro.ak.sportshub.data.MatchDetailsState
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.theme.AkColors

private val DateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

private val Win = Color(0xFF35C46A)
private val Draw = Color(0xFFB0B7C3)
private val Loss = Color(0xFFE5484D)

/** Tabul Pre-meci: detalii, formă, meciuri directe, clasament, echipe de start. Fiecare rând primește focus (se derulează cu săgețile). */
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
    val e = d.event
    val zone = ZoneId.systemDefault()
    LazyColumn(modifier = modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                ActionButton(text = if (state.loading) "Se încarcă…" else "↻ Actualizează", onClick = onRefresh, enabled = !state.loading)
            }
        }
        statusItem(state, sportsDbEnabled)

        val facts = listOfNotNull(
            e?.league?.let { "Competiție" to (it + (e?.round?.let { r -> ", etapa $r" } ?: "")) },
            (e?.venue ?: match.venue)?.let { "Stadion" to (it + (e?.city?.let { c -> ", $c" } ?: "")) },
            e?.referee?.let { "Arbitru" to it },
            e?.spectators?.let { "Spectatori" to "%,d".format(it).replace(',', '.') },
        )
        if (facts.isNotEmpty()) {
            item { SectionLabel("Meciul") }
            items(facts, key = { "f:" + it.first }) { (k, v) -> ListRow(title = v, subtitle = k, onClick = {}) }
        }

        if (match.away.isNotBlank() && (d.homeRecent.isNotEmpty() || d.awayRecent.isNotEmpty())) {
            item { SectionLabel("Forma (ultimele meciuri)") }
            item(key = "form-home") { FormRow(match.home, d.homeRecent, zone) }
            item(key = "form-away") { FormRow(match.away, d.awayRecent, zone) }
        }

        if (d.headToHead.isNotEmpty()) {
            val (w, dr, l) = MatchInsights.headToHeadSummary(d.headToHead, match.home)
            item { SectionLabel("Meciuri directe") }
            item(key = "h2h-sum") {
                ListRow(
                    title = "${match.home}: $w victorii, $dr egaluri, $l înfrângeri",
                    subtitle = "Ultimele ${d.headToHead.size} meciuri dintre echipe",
                    onClick = {},
                )
            }
            items(d.headToHead, key = { "h2h:" + it.providerId }) { r -> ResultRow(r, zone) }
        }

        if (d.table.isNotEmpty()) {
            item { SectionLabel("Clasament") }
            if (hideSpoilers) {
                item(key = "table-hidden") {
                    ListRow(
                        title = "Clasament ascuns (fără spoilere)",
                        subtitle = "Poate conține deja rezultatul acestui meci. OK îl arată.",
                        onClick = onReveal,
                    )
                }
            } else {
                val mine = MatchInsights.tableRowsFor(d.table, e, match.home, match.away)
                items(d.table, key = { "t:" + it.rank + it.team }) { row ->
                    ListRow(
                        title = "${row.rank}. ${row.team}",
                        subtitle = listOfNotNull(
                            row.played?.let { "$it meciuri" },
                            if (row.won != null && row.drawn != null && row.lost != null) "${row.won}V ${row.drawn}E ${row.lost}Î" else null,
                            if (row.goalsFor != null && row.goalsAgainst != null) "golaveraj ${row.goalsFor}–${row.goalsAgainst}" else null,
                        ).joinToString(" · "),
                        selected = row.rank in mine,
                        onClick = {},
                        trailing = { row.points?.let { Text(text = "$it p", fontSize = 15.sp) } },
                    )
                }
            }
        }

        val homeF = Formation.of(d.lineup.filter { it.home })
        val awayF = Formation.of(d.lineup.filter { !it.home })
        if (homeF != null && awayF != null) {
            item(key = "pitch-h") {
                SectionLabel(
                    "Formații: ${match.home} ${homeF.label} · ${match.away} ${awayF.label}" +
                        ((e?.venue ?: match.venue)?.let { " · $it" + (e?.city?.let { c -> ", $c" } ?: "") } ?: ""),
                )
            }
            item(key = "pitch") { PitchView(homeF, awayF, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        }
        if (d.lineup.isNotEmpty()) {
            lineupSection("Titulari ${match.home}", d.lineup.filter { it.home && !it.substitute }, "lh")
            lineupSection("Titulari ${match.away}", d.lineup.filter { !it.home && !it.substitute }, "la")
            lineupSection("Rezerve ${match.home}", d.lineup.filter { it.home && it.substitute }, "rh")
            lineupSection("Rezerve ${match.away}", d.lineup.filter { !it.home && it.substitute }, "ra")
        } else if (e != null && match.status == MatchStatus.SCHEDULED) {
            item(key = "lineup-later") {
                ListRow(
                    title = "Echipele de start nu sunt încă anunțate",
                    subtitle = "Apar de obicei cu o oră înainte de meci; în ultimele două ore datele se reiau la 20 de minute.",
                    onClick = {},
                )
            }
        }

        if (!state.loading && d.isEmpty && sportsDbEnabled) {
            item(key = "nothing") {
                ListRow(
                    title = "Nicio statistică pentru acest meci",
                    subtitle = "Nici ESPN, nici TheSportsDB nu au date despre el. Sursele rămân în primul tab.",
                    onClick = {},
                )
            }
        }
        item {
            Text(
                text = "Date de la ESPN (sau TheSportsDB, pentru meciurile care nu sunt la ESPN), cerute doar cât e deschis meciul; nu folosesc conexiunea playlisturilor.",
                fontSize = 13.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** Tabul Live: scor, minut, evenimente, statistici; respectă „fără spoilere”. */
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
    LazyColumn(modifier = modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (status == MatchStatus.SCHEDULED) {
            val at = Instant.ofEpochMilli(match.start).atZone(ZoneId.systemDefault()).format(HourFormat)
            item(key = "not-started") {
                ListRow(
                    title = "Meciul începe la $at",
                    subtitle = "Scorul, evenimentele și statisticile apar aici în timpul meciului (actualizate la un minut cât stai pe acest tab).",
                    onClick = onRefresh,
                )
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                ActionButton(text = if (state.loading) "Se încarcă…" else "↻ Actualizează", onClick = onRefresh, enabled = !state.loading)
                if (noSpoilersOn && !hideSpoilers) ActionButton(text = "Ascunde din nou", onClick = onHide)
            }
        }
        statusItem(state, sportsDbEnabled)
        val label = MatchInsights.liveLabel(status, e?.statusCode, e?.progress)
        if (hideSpoilers) {
            item(key = "hidden") {
                ListRow(
                    title = "Scor ascuns (fără spoilere)",
                    subtitle = listOf(label.ifEmpty { "Meci început" }, "OK arată scorul, evenimentele și statisticile acestui meci").joinToString(" · "),
                    onClick = onReveal,
                )
            }
            return@LazyColumn
        }
        item(key = "score") {
            ListRow(
                title = if (homeScore != null && awayScore != null) "${match.home}  $homeScore – $awayScore  ${match.away}" else match.title,
                subtitle = label.ifEmpty { if (status == MatchStatus.LIVE) "În desfășurare" else "Încheiat" },
                onClick = onRefresh,
                trailing = { if (status == MatchStatus.LIVE) Badge(text = "LIVE", color = AkColors.Live) },
            )
        }
        if (d.timeline.isNotEmpty()) {
            item { SectionLabel("Evenimente") }
            items(d.timeline.withIndex().toList(), key = { "tl:" + it.index }) { (_, t) ->
                val team = if (t.home) match.home else match.away
                val icon = timelineIcon(t.kind)
                ListRow(
                    title = listOfNotNull(t.minute?.let { "$it'" }, icon, t.player).joinToString("  "),
                    subtitle = listOfNotNull(
                        team,
                        t.kind.label,
                        t.other?.let { if (t.kind == TimelineKind.SUBSTITUTION) "cu $it" else "pasă: $it" },
                    ).joinToString(" · "),
                    onClick = {},
                )
            }
        }
        if (d.stats.isNotEmpty()) {
            item { SectionLabel("Statistici  (${match.home} – ${match.away})") }
            items(d.stats, key = { "st:" + it.name }) { s ->
                ListRow(title = s.name, onClick = {}, trailing = { Text(text = "${s.home}  –  ${s.away}", fontSize = 16.sp) })
            }
        }
        if (d.timeline.isEmpty() && d.stats.isEmpty() && !state.loading) {
            item(key = "no-live") {
                ListRow(
                    title = "Fără evenimente sau statistici",
                    subtitle = "ESPN și TheSportsDB nu le au (încă) pentru acest meci.",
                    onClick = {},
                )
            }
        }
    }
}

/** Simbolul unui eveniment din meci (și în panoul din player). */
fun timelineIcon(kind: TimelineKind): String = when (kind) {
    TimelineKind.GOAL, TimelineKind.PENALTY -> "⚽"
    TimelineKind.OWN_GOAL -> "⚽ (autogol)"
    TimelineKind.YELLOW -> "🟨"
    TimelineKind.RED -> "🟥"
    TimelineKind.SUBSTITUTION -> "⇄"
    else -> "•"
}

private fun LazyListScope.statusItem(state: MatchDetailsState, sportsDbEnabled: Boolean) {
    val text = when {
        else -> state.status
    } ?: return
    item(key = "status") {
        Text(text = text, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

private fun LazyListScope.lineupSection(title: String, players: List<LineupPlayer>, prefix: String) {
    if (players.isEmpty()) return
    item(key = "lh:$prefix") { SectionLabel(title) }
    items(players.withIndex().toList(), key = { "$prefix:" + it.index }) { (_, p) ->
        ListRow(
            title = listOfNotNull(p.number, p.name).joinToString("  "),
            subtitle = p.position,
            onClick = {},
        )
    }
}

@Composable
private fun FormRow(team: String, recent: List<SportEvent>, zone: ZoneId) {
    val form = MatchInsights.form(recent, team)
    ListRow(
        title = team,
        subtitle = if (recent.isEmpty()) {
            "Fără meciuri recente"
        } else {
            recent.joinToString("  ·  ") { r ->
                "${Instant.ofEpochMilli(r.start).atZone(zone).format(DateFormat).substring(0, 5)} ${r.home} ${r.homeScore}–${r.awayScore} ${r.away}"
            }
        },
        onClick = {},
        trailing = {
            form.forEach { f ->
                Badge(
                    text = f.letter,
                    color = when (f) {
                        FormResult.WIN -> Win
                        FormResult.DRAW -> Draw
                        FormResult.LOSS -> Loss
                    },
                )
            }
        },
    )
}

@Composable
private fun ResultRow(r: SportEvent, zone: ZoneId) {
    ListRow(
        title = "${r.home}  ${r.homeScore} – ${r.awayScore}  ${r.away}",
        subtitle = listOfNotNull(Instant.ofEpochMilli(r.start).atZone(zone).format(DateFormat), r.competition).joinToString(" · "),
        onClick = {},
    )
}
