package ro.ak.sportshub.core.common.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ro.ak.sportshub.core.common.ConnectionGate

/**
 * Permisul de conexiune al playerului. Cât timp rulează un stream, playerul ține permisul
 * playlistului în [ConnectionGate], deci actualizările din fundal ale aceluiași playlist sunt amânate
 * și nu pot deschide o a doua conexiune.
 *
 * Playerul ține cel mult un permis: când trece pe alt playlist, îl eliberează întâi pe cel vechi.
 * Stream-ul vechi trebuie oprit înainte de [acquire] sau [release].
 */
class StreamLease(
    private val gate: ConnectionGate,
    /** Scope-ul în care stă permisul; trebuie să trăiască mai mult decât playerul. */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var holder: Job? = null

    /** Playlistul pentru care se ține permisul acum, sau null. */
    @Volatile
    var heldFor: String? = null
        private set

    /**
     * Obține permisul pentru [playlistId], așteptând cel mult [timeoutMs] (de exemplu cât se termină
     * descărcarea playlistului). Dacă permisul e deja ținut pentru același playlist, nu face nimic.
     * Întoarce false dacă playlistul a rămas ocupat.
     */
    suspend fun acquire(playlistId: String, timeoutMs: Long): Boolean = mutex.withLock {
        if (heldFor == playlistId && holder?.isActive == true) return@withLock true
        releaseLocked()
        val acquired = CompletableDeferred<Unit>()
        val job = scope.launch {
            gate.withConnection(playlistId) {
                acquired.complete(Unit)
                awaitCancellation()
            }
        }
        val ok = try {
            withTimeoutOrNull(timeoutMs) { acquired.await() } != null
        } catch (e: CancellationException) {
            // Cel care aștepta a renunțat (de exemplu s-a schimbat canalul): permisul nu rămâne agățat.
            job.cancel()
            throw e
        }
        if (!ok) {
            job.cancelAndJoin()
            return@withLock false
        }
        holder = job
        heldFor = playlistId
        true
    }

    /** Eliberează permisul (de exemplu la ieșirea din player sau când aplicația trece în fundal). */
    suspend fun release() = mutex.withLock { releaseLocked() }

    private suspend fun releaseLocked() {
        holder?.cancelAndJoin()
        holder = null
        heldFor = null
    }
}
