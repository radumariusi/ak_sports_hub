package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.theme.AkColors

/** O listă de opțiuni într-o fereastră: săgeți sus/jos, OK alege, Înapoi închide. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    hint: String? = null,
    /** Câteva rânduri scurte sub titlu, mai vizibile decât [hint] (de exemplu „de ce e aici sursa”). */
    details: List<String> = emptyList(),
) {
    val focus = remember { FocusRequester() }
    val start = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val jump = rememberListJump(listState)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Deschisă cu OK lung: tasta e încă apăsată, iar ridicarea ei ar alege prima opțiune.
    // OK/Enter contează abia după o apăsare nouă, făcută în fereastră.
    val armed = remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { e -> heldConfirm(e.nativeKeyEvent, armed) }
                .width(560.dp)
                .background(AkColors.Panel, RoundedCornerShape(10.dp))
                .padding(20.dp),
        ) {
            Text(text = title, fontSize = 20.sp)
            details.forEach { line ->
                Text(text = line, fontSize = 15.sp, color = AkColors.Accent, modifier = Modifier.padding(top = 4.dp))
            }
            if (hint != null) {
                Text(text = hint, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(contentPadding = ListPadding,
                state = listState,
                modifier = Modifier.padding(top = 12.dp).heightIn(max = 420.dp).then(jump.modifier(options.size, backToTop = false)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(options) { index, option ->
                    ListRow(
                        title = option,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = (if (index == start) Modifier.focusRequester(focus) else Modifier).then(jump.row(index, options.size)),
                        trailing = if (index == selectedIndex) {
                            { Text(text = "✓", fontSize = 16.sp, color = ro.ak.sportshub.ui.components.accentText()) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** true = evenimentul OK/Enter rămas de la apăsarea care a deschis fereastra (se înghite). */
internal fun heldConfirm(e: android.view.KeyEvent, armed: androidx.compose.runtime.MutableState<Boolean>): Boolean {
    val confirm = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        e.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        e.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    if (!confirm) {
        if (e.action == android.view.KeyEvent.ACTION_DOWN) armed.value = true
        return false
    }
    if (armed.value) return false
    if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
        armed.value = true
        return false
    }
    return true
}
