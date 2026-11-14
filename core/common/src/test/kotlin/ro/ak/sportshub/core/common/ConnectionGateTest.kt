package ro.ak.sportshub.core.common

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionGateTest {

    @Test
    fun `cel mult o conexiune simultana pe playlist`() = runTest {
        val gate = ConnectionGate()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        (1..5).map {
            async {
                gate.withConnection("p1") {
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, peak.get())
    }

    @Test
    fun `playlisturi diferite nu se blocheaza intre ele`() = runTest {
        val gate = ConnectionGate()
        val release = CompletableDeferred<Unit>()
        val job = launch { gate.withConnection("p1") { release.await() } }
        testScheduler.runCurrent()
        assertEquals("ok", gate.withConnection("p2") { "ok" })
        assertNull(gate.tryWithConnection("p1") { "nu trebuie" })
        release.complete(Unit)
        job.join()
        assertEquals("ok", gate.tryWithConnection("p1") { "ok" })
    }
}
