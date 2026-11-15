package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.StatGroups
import ro.ak.sportshub.core.sports.StatLine
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

/**
 * Statisticile pe grupuri (Posesie, Atac, Pase, Apărare, Disciplină, Jucători), fiecare cu valoarea gazdelor în
 * stânga, a oaspeților în dreapta și bara care arată cine domină. Rândurile primesc focus, ca lista să se deruleze
 * cu telecomanda.
 */
fun LazyListScope.groupedStats(stats: List<StatLine>, keyPrefix: String, home: String, away: String, title: String? = null, compact: Boolean = false) {
    if (stats.isEmpty()) return
    if (title != null) item(key = "$keyPrefix-title") { SectionLabel(title) }
    statTeams(home, away, keyPrefix)
    statGroups(StatGroups.group(stats), keyPrefix, compact)
}

/** Rândul cu numele echipelor deasupra statisticilor (gazdele în stânga, cu chihlimbar). */
fun LazyListScope.statTeams(home: String, away: String, keyPrefix: String) {
    item(key = "$keyPrefix-teams") {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            Text(text = home, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AkColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(text = away, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Doar grupurile date (pentru două coloane: o parte din grupuri în stânga, restul în dreapta). */
fun LazyListScope.statGroups(groups: List<StatGroups.Group>, keyPrefix: String, compact: Boolean = false) {
    groups.forEach { g ->
        item(key = "$keyPrefix-g-${g.title}") {
            Text(
                text = g.title.uppercase(),
                fontSize = if (compact) 10.sp else 12.sp,
                letterSpacing = 1.5.sp,
                color = AkColors.Accent.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 16.dp, top = if (compact) 3.dp else 8.dp, bottom = if (compact) 0.dp else 2.dp),
            )
        }
        items(g.rows, key = { "$keyPrefix-${g.title}-${it.line.name}" }) { r -> StatBarRow(r, compact) }
    }
}

@Composable
private fun StatBarRow(r: StatGroups.Row, compact: Boolean = false) {
    // Mărunt (tabul Statistici, Pre): toate grupurile încap pe o pagină, pe două coloane.
    val valueSize = if (compact) 13.sp else 16.sp
    val nameSize = if (compact) 11.sp else 14.sp
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        CompositionLocalProvider(LocalOnFocus provides focused) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 1.dp else 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val share = r.homeShare
                    val homeLeads = share != null && share > 0.5f
                    val awayLeads = share != null && share < 0.5f
                    Text(
                        text = r.line.home,
                        fontSize = valueSize,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = if (homeLeads) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(if (share == null) 2f else 1f),
                    )
                    Text(
                        text = r.line.name,
                        fontSize = nameSize,
                        color = dimText(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(2f),
                    )
                    Text(
                        text = r.line.away,
                        fontSize = valueSize,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = if (awayLeads) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(if (share == null) 2f else 1f),
                    )
                }
                val share = r.homeShare
                if (share != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = if (compact) 1.dp else 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Două jumătăți care pornesc din mijloc: bara gazdelor spre stânga, a oaspeților spre dreapta.
                        val top = maxOf(share, 1f - share).coerceAtLeast(0.0001f)
                        Bar(fraction = share / top, fromEnd = true, color = if (focused) Color(0xFF0B0F14) else AkColors.Accent, modifier = Modifier.weight(1f))
                        Bar(fraction = (1f - share) / top, fromEnd = false, color = if (focused) Color(0x990B0F14) else AkColors.Text.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** O jumătate de bară: [fraction] din lățime (cea mai mare valoare umple jumătatea), lipită de mijloc. */
@Composable
private fun Bar(fraction: Float, fromEnd: Boolean, color: Color, modifier: Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier = modifier.height(4.dp).background(Color(0x22FFFFFF), RoundedCornerShape(2.dp))) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (fromEnd && f < 1f) Spacer(Modifier.weight(1f - f))
            if (f > 0f) Box(Modifier.weight(f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
            if (!fromEnd && f < 1f) Spacer(Modifier.weight(1f - f))
        }
    }
}
