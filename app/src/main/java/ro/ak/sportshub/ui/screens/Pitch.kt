package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.Formation
import ro.ak.sportshub.core.sports.LineupPlayer

private val Grass = Color(0xFF1E5E35)
private val Chalk = Color(0x99FFFFFF)
private val HomeShirt = Color(0xFFFFB547)
private val AwayShirt = Color(0xFFEEF1F4)

/**
 * Terenul desenat cu formațiile celor două echipe: gazdele în stânga (portarul la margine), oaspeții în dreapta.
 * Primește focus, ca lista să se deruleze până la el cu telecomanda.
 */
@Composable
fun PitchView(home: Formation, away: Formation, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 380.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Grass, RoundedCornerShape(10.dp))
            .focusable(),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = Stroke(width = 2.dp.toPx())
            val w = size.width
            val h = size.height
            drawRect(Chalk, style = stroke)
            drawLine(Chalk, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = stroke.width)
            drawCircle(Chalk, radius = h * 0.15f, center = Offset(w / 2, h / 2), style = stroke)
            val boxW = w * 0.13f
            val boxH = h * 0.56f
            drawRect(Chalk, topLeft = Offset(0f, (h - boxH) / 2), size = Size(boxW, boxH), style = stroke)
            drawRect(Chalk, topLeft = Offset(w - boxW, (h - boxH) / 2), size = Size(boxW, boxH), style = stroke)
            val smallW = w * 0.05f
            val smallH = h * 0.26f
            drawRect(Chalk, topLeft = Offset(0f, (h - smallH) / 2), size = Size(smallW, smallH), style = stroke)
            drawRect(Chalk, topLeft = Offset(w - smallW, (h - smallH) / 2), size = Size(smallW, smallH), style = stroke)
        }
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp)) {
            home.lines.forEach { line -> PitchLineColumn(line, HomeShirt, Modifier.weight(1f)) }
            away.lines.reversed().forEach { line -> PitchLineColumn(line, AwayShirt, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PitchLineColumn(players: List<LineupPlayer>, shirt: Color, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        players.forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = p.number ?: "•",
                    fontSize = 13.sp,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(shirt, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(text = shortName(p.name), fontSize = 12.sp, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** „Bukayo Saka” → „Saka”; numele scurte rămân întregi. */
private fun shortName(name: String): String = name.trim().split(Regex("""\s+""")).let { if (it.size > 1) it.last() else name.trim() }
