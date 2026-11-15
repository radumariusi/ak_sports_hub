package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.Border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import ro.ak.sportshub.ui.theme.AkFonts

/** Adevărat în conținutul unui rând focusat (fundal alb): etichetele și textele secundare își inversează culorile. */
val LocalOnFocus = compositionLocalOf { false }

/** Textul secundar, potrivit fundalului (rând focusat sau nu). */
@Composable
@ReadOnlyComposable
fun dimText(): Color = if (LocalOnFocus.current) AkColors.TextDimOnFocus else AkColors.TextDim

/** Chihlimbarul, potrivit fundalului (pe alb e mai închis, ca să se vadă). */
@Composable
@ReadOnlyComposable
fun accentText(): Color = if (LocalOnFocus.current) AkColors.AccentOnFocus else AkColors.Accent

/** Rama chihlimbar a elementului focusat. */
internal val FocusBorder = Border(BorderStroke(3.dp, AkColors.Accent), shape = RoundedCornerShape(10.dp))
private val PillFocusBorder = Border(BorderStroke(3.dp, AkColors.Accent), shape = RoundedCornerShape(50))

/**
 * Rând de taburi text, cu litere înguste. Tabul curent e alb cu o linie chihlimbar dedesubt; cel focusat e alb plin.
 * Tabul se schimbă când primește focus, fără să apeși OK.
 */
@Composable
fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    firstTabModifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    /** Crește-l ca focusul să sară pe tabul selectat (de exemplu după ◀ ▶ din conținutul tabului). */
    focusSignal: Int = 0,
) {
    // Când intri în rândul de taburi (din listă sau din bara de sus), focusul vine pe tabul curent, nu pe cel aflat
    // dedesubt: focusRestorer ține minte ultimul tab, iar dacă ecranul a fost refăcut, merge pe tabul selectat.
    val requesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0) runCatching { requesters.getOrNull(selectedIndex)?.requestFocus() }
    }
    Row(
        // Defilează orizontal când taburile nu încap (focusul aduce tabul în vedere).
        modifier = modifier
            .focusRestorer(requesters.getOrElse(selectedIndex) { requesters.firstOrNull() ?: FocusRequester.Default })
            .focusGroup()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                modifier = (if (index == 0) firstTabModifier else Modifier)
                    .focusRequester(requesters[index])
                    .onFocusChanged { if (it.isFocused) onSelect(index) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (selected) AkColors.Text else AkColors.TextDim,
                    focusedContainerColor = AkColors.Focus,
                    focusedContentColor = AkColors.OnFocus,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        fontSize = fontSize,
                        fontFamily = AkFonts.Condensed,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .height(3.dp)
                            .width(28.dp)
                            .background(if (selected) AkColors.Accent else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/**
 * Un rând de listă: text principal, text secundar, conținut la dreapta.
 * Focusat: alb, cu ramă chihlimbar și ușor mărit; conținutul vede [LocalOnFocus] ca să-și inverseze culorile.
 */
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
    /** Coloana din stânga (ora, minutul), cu litere înguste. */
    leading: @Composable (() -> Unit)? = null,
    leadingWidth: Dp = 76.dp,
    titleSize: TextUnit = 17.sp,
    trailing: @Composable (() -> Unit)? = null,
    /** Între coloana din stânga și text: sigle, banda culorilor echipelor. */
    icon: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AkColors.Panel else Color.Transparent,
            contentColor = AkColors.Text,
            focusedContainerColor = AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
        ),
    ) {
        CompositionLocalProvider(LocalOnFocus provides focused) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Box(modifier = Modifier.width(leadingWidth)) { leading() }
                }
                if (icon != null) {
                    Box(modifier = Modifier.padding(end = 10.dp)) { icon() }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = titleSize, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (subtitle != null) {
                        Text(text = subtitle, fontSize = 14.sp, color = dimText(), maxLines = 1, modifier = Modifier.padding(top = 1.dp))
                    }
                    if (progress != null) ProgressLine(progress, Modifier.padding(top = 4.dp).width(160.dp))
                }
                if (trailing != null) trailing()
            }
        }
    }
}

/** O bară subțire de progres (cât s-a scurs din emisiune). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val track = if (LocalOnFocus.current) Color(0x330B0F14) else Color(0x33FFFFFF)
    Box(modifier = modifier.height(3.dp).background(track, RoundedCornerShape(2.dp))) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(accentText(), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * O etichetă plină, cu litere înguste. Eticheta albă ([AkColors.Focus]) devine închisă pe un rând focusat,
 * ca să nu dispară pe fundalul alb.
 */
@Composable
fun Badge(text: String, color: Color = AkColors.Accent, modifier: Modifier = Modifier) {
    val onFocus = LocalOnFocus.current
    val (bg, fg) = when {
        onFocus && color == AkColors.Focus -> AkColors.OnFocus to AkColors.Text
        else -> color to AkColors.OnFocus
    }
    Text(
        text = text,
        color = fg,
        fontSize = 15.sp,
        fontFamily = AkFonts.Condensed,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        modifier = modifier
            .padding(start = 8.dp)
            .background(bg, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp),
    )
}

@Composable
fun QualityBadge(quality: Quality) {
    QualityTag(text = quality.label, quality = quality)
}

/**
 * Eticheta de calitate, după luminozitate: 4K chihlimbar plin, FHD alb plin, HD doar contur, SD doar text.
 * Pe rândul focusat (alb), 4K și FHD trec pe fundal închis.
 */
