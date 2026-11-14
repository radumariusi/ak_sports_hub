package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
        modifier = modifier.focusRestorer().focusGroup(),
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
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
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
            }
            if (trailing != null) trailing()
        }
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
    val color = when (quality) {
        Quality.UHD -> Color(0xFFF2C94C)
        Quality.FHD -> AkColors.Accent
        Quality.HD -> Color(0xFF56B6F7)
        Quality.SD -> Color(0xFFB0B7C3)
        Quality.UNKNOWN -> Color(0xFF6B7280)
    }
    Badge(text = quality.label, color = color)
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
