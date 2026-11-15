package ro.ak.sportshub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/** Un grup de setări: numele din stânga, un rezumat scurt și rândurile din dreapta. */
class SettingsSection(
    val title: String,
    val summary: String? = null,
    val content: LazyListScope.() -> Unit,
)

/**
 * Setările pe două coloane: grupurile în stânga (▲▼ le schimbă, conținutul apare imediat), rândurile grupului în
 * dreapta (▶ intră în ele). În loc de o listă lungă de derulat, câteva rânduri pe grup. La întoarcere, focusul
 * intră pe grupul ales.
 */
@Composable
fun SettingsPane(sections: List<SettingsSection>, modifier: Modifier = Modifier, header: LazyListScope.() -> Unit = {}) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, (sections.size - 1).coerceAtLeast(0))
    val requesters = remember(sections.size) { List(sections.size) { FocusRequester() } }
    Row(modifier = modifier.fillMaxSize()) {
        LazyColumn(contentPadding = ListPadding, 
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .focusRestorer(requesters.getOrElse(index) { FocusRequester.Default }),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(sections, key = { _, s -> s.title }) { i, s ->
                ListRow(
                    title = s.title,
                    subtitle = s.summary,
                    selected = i == index,
                    onClick = { selected = i },
                    modifier = Modifier
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) selected = i },
                    trailing = { if (i == index) androidx.tv.material3.Text(text = "›") },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val section = sections.getOrNull(index)
            LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                header()
                if (section != null) {
                    item(key = "titlu-" + section.title) { SectionLabel(section.title) }
                    section.content(this)
                }
            }
        }
    }
}
