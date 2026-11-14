package ro.ak.sportshub.core.common

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O încărcare în curs (un playlist, o sursă EPG, potrivirea canalelor), cu numărătorile ei,
 * ca să se vadă pe ecran cum avansează.
 */
data class LoadTask(
    val key: String,
    /** Ce se încarcă: „Strong 8K”, „EPG · myepg.top”. */
    val title: String,
    /** Pasul: „se descarcă”, „se citesc canalele”… */
    val phase: String,
    val bytes: Long = 0,
    /** Mărimea totală, sau −1 dacă serverul n-a spus-o. */
    val totalBytes: Long = -1,
    /** Numărătorile, în ordinea afișării: „canale” → 12.345. */
    val counts: List<Pair<String, Long>> = emptyList(),
    val startedAt: Long = 0,
) {
    fun withCount(label: String, value: Long): LoadTask {
        val i = counts.indexOfFirst { it.first == label }
        val next = if (i < 0) counts + (label to value) else counts.toMutableList().also { it[i] = label to value }
        return copy(counts = next)
    }

    /** „se descarcă · 45,3 MB din 120,0 MB (38%)” sau „se citesc canalele · 12.345 canale”. */
    fun describe(): String = buildList {
        add(phase)
        if (bytes > 0 || totalBytes > 0) add(LoadFormat.bytes(bytes, totalBytes))
        counts.forEach { (label, value) -> add("${LoadFormat.count(value)} $label") }
    }.joinToString(" · ")

    /** Proporția gata (0..1), dacă se știe. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** Primește progresul unei încărcări (folosit de import, descărcări și EPG). */
interface LoadReporter {
    fun phase(text: String)
    fun bytes(read: Long, total: Long)
    fun count(label: String, value: Long)

    companion object {
        val NONE: LoadReporter = object : LoadReporter {
            override fun phase(text: String) = Unit
            override fun bytes(read: Long, total: Long) = Unit
            override fun count(label: String, value: Long) = Unit
        }
    }
}

/**
 * Încărcările în curs, pentru ecran. Actualizările dese (fiecare canal, fiecare 64 KB) se adună:
 * lista se publică cel mult o dată la [minIntervalMs] pe încărcare, iar schimbarea pasului imediat.
 */
class LoadProgress(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 250,
) {
    private val current = LinkedHashMap<String, LoadTask>()
    private val lastPublish = HashMap<String, Long>()
    private val _tasks = MutableStateFlow<List<LoadTask>>(emptyList())

    val tasks: StateFlow<List<LoadTask>> = _tasks.asStateFlow()

    @Synchronized
    fun start(key: String, title: String, phase: String): LoadReporter {
        current[key] = LoadTask(key, title, phase, startedAt = clock())
        publish(key)
        return reporter(key)
    }

    @Synchronized
    fun finish(key: String) {
        if (current.remove(key) != null) {
            lastPublish.remove(key)
            _tasks.value = current.values.toList()
        }
    }

    /** Rulează [block] ca încărcare vizibilă; dispare din listă la final, chiar și la eroare. */
    inline fun <T> track(key: String, title: String, phase: String, block: (LoadReporter) -> T): T {
        val reporter = start(key, title, phase)
        try {
            return block(reporter)
        } finally {
            finish(key)
        }
    }

    private fun reporter(key: String) = object : LoadReporter {
        override fun phase(text: String) = change(key, force = true) { it.copy(phase = text, bytes = 0, totalBytes = -1) }
        override fun bytes(read: Long, total: Long) = change(key) { it.copy(bytes = read, totalBytes = total) }
        override fun count(label: String, value: Long) = change(key) { it.withCount(label, value) }
    }

    @Synchronized
    private fun change(key: String, force: Boolean = false, f: (LoadTask) -> LoadTask) {
        val task = current[key] ?: return
        current[key] = f(task)
        val now = clock()
        if (force || now - (lastPublish[key] ?: 0L) >= minIntervalMs) publish(key, now)
    }

    private fun publish(key: String, now: Long = clock()) {
        lastPublish[key] = now
        _tasks.value = current.values.toList()
    }
}

object LoadFormat {
    private val ro = Locale.forLanguageTag("ro-RO")

    /** 1234567 → „1.234.567”. */
    fun count(value: Long): String = String.format(ro, "%,d", value)

    /** 47_400_000 → „45,2 MB”. */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(ro, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(ro, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(ro, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun bytes(read: Long, total: Long): String =
        if (total > 0) "${size(read)} din ${size(total)} (${(read * 100 / total).coerceIn(0, 100)}%)" else size(read)
}
