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
import ro.ak.sportshub.ui.theme.AkColors

/** Cum arată rândul de filtre din Sports Hub (A: doar text; C: iconiță + valoarea, doar când filtrul e pus). */
enum class FilterRowStyle { TEXT, ICONS }

/** Iconițele rândului de filtre, desenate cu linii (fără emoji, ca să aibă aceeași culoare ca textul). */
enum class FilterIcon { SPORT, COMPETITION, TEAM, LANGUAGE, QUALITY, SOURCES, CLEAR, REFRESH, SETTINGS }

/**
 * Un filtru pe rândul din Sports Hub. Fără valoare arată numele filtrului („Sport”) sau doar iconița; cu valoare
 * arată valoarea („Fotbal”, „UCL”) cu chihlimbar, tăiată cu „…” dacă e prea lungă, ca rândul să încapă pe ecran.
 */
@Composable
fun FilterPill(
    icon: FilterIcon,
    label: String,
    value: String?,
    style: FilterRowStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 190.dp,
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
            val showIcon = style == FilterRowStyle.ICONS
            val text = if (showIcon) value else (value ?: label)
            Row(
                modifier = Modifier.padding(horizontal = if (showIcon && text == null) 9.dp else 13.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showIcon) {
                    FilterGlyph(icon, color = if (value != null) accentText() else LocalContentColor.current)
                }
                if (text != null) {
                    Text(
                        text = text,
                        fontSize = 15.sp,
                        fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (value != null) accentText() else LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = maxWidth),
                    )
                }
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
