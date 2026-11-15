package ro.ak.sportshub.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ro.ak.sportshub.core.common.DisplayModeInfo
import ro.ak.sportshub.core.common.RefreshRateMatch

/**
 * Potrivirea frecvenței ecranului cu cadrele stream-ului (50 Hz pentru 25/50p, 60 Hz pentru 30/60p), ca la sport
 * mișcarea să fie fluidă. Se cere modul de afișare al ferestrei (`preferredDisplayModeId`), cu aceeași rezoluție;
 * la ieșirea din player (sau dacă oprești setarea) ecranul revine la ce era. Unele televizoare se înnegresc
 * o secundă la schimbare: de aceea e o setare, oprită implicit.
 */
@Composable
fun FrameRateMatch(frameRate: Float?, enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val original = remember { activity.window.attributes.preferredDisplayModeId }
    LaunchedEffect(frameRate, enabled) {
        if (!enabled) {
            setMode(activity, original)
            return@LaunchedEffect
        }
        val display = activity.window.decorView.display ?: return@LaunchedEffect
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val current = display.mode.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        RefreshRateMatch.pick(modes, current, frameRate)?.let { setMode(activity, it.id) }
    }
    DisposableEffect(activity) { onDispose { setMode(activity, original) } }
}

private fun setMode(activity: Activity, id: Int) {
    val window = activity.window
    val attrs = window.attributes
    if (attrs.preferredDisplayModeId == id) return
    attrs.preferredDisplayModeId = id
    window.attributes = attrs
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
