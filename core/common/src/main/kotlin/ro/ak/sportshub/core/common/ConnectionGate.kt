package ro.ak.sportshub.core.common

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limitează conexiunile simultane către fiecare playlist (furnizorii permit de obicei una singură).
 * Orice acces la rețea al unui playlist (descărcare listă, EPG de la furnizor, player) trece pe aici,
 * ca actualizările din fundal să nu-ți taie stream-ul și să nu fie blocat contul.
 */
class ConnectionGate(private val limitFor: (playlistId: String) -> Int = { 1 }) {

    private val permits = ConcurrentHashMap<String, Semaphore>()

    private fun semaphore(playlistId: String): Semaphore =
        permits.getOrPut(playlistId) { Semaphore(limitFor(playlistId).coerceAtLeast(1)) }

    suspend fun <T> withConnection(playlistId: String, block: suspend () -> T): T =
        semaphore(playlistId).withPermit { block() }

    /** Pentru sarcini care pot fi amânate: nu așteaptă, renunță dacă playlistul e ocupat. */
    suspend fun <T> tryWithConnection(playlistId: String, block: suspend () -> T): T? {
        val s = semaphore(playlistId)
        if (!s.tryAcquire()) return null
        return try {
            block()
        } finally {
            s.release()
        }
    }
}
