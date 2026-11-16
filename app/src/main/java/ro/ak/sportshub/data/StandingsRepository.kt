package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.sportshub.core.sports.Bracket
import ro.ak.sportshub.core.sports.Brackets
import ro.ak.sportshub.core.sports.Espn
import ro.ak.sportshub.core.sports.Standings
import ro.ak.sportshub.core.sports.TableGroup

/** Clasamentul unei ligi, cu starea cererii. */
data class StandingsState(
    val groups: List<TableGroup> = emptyList(),
    val loading: Boolean = false,
    /** Ce se cere acum sau ultima problemă; null dacă nimic. */
    val status: String? = null,
    /** Când s-a descărcat clasamentul arătat (0: niciodată). */
    val fetchedAt: Long = 0,
)

/** Tabloul unei cupe sau al unui Grand Slam (A6), cu starea cererii. */
data class BracketState(
    val bracket: Bracket? = null,
    val loading: Boolean = false,
    val status: String? = null,
    val fetchedAt: Long = 0,
)

/**
 * Clasamentele (F3), de la ESPN: cerute doar cât e deschis tabul „Clasamente”, câte o ligă (cea aleasă), cel mult
 * o dată la [MAX_AGE_MS]; răspunsul se păstrează în fișier, iar o eroare nu șterge clasamentul vechi. Nu trec prin
 * `ConnectionGate` (nu e furnizorul de IPTV) și nu se cer cât rulează playerul (tabul nu e pe ecran atunci).
 * Schimbarea de loc (▲/▼): de la ESPN, altfel față de clasamentul de dinaintea ultimei schimbări, ținut minte aici.
 */
class StandingsRepository(context: Context, private val http: OkHttpClient, private val log: DiagnosticLog? = null) {

    private val dir = File(context.noBackupFilesDir, "sports/standings")
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, StandingsState>>(emptyMap())
    val states: StateFlow<Map<String, StandingsState>> = _states.asStateFlow()

    suspend fun load(league: Espn.League, force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val file = File(dir, "${league.key}.json")
            if (_states.value[league.key]?.groups.isNullOrEmpty() && file.exists()) set(league) { read(league, file) }
            val age = System.currentTimeMillis() - file.lastModifiedOrZero()
            if (!force && file.exists() && age < MAX_AGE_MS) return@withContext
            set(league) { it.copy(loading = true, status = "Se cere clasamentul (ESPN)…") }
            val error = runCatching {
                http.newCall(Request.Builder().url(Standings.url(league)).header("Accept", "application/json").build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching "ESPN a răspuns ${r.code}."
                    val body = r.body?.string().orEmpty()
                    val groups = Standings.parse(body, league.sport)
                    if (groups.isEmpty()) return@runCatching "ESPN nu are clasament pentru competiția asta."
                    noteRanks(league, groups)
                    val tmp = File(dir, "${league.key}.json.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    null
                }
            }.getOrElse { "Fără conexiune la ESPN (${it.javaClass.simpleName})." }
            log?.log("clasament", "${league.key}: " + (error ?: "ok"))
            set(league) { read(league, file).copy(loading = false, status = error?.let { e -> if (file.exists()) "$e Se arată clasamentul salvat." else e }) }
        }
    }

    private val _brackets = MutableStateFlow<Map<String, BracketState>>(emptyMap())
    val brackets: StateFlow<Map<String, BracketState>> = _brackets.asStateFlow()

