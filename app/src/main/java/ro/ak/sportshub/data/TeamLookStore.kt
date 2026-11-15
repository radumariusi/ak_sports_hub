package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.TeamLookBook

/**
 * Siglele, culorile și forma echipelor ([TeamLookBook]), învățate din programul furnizorilor la fiecare calcul al
 * listei Sports Hub (fără nicio cerere în plus) și păstrate în `noBackupFilesDir/aspect-echipe.txt`, ca la pornire
 * lista să arate siglele imediat. Fișierul se rescrie doar când se schimbă ceva.
 */
class TeamLookStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.noBackupFilesDir, "aspect-echipe.txt")
    private val _book = MutableStateFlow(TeamLookBook())
    val book: StateFlow<TeamLookBook> = _book.asStateFlow()

    private var loaded = false

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { TeamLookBook.decode(file.takeIf { it.exists() }?.readText()) }.getOrNull()?.let { _book.value = it }
    }

    /** Învață din programul citit; apelat din calculul listei (deja pe un fir de fundal). */
    @Synchronized
    fun learn(events: Collection<SportEvent>) {
        ensureLoaded()
        val old = _book.value
        val next = runCatching { old.learn(events) }.getOrNull() ?: return
        if (next === old) return
        _book.value = next
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(next.encode())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}
