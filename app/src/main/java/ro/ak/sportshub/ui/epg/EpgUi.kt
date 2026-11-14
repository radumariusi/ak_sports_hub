package ro.ak.sportshub.ui.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import ro.ak.sportshub.core.epg.NowNext
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.theme.AkColors

private val ro = Locale.forLanguageTag("ro")
private val hm = SimpleDateFormat("HH:mm", ro)
private val dayName = SimpleDateFormat("EEEE d MMMM", ro)

fun formatTime(ms: Long): String = synchronized(hm) { hm.format(Date(ms)) }

fun formatRange(p: Programme): String = "${formatTime(p.start)}–${formatTime(p.stop)}"

/** „Azi”, „Mâine”, „Ieri” sau ziua cu dată. */
fun formatDay(ms: Long, now: Long = System.currentTimeMillis()): String {
    fun dayIndex(t: Long) = Calendar.getInstance().run {
        timeInMillis = t
        get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
    }
    return when (dayIndex(ms) - dayIndex(now)) {
        0 -> "Azi"
        1 -> "Mâine"
        -1 -> "Ieri"
        else -> synchronized(dayName) { dayName.format(Date(ms)) }.replaceFirstChar { it.uppercase() }
    }
}

/** Textul scurt pentru o listă de canale: „20:00 Știri · apoi 21:00 Film”. */
fun nowNextLine(nn: NowNext?): String? {
    if (nn == null) return null
    val now = nn.now?.let { "${formatTime(it.start)} ${it.title}" }
    val next = nn.next?.let { "apoi ${formatTime(it.start)} ${it.title}" }
    return listOfNotNull(now, next).joinToString(" · ").ifEmpty { null }
}

/** Ora curentă, actualizată la fiecare minut (pentru acum / urmează și progres). */
@Composable
fun rememberMinuteClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(60_000 - t % 60_000 + 50)
        }
    }
    return now
}

/**
 * Programul unui canal ca listă de text, pe zile. Focusul pornește pe emisiunea curentă;
 * descrierea emisiunii cu focus apare sub listă.
 */
@Composable
fun ScheduleList(programmes: List<Programme>, modifier: Modifier = Modifier, focusOnStart: Boolean = true) {
    val now = rememberMinuteClock()
    // Rândurile: titluri de zi și emisiuni.
    val rows = remember(programmes) {
        buildList<Any> {
            var lastDay: String? = null
            for (p in programmes) {
                val day = formatDay(p.start)
                if (day != lastDay) {
                    add(day)
                    lastDay = day
                }
                add(p)
            }
        }
    }
    val currentIndex = remember(rows) {
        rows.indexOfFirst { it is Programme && it.stop > System.currentTimeMillis() }.coerceAtLeast(0)
    }
    var focused by remember(rows) { mutableIntStateOf(currentIndex) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(rows) { if (focusOnStart) runCatching { focus.requestFocus() } }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is String -> SectionLabel(row, modifier = Modifier.padding(top = 6.dp))
                    is Programme -> {
                        val onAir = row.isOnAir(now)
                        ListRow(
                            title = "${formatTime(row.start)}  ${row.title}",
                            subtitle = listOfNotNull(
                                if (onAir) "ACUM" else null,
                                row.subtitle,
                                row.category,
                                formatRange(row),
                            ).joinToString(" · "),
                            selected = onAir,
                            progress = if (onAir) row.progress(now) else null,
                            onClick = { focused = index },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) focused = index }
                                .then(if (index == currentIndex) Modifier.focusRequester(focus) else Modifier),
                        )
                    }
                }
            }
        }
        val selected = rows.getOrNull(focused) as? Programme
        Text(
            text = selected?.description ?: selected?.let { "Fără descriere." } ?: "",
            fontSize = 14.sp,
            color = AkColors.TextDim,
            maxLines = 4,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
