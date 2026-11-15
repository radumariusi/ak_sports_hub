package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.ui.theme.AkColors

/** Rând de taburi text. Tabul se schimbă când primește focus, fără să apeși OK. */
@Composable
fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    firstTabModifier: Modifier = Modifier,
) {
    // focusRestorer: când revii din listă în rândul de taburi, focusul se întoarce pe tabul curent.
    Row(
        // Defilează orizontal când taburile nu încap (focusul aduce tabul în vedere).
        modifier = modifier.focusRestorer().focusGroup().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                modifier = (if (index == 0) firstTabModifier else Modifier)
                    .onFocusChanged { if (it.isFocused) onSelect(index) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) AkColors.Panel else Color.Transparent,
                    contentColor = if (selected) AkColors.Accent else AkColors.TextDim,
                    focusedContainerColor = AkColors.Focus,
                    focusedContentColor = AkColors.OnFocus,
                ),
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** Un rând de listă: text principal, text secundar, conținut la dreapta. */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    /** OK ținut apăsat (de exemplu programul canalului). */
    onLongClick: (() -> Unit)? = null,
    /** Cât s-a scurs din emisiunea curentă (0–1); o linie subțire sub text. */
    progress: Float? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AkColors.Panel else Color.Transparent,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 16.sp, maxLines = 1)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                if (progress != null) ProgressLine(progress, Modifier.padding(top = 4.dp).width(160.dp))
            }
            if (trailing != null) trailing()
        }
    }
}

/** O bară subțire de progres (cât s-a scurs din emisiune). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(3.dp).background(Color(0x40FFFFFF), RoundedCornerShape(2.dp))) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(AkColors.Accent, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
fun Badge(text: String, color: Color = AkColors.Accent, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.Black,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .padding(start = 6.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun QualityBadge(quality: Quality) {
    Badge(text = quality.label, color = qualityColor(quality))
}

/** Culoarea etichetei de calitate. */
fun qualityColor(quality: Quality): Color = when (quality) {
    Quality.UHD -> Color(0xFFF2C94C)
    Quality.FHD -> AkColors.Accent
    Quality.HD -> Color(0xFF56B6F7)
    Quality.SD -> Color(0xFFB0B7C3)
    Quality.UNKNOWN -> Color(0xFF6B7280)
}

/**
 * Eticheta de calitate a unei variante, cu ce s-a văzut la redare: „FHD 50p ✓” când rezoluția redată bate cu
 * playlistul, altfel eticheta reală și, lângă ea, cea din playlist tăiată („FHD 50p, în playlist ~~4K~~”).
 * Corecția ta manuală are prioritate.
 */
@Composable
fun VariantQualityBadge(playlistId: Long, name: String, quality: Quality) {
    val container = ro.ak.sportshub.ui.appContainer()
    val seen by container.observedVideo.state.collectAsStateWithLifecycle()
    val variants by container.variantStore.state.collectAsStateWithLifecycle()
    val view = ro.ak.sportshub.data.qualityView(playlistId, name, quality, seen, variants)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(text = view.label + if (view.verified) " ✓" else "", color = qualityColor(view.quality))
        view.playlistLabel?.let { old ->
            Text(text = ", în playlist ", fontSize = 12.sp, color = AkColors.TextDim, modifier = Modifier.padding(start = 4.dp))
            Text(text = old, fontSize = 12.sp, color = AkColors.TextDim, textDecoration = TextDecoration.LineThrough)
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, fontSize = 20.sp)
            Text(
                text = hint,
                fontSize = 14.sp,
                color = AkColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = AkColors.TextDim,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Buton text compact, pentru acțiuni (Salvează, Actualizează…). */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AkColors.Panel,
            contentColor = if (danger) AkColors.Live else AkColors.Text,
            focusedContainerColor = if (danger) AkColors.Live else AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
            disabledContainerColor = AkColors.Panel,
            disabledContentColor = AkColors.TextDim,
        ),
    ) {
        Text(text = text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
