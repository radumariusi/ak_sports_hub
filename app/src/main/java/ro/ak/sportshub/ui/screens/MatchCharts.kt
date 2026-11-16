package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlin.math.sqrt
import ro.ak.sportshub.core.sports.MomentumPoint
import ro.ak.sportshub.core.sports.Shot
import ro.ak.sportshub.core.sports.ShotOutcome
import ro.ak.sportshub.core.sports.WinProbability
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

/*
 * Desenele din fereastra meciului (calupul 1): șansele de câștig (ESPN), presiunea pe minute și harta șuturilor
 * (FotMob). Gazdele chihlimbar, oaspeții alb, ca pe teren. Fiecare desen primește focus (rama albă), ca lista
 * să se deruleze până la el cu telecomanda.
 */

private val DrawColor = Color(0xFF9AA4B1)

@Composable
private fun FocusBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(2.dp, if (focused) AkColors.Focus else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(6.dp),
    ) { content() }
}

private fun pct(v: Float): String = "${(v * 100).roundToInt()}%"

/** „Arsenal 62% · Egal 23% · Chelsea 15%” și bara împărțită pe culori. */
@Composable
fun ChanceBar(home: String, away: String, wp: WinProbability, modifier: Modifier = Modifier) {
    FocusBox(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$home ${pct(wp.home)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = HomeShirt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                wp.draw?.let { Text("Egal ${pct(it)}", fontSize = 14.sp, color = AkColors.TextDim, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp)) }
                Text("${pct(wp.away)} $away", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AwayShirt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val total = (wp.home + (wp.draw ?: 0f) + wp.away).takeIf { it > 0f } ?: 1f
                val hw = size.width * wp.home / total
                val dw = size.width * (wp.draw ?: 0f) / total
                drawRect(HomeShirt, size = Size(hw, size.height))
                drawRect(DrawColor.copy(alpha = 0.5f), topLeft = Offset(hw, 0f), size = Size(dw, size.height))
                drawRect(AwayShirt, topLeft = Offset(hw + dw, 0f), size = Size(size.width - hw - dw, size.height))
            }
        }
    }
}

/** Șansele gazdelor de-a lungul meciului: deasupra mijlocului gazdele, dedesubt oaspeții. */
@Composable
fun ChanceChart(series: List<Float>, modifier: Modifier = Modifier) {
    if (series.size < 2) return
    FocusBox(modifier) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2
            drawLine(DrawColor.copy(alpha = 0.4f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())
            val step = w / (series.size - 1)
            val line = Path()
            val area = Path().apply { moveTo(0f, mid) }
            series.forEachIndexed { i, v ->
                val p = Offset(i * step, h * (1f - v.coerceIn(0f, 1f)))
                if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                area.lineTo(p.x, p.y)
            }
            // Zona dintre linie și mijloc: chihlimbar deasupra (gazdele conduc), alb dedesubt.
            area.lineTo(w, mid)
            area.close()
            clipRect(0f, 0f, w, mid) { drawPath(area, HomeShirt.copy(alpha = 0.35f)) }
            clipRect(0f, mid, w, h) { drawPath(area, AwayShirt.copy(alpha = 0.25f)) }
            drawPath(line, AkColors.Text, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/**
 * Presiunea pe minute (FotMob): bare în sus pentru gazde, în jos pentru oaspeți; linia punctată la pauză (45′).
 * [homeGoals] / [awayGoals]: minutele golurilor, marcate cu un punct sus / jos.
 */
@Composable
fun MomentumChart(points: List<MomentumPoint>, homeGoals: List<Int>, awayGoals: List<Int>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    FocusBox(modifier) {
        Column {
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val w = size.width
                val h = size.height
                val mid = h / 2
                val last = maxOf(90f, points.last().minute)
                fun x(m: Float) = w * (m / last).coerceIn(0f, 1f)
                drawLine(DrawColor.copy(alpha = 0.4f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())
                drawLine(DrawColor.copy(alpha = 0.5f), Offset(x(45f), 0f), Offset(x(45f), h), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val barW = (w / points.size).coerceAtLeast(1f) * 0.8f
                points.forEach { p ->
                    val v = p.value / 100f * (mid - 4.dp.toPx())
                    val left = x(p.minute) - barW / 2
                    if (v >= 0) drawRect(HomeShirt, topLeft = Offset(left, mid - v), size = Size(barW, v))
                    else drawRect(AwayShirt.copy(alpha = 0.85f), topLeft = Offset(left, mid), size = Size(barW, -v))
                }
                homeGoals.forEach { m -> drawCircle(HomeShirt, radius = 4.dp.toPx(), center = Offset(x(m.toFloat()), 4.dp.toPx())) }
                awayGoals.forEach { m -> drawCircle(AwayShirt, radius = 4.dp.toPx(), center = Offset(x(m.toFloat()), h - 4.dp.toPx())) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0′", fontSize = 11.sp, color = AkColors.TextDim)
                Text("pauză", fontSize = 11.sp, color = AkColors.TextDim)
                Text("${maxOf(90, points.last().minute.toInt())}′", fontSize = 11.sp, color = AkColors.TextDim)
            }
        }
    }
}

/**
 * Harta șuturilor pe teren: gazdele atacă spre dreapta, oaspeții spre stânga. Cercul crește cu golurile
 * așteptate (xG); plin = gol, contur gros = pe poartă, contur subțire = pe lângă, bară sau blocat.
 */
@Composable
fun ShotMapView(shots: List<Shot>, home: String, away: String, modifier: Modifier = Modifier) {
    if (shots.isEmpty()) return
    FocusBox(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(105f / 68f)
                    .background(Grass, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawPitchLines()
                    val w = size.width
                    val h = size.height
                    shots.forEach { s ->
                        // Gazdele spre dreapta; oaspeții întorși (și pe lățime, ca șutul din stânga lor să rămână pe partea lor).
                        val cx = if (s.home) w * s.x else w * (1f - s.x)
                        val cy = if (s.home) h * s.y else h * (1f - s.y)
                        val color = if (s.home) HomeShirt else AwayShirt
                        val r = (4.dp.toPx() + 14.dp.toPx() * sqrt((s.xg ?: 0.05f).coerceIn(0f, 1f)))
                        when (s.outcome) {
                            ShotOutcome.GOAL -> {
                                drawCircle(color, radius = r, center = Offset(cx, cy))
                                drawCircle(Color.Black.copy(alpha = 0.6f), radius = r, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                            }
                            ShotOutcome.SAVED -> drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
                            else -> drawCircle(color.copy(alpha = 0.55f), radius = r, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shotSummary(away, shots.filter { !it.home }), fontSize = 13.sp, color = AwayShirt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shotSummary(home, shots.filter { it.home }), fontSize = 13.sp, color = HomeShirt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Text(
                text = "● gol   ◯ pe poartă   ○ pe lângă / blocat   · cercul mai mare = ocazie mai bună (xG)",
                fontSize = 11.sp,
                color = AkColors.TextDim,
                fontFamily = AkFonts.Condensed,
            )
        }
    }
}

/** „Arsenal: 20 șuturi, 6 pe poartă, xG 1.88”. */
private fun shotSummary(team: String, shots: List<Shot>): String {
    val onTarget = shots.count { it.outcome == ShotOutcome.GOAL || it.outcome == ShotOutcome.SAVED }
    val xg = shots.mapNotNull { it.xg }.takeIf { it.isNotEmpty() }?.sum()
    return "$team: ${shots.size} șuturi, $onTarget pe poartă" + (xg?.let { ", xG " + "%.2f".format(java.util.Locale.ROOT, it) } ?: "")
}
