package ro.ak.sportshub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.darkColorScheme
import ro.ak.sportshub.R

/**
 * Identitatea „Reflector”: negru-albastru ca un stadion noaptea, chihlimbar ca lumina reflectoarelor.
 * Rândul focusat e alb cu ramă chihlimbar; calitatea se vede după luminozitate (4K plin chihlimbar, FHD plin alb,
 * HD contur, SD doar text).
 */
object AkColors {
    val Background = Color(0xFF0B0F14)
    val Panel = Color(0xFF151B24)
    val Divider = Color(0xFF1E2631)
    val Focus = Color(0xFFEEF1F4)
    val OnFocus = Color(0xFF0B0F14)
    val Text = Color(0xFFEEF1F4)
    val TextDim = Color(0xFF9AA4B1)
    /** Textul secundar pe rândul focusat (alb). */
    val TextDimOnFocus = Color(0xFF3A4452)
    val Accent = Color(0xFFFFB547)
    /** Chihlimbarul pe fundal alb (stea, accente pe rândul focusat). */
    val AccentOnFocus = Color(0xFFB87A0F)
    val Live = Color(0xFFFF4D4F)
    /** Roșul „live” pe fundal alb. */
    val LiveOnFocus = Color(0xFFD92D30)
}

/** Literele: Barlow pentru text, Barlow Condensed (înguste) pentru titluri, ore, scoruri și etichete. */
object AkFonts {
    val Body = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_semibold, FontWeight.Bold),
    )
    val Condensed = FontFamily(
        Font(R.font.barlowcondensed_medium, FontWeight.Normal),
        Font(R.font.barlowcondensed_medium, FontWeight.Medium),
        Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlowcondensed_bold, FontWeight.Bold),
    )
}

private val Scheme = darkColorScheme(
    primary = AkColors.Accent,
    onPrimary = AkColors.OnFocus,
    background = AkColors.Background,
    onBackground = AkColors.Text,
    surface = AkColors.Background,
    onSurface = AkColors.Text,
    surfaceVariant = AkColors.Panel,
    onSurfaceVariant = AkColors.TextDim,
    border = AkColors.Accent,
)

@Composable
fun AkTheme(textScale: Float = TextScale.DEFAULT, content: @Composable () -> Unit) {
    // Mărimea textului (setare): toate mărimile în sp se scalează, ca listele să fie mai strânse sau mai mari.
    val density = LocalDensity.current
    MaterialTheme(colorScheme = Scheme) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = AkFonts.Body)),
            LocalDensity provides Density(density.density, density.fontScale * textScale),
        ) {
            // Un Box simplu, nu Surface din tv-material: acela pune tot ecranul într-un strat separat, desenat în plus la
            // fiecare cadru (liste sacadate, 4K sacadat în TextureView), iar SurfaceView-ul playerului („Imagine
            // fluidă”) nu mai poate face „gaura” prin fereastră, deci rămânea negru, doar cu sunet.
            CompositionLocalProvider(LocalContentColor provides AkColors.Text) {
                Box(modifier = Modifier.fillMaxSize().background(AkColors.Background)) {
                    content()
                }
            }
        }
    }
}

/** Mărimile textului din setări (Afișare → Mărimea textului). */
object TextScale {
    val options: List<Pair<String, Float>> = listOf("Mic" to 0.85f, "Compact" to 0.92f, "Normal" to 1f)
    const val DEFAULT = 0.92f

    fun label(scale: Float): String = options.minByOrNull { kotlin.math.abs(it.second - scale) }?.first ?: "Compact"

    fun next(scale: Float): Float {
        val i = options.indexOfFirst { kotlin.math.abs(it.second - scale) < 0.001f }
        return options[(i + 1).mod(options.size)].second
    }
}
