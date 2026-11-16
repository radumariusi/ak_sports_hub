package ro.ak.sportshub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.ui.theme.AkColors

/** Iconițele rândului de filtre, desenate cu linii (fără emoji, ca să aibă aceeași culoare ca textul). */
enum class FilterIcon { SPORT, COMPETITION, TEAM, LANGUAGE, QUALITY, SOURCES, CLEAR, REFRESH, SETTINGS }

/**
 * Un filtru pe rândul din Sports Hub (varianta D aleasă de Radu): iconița, apoi numele filtrului („Sport”) sau,
 * când e pus, valoarea („Fotbal”, „UCL”) cu chihlimbar, tăiată cu „…” dacă e prea lungă. La sport, iconița devine
 * a sportului ales.
 */
@Composable
fun FilterPill(
    icon: FilterIcon,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sportul ales (doar la [FilterIcon.SPORT]): iconița lui în locul celei generice. */
    sport: Sport? = null,
    maxWidth: Dp = 170.dp,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.touchClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = PillFocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AkColors.Panel,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        CompositionLocalProvider(LocalOnFocus provides focused) {
            val text = value ?: label
            val tint = if (value != null) accentText() else LocalContentColor.current
            Row(
                modifier = Modifier.padding(start = 11.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon == FilterIcon.SPORT) SportGlyph(sport, color = tint) else FilterGlyph(icon, color = tint)
                if (text.isNotEmpty()) Text(
                    text = text,
                    fontSize = 15.sp,
                    fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxWidth),
                )
            }
        }
    }
}

/** Buton rotund doar cu iconiță (Actualizează, Setări): altfel decât filtrele, ca să se vadă că e altceva. */
@Composable
fun IconPill(icon: FilterIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(32.dp).touchClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, AkColors.TextDim.copy(alpha = 0.5f)), shape = CircleShape),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, AkColors.Accent), shape = CircleShape),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AkColors.TextDim,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            FilterGlyph(icon, color = LocalContentColor.current)
        }
    }
}

@Composable
fun FilterGlyph(icon: FilterIcon, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) { drawGlyph(icon, color) }
}

private fun DrawScope.drawGlyph(icon: FilterIcon, color: Color) {
    val w = this.size.width
    val s = w / 24f
    val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
        if (close) close()
    }
    when (icon) {
        FilterIcon.SPORT -> {
            // Minge: cercul, pentagonul din mijloc și cusăturile spre margine.
            drawCircle(color, radius = 10f * s, center = p(12f, 12f), style = stroke)
            val pent = path(12f to 8f, 15.8f to 10.8f, 14.4f to 15.2f, 9.6f to 15.2f, 8.2f to 10.8f, close = true)
            drawPath(pent, color)
            listOf(12f to 8f to (12f to 2.5f), 15.8f to 10.8f to (21f to 9f), 14.4f to 15.2f to (17.5f to 20f),
                9.6f to 15.2f to (6.5f to 20f), 8.2f to 10.8f to (3f to 9f)).forEach { (a, b) ->
                drawLine(color, p(a.first, a.second), p(b.first, b.second), strokeWidth = 1.6f * s)
            }
        }
        FilterIcon.COMPETITION -> {
            // Cupa: vasul, toartele, piciorul și soclul.
            drawPath(path(7f to 3.5f, 17f to 3.5f, 17f to 9f, 15.5f to 12.5f, 12f to 14f, 8.5f to 12.5f, 7f to 9f, close = true), color, style = stroke)
            drawPath(path(7f to 5.5f, 3.5f to 5.5f, 3.5f to 8f, 7.4f to 11f), color, style = stroke)
            drawPath(path(17f to 5.5f, 20.5f to 5.5f, 20.5f to 8f, 16.6f to 11f), color, style = stroke)
            drawLine(color, p(12f, 14f), p(12f, 18f), strokeWidth = 1.9f * s)
            drawPath(path(8f to 20.5f, 16f to 20.5f, 15f to 18f, 9f to 18f, close = true), color, style = stroke)
        }
        FilterIcon.TEAM -> {
            // Tricoul.
            drawPath(
                path(9f to 3f, 4f to 5.5f, 2f to 10f, 5.5f to 11.5f, 6.5f to 9.5f, 6.5f to 21f, 17.5f to 21f, 17.5f to 9.5f,
                    18.5f to 11.5f, 22f to 10f, 20f to 5.5f, 15f to 3f, 13.8f to 5f, 10.2f to 5f, close = true),
                color, style = stroke,
            )
        }
        FilterIcon.LANGUAGE -> {
            // Globul: cercul, meridianul și paralelele.
            drawCircle(color, radius = 9.5f * s, center = p(12f, 12f), style = stroke)
            drawOval(color, topLeft = p(8f, 2.5f), size = Size(8f * s, 19f * s), style = stroke)
            drawLine(color, p(2.5f, 12f), p(21.5f, 12f), strokeWidth = 1.6f * s)
            drawLine(color, p(4f, 7.5f), p(20f, 7.5f), strokeWidth = 1.4f * s)
            drawLine(color, p(4f, 16.5f), p(20f, 16.5f), strokeWidth = 1.4f * s)
        }
        FilterIcon.QUALITY -> {
            // Ecranul cu piciorul lui și o stea mică (calitatea imaginii).
            drawRoundRect(color, topLeft = p(2.5f, 4f), size = Size(19f * s, 13f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s), style = stroke)
            drawLine(color, p(9f, 20.5f), p(15f, 20.5f), strokeWidth = 1.9f * s)
            drawLine(color, p(12f, 17f), p(12f, 20.5f), strokeWidth = 1.9f * s)
            drawPath(path(12f to 7f, 13.2f to 9.4f, 15.6f to 9.8f, 13.8f to 11.5f, 14.3f to 14f, 12f to 12.8f,
                9.7f to 14f, 10.2f to 11.5f, 8.4f to 9.8f, 10.8f to 9.4f, close = true), color)
        }
        FilterIcon.SOURCES -> {
            // Triunghiul play într-un cerc: are cel puțin o sursă.
            drawCircle(color, radius = 9.5f * s, center = p(12f, 12f), style = stroke)
            drawPath(path(10f to 7.8f, 16.2f to 12f, 10f to 16.2f, close = true), color)
        }
        FilterIcon.CLEAR -> {
            drawLine(color, p(6f, 6f), p(18f, 18f), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
            drawLine(color, p(18f, 6f), p(6f, 18f), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
        }
        FilterIcon.REFRESH -> {
            // Săgeata în cerc.
            drawArc(color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = p(4f, 4f), size = Size(16f * s, 16f * s), style = stroke)
            drawPath(path(16f to 2.5f, 17f to 6.6f, 12.8f to 7.4f), color, style = stroke)
        }
        FilterIcon.SETTINGS -> {
            // Rotița: 8 dinți, cercul și gaura din mijloc.
            for (i in 0 until 8) {
                rotate(degrees = i * 45f, pivot = p(12f, 12f)) {
                    drawRoundRect(color, topLeft = p(10.3f, 1.8f), size = Size(3.4f * s, 4.5f * s),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8f * s))
                }
            }
            drawCircle(color, radius = 6.3f * s, center = p(12f, 12f), style = Stroke(width = 3.4f * s))
            drawCircle(color, radius = 2.2f * s, center = p(12f, 12f), style = Stroke(width = 1.6f * s))
        }
    }
}

