package ro.ak.sportshub.core.common.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.common.ConnectionGate

class StreamLeaseTest {

    @Test
    fun `cat timp playerul tine permisul, actualizarea programata e amanata`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        assertTrue(lease.acquire("1", timeoutMs = 1_000))
        assertNull(gate.tryWithConnection("1") { "descarcare" })
        // Alt playlist nu e afectat.
        assertEquals("ok", gate.tryWithConnection("2") { "ok" })
        lease.release()
        assertEquals("descarcare", gate.tryWithConnection("1") { "descarcare" })
    }

    @Test
    fun `trecerea pe alt playlist elibereaza permisul vechi`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        assertTrue(lease.acquire("1", 1_000))
        assertTrue(lease.acquire("2", 1_000))
        assertEquals("2", lease.heldFor)
        assertEquals("liber", gate.tryWithConnection("1") { "liber" })
        assertNull(gate.tryWithConnection("2") { "ocupat" })
    }

    @Test
    fun `acelasi playlist nu cere un al doilea permis`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        assertTrue(lease.acquire("1", 1_000))
        // Cu un singur permis pe playlist, o a doua cerere ar aștepta la nesfârșit.
        assertTrue(lease.acquire("1", 1_000))
    }

    @Test
    fun `asteapta sa se termine descarcarea, apoi porneste`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        launch { gate.withConnection("1") { kotlinx.coroutines.delay(5_000) } }
        testScheduler.runCurrent()
        assertTrue(lease.acquire("1", timeoutMs = 10_000))
    }

    @Test
    fun `renunta daca playlistul ramane ocupat`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        val download = launch { gate.withConnection("1") { kotlinx.coroutines.delay(60_000) } }
        testScheduler.runCurrent()
        assertFalse(lease.acquire("1", timeoutMs = 2_000))
        assertNull(lease.heldFor)
        download.cancel()
        testScheduler.runCurrent()
        // Încercarea abandonată nu a lăsat permisul blocat.
        assertEquals("ok", gate.tryWithConnection("1") { "ok" })
    }

    @Test
    fun `asteptarea anulata nu lasa permisul agatat`() = runTest {
        val gate = ConnectionGate()
        val lease = StreamLease(gate, backgroundScope)
        val download = launch { gate.withConnection("1") { kotlinx.coroutines.delay(5_000) } }
        testScheduler.runCurrent()
        val waiting = launch { lease.acquire("1", timeoutMs = 60_000) }
        testScheduler.runCurrent()
        waiting.cancel()
        download.join()
        testScheduler.advanceUntilIdle()
        assertEquals("ok", gate.tryWithConnection("1") { "ok" })
    }
}
