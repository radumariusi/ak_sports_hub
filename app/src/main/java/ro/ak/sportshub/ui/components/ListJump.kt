package ro.ak.sportshub.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Saltul la capetele unei liste (Radu: dublu-clicul se făcea din greșeală când derula repede):
 * - Back, cât lista nu e la început, duce la primul rând; abia al doilea Back face ce făcea înainte (ca pe YouTube).
 * - CH+ / Page Up / ⏪ → primul rând, CH− / Page Down / ⏩ → ultimul rând, dacă telecomanda are tastele.
 */
class ListJump internal constructor(val state: LazyListState, private val scope: CoroutineScope) {
    val first = FocusRequester()
    val last = FocusRequester()
    private var backConsumed = false

    /**
     * Pe `LazyColumn`; [count] = numărul de elemente din listă (pentru derularea la capăt). [backToTop] = Back urcă
     * întâi la primul rând (nu în ferestrele de alegere, unde Back o închide; nu cât rulează altceva care ia Back).
     */
    fun modifier(count: Int, backToTop: Boolean = true): Modifier = Modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        when (k.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (k.action == KeyEvent.ACTION_UP) {
                    // Ridicarea tastei aparține apăsării pe care am folosit-o, ca Back să nu închidă și ecranul.
                    val mine = backConsumed
                    backConsumed = false
                    return@onPreviewKeyEvent mine
                }
                if (k.action != KeyEvent.ACTION_DOWN || k.repeatCount != 0) return@onPreviewKeyEvent backConsumed
                val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
                if (backToTop && !atTop) {
                    backConsumed = true
                    jump(0, first)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MOVE_HOME -> {
                if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) jump(0, first)
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MOVE_END -> {
                if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                    jump(((state.layoutInfo.totalItemsCount.takeIf { it > 0 } ?: count) - 1).coerceAtLeast(0), last)
                }
                true
            }
            else -> false
        }
    }

    private fun jump(index: Int, target: FocusRequester) {
        scope.launch {
            runCatching { state.scrollToItem(index) }
            // Rândul abia intră în compoziție: un cadru mai târziu poate primi focusul.
            androidx.compose.runtime.withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }

    /** Modificatorul rândului de pe poziția [index] din [size] (primul / ultimul primesc ținta saltului). */
    fun row(index: Int, size: Int): Modifier = when {
        size <= 0 -> Modifier
        index == 0 && size == 1 -> Modifier.focusRequester(first).focusRequester(last)
        index == 0 -> Modifier.focusRequester(first)
        index == size - 1 -> Modifier.focusRequester(last)
        else -> Modifier
    }

}

@Composable
fun rememberListJump(state: LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()): ListJump {
    val scope = rememberCoroutineScope()
    return remember(state) { ListJump(state, scope) }
}
