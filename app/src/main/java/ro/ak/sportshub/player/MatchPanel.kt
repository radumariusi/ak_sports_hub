package ro.ak.sportshub.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.Formation
import ro.ak.sportshub.core.sports.LineupPlayer
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.PanelView
import ro.ak.sportshub.core.sports.TimelineEntry
import ro.ak.sportshub.core.sports.TimelineKind
import ro.ak.sportshub.ui.components.Badge
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.core.sports.Scoreboard
import ro.ak.sportshub.core.sports.StatLine
import ro.ak.sportshub.core.sports.TeamCodes
import ro.ak.sportshub.ui.components.dimText
import ro.ak.sportshub.ui.theme.AkFonts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.components.bandColor
import androidx.compose.foundation.layout.width

/** Ce se poate face din panoul meciului, după unde ești (live, reluare) și ce are canalul (arhivă). */
data class MatchPanelActions(
    /** Salt la un eveniment în arhivă (null = canalul n-are arhiva meciului sau sportul n-are minute). */
    val onJump: ((TimelineEntry) -> Unit)? = null,
    /** Reluarea meciului de la început (null = fără arhivă). */
    val onFromStart: (() -> Unit)? = null,
    /** Înapoi la live (doar la reluarea unui meci care încă se joacă). */
    val onLive: (() -> Unit)? = null,
    val onReveal: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    /** Întârzierea stream-ului (doar live). */
    val onDelay: (() -> Unit)? = null,
    /** Corecția saltului pe playlist (doar când se poate sări). */
    val onOffset: (() -> Unit)? = null,
)

/**
 * Panoul meciului din player (dreapta): scorul, evenimentele, statisticile și echipele, live sau la reluare.
 * Live, ce e nou apare după întârzierea stream-ului; la reluare, doar ce s-a întâmplat până unde te uiți.
 * OK pe un eveniment: saltul în arhivă, cu un minut înainte.
 */
