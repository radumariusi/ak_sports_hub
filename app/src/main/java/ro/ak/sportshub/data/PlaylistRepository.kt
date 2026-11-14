package ro.ak.sportshub.data

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.ak.sportshub.core.database.AkDatabase
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.core.database.RoomImportSink
import ro.ak.sportshub.core.model.ChannelCorrection
import ro.ak.sportshub.core.playlist.CacheTag
import ro.ak.sportshub.core.playlist.ImportOutcome
import ro.ak.sportshub.core.playlist.ImportSource
import ro.ak.sportshub.core.playlist.PlaylistException
import ro.ak.sportshub.core.playlist.PlaylistImporter
import ro.ak.sportshub.core.playlist.XtreamAccount

/** Rezultatul unei actualizări, pentru mesajele din interfață. */
sealed interface RefreshResult {
    data class Done(val channels: Int, val skipped: Int) : RefreshResult
    data object Unchanged : RefreshResult
    data object Busy : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

/**
 * Playlisturile: salvare, ștergere și actualizare (manuală sau programată).
 * Un playlist se actualizează o singură dată în același timp, iar descărcarea trece prin
 * `ConnectionGate` (o conexiune pe playlist).
 */
class PlaylistRepository(
    private val context: Context,
    private val db: AkDatabase,
    private val importer: PlaylistImporter,
    private val scope: CoroutineScope,
    /** Corecțiile manuale ale variantelor, aplicate la fiecare import. */
    corrections: (playlistId: Long) -> Map<String, ChannelCorrection> = { emptyMap() },
    /** Fusul orar al serverului Xtream, aflat la actualizare (pentru catch-up). */
    private val onServerZone: (playlistId: Long, timezone: String?) -> Unit = { _, _ -> },
) {
    private val sink = RoomImportSink(db, corrections)
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val _refreshing = MutableStateFlow<Set<Long>>(emptySet())

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Id-ul unui playlist ale cărui canale s-au schimbat (import, ștergere, activare); pentru EPG. */
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    /** Playlisturile care se actualizează chiar acum. */
    val refreshing: StateFlow<Set<Long>> = _refreshing.asStateFlow()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = db.playlists().observe(id)

    suspend fun get(id: Long): PlaylistEntity? = db.playlists().get(id)

    /**
     * Salvează playlistul. Dacă s-a schimbat sursa (adresă, cont, format), se uită antetele de cache,
     * ca următoarea actualizare să descarce totul din nou.
     */
    suspend fun save(playlist: PlaylistEntity): Long {
        val dao = db.playlists()
        if (playlist.id == 0L) return dao.insert(playlist.copy(sortOrder = dao.all().size))
        val old = dao.get(playlist.id)
        val sourceChanged = old == null || old.url != playlist.url || old.username != playlist.username ||
            old.password != playlist.password || old.streamFormat != playlist.streamFormat ||
            old.userAgent != playlist.userAgent
        // Ecranul de editare poate ține o copie veche: starea importului (canale, EPG declarat, expirare)
        // rămâne cea din bază, ca o salvare să nu șteargă ce a adus ultima actualizare.
        val merged = if (old == null) playlist else playlist.copy(
            lastUpdated = old.lastUpdated,
            lastAttempt = old.lastAttempt,
            lastError = old.lastError,
            etag = old.etag,
            lastModified = old.lastModified,
            channelCount = old.channelCount,
            skippedCount = old.skippedCount,
            epgUrls = old.epgUrls,
            expiresAt = old.expiresAt,
            sortOrder = old.sortOrder,
        )
        dao.update(if (sourceChanged) merged.copy(etag = null, lastModified = null) else merged)
        if (old?.enabled != playlist.enabled) _changes.tryEmit(playlist.id)
        return playlist.id
    }

    /** Activează sau oprește un playlist (canalele lui apar sau nu în liste, EPG și Sports Hub). */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val p = db.playlists().get(id) ?: return
        if (p.enabled == enabled) return
        db.playlists().update(p.copy(enabled = enabled))
        _changes.tryEmit(id)
    }

    suspend fun delete(id: Long) {
        db.playlists().delete(id)
        _changes.tryEmit(id)
    }

    /** Pornește actualizarea fără să blocheze ecranul; rezultatul apare în lista de playlisturi. */
    fun refreshInBackground(id: Long, force: Boolean = false) {
        scope.launch { refresh(id, manual = true, force = force) }
    }

    fun refreshAllInBackground() {
        scope.launch {
            db.playlists().all().filter { it.enabled }.forEach { refresh(it.id, manual = true) }
        }
    }

