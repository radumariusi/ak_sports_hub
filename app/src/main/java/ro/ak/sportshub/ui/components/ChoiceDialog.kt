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
) {
    val focus = remember { FocusRequester() }
    val start = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(AkColors.Panel, RoundedCornerShape(10.dp))
                .padding(20.dp),
        ) {
            Text(text = title, fontSize = 20.sp)
            if (hint != null) {
                Text(text = hint, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(contentPadding = ListPadding, 
                state = listState,
                modifier = Modifier.padding(top = 12.dp).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(options) { index, option ->
                    ListRow(
                        title = option,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = if (index == start) Modifier.focusRequester(focus) else Modifier,
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
