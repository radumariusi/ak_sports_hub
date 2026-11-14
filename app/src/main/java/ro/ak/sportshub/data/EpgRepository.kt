package ro.ak.sportshub.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.ak.sportshub.core.common.LoadFormat
import ro.ak.sportshub.core.common.LoadProgress
import ro.ak.sportshub.core.common.LoadReporter
import ro.ak.sportshub.core.common.LoadTask
import ro.ak.sportshub.core.common.ConnectionGate
import ro.ak.sportshub.core.common.CountingInputStream
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.EpgChannelEntity
import ro.ak.sportshub.core.database.EpgDatabase
import ro.ak.sportshub.core.database.EpgProgrammeRow
import ro.ak.sportshub.core.database.EpgMapEntity
import ro.ak.sportshub.core.database.EpgSourceState
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.core.database.ProgrammeEntity
import ro.ak.sportshub.core.epg.ChannelForEpg
import ro.ak.sportshub.core.epg.EpgChannelRef
import ro.ak.sportshub.core.epg.EpgException
import ro.ak.sportshub.core.epg.EpgMatcher
import ro.ak.sportshub.core.epg.EpgSource
import ro.ak.sportshub.core.epg.EpgSources
import ro.ak.sportshub.core.epg.NowNext
import ro.ak.sportshub.core.epg.PlaylistEpgInfo
import ro.ak.sportshub.core.epg.Programme
import ro.ak.sportshub.core.epg.WantedKeys
import ro.ak.sportshub.core.epg.XmltvChannel
import ro.ak.sportshub.core.epg.XmltvParser
import ro.ak.sportshub.core.epg.XmltvProgramme
import ro.ak.sportshub.core.epg.XmltvSink
import ro.ak.sportshub.core.epg.XmltvStats
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.core.playlist.CacheTag
import ro.ak.sportshub.core.playlist.PlaylistDownloader
import ro.ak.sportshub.core.playlist.PlaylistException

/** Spațiul ocupat pe televizor, în octeți. */
data class DiskUsage(val epgFiles: Long, val epgDatabase: Long, val channelsDatabase: Long) {
    val total: Long get() = epgFiles + epgDatabase + channelsDatabase
}

/**
 * EPG-ul: descărcare (din playlist, de la furnizorul Xtream sau extern), citire XMLTV în flux,
 * potrivire canal ↔ EPG și interogări pentru „acum / urmează” și programul canalului.
 *
 * Reguli:
 * - fișierul descărcat se păstrează, ca o schimbare de playlist să poată reciti EPG-ul fără rețea;
 * - sursele de pe serverul furnizorului țin permisul playlistului din [ConnectionGate]; în fundal se
 *   amână dacă playlistul e ocupat, iar playerul le poate opri ([yieldConnection]);
 * - un fișier stricat sau o pagină de eroare nu șterge programul vechi.
 */
