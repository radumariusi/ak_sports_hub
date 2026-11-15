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
import ro.ak.sportshub.ui.screens.timelineIcon
import ro.ak.sportshub.ui.theme.AkColors

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
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(text = match.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        text = listOfNotNull(match.competition, if (replay) "reluare din arhivă" else null).joinToString(" · "),
                        fontSize = 13.sp,
                        color = AkColors.TextDim,
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
            title = listOfNotNull(t.minute?.let { "$it'" }, timelineIcon(t.kind), t.player).joinToString("  "),
            subtitle = listOfNotNull(
                if (t.home) match.home else match.away,
                t.kind.label,
                t.other?.let { if (t.kind == TimelineKind.SUBSTITUTION) "cu $it" else "pasă: $it" },
                if (canJump) "OK: vezi faza" else null,
            ).joinToString(" · "),
            onClick = { if (canJump) onJump?.invoke(t) },
        )
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
