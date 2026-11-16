package ro.ak.sportshub.core.common.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftTest {

    /** 1 000 octeți pe secundă, scriși în bucăți de 100 la fiecare 100 ms. */
    private fun recorded(seconds: Int, capacity: Long = 1_000_000, chunk: Long = 1_000): TimeshiftIndex {
        val index = TimeshiftIndex(capacity, chunk, markEveryMs = 500)
        var total = 0L
        for (step in 1..seconds * 10) {
            total += 100
            index.onWrite(nowMs = 10_000L + step * 100L, total = total)
        }
        return index
    }

    @Test
    fun `ora si pozitia se corespund`() {
        val index = recorded(60)
        assertEquals(60_000L, index.written)
        // Octetul 30 000 a sosit pe la 30 s după început.
        val t = index.timeAt(30_000)!!
        assertTrue("ora $t", t in 39_900L..40_200L)
        // Înapoi la ora aceea: reperul de dinainte, deci puțin mai devreme, niciodată mai târziu.
        val b = index.byteAt(40_000)
        assertTrue("octetul $b", b in 29_000L..30_000L)
        assertEquals(0L, index.byteAt(0))
        assertTrue(index.byteAt(Long.MAX_VALUE) in (index.written - 1_000)..index.written)
    }

    @Test
    fun `cele mai vechi bucati se sterg, nu cea in care se scrie`() {
        val index = TimeshiftIndex(capacityBytes = 4_000, chunkBytes = 1_000, markEveryMs = 500)
        val dropped = mutableListOf<Long>()
        var total = 0L
        for (step in 1..100) {
            total += 100
            dropped += index.onWrite(step * 100L, total)
        }
        assertEquals(10_000L, index.written)
        assertTrue(index.written - index.first <= 4_000)
        assertEquals((0L until index.chunkOf(index.first)).toList(), dropped)
        // Nu se poate da înapoi mai departe decât ce a rămas.
        assertTrue(index.byteAt(0) >= index.first)
        assertTrue(index.oldestTime!! >= 5_000L)
    }

    @Test
    fun `fara nimic scris`() {
        val index = TimeshiftIndex(4_000, 1_000)
        assertNull(index.timeAt(0))
        assertEquals(0L, index.byteAt(5_000))
        assertNull(index.newestTime)
    }

    @Test
    fun `reperele cresc fara limita fixa`() {
        val index = recorded(seconds = 400, capacity = 10_000_000, chunk = 100_000)
        // 2 repere pe secundă × 400 s: mai mult decât capacitatea inițială a tabelului.
        val t = index.timeAt(200_000)!!
        assertTrue("ora $t", t in 209_800L..210_200L)
    }

    @Test
    fun `saltul se opreste la cel mai vechi moment si la live`() {
        assertEquals(50_000L, Timeshift.target(watchedMs = 55_000, deltaMs = -10_000, oldestMs = 50_000, liveMs = 100_000))
        assertEquals(80_000L, Timeshift.target(90_000, -10_000, 0, 100_000))
        // Înainte până aproape de live: live.
        assertNull(Timeshift.target(88_000, 10_000, 0, 100_000))
        assertEquals(90_000L, Timeshift.target(80_000, 10_000, 0, 100_000))
    }

    @Test
    fun `capacitatea tine cont de spatiul liber`() {
        val gb = 1024L * 1024 * 1024
        assertNull(Timeshift.capacityBytes(0, 100 * gb))
        assertEquals(gb, Timeshift.capacityBytes(1024, 100 * gb))
        assertEquals(gb / 2, Timeshift.capacityBytes(1024, gb))
        assertNull(Timeshift.capacityBytes(1024, 20L * 1024 * 1024))
    }

    @Test
    fun `doar stream-urile TS se inregistreaza`() {
        assertTrue(Timeshift.canRecord("http://h:80/user/pass/12345"))
        assertTrue(Timeshift.canRecord("http://h/live/user/pass/12345.ts?token=a.b"))
        assertFalse(Timeshift.canRecord("http://h/live/user/pass/12345.m3u8"))
        assertFalse(Timeshift.canRecord("http://h/stream.flv"))
        assertFalse(Timeshift.canRecord("http://h/movie/a.mp4"))
    }

    @Test
    fun `etichete`() {
        assertEquals("−0:45", Timeshift.behindLabel(45_400))
        assertEquals("−12:03", Timeshift.behindLabel(723_000))
        assertEquals("−1:00:05", Timeshift.behindLabel(3_605_000))
        assertEquals("1 GB", Timeshift.label(1024))
        assertEquals("512 MB", Timeshift.label(512))
        assertEquals("Oprit", Timeshift.label(0))
        assertEquals(512, Timeshift.nextOption(0))
        assertEquals(0, Timeshift.nextOption(2048))
    }
}
