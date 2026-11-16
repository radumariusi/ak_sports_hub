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
 * Dublu-clic sus / jos într-o listă: sare la primul / ultimul rând. Primul clic mută focusul ca de obicei, al
 * doilea (în [WINDOW_MS]) derulează la capăt și pune focusul pe rândul marcat cu [first] / [last].
 */
class ListJump internal constructor(val state: LazyListState, private val scope: CoroutineScope) {
    val first = FocusRequester()
    val last = FocusRequester()
    private var upAt = 0L
    private var downAt = 0L

    /** Pe `LazyColumn`; [count] = numărul de elemente din listă (pentru derularea la capăt). */
    fun modifier(count: Int): Modifier = Modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.action != KeyEvent.ACTION_DOWN || k.repeatCount != 0) return@onPreviewKeyEvent false
        val now = k.eventTime
        when (k.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val double = now - upAt < WINDOW_MS
                upAt = if (double) 0L else now
                if (double) jump(0, first)
                double
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val double = now - downAt < WINDOW_MS
                downAt = if (double) 0L else now
                if (double) jump((count - 1).coerceAtLeast(0), last)
                double
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

    companion object {
        const val WINDOW_MS = 400L
    }
}

@Composable
fun rememberListJump(state: LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()): ListJump {
    val scope = rememberCoroutineScope()
    return remember(state) { ListJump(state, scope) }
}
