package ro.ak.sportshub.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * Atingerea pe telefon. `Surface` din tv-material răspunde doar la telecomandă (OK), nu și la degete: modificatorul
 * ăsta adaugă atingerea (apăsare = OK, apăsare lungă = OK lung). Înainte de acțiune elementul primește focusul, ca
 * tot ce urmărește focusul (panoul din dreapta din Sports Hub, taburile) să meargă la fel ca pe televizor.
 * Pe televizor nu schimbă nimic: telecomanda nu trimite atingeri.
 *
 * Se pune pe modificatorul dat lui `Surface` (focusul `Surface` e după el în lanț, deci îl prinde [FocusRequester]).
 * Dacă elementul ar trata singur atingerea, el o consumă primul și aici nu se mai declanșează nimic (fără dublă apăsare).
 */
fun Modifier.touchClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = if (!enabled) this else composed {
    val focus = remember { FocusRequester() }
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    this
        .pointerInput(hasLong) {
            detectTapGestures(
                onTap = {
                    runCatching { focus.requestFocus() }
                    click()
                },
                onLongPress = if (hasLong) {
                    {
                        runCatching { focus.requestFocus() }
                        long?.invoke()
                    }
                } else {
                    null
                },
            )
        }
        .focusRequester(focus)
}

/**
 * Adevărat pe telefon / tabletă (ecran tactil și nu Android TV): apar butoanele mici pentru gesturile care pe
 * televizor sunt săgeți (ecranul canalului, mutarea ghidului cu o oră).
 */
@Composable
fun isTouchDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
