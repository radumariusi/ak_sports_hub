package ro.ak.sportshub.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme

object AkColors {
    val Background = Color(0xFF0E1116)
    val Panel = Color(0xFF171B22)
    val Focus = Color(0xFFE8EAED)
    val OnFocus = Color(0xFF0E1116)
    val Text = Color(0xFFE8EAED)
    val TextDim = Color(0xFF9AA3AF)
    val Accent = Color(0xFF35C46A)
    val Live = Color(0xFFE5484D)
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
)

@Composable
fun AkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            colors = SurfaceDefaults.colors(
                containerColor = AkColors.Background,
                contentColor = AkColors.Text,
            ),
        ) {
            content()
        }
    }
}