    /**
     * A6: tabloul cupei (programul ESPN pe ani întregi, `Brackets.years`) sau al Grand Slam-ului în curs (programul
     * zilei ATP/WTA). Cerut doar la alegerea tabloului, cel mult o dată la [MAX_AGE_MS]; răspunsurile se păstrează
     * comprimate, iar o eroare nu șterge tabloul vechi.
     */
    suspend fun loadBracket(league: Espn.League, force: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tennis = league.key in Brackets.TENNIS
            val files: List<Pair<String, File>> = if (tennis) {
                listOf(Brackets.dayUrl(league, Espn.today()) to File(dir, "${league.key}-tablou.json.gz"))
            } else {
                Brackets.years(System.currentTimeMillis()).map { y -> Brackets.yearUrl(league, y) to File(dir, "${league.key}-tablou-$y.json.gz") }
            }
            fun readAll(): BracketState {
                val bodies = files.mapNotNull { (_, f) -> if (f.exists()) runCatching { gunzip(f) }.getOrNull() else null }
                if (bodies.isEmpty()) return BracketState()
                val bracket = runCatching {
                    if (tennis) Brackets.parseTennis(bodies.first(), men = league.key == "ATP") else Brackets.parseCup(bodies, league.competition)
                }.getOrNull()
                return BracketState(bracket, fetchedAt = files.maxOf { it.second.lastModifiedOrZero() })
            }
            if (_brackets.value[league.key]?.bracket == null) readAll().let { saved -> setBracket(league) { saved } }
            val oldest = files.minOf { it.second.lastModifiedOrZero() }
            if (!force && oldest > 0 && System.currentTimeMillis() - oldest < MAX_AGE_MS) return@withContext
            setBracket(league) { it.copy(loading = true, status = "Se cere tabloul (ESPN)…") }
            var error: String? = null
            for ((url, file) in files) {
                val e = runCatching {
                    http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching "ESPN a răspuns ${r.code}."
                        val body = r.body?.string().orEmpty()
                        if (!body.trimStart().startsWith("{") || "\"events\"" !in body) return@runCatching "ESPN a dat un răspuns fără meciuri."
                        val tmp = File(dir, file.name + ".tmp")
                        java.util.zip.GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(body) }
                        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                        null
                    }
                }.getOrElse { "Fără conexiune la ESPN (${it.javaClass.simpleName})." }
                if (e != null) error = e
            }
            log?.log("clasament", "${league.key} tablou: " + (error ?: "ok"))
            val read = readAll()
            setBracket(league) {
                val status = when {
                    error != null && read.bracket != null -> "$error Se arată tabloul salvat."
                    error != null -> error
                    read.bracket == null && tennis -> "Niciun Grand Slam în desfășurare acum."
                    read.bracket == null -> "Faza eliminatorie n-a început încă."
                    else -> null
                }
                read.copy(loading = false, status = status)
            }
        }
    }

    private fun gunzip(f: File): String = java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }

    private fun setBracket(league: Espn.League, transform: (BracketState) -> BracketState) =
        _brackets.update { it + (league.key to transform(it[league.key] ?: BracketState())) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TableGroup>>>()

    /** Clasamentul salvat al ligii (fără cerere), pentru Pre-meci; citit din nou doar dacă fișierul s-a schimbat. */
    fun cached(league: Espn.League): List<TableGroup> {
        val file = File(dir, "${league.key}.json")
        if (!file.exists()) return emptyList()
        val stamp = file.lastModified()
        cache[league.key]?.let { (at, groups) -> if (at == stamp) return groups }
        val groups = runCatching { Standings.parse(file.readText(), league.sport) }.getOrDefault(emptyList())
        cache[league.key] = stamp to groups
        return groups
    }

    private fun read(league: Espn.League, file: File): StandingsState {
        if (!file.exists()) return StandingsState()
        val groups = runCatching { Standings.parse(file.readText(), league.sport) }.getOrDefault(emptyList())
        return StandingsState(Standings.withChanges(groups, ranks(File(dir, "${league.key}.prev"))), fetchedAt = file.lastModified())
    }

    /** Clasamentul s-a schimbat: cel de până acum devine „anteriorul” pentru ▲/▼. */
    private fun noteRanks(league: Espn.League, groups: List<TableGroup>) {
        val now = Standings.ranks(groups)
        val currentFile = File(dir, "${league.key}.ranks")
        val current = ranks(currentFile)
        if (current == now) return
        if (current.isNotEmpty()) write(File(dir, "${league.key}.prev"), current)
        write(currentFile, now)
    }

    private fun ranks(f: File): Map<String, Int> = if (!f.exists()) emptyMap() else runCatching {
        f.readLines().mapNotNull { line ->
            val at = line.lastIndexOf('\t')
            if (at <= 0) null else line.substring(at + 1).toIntOrNull()?.let { line.substring(0, at) to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun write(f: File, ranks: Map<String, Int>) {
        runCatching { f.writeText(ranks.entries.joinToString("\n") { "${it.key.replace('\t', ' ')}\t${it.value}" }) }
    }

    private fun set(league: Espn.League, transform: (StandingsState) -> StandingsState) =
        _states.update { it + (league.key to transform(it[league.key] ?: StandingsState())) }

    private fun File.lastModifiedOrZero(): Long = if (exists()) lastModified() else 0L

    private companion object {
        val MAX_AGE_MS: Long = TimeUnit.HOURS.toMillis(6)
    }
}
