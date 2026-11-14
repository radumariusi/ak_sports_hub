package ro.ak.sportshub.core.common.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {

    @Test
    fun `zapping trece de la capat la inceput`() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(20L, Zapping.neighbor(ids, 10L, 1))
        assertEquals(10L, Zapping.neighbor(ids, 30L, 1))
        assertEquals(30L, Zapping.neighbor(ids, 10L, -1))
        assertEquals(10L, Zapping.neighbor(ids, 99L, 1))
        assertNull(Zapping.neighbor(emptyList<Long>(), 1L, 1))
        assertEquals("2 / 3", Zapping.position(ids, 20L))
    }

    @Test
    fun `canalul anterior comuta intre ultimele doua`() {
        val h = ChannelHistory<String>()
        h.visit("A")
        h.visit("B")
        assertEquals("A", h.previous)
        h.visit("B")
        assertEquals("A", h.previous)
        h.visit(h.previous!!)
        assertEquals("A", h.current)
        assertEquals("B", h.previous)
        h.forget("B")
        assertNull(h.previous)
    }

    @Test
    fun `numarul tastat`() {
        val n = NumberInput(maxDigits = 3)
        assertFalse(n.isActive)
        n.type(0)
        assertFalse(n.isActive)
        n.type(1); n.type(0); n.type(5); n.type(7)
        assertEquals("105", n.text)
        assertEquals(105, n.take())
        assertNull(n.take())
    }

    @Test
    fun `reconectarea are pauze crescatoare si se opreste`() {
        val b = ReconnectBackoff(listOf(1, 2, 4))
        assertEquals(1L, b.next())
        assertEquals(2L, b.next())
        assertEquals(4L, b.next())
        assertNull(b.next())
        assertEquals(3, b.attempt)
        b.reset()
        assertEquals(1L, b.next())
    }

    @Test
    fun `mesajele de eroare nu contin date de acces`() {
        assertTrue(PlaybackErrors.forHttpStatus(403).contains("refuzat"))
        assertTrue(PlaybackErrors.forHttpStatus(502).contains("502"))
        assertTrue(PlaybackErrors.isPermanent(404))
        assertFalse(PlaybackErrors.isPermanent(503))
    }

    @Test
    fun `numele pistelor audio`() {
        assertEquals("Engleză · 5.1 · AC3", TrackLabels.audio(null, "en", 6, "audio/ac3"))
        assertEquals("Română · stereo · AAC", TrackLabels.audio(null, "ron", 2, "audio/mp4a-latm"))
        assertEquals("Comentariu · stereo", TrackLabels.audio("Comentariu", "en", 2, null))
        assertEquals("Pistă necunoscută", TrackLabels.audio(null, "und", 0, null))
        assertEquals("Germană", TrackLabels.text(null, "de"))
    }
}