    /** Verifică contul Xtream; pentru un playlist existent folosește permisul lui de conexiune. */
    suspend fun checkXtream(
        playlistId: Long,
        server: String,
        username: String,
        password: String,
        userAgent: String?,
    ): XtreamAccount {
        val key = if (playlistId > 0) playlistId.toString() else "verificare"
        return importer.checkXtream(ImportSource.Xtream(key, server, username, password, userAgent = userAgent))
    }

    /**
     * Actualizările programate: playlisturile active al căror interval a trecut, una după alta.
     * Dacă un playlist e ocupat (player), se sare și se încearcă la următoarea rulare.
     */
    suspend fun refreshDue(now: Long = System.currentTimeMillis()) {
        for (p in db.playlists().all()) {
            if (!p.enabled || p.updateIntervalHours <= 0) continue
            val interval = TimeUnit.HOURS.toMillis(p.updateIntervalHours.toLong())
            val lastUpdated = p.lastUpdated
            val lastAttempt = p.lastAttempt
            val due = lastUpdated == null || now - lastUpdated >= interval
            // După o eroare nu insistăm: cel mult o încercare pe oră.
            val recentlyTried = lastAttempt != null && now - lastAttempt < TimeUnit.HOURS.toMillis(1)
            if (due && !(recentlyTried && p.lastError != null)) refresh(p.id, manual = false)
        }
    }

    suspend fun refresh(id: Long, manual: Boolean, force: Boolean = false): RefreshResult {
        val lock = locks.getOrPut(id) { Mutex() }
        if (!manual && lock.isLocked) return RefreshResult.Busy
        return lock.withLock {
            _refreshing.update { it + id }
            try {
                doRefresh(id, manual, force)
            } finally {
                _refreshing.update { it - id }
            }
        }
    }

    private suspend fun doRefresh(id: Long, manual: Boolean, force: Boolean): RefreshResult {
        val dao = db.playlists()
        val p = dao.get(id) ?: return RefreshResult.Failed("Playlistul nu mai există.")
        val now = System.currentTimeMillis()
        val previous = if (!force && p.channelCount > 0) CacheTag(p.etag, p.lastModified) else null

        val outcome = try {
            importer.import(source(p), sink, previous, waitForConnection = manual)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaylistException) {
            dao.markFailed(id, now, e.message)
            return RefreshResult.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            // Fără detalii care ar putea conține adrese sau parole.
            val msg = "Eroare neașteptată la încărcare (${e.javaClass.simpleName})."
            dao.markFailed(id, now, msg)
            return RefreshResult.Failed(msg)
        }

        val fresh = dao.get(id) ?: return RefreshResult.Failed("Playlistul nu mai există.")
        return when (outcome) {
            ImportOutcome.Busy -> RefreshResult.Busy
            ImportOutcome.NotModified -> {
                dao.update(fresh.copy(lastUpdated = now, lastAttempt = now, lastError = null))
                RefreshResult.Unchanged
            }
            is ImportOutcome.Imported -> {
                outcome.account?.let { onServerZone(id, it.timezone) }
                dao.update(
                    fresh.copy(
                        lastUpdated = now,
                        lastAttempt = now,
                        lastError = null,
                        etag = outcome.tag?.etag,
                        lastModified = outcome.tag?.lastModified,
                        channelCount = outcome.channels,
                        skippedCount = outcome.skipped,
                        epgUrls = outcome.epgUrls.joinToString("\n").ifEmpty { null },
                        expiresAt = outcome.account?.expiresAt ?: fresh.expiresAt,
                    ),
                )
                _changes.tryEmit(id)
                RefreshResult.Done(outcome.channels, outcome.skipped)
            }
        }
    }

    private fun source(p: PlaylistEntity): ImportSource {
        val id = p.id.toString()
        return when (p.type) {
            PlaylistType.M3U_URL -> ImportSource.M3uUrl(id, p.url, p.userAgent)
            PlaylistType.M3U_FILE -> ImportSource.M3uLocal(id) {
                runCatching { context.contentResolver.openInputStream(Uri.parse(p.url)) }.getOrNull()
                    ?: throw PlaylistException("Fișierul nu mai poate fi citit. Alege-l din nou.")
            }
            PlaylistType.XTREAM -> ImportSource.Xtream(
                playlistId = id,
                server = p.url,
                username = p.username.orEmpty(),
                password = p.password.orEmpty(),
                format = p.streamFormat,
                userAgent = p.userAgent,
            )
        }
    }
}