@Composable
fun MatchPanelContent(
    match: Match,
    view: PanelView?,
    hidden: Boolean,
    replay: Boolean,
    lineup: List<LineupPlayer>,
    loading: Boolean,
    status: String?,
    delaySeconds: Int,
    offsetSeconds: Int?,
    actions: MatchPanelActions,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SidePanel(modifier.padding(0.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "title") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MECIUL",
                            fontSize = 28.sp,
                            fontFamily = AkFonts.Condensed,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when {
                                replay -> "reluare din arhivă"
                                delaySeconds > 0 -> "afișat cu $delaySeconds s întârziere"
                                else -> "fără întârziere"
                            },
                            fontSize = 14.sp,
                            color = AkColors.TextDim,
                        )
                    }
                    Text(
                        text = listOfNotNull(match.title, match.competition).joinToString(" · "),
                        fontSize = 14.sp,
                        color = AkColors.TextDim,
                        maxLines = 1,
                    )
                }
            }
            item(key = "score") {
                val score = when {
                    hidden -> "Scor ascuns (fără spoilere)"
                    view == null -> if (loading) "Se încarcă…" else "Fără scor încă"
                    view.homeScore != null && view.awayScore != null -> "${match.home}  ${view.homeScore} – ${view.awayScore}  ${match.away}"
                    else -> match.title
                }
                ListRow(
                    title = score,
                    titleSize = 20.sp,
                    subtitle = when {
                        hidden -> "OK arată scorul și evenimentele acestui meci"
                        view == null -> status ?: "Datele vin de la TheSportsDB, cerute cât e deschis panoul."
                        else -> listOfNotNull(
                            view.label.ifEmpty { null },
                            if (view.pending > 0) "${view.pending} evenimente noi vin în stream" else null,
                        ).joinToString(" · ").ifEmpty { null }
                    },
                    onClick = if (hidden) actions.onReveal else actions.onRefresh,
                    modifier = Modifier.focusRequester(focus),
                    trailing = {
                        when {
                            replay -> Badge(text = "RELUARE", color = AkColors.Focus)
                            view?.status == MatchStatus.LIVE || match.isLive -> Badge(text = "LIVE", color = AkColors.Live)
                        }
                    },
                )
            }
            actions.onLive?.let { item(key = "live") { ListRow(title = "● Înapoi la live", onClick = it) } }
            actions.onFromStart?.let {
                item(key = "start") {
                    ListRow(title = "⏮ De la început", subtitle = "Arhiva canalului, de la fluierul de start", onClick = it)
                }
            }
            actions.onDelay?.let {
                item(key = "delay") {
                    ListRow(
                        title = "Întârzierea stream-ului: ${if (delaySeconds == 0) "fără" else "$delaySeconds s"}",
                        subtitle = "Golurile apar în panou abia după atât, ca să le vezi întâi pe ecran",
                        onClick = it,
                    )
                }
            }
            if (actions.onOffset != null && offsetSeconds != null) {
                item(key = "offset") {
                    ListRow(
                        title = "Corecția saltului: ${if (offsetSeconds > 0) "+" else ""}$offsetSeconds s",
                        subtitle = "Pentru playlistul canalului: mărește dacă saltul ajunge prea devreme, micșorează dacă ajunge târziu",
                        onClick = actions.onOffset,
                    )
                }
            }
            if (!hidden && view != null) {
                events(match, view.timeline, actions.onJump)
                // Ca în tabela TV: posesia și șuturile pe poartă, sub evenimente.
                Scoreboard.possession(view.stats)?.let { (h, a) ->
                    item(key = "possession") {
                        PossessionBlock(home = h, away = a, shots = Scoreboard.stat(view.stats, "Șuturi pe poartă"))
                    }
                }
                if (view.stats.isNotEmpty()) {
                    item(key = "st-h") { SectionLabel("Statistici", modifier = Modifier.padding(top = 6.dp)) }
                    items(view.stats, key = { "st:" + it.name }) { s ->
                        ListRow(title = s.name, onClick = {}, trailing = { Text(text = "${s.home}  –  ${s.away}", fontSize = 15.sp) })
                    }
                } else if (view.statsHidden) {
                    item(key = "st-hidden") {
                        ListRow(title = "Statisticile apar la final", subtitle = "Sunt cele de la sfârșitul meciului (spoiler la reluare)", onClick = {})
                    }
                }
            }
            if (lineup.isNotEmpty()) lineups(match, lineup)
            item(key = "foot") {
                Text(
                    text = "TheSportsDB, cerut la un minut doar cât e deschis panoul; nu folosește conexiunea playlistului." +
                        if (actions.onJump != null) " OK pe un eveniment: arhiva, cu un minut înainte." else "",
                    fontSize = 12.sp,
                    color = AkColors.TextDim,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

private fun LazyListScope.events(match: Match, timeline: List<TimelineEntry>, onJump: ((TimelineEntry) -> Unit)?) {
    item(key = "ev-h") { SectionLabel("Evenimente", modifier = Modifier.padding(top = 6.dp)) }
    if (timeline.isEmpty()) {
        item(key = "ev-none") { ListRow(title = "Niciun eveniment încă", onClick = {}) }
        return
    }
    // Cele mai noi sus: la live te interesează ultimul gol.
    items(timeline.withIndex().reversed().toList(), key = { "ev:" + it.index }) { (_, t) ->
        val canJump = onJump != null && t.minute != null
        ListRow(
            title = listOfNotNull(t.minute?.let { "$it′" }, t.player ?: t.kind.label).joinToString("  ") +
                (t.other?.takeIf { t.kind == TimelineKind.SUBSTITUTION }?.let { " ↔ $it" } ?: ""),
            subtitle = if (canJump) {
                "OK: sari în arhivă la ${Scoreboard.jumpMinute(t.minute!!)}′"
            } else {
                listOfNotNull(
                    if (t.home) match.home else match.away,
                    t.kind.label,
                    t.other?.takeIf { t.kind != TimelineKind.SUBSTITUTION }?.let { "pasă: $it" },
                ).joinToString(" · ")
            },
            onClick = { if (canJump) onJump?.invoke(t) },
            leading = { EventTag(t.kind) },
            trailing = {
                Text(
                    text = TeamCodes.of(if (t.home) match.home else match.away),
                    fontSize = 15.sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.SemiBold,
                    color = dimText(),
                )
            },
        )
    }
}

/** Semnul evenimentului, în stilul temei: GOL chihlimbar plin, cartonașele ca dreptunghiuri colorate, schimbarea ⇄. */
@Composable
private fun EventTag(kind: TimelineKind) {
    when (kind) {
        TimelineKind.GOAL, TimelineKind.PENALTY -> Badge(text = if (kind == TimelineKind.PENALTY) "GOL (P)" else "GOL", color = AkColors.Accent)
        TimelineKind.OWN_GOAL -> Badge(text = "AUTOGOL", color = AkColors.Focus)
        TimelineKind.YELLOW -> Box(Modifier.padding(start = 8.dp).size(width = 15.dp, height = 21.dp).background(CardYellow, RoundedCornerShape(3.dp)))
        TimelineKind.RED -> Box(Modifier.padding(start = 8.dp).size(width = 15.dp, height = 21.dp).background(AkColors.Live, RoundedCornerShape(3.dp)))
        TimelineKind.SUBSTITUTION -> Text(text = "⇄", fontSize = 22.sp, color = dimText(), modifier = Modifier.padding(start = 8.dp))
        TimelineKind.MISSED_PENALTY -> Text(text = "PEN ✗", fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold, color = dimText())
        TimelineKind.VAR -> Text(text = "VAR", fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold, color = dimText())
        TimelineKind.OTHER -> Text(text = "•", fontSize = 18.sp, color = dimText(), modifier = Modifier.padding(start = 8.dp))
    }
}

private val CardYellow = Color(0xFFF5C542)

/** Posesia ca bară (gazdele alb, oaspeții chihlimbar) și șuturile pe poartă. */
@Composable
private fun PossessionBlock(home: Int, away: Int, shots: StatLine?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .background(AkColors.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Text(text = "Posesie", fontSize = 15.sp, color = AkColors.TextDim, modifier = Modifier.weight(1f))
            Text(text = "$home% – $away%", fontSize = 16.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
        }
        Row(modifier = Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(home.coerceAtLeast(1).toFloat()).fillMaxHeight().background(AkColors.Focus, RoundedCornerShape(2.dp)))
            Box(Modifier.weight(away.coerceAtLeast(1).toFloat()).fillMaxHeight().background(AkColors.Accent, RoundedCornerShape(2.dp)))
        }
        shots?.let {
            Row {
                Text(text = it.name, fontSize = 15.sp, color = AkColors.TextDim, modifier = Modifier.weight(1f))
                Text(text = "${it.home} – ${it.away}", fontSize = 16.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Tabela de scor din stânga sus a playerului: „ARS 1 – 0 CHE 67′”, colțuri drepte (stilul Reflector).
 * Scorul vine din [view] (după întârzierea stream-ului sau, la reluare, până la poziția redată); ascuns la „fără spoilere”.
 */
@Composable
fun MatchScoreboard(match: Match, view: PanelView?, hidden: Boolean, replay: Boolean, modifier: Modifier = Modifier) {
    val score = if (!hidden && view?.homeScore != null && view.awayScore != null) "${view.homeScore} – ${view.awayScore}" else "–"
    val clock = Scoreboard.clock(view?.label)
    Row(
        modifier = modifier.height(48.dp).clip(RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val looks = ro.ak.sportshub.ui.components.rememberTeamLooks()
        TeamCell(match.home, looks.team(match.home), stripeFirst = true)
        ScoreCell(score, AkColors.Background, AkColors.Text)
        if (match.away.isNotBlank()) TeamCell(match.away, looks.team(match.away), stripeFirst = false)
        clock?.let { ScoreCell(it, AkColors.Accent, AkColors.OnFocus) }
        if (replay) ScoreCell("RELUARE", AkColors.Panel, AkColors.Text)
    }
}

/**
 * Echipa pe tabela de scor: banda culorii ei spre margine, sigla (doar dacă e deja pe disc: cât rulează playerul nu se
 * descarcă nimic) și codul (ESPN, altfel [TeamCodes]).
 */
@Composable
private fun TeamCell(team: String, look: ro.ak.sportshub.core.sports.TeamLook?, stripeFirst: Boolean) {
    val band = look.bandColor()
    Row(modifier = Modifier.background(AkColors.Focus).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        if (stripeFirst && band != null) Box(Modifier.width(6.dp).fillMaxHeight().background(band))
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stripeFirst) ro.ak.sportshub.ui.components.TeamLogo(team, 30.dp, look = look, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = look?.code?.take(4)?.uppercase() ?: TeamCodes.of(team),
                color = AkColors.OnFocus,
                fontSize = 28.sp,
                fontFamily = AkFonts.Condensed,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            if (!stripeFirst) ro.ak.sportshub.ui.components.TeamLogo(team, 30.dp, look = look, modifier = Modifier.padding(start = 8.dp))
        }
        if (!stripeFirst && band != null) Box(Modifier.width(6.dp).fillMaxHeight().background(band))
    }
}

@Composable
private fun ScoreCell(text: String, bg: Color, fg: Color) {
    Box(modifier = Modifier.background(bg).fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = fg, fontSize = 28.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

private fun LazyListScope.lineups(match: Match, lineup: List<LineupPlayer>) {
    listOf(true to match.home, false to match.away).forEach { (home, team) ->
        val players = lineup.filter { it.home == home && !it.substitute }
        if (players.isEmpty()) return@forEach
        val formation = Formation.of(lineup.filter { it.home == home })?.label
        item(key = "lu-h:$home") {
            SectionLabel("Titulari $team" + (formation?.let { " · $it" } ?: ""), modifier = Modifier.padding(top = 6.dp))
        }
        items(players.withIndex().toList(), key = { "lu:$home:" + it.index }) { (_, p) ->
            ListRow(title = listOfNotNull(p.number, p.name).joinToString("  "), subtitle = p.position, onClick = {})
        }
    }
}
