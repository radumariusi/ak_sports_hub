package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsMeterTest {
    @Test
    fun `măsoară 50 și 25 de cadre pe secundă din orele cadrelor`() {
        val m = FpsMeter()
        var result: Float? = null
        for (i in 0..FpsMeter.SAMPLES) result = m.add(i * 20_000L + (if (i % 7 == 0) 300 else 0)) ?: result
        assertEquals(50f, result!!, 0.001f)
        assertNull(m.add(10_000_000L)) // după rezultat, nimic nou
        m.reset()
        var r2: Float? = null
        for (i in 0..FpsMeter.SAMPLES + 3) r2 = m.add(i * 40_000L + if (i == 30) 900_000L else 0L) ?: r2
        assertEquals(25f, r2!!, 0.001f)
    }
}
