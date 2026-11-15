package ro.ak.sportshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.theme.AkColors

/**
 * Câmp de text într-o fereastră separată. Pe televizor e mai sigur așa: în liste, săgețile
 * nu intră niciodată într-un câmp de text (care ar deschide tastatura); tastatura apare doar
 * după ce apeși OK pe rândul pe care vrei să-l modifici.
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    hint: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * Sugestii pentru ce scrii (lista de favorite: ultimul nume de după virgulă). Alegerea înlocuiește numele început
     * cu cel întreg și adaugă „, ” pentru următorul.
     */
    suggestions: ((String) -> List<Pair<String, String?>>)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(AkColors.Panel, RoundedCornerShape(10.dp))
                .padding(24.dp),
        ) {
            Text(text = title, fontSize = 20.sp)
            if (hint != null) {
                Text(text = hint, fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = AkColors.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(AkColors.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text.trim()) }),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .border(2.dp, AkColors.Accent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            if (suggestions != null) {
                val typed = value.text.substringAfterLast(',').trim()
                val found = remember(typed) { if (typed.length >= 2) suggestions(typed) else emptyList() }
                if (found.isNotEmpty()) {
                    Text(text = "Sugestii (↓ și OK pentru a alege)", fontSize = 13.sp, color = AkColors.TextDim, modifier = Modifier.padding(top = 10.dp))
                    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        found.forEach { (name, detail) ->
                            ListRow(
                                title = name,
                                subtitle = detail,
                                titleSize = 16.sp,
                                onClick = {
                                    val head = value.text.substringBeforeLast(',', "").trim()
                                    val text = (if (head.isEmpty()) "" else "$head, ") + name + ", "
                                    value = TextFieldValue(text, TextRange(text.length))
                                    runCatching { focus.requestFocus() }
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(text = "Gata", onClick = { onConfirm(value.text.trim()) })
                ActionButton(text = "Renunță", onClick = onDismiss)
            }
        }
    }
}