class EpgRepository(
    context: Context,
    private val db: AkDatabase,
    private val epgDb: EpgDatabase,
    private val settings: EpgSettingsStore,
    private val downloader: PlaylistDownloader,
    private val gate: ConnectionGate,
    private val scope: CoroutineScope,
    /** Încărcările în curs, cu numărătorile lor (pentru ecran). */
    private val progress: LoadProgress = LoadProgress(),
) {
    private val dao = epgDb.epg()
    private val dir = File(context.noBackupFilesDir, "epg")
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val pending = AtomicBoolean(false)
    private val downloads = ConcurrentHashMap<Long, Job>()
    private val _status = MutableStateFlow<String?>(null)

    /** Ce face EPG-ul acum („Se descarcă…”), sau null. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun observeSources(): Flow<List<EpgSource>> =
        combine(db.playlists().observeAll(), settings.settings) { playlists, s -> EpgSources.resolve(playlists.map(::info), s) }

    fun observeStates(): Flow<List<EpgSourceState>> = dao.observeStates()

    /** Încărcările în curs (playlisturi, surse EPG, potrivire), cu numărătorile lor. */
    val loads: StateFlow<List<LoadTask>> = progress.tasks

    /** Cât ocupă pe televizor fișierele EPG păstrate și cele două baze de date. */
    suspend fun diskUsage(): DiskUsage = withContext(Dispatchers.IO) {
        fun database(name: String): Long {
            val main = appContext.getDatabasePath(name)
            return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal")).sumOf { it.length() }
        }
        DiskUsage(
            epgFiles = dir.listFiles()?.sumOf { it.length() } ?: 0L,
            epgDatabase = database("ak-epg.db"),
            channelsDatabase = database("ak-sports-hub.db"),
        )
    }

    /** Câte canale din playlisturi au program. */
    fun observeMappedCount(): Flow<Int> = dao.observeMappedCount()

    /** Playerul are nevoie de conexiunea playlistului: o descărcare EPG în curs de pe același server se oprește. */
    fun yieldConnection(playlistId: Long) {
        downloads[playlistId]?.cancel()
    }

    /** Actualizare pornită de tine: descarcă din nou (sau doar sursa [onlyKey]). */
    fun refreshInBackground(onlyKey: String? = null) {
        scope.launch { refresh(manual = true, onlyKey = onlyKey) }
    }

    /** Actualizarea programată: doar sursele al căror interval a trecut. */
    suspend fun refreshDue() = refresh(manual = false)

    /**
     * Playlisturile s-au schimbat (import, ștergere, activare): se recitește EPG-ul din fișierele salvate
     * pentru canalele noi și se refac potrivirile. Mai multe schimbări la rând se adună într-o singură rulare.
     */
    fun onPlaylistsChanged() {
        if (!pending.compareAndSet(false, true)) return
        scope.launch { refresh(manual = false) }
    }

    /** Șterge datele unei surse externe eliminate (fișier, program, potriviri). */
    fun onSourcesChanged() = onPlaylistsChanged()

    suspend fun refresh(manual: Boolean, onlyKey: String? = null) = mutex.withLock {
        pending.set(false)
        try {
            withContext(Dispatchers.IO) { doRefresh(manual, onlyKey) }
        } finally {
            _status.value = null
        }
    }

    private suspend fun doRefresh(manual: Boolean, onlyKey: String?) {
        val policy = settings.current.policy
        val sources = EpgSources.resolve(db.playlists().all().map(::info), settings.current)
        val active = sources.filter { it.enabled }

        // Sursele scoase sau oprite nu mai au program, potriviri sau fișiere.
        val keys = active.map { it.key }
        if (keys.isEmpty()) {
            dao.deleteAllChannels()
            dao.deleteAllStates()
        } else {
            dao.deleteChannelsExcept(keys)
            dao.deleteStatesExcept(keys)
        }
        dir.mkdirs()
        dir.listFiles()?.forEach { f -> if (keys.none { f.name.startsWith("$it.") }) f.delete() }

        val wanted = loadWanted()
        val now = System.currentTimeMillis()
        val todo = if (wanted.isEmpty) emptyList() else active.filter { onlyKey == null || it.key == onlyKey }
        // Potrivirea se reface după fiecare sursă: programul primei surse apare imediat,
        // fără să aștepte un EPG extern mare care încă se descarcă sau se citește.
        for (s in todo) {
            currentCoroutineContext().ensureActive()
            updateSource(s, wanted, manual, now)
            rematch(sources)
        }
        if (todo.isEmpty()) rematch(sources)
        dao.deleteEndedBefore(now - TimeUnit.HOURS.toMillis(policy.hoursBack.toLong()))
    }

    private suspend fun updateSource(s: EpgSource, wanted: WantedKeys, manual: Boolean, now: Long) =
        progress.track("epg-${s.key}", "EPG · ${s.label}", "se verifică") { report -> updateSource(s, wanted, manual, now, report) }

    private suspend fun updateSource(s: EpgSource, wanted: WantedKeys, manual: Boolean, now: Long, report: LoadReporter) {
        val policy = settings.current.policy
        val file = File(dir, "${s.key}.xml")
        val tmp = File(dir, "${s.key}.tmp")
        var state = dao.state(s.key) ?: EpgSourceState(s.key)

        var candidate: File? = file.takeIf { it.exists() }
        var fresh = false
        var tag: CacheTag? = null
        val due = manual || candidate == null ||
            policy.isDue(state.lastUpdated, state.lastAttempt, state.lastError != null, now)
        if (due) {
            _status.value = "Se descarcă: ${s.label}"
            report.phase("se descarcă")
            val previous = if (!manual && candidate != null) CacheTag(state.etag, state.lastModified) else null
            when (val r = download(s, tmp, previous, manual, report)) {
                Download.Busy -> Unit // playlistul e folosit de player: rămâne pentru rularea următoare
                Download.NotModified -> state = state.copy(lastUpdated = now, lastAttempt = now, lastError = null)
                is Download.Saved -> {
                    candidate = tmp
                    fresh = true
                    tag = r.tag
                    state = state.copy(lastAttempt = now)
                }
                is Download.Failed -> state = state.copy(lastAttempt = now, lastError = r.message)
            }
            dao.saveState(state)
        }

        val input = candidate ?: return
        val signature = listOf(
            wanted.signature, s.shiftMinutes, policy.daysAhead, policy.hoursBack, TimeUnit.MILLISECONDS.toDays(now),
        ).joinToString("|")
        if (!fresh && state.parseSignature == signature) return

        _status.value = "Se citește programul: ${s.label}"
        report.phase("se citește programul")
        state = try {
            val stats = parse(s, input, wanted, now, report)
            if (fresh) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true).also { tmp.delete() }
            }
            state.copy(
                lastUpdated = if (fresh) now else state.lastUpdated,
                // Antetele de cache se păstrează doar pentru un fișier citit cu succes.
                etag = if (fresh) tag?.etag else state.etag,
                lastModified = if (fresh) tag?.lastModified else state.lastModified,
                lastError = stats.stats.partialError ?: if (fresh) null else state.lastError,
                channelCount = stats.channelsKept,
                programmeCount = stats.stats.programmes,
                parseSignature = signature,
            )
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: EpgException) {
            tmp.delete()
            state.copy(lastError = e.message)
        } catch (e: IOException) {
            tmp.delete()
            state.copy(lastError = "Fișierul EPG nu a putut fi citit.")
        } catch (e: RuntimeException) {
            tmp.delete()
            state.copy(lastError = "Eroare neașteptată la citirea EPG-ului (${e.javaClass.simpleName}).")
        }
        dao.saveState(state)
    }

    private sealed interface Download {
        data object Busy : Download
        data object NotModified : Download
        data class Saved(val tag: CacheTag) : Download
        data class Failed(val message: String) : Download
    }

    private suspend fun download(s: EpgSource, target: File, previous: CacheTag?, manual: Boolean, report: LoadReporter): Download {
        val userAgent = s.playlistId?.let { db.playlists().get(it)?.userAgent }
        val block: suspend () -> PlaylistDownloader.Result = {
            downloader.download(s.url, target, userAgent, previous, what = "EPG-ul", onBytes = report::bytes)
        }
        return try {
            val result = if (!s.gated || s.playlistId == null) {
                block()
            } else {
                val pid = s.playlistId!!
                val job = scope.async(Dispatchers.IO) {
                    if (manual) gate.withConnection(pid.toString(), block) else gate.tryWithConnection(pid.toString(), block)
                }
                downloads[pid] = job
                try {
                    job.await() ?: return Download.Busy
                } catch (e: CancellationException) {
                    // Oprit de player (yieldConnection) sau anulat de sus: în al doilea caz, mai departe.
                    currentCoroutineContext().ensureActive()
                    target.delete()
                    return Download.Busy
                } finally {
                    downloads.remove(pid, job)
                }
            }
            when (result) {
                PlaylistDownloader.Result.NotModified -> Download.NotModified
                is PlaylistDownloader.Result.Saved -> Download.Saved(result.tag)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaylistException) {
            target.delete()
            Download.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            target.delete()
            // Fără detalii care ar putea conține adrese sau parole.
            Download.Failed("Eroare neașteptată la descărcarea EPG-ului (${e.javaClass.simpleName}).")
        }
    }

    private class ParseResult(val stats: XmltvStats, val channelsKept: Int)

    /** Înlocuiește atomic programul sursei: o eroare la mijloc lasă programul vechi neatins. */
    private suspend fun parse(s: EpgSource, file: File, wanted: WantedKeys, now: Long, report: LoadReporter): ParseResult {
        val ctx = currentCoroutineContext()
        val policy = settings.current.policy
        val parser = XmltvParser(
            window = policy.window(now),
            shiftMs = TimeUnit.MINUTES.toMillis(s.shiftMinutes.toLong()),
            preferredLanguages = SourcePreferences().preferredLanguages.reversed(), // română, apoi engleză
            checkCancelled = { ctx.ensureActive() },
        )
        return epgDb.runInTransaction<ParseResult> {
            dao.deleteChannelsBlocking(s.key)
            val sink = RoomSink(s.key, wanted, report)
            val total = file.length()
            val stats = CountingInputStream(file.inputStream()) { read -> report.bytes(read, total) }.use { parser.parse(it, sink) }
            sink.flush()
            dao.fillMissingStopsBlocking(s.key)
            ParseResult(stats, sink.channelCount)
        }
    }

    /** Scrie în loturi canalele EPG folosite și emisiunile lor. */
    private inner class RoomSink(
        private val key: String,
        private val wanted: WantedKeys,
        private val report: LoadReporter,
    ) : XmltvSink {
        private val ids = HashMap<String, Long>()
        private val batch = ArrayList<ProgrammeEntity>(BATCH)
        private var programmes = 0L
        val channelCount: Int get() = ids.size

        override fun onChannel(channel: XmltvChannel) {
            if (channel.id in ids || !wanted.wants(channel)) return
            ids[channel.id] = dao.insertChannelBlocking(
                EpgChannelEntity(sourceKey = key, xmltvId = channel.id, displayNames = channel.displayNames.joinToString("\n")),
            )
            report.count("canale", ids.size.toLong())
        }

        override fun wantsProgrammes(channelId: String): Boolean {
            if (channelId in ids) return true
            // Emisiuni pentru un canal nedeclarat în <channel>, dar cerut prin tvg-id.
            if (!wanted.wantsId(channelId)) return false
            ids[channelId] = dao.insertChannelBlocking(EpgChannelEntity(sourceKey = key, xmltvId = channelId, displayNames = ""))
            report.count("canale", ids.size.toLong())
            return true
        }

        override fun onProgramme(programme: XmltvProgramme) {
            val channel = ids[programme.channelId] ?: return
            batch += ProgrammeEntity(
                epgChannelId = channel,
                start = programme.start,
                stop = programme.stop ?: 0,
                title = programme.title,
                subtitle = programme.subtitle,
                description = programme.description,
                category = programme.category,
            )
            report.count("emisiuni", ++programmes)
            if (batch.size >= BATCH) flush()
        }

        fun flush() {
            if (batch.isEmpty()) return
            dao.insertProgrammesBlocking(batch)
            batch.clear()
        }
    }

    private suspend fun loadWanted(): WantedKeys {
        val builder = WantedKeys.Builder()
        var after = 0L
        while (true) {
            val page = db.channels().channelsForEpg(after, PAGE)
            page.forEach { builder.add(ChannelForEpg(it.id, it.tvgId, it.tvgName, it.name, it.groupName)) }
            if (page.size < PAGE) break
            after = page.last().id
        }
        return builder.build()
    }

    /** Reface tabelul canal → canal EPG pentru toate playlisturile active. */
    private suspend fun rematch(sources: List<EpgSource>) =
        progress.track("epg-potrivire", "Potrivirea canalelor cu programul", "se potrivesc canalele") { report ->
            rematch(sources, report)
        }

    private suspend fun rematch(sources: List<EpgSource>, report: LoadReporter) {
        _status.value = "Se potrivesc canalele cu programul…"
        val playlists = db.playlists().all().filter { it.enabled }
        dao.deleteMapExcept(playlists.map { it.id }.ifEmpty { listOf(-1L) })
        val epgChannels = dao.allChannels().map {
            EpgChannelRef(it.id, it.sourceKey, it.xmltvId, it.displayNames.split('\n').filter(String::isNotBlank))
        }
        val matcher = EpgMatcher(epgChannels)
        val priorities = playlists.associate { it.id to EpgSources.priorityFor(it.id, sources) }
        val rows = HashMap<Long, MutableList<EpgMapEntity>>()
        val totalChannels = playlists.sumOf { it.channelCount.toLong() }
        var checked = 0L
        var matched = 0L
        if (epgChannels.isNotEmpty()) {
            var after = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = db.channels().channelsForEpg(after, PAGE)
                for (c in page) {
                    val priority = priorities[c.playlistId] ?: continue
                    checked++
                    val m = matcher.match(ChannelForEpg(c.id, c.tvgId, c.tvgName, c.name, c.groupName), priority)
                    if (m != null) {
                        matched++
                        rows.getOrPut(c.playlistId) { ArrayList() } += EpgMapEntity(c.id, c.playlistId, m.ref)
                    }
                }
                report.phase("se potrivesc canalele: ${LoadFormat.count(checked)} din ${LoadFormat.count(totalChannels)}")
                report.count("cu program", matched)
                if (page.size < PAGE) break
                after = page.last().id
            }
        }
        report.phase("se salvează potrivirile")
        epgDb.withTransaction {
            for (p in playlists) {
                dao.deleteMap(p.id)
                rows[p.id]?.chunked(BATCH)?.forEach { dao.insertMap(it) }
            }
        }
    }

    /** Acum / urmează pentru mai multe canale deodată (de exemplu un grup din Live TV). */
    suspend fun nowNext(channelIds: List<Long>, now: Long = System.currentTimeMillis()): Map<Long, NowNext> {
        if (channelIds.isEmpty()) return emptyMap()
        val until = now + TimeUnit.HOURS.toMillis(12)
        val rows = channelIds.distinct().chunked(IN_CHUNK).flatMap { dao.programmesFor(it, now, until) }
        return rows.groupBy { it.channelId }.mapValues { (_, list) ->
            NowNext.of(list.map { Programme(it.start, it.stop, it.title, it.subtitle, null, it.category) }, now)
        }
    }

    /** Programul unui canal (de la emisiunea curentă), sau null dacă nu are EPG. */
    suspend fun schedule(channelId: Long, now: Long = System.currentTimeMillis()): List<Programme>? {
        val epgChannel = dao.epgChannelFor(channelId) ?: return null
        val from = now - TimeUnit.HOURS.toMillis(settings.current.policy.hoursBack.toLong())
        return dao.schedule(epgChannel, from).map {
            Programme(it.start, it.stop, it.title, it.subtitle, it.description, it.category)
        }
    }

    // ---------- pentru Sports Hub (doar citire din baza EPG, fără rețea) ----------

    /** Canalele EPG ale canalelor [channelIds] din playlist. */
    suspend fun epgChannelsOf(channelIds: Collection<Long>): Set<Long> =
        channelIds.distinct().chunked(IN_CHUNK).flatMap { dao.mapForChannels(it) }.map { it.epgChannelId }.toSet()

    /** Care din canalele [channelIds] din playlist au un canal EPG potrivit. */
    suspend fun mappedChannels(channelIds: Collection<Long>): Set<Long> =
        channelIds.distinct().chunked(IN_CHUNK).flatMap { dao.mapForChannels(it) }.map { it.channelId }.toSet()

    /** Canalele din playlist care au programul canalelor EPG [epgChannelIds]. */
    suspend fun channelsShowing(epgChannelIds: Collection<Long>): Map<Long, List<Long>> =
        epgChannelIds.distinct().chunked(IN_CHUNK).flatMap { dao.mapForEpgChannels(it) }
            .groupBy({ it.epgChannelId }, { it.channelId })

    /** Emisiunile canalelor EPG [epgChannelIds] dintre [from] și [until]. */
    suspend fun programmesOn(epgChannelIds: Collection<Long>, from: Long, until: Long): List<EpgProgrammeRow> =
        epgChannelIds.distinct().chunked(IN_CHUNK).flatMap { dao.programmesOn(it, from, until) }

    /** Emisiunile cu categorie de sport de pe orice canal folosit (cel mult [limit]). */
    suspend fun sportCategoryProgrammes(from: Long, until: Long, limit: Int): List<EpgProgrammeRow> =
        dao.sportCategoryProgrammes(from, until, limit)

    private fun info(p: PlaylistEntity) = PlaylistEpgInfo(
        playlistId = p.id,
        name = p.name,
        enabled = p.enabled,
        url = p.url,
        xtream = p.type == PlaylistType.XTREAM,
        epgUrls = p.epgUrls?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
    )

    private companion object {
        const val BATCH = 1_000
        const val PAGE = 5_000
        const val IN_CHUNK = 500
    }
}
