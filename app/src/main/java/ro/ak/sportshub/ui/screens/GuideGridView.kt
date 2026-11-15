package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.epg.GuideGrid
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.FocusBorder
import ro.ak.sportshub.ui.components.LocalOnFocus
import ro.ak.sportshub.ui.components.ReturnFocus
import ro.ak.sportshub.ui.components.VariantQualityBadge
import ro.ak.sportshub.ui.components.dimText
import ro.ak.sportshub.ui.epg.formatDay
import ro.ak.sportshub.ui.epg.formatTime
import ro.ak.sportshub.ui.epg.rememberMinuteClock
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

private val NameWidth = 260.dp
private const val GRID_EPG_VARIANTS = 6

/** Un rând al grilei: un canal (cu variantele lui, dacă gruparea e activă). */
private data class GridRow(val key: String, val shown: ChannelWithPlaylist, val variants: List<ChannelWithPlaylist>)

/**
 * Ghidul în grilă (F6): canalele pe rânduri, [GuideGrid.HOURS] ore pe orizontală, emisiunile ca blocuri cu lățimea
 * după durată și linia lui „acum”. ▲▼ canalele, ◀ ▶ mută ora (◀ de la ora curentă trece la grupuri), OK pornește
 * canalul, OK ținut îl pune la favorite. Programul vine doar din baza EPG locală (nicio cerere la furnizor).
 */
@Composable
internal fun ChannelGrid(
    channels: List<ChannelWithPlaylist>,
    onClick: (ChannelWithPlaylist) -> Unit,
    onLongClick: (ChannelWithPlaylist) -> Unit,
    returnFocus: ReturnFocus,
) {
    val container = appContainer()
    val variantState by container.variantStore.state.collectAsStateWithLifecycle()
    val grouped = variantState.groupVariants
    val now = rememberMinuteClock()
    var shift by rememberSaveable { mutableIntStateOf(0) }
    val from = GuideGrid.windowStart(now, shift)
    val until = from + GuideGrid.SPAN_MS

    val families by produceState(emptyMap<String, List<ChannelWithPlaylist>>(), channels, grouped, variantState) {
        value = if (grouped) container.variants.families(channels.map { it.groupKey }) else emptyMap()
    }
    val rows = remember(channels, grouped, families) {
        if (grouped) {
            ChannelFamilies.collapse(channels) { it.groupKey }.map { f ->
                val all = families[f.key] ?: f.members
                GridRow(f.key, all.first(), all)
            }
        } else {
            channels.map { GridRow(it.id.toString(), it, listOf(it)) }
        }
    }
    val programmes by produceState(emptyMap<Long, List<Programme>>(), rows, from) {
        value = container.epg.programmesWindow(rows.flatMap { r -> r.variants.take(GRID_EPG_VARIANTS).map { it.id } }, from, until)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Capul grilei: ziua (dacă nu e azi) și orele, din jumătate în jumătate de oră.
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDay(from, now) + if (shift > 0) "  ◀" else "",
                fontSize = 13.sp,
                color = AkColors.TextDim,
                modifier = Modifier.width(NameWidth),
            )
            Row(modifier = Modifier.weight(1f)) {
                GuideGrid.ticks(from).forEach { t ->
                    Text(text = formatTime(t), fontSize = 13.sp, fontFamily = AkFonts.Condensed, color = AkColors.TextDim, modifier = Modifier.weight(1f))
                }
            }
            Text(text = "▶", fontSize = 13.sp, color = AkColors.TextDim)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(rows, key = { it.key }) { row ->
                val list = row.variants.take(GRID_EPG_VARIANTS).firstNotNullOfOrNull { programmes[it.id]?.takeIf { p -> p.isNotEmpty() } }.orEmpty()
                GridChannelRow(
                    row = row,
                    blocks = remember(list, from) { GuideGrid.blocks(list, from) },
                    nowFraction = ((now - from).toFloat() / GuideGrid.SPAN_MS).takeIf { it > 0.005f && it < 0.995f },
                    onClick = { returnFocus.clicked(row.key); onClick(row.shown) },
                    onLongClick = { onLongClick(row.shown) },
                    modifier = returnFocus.on(row.key).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionRight -> {
                                if (shift < GuideGrid.MAX_SHIFT_HOURS) shift++
                                true
                            }
                            Key.DirectionLeft -> if (shift > 0) {
                                shift--
                                true
                            } else {
                                false
                            }
                            else -> false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GridChannelRow(
    row: GridRow,
    blocks: List<GuideGrid.Block>,
    nowFraction: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val c = row.shown
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
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
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.width(NameWidth - 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = (c.number?.let { "$it  " } ?: "") + c.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    VariantQualityBadge(c.playlistId, c.name, c.quality)
                }
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        blocks.forEach { b ->
                            val w = b.end - b.start
                            if (w <= 0f) return@forEach
                            val p = b.programme
                            Box(
                                modifier = Modifier
                                    .weight(w)
                                    .fillMaxHeight()
                                    .padding(end = 2.dp)
                                    .background(
                                        when {
                                            p == null -> Color.Transparent
                                            focused -> Color(0x1A0B0F14)
                                            else -> AkColors.Panel
                                        },
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (p != null) {
                                    Text(text = p.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else if (blocks.size == 1) {
                                    Text(text = "Fără program", fontSize = 12.sp, color = dimText())
                                }
                            }
                        }
                    }
                    // Linia lui „acum”.
                    if (nowFraction != null) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(nowFraction))
                            Box(Modifier.width(2.dp).fillMaxHeight().background(AkColors.Accent))
                            Spacer(Modifier.weight(1f - nowFraction))
                        }
                    }
                }
            }
        }
    }
}