@Composable
fun QualityTag(text: String, quality: Quality, modifier: Modifier = Modifier) {
    val onFocus = LocalOnFocus.current
    val base = modifier.padding(start = 8.dp)
    val shape = RoundedCornerShape(5.dp)
    val style = Modifier.padding(horizontal = 7.dp)
    when (quality) {
        Quality.UHD -> Text(
            text = text, fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold,
            color = if (onFocus) AkColors.Accent else AkColors.OnFocus,
            modifier = base.background(if (onFocus) AkColors.OnFocus else AkColors.Accent, shape).then(style),
        )
        Quality.FHD -> Text(
            text = text, fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold,
            color = if (onFocus) AkColors.Text else AkColors.OnFocus,
            modifier = base.background(if (onFocus) AkColors.OnFocus else AkColors.Focus, shape).then(style),
        )
        Quality.HD -> Text(
            text = text, fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold,
            color = if (onFocus) AkColors.OnFocus else AkColors.Text,
            modifier = base.border(2.dp, if (onFocus) AkColors.TextDimOnFocus else AkColors.TextDim, shape).padding(horizontal = 5.dp),
        )
        Quality.SD, Quality.UNKNOWN -> Text(
            text = text, fontSize = 15.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold,
            color = dimText(),
            modifier = base.padding(horizontal = 2.dp),
        )
    }
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
    val suspect by container.suspectChannels.keys.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Semnalat din player: rămâne sursă, doar cu eticheta asta.
        if (ro.ak.sportshub.data.CorrectionKey(playlistId, name) in suspect) {
            Text(text = "canal posibil greșit", fontSize = 13.sp, color = accentText(), maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 6.dp))
        }
        QualityTag(text = view.label + if (view.verified) " ✓" else "", quality = view.quality)
        view.playlistLabel?.let { old ->
            Text(text = ", în playlist ", fontSize = 13.sp, color = dimText(), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 4.dp))
            Text(text = old, fontSize = 13.sp, color = dimText(), maxLines = 1, softWrap = false, textDecoration = TextDecoration.LineThrough)
        }
    }
}

/** Sigla: patru reflectoare chihlimbar deasupra literelor „AK”. */
@Composable
fun AkLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).background(AkColors.Panel, RoundedCornerShape(size * 0.23f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = this.size.width / 96f
            // Reflectoarele, înclinate spre centru (ca în pictograma aplicației).
            listOf(Triple(20f, 23f, -24f), Triple(38f, 16f, -8f), Triple(58f, 16f, 8f), Triple(76f, 23f, 24f)).forEach { (cx, cy, angle) ->
                rotate(angle, pivot = Offset(cx * u, cy * u)) {
                    drawRoundRect(
                        color = AkColors.Accent,
                        topLeft = Offset((cx - 6f) * u, (cy - 3.5f) * u),
                        size = Size(12f * u, 7f * u),
                        cornerRadius = CornerRadius(2f * u),
                    )
                }
            }
        }
        Text(
            text = "AK",
            fontSize = (size.value * 0.46f).sp,
            fontFamily = AkFonts.Condensed,
            fontWeight = FontWeight.Bold,
            color = AkColors.Text,
            modifier = Modifier.padding(bottom = size * 0.06f),
        )
    }
}

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, fontSize = 26.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.SemiBold)
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
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = AkColors.TextDim,
        maxLines = 1,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Buton text compact, în formă de pastilă, pentru acțiuni și filtre (Salvează, Actualizează…). */
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = PillFocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AkColors.Panel,
            contentColor = if (danger) AkColors.Live else AkColors.Text,
            focusedContainerColor = if (danger) AkColors.Live else AkColors.Focus,
            focusedContentColor = AkColors.OnFocus,
            disabledContainerColor = AkColors.Panel,
            disabledContentColor = AkColors.TextDim,
        ),
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

/**
 * Întoarcerea pe rândul de unde ai plecat: la Back de pe alt ecran (canal, meci, player), focusul revine pe
 * rândul apăsat, nu pe primul element (care, în bara de sus, ar schimba secțiunea). Cheia rândului se ține în
 * starea salvată a ecranului, deci rezistă și când Android refăcea ecranul.
 */
class ReturnFocus internal constructor(private val saved: androidx.compose.runtime.MutableState<String?>) {
    internal val requester = FocusRequester()

    /** Adevărat după întoarcerea pe ecran, dacă un rând a fost apăsat (focusul inițial al ecranului se sare). */
    val returning: Boolean get() = saved.value != null

    /** Se cheamă la apăsarea rândului, înainte de a pleca pe alt ecran. */
    fun clicked(key: String) {
        saved.value = key
    }

    /** Modificatorul rândului cu cheia [key] (doar rândul ținut minte primește cererea de focus). */
    fun on(key: String): Modifier = if (saved.value == key) Modifier.focusRequester(requester) else Modifier
}

@Composable
fun rememberReturnFocus(): ReturnFocus {
    val saved = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val rf = remember { ReturnFocus(saved) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (saved.value == null) return@LaunchedEffect
        // Lista apare după câteva cadre (datele vin din bază): încearcă până la ~1,5 s.
        repeat(90) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { rf.requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    return rf
}

/**
 * Marginea listelor: rândul focusat e ușor mărit și are ramă, iar fără loc în jur primul (și ultimul) rând
 * apărea tăiat sus.
 */
val ListPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