/** Iconița unui sport, în același stil (linii, o singură culoare); fără sport, o medalie. */
@Composable
fun SportGlyph(sport: Sport?, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) { drawSport(sport, color) }
}

private fun DrawScope.drawSport(sport: Sport?, color: Color) {
    val s = this.size.width / 24f
    val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = 1.5f * s
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 1.9f * s) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(color, radius = r * s, center = p(x, y)) else drawCircle(color, radius = r * s, center = p(x, y), style = stroke)
    fun arc(l: Float, t: Float, w: Float, h: Float, start: Float, sweep: Float, sw: Float = 1.9f * s) =
        drawArc(color, start, sweep, useCenter = false, topLeft = p(l, t), size = Size(w * s, h * s),
            style = Stroke(width = sw, cap = StrokeCap.Round))
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
        if (close) close()
    }
    when (sport) {
        Sport.FOOTBALL -> drawGlyph(FilterIcon.SPORT, color)
        Sport.BASKETBALL -> {
            circle(12f, 12f, 10f)
            line(12f, 2f, 12f, 22f, thin)
            line(2f, 12f, 22f, 12f, thin)
            arc(-3f, 4.5f, 12f, 15f, -50f, 100f, thin)
            arc(15f, 4.5f, 12f, 15f, 130f, 100f, thin)
        }
        Sport.TENNIS -> {
            circle(12f, 12f, 10f)
            arc(-6f, 3f, 14f, 18f, -55f, 110f, thin)
            arc(16f, 3f, 14f, 18f, 125f, 110f, thin)
        }
        Sport.ICE_HOCKEY -> {
            // Crosa și pucul.
            drawPath(path(15f to 2.5f, 7.5f to 18.5f, 3f to 18.5f), color, style = stroke)
            line(3f, 18.5f, 3f, 20.5f)
            drawOval(color, topLeft = p(13f, 17.5f), size = Size(8f * s, 3.5f * s))
        }
        Sport.HANDBALL -> {
            // Mingea de handbal: cercul și panourile curbe.
            circle(12f, 12f, 10f)
            arc(5f, -3f, 14f, 14f, 30f, 120f, thin)
            arc(5f, 13f, 14f, 14f, 210f, 120f, thin)
            line(12f, 11f, 12f, 13f, thin)
        }
        Sport.VOLLEYBALL -> {
            circle(12f, 12f, 10f)
            arc(2f, -6f, 22f, 22f, 70f, 90f, thin)
            arc(4f, 8f, 22f, 22f, 190f, 90f, thin)
            arc(-2f, 6f, 16f, 16f, -60f, 90f, thin)
        }
        Sport.RUGBY, Sport.AMERICAN_FOOTBALL -> {
            // Mingea ovală, înclinată; rugby = cusătura lungă, fotbal american = șiretul.
            rotate(-40f, pivot = p(12f, 12f)) {
                drawOval(color, topLeft = p(2f, 6.5f), size = Size(20f * s, 11f * s), style = stroke)
                if (sport == Sport.RUGBY) {
                    line(6f, 12f, 18f, 12f, thin)
                } else {
                    line(8.5f, 12f, 15.5f, 12f, thin)
                    for (x in listOf(9.5f, 12f, 14.5f)) line(x, 10.7f, x, 13.3f, thin)
                }
            }
        }
        Sport.BASEBALL -> {
            circle(12f, 12f, 10f)
            arc(-5f, 4f, 12f, 16f, -60f, 120f, thin)
            arc(17f, 4f, 12f, 16f, 120f, 120f, thin)
            for (y in listOf(8.5f, 12f, 15.5f)) {
                line(5.5f, y, 7.2f, y - 0.6f, 1.2f * s)
                line(16.8f, y - 0.6f, 18.5f, y, 1.2f * s)
            }
        }
        Sport.WINTER -> {
            // Fulgul de zăpadă.
            for (a in listOf(0f, 60f, 120f)) {
                rotate(a, pivot = p(12f, 12f)) {
                    line(12f, 2.5f, 12f, 21.5f)
                    line(12f, 6f, 9.8f, 3.8f, thin); line(12f, 6f, 14.2f, 3.8f, thin)
                    line(12f, 18f, 9.8f, 20.2f, thin); line(12f, 18f, 14.2f, 20.2f, thin)
                }
            }
        }
        Sport.MOTORSPORT -> {
            // Steagul în carouri.
            line(4.5f, 2.5f, 4.5f, 21.5f)
            drawRect(color, topLeft = p(5f, 3.5f), size = Size(15f * s, 10f * s), style = stroke)
            for (r in 0 until 2) for (c in 0 until 3) {
                if ((r + c) % 2 == 0) drawRect(color, topLeft = p(5f + c * 5f, 3.5f + r * 5f), size = Size(5f * s, 5f * s))
            }
        }
        Sport.FIGHTING -> {
            // Mănușa de box.
            drawPath(
                path(7f to 14f, 5f to 9f, 6f to 4.5f, 10f to 2.5f, 16f to 3f, 19f to 6f, 19f to 11f, 17f to 14f, close = true),
                color, style = stroke,
            )
            drawPath(path(5.5f to 9f, 3.5f to 9.5f, 3.5f to 12f, 6.5f to 13f), color, style = stroke)
            drawRoundRect(color, topLeft = p(7f, 15.5f), size = Size(10f * s, 5.5f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * s), style = stroke)
        }
        Sport.DARTS -> {
            circle(11f, 13f, 9f)
            circle(11f, 13f, 5f)
            circle(11f, 13f, 1.4f, fill = true)
            line(11f, 13f, 20f, 4f)
            line(20f, 4f, 22f, 4.5f, thin); line(20f, 4f, 19.5f, 2f, thin)
        }
        Sport.SNOOKER -> {
            circle(7f, 16f, 4.5f)
            circle(16.5f, 16f, 4.5f)
            circle(16.5f, 16f, 1.2f, fill = true)
            line(2.5f, 3f, 14f, 10f)
        }
        Sport.CYCLING -> {
            circle(5.5f, 16f, 4f)
            circle(18.5f, 16f, 4f)
            drawPath(path(5.5f to 16f, 9.5f to 8f, 16f to 8f, 18.5f to 16f), color, style = stroke)
            drawPath(path(9.5f to 8f, 12f to 16f, 16f to 8f), color, style = stroke)
            line(8f, 6f, 11f, 6f)
            line(16f, 8f, 15.5f, 5f); line(14.5f, 5f, 17f, 5f)
        }
        Sport.GOLF -> {
            line(10f, 3f, 10f, 19f)
            drawPath(path(10f to 3f, 18f to 6f, 10f to 9f, close = true), color)
            drawOval(color, topLeft = p(4f, 18f), size = Size(14f * s, 4f * s), style = stroke)
        }
        Sport.CRICKET -> {
            rotate(35f, pivot = p(12f, 12f)) {
                drawRoundRect(color, topLeft = p(9.5f, 2f), size = Size(5f * s, 14f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * s), style = stroke)
                line(12f, 16f, 12f, 21f)
            }
            circle(19f, 18.5f, 2.6f, fill = true)
        }
        null -> {
            // Generic: medalia cu panglică.
            drawPath(path(7f to 2.5f, 10.5f to 10f), color, style = stroke)
            drawPath(path(17f to 2.5f, 13.5f to 10f), color, style = stroke)
            circle(12f, 15f, 6.5f)
            drawPath(path(12f to 11.5f, 13f to 13.8f, 15.4f to 14f, 13.6f to 15.6f, 14.1f to 18f, 12f to 16.8f,
                9.9f to 18f, 10.4f to 15.6f, 8.6f to 14f, 11f to 13.8f, close = true), color)
        }
    }
}
