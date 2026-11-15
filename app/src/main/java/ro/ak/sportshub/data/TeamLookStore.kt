package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.sports.NameCatalog
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.TeamNames
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

    /** Catalogul de nume pentru favorite (echipe, sportivi, competiții), în `nume-catalog.txt`. */
    private val catalogFile = File(context.noBackupFilesDir, "nume-catalog.txt")
    private val _catalog = MutableStateFlow(NameCatalog.EMPTY)
    val catalog: StateFlow<NameCatalog> = _catalog.asStateFlow()

    private var loaded = false

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { TeamLookBook.decode(file.takeIf { it.exists() }?.readText()) }.getOrNull()?.let { _book.value = it }
        runCatching { NameCatalog.decode(catalogFile.takeIf { it.exists() }?.readText()) }.getOrNull()?.let {
            _catalog.value = it
            TeamNames.setLearnedAliases(it.teamAliases())
        }
    }

    /** Învață din programul citit; apelat din calculul listei (deja pe un fir de fundal). */
    @Synchronized
    fun learn(events: Collection<SportEvent>) {
        ensureLoaded()
        val oldCatalog = _catalog.value
        val nextCatalog = runCatching { oldCatalog.learn(events) }.getOrNull()
        if (nextCatalog != null && nextCatalog !== oldCatalog) {
            _catalog.value = nextCatalog
            TeamNames.setLearnedAliases(nextCatalog.teamAliases())
            write(catalogFile, nextCatalog.encode())
        }
        val old = _book.value
        val next = runCatching { old.learn(events) }.getOrNull() ?: return
        if (next === old) return
        _book.value = next
        write(file, next.encode())
    }

    private fun write(target: File, text: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(target.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            }
        }
    }
}
