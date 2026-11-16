package ro.ak.sportshub.core.common.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.common.DiagnosticText

class PlayerTweaksTest {

    @Test
    fun `intarzierea sunetului ramane intre limite, din 50 in 50`() {
        assertEquals(1_000, AudioDelay.clamp(5_000))
        assertEquals(-1_000, AudioDelay.clamp(-1_200))
        assertEquals(41, AudioDelay.options.size)
        assertTrue(0 in AudioDelay.options)
        assertEquals("+150 ms", AudioDelay.short(150))
        assertEquals("-50 ms", AudioDelay.short(-50))
    }

    @Test
    fun `pista audio tinuta minte se gaseste dupa limba si eticheta`() {
        val tracks = listOf<Pair<String?, String?>>("eng" to "Commentary", "en" to "Original", "ron" to null)
        assertEquals(1, AudioChoice("en", "Original").pick(tracks))
        // Ordinea s-a schimbat la pornirea următoare: tot pista originală.
        assertEquals(1, AudioChoice("en", "Original").pick(tracks.reversed()))
        assertEquals(2, AudioChoice("en", "Commentary").pick(tracks.reversed()))
        // Doar limba: prima pistă în limba aceea (ron = ro).
        assertEquals(2, AudioChoice("ro", null).pick(tracks))
        assertNull(AudioChoice("de", null).pick(tracks))
        // „und” nu e o limbă: se caută după etichetă.
        assertEquals(0, AudioChoice("und", "commentary").pick(tracks))
        assertNull(AudioChoice(null, null).pick(tracks))
    }

    @Test
    fun `alegerea pistei se scrie si se citeste`() {
        val c = AudioChoice("en", "Original")
        assertEquals(c, AudioChoice.decode(c.encode()))
        assertEquals(AudioChoice(null, "Stereo"), AudioChoice.decode(AudioChoice(null, "Stereo").encode()))
        assertNull(AudioChoice.decode(""))
        assertNull(AudioChoice.decode("fara separator"))
    }

    @Test
    fun `modul de noapte coboara sunetul tare si urca sunetul incet, fara taiere`() {
        val rate = 48_000
        fun tone(amplitude: Double): ShortArray = ShortArray(rate * 2) { i ->
            (amplitude * 32_767 * kotlin.math.sin(2 * Math.PI * 440 * (i / 2) / rate)).toInt().toShort()
        }
        fun peakOfEnd(s: ShortArray) = s.takeLast(rate / 2).maxOf { abs(it.toInt()) } / 32_767.0

        val loud = tone(0.9).also { NightCompressor(rate, 2).process(it) }
        val quiet = tone(0.01).also { NightCompressor(rate, 2).process(it) }
        assertTrue("tare: ${peakOfEnd(loud)}", peakOfEnd(loud) < 0.6)
        assertTrue("încet: ${peakOfEnd(quiet)}", peakOfEnd(quiet) > 0.02)
        assertTrue(loud.all { abs(it.toInt()) <= (NightCompressor.CEILING * 32_768).toInt() + 1 })

        val silence = ShortArray(1_000).also { NightCompressor(rate, 2).process(it) }
        assertTrue(silence.all { it.toInt() == 0 })
    }

    @Test
    fun `incadrarea necunoscuta e cea implicita`() {
        assertEquals(VideoFit.CROP, VideoFit.of("CROP"))
        assertEquals(VideoFit.FIT, VideoFit.of(null))
        assertEquals(VideoFit.FIT, VideoFit.of("altceva"))
    }

    @Test
    fun `caderea nu scrie adresele stream-urilor`() {
        val inner = IllegalStateException("http://srv.example:8080/live/radu/parola123/55.ts a murit")
        val e = RuntimeException("Eroare la https://a.b/c?token=x", inner)
        val text = DiagnosticText.crash(e, "main")
        assertFalse(text.contains("parola123"))
        assertFalse(text.contains("token=x"))
        assertTrue(text.contains("java.lang.RuntimeException: Eroare la ‹adresă›"))
        assertTrue(text.contains("Cauza: java.lang.IllegalStateException: ‹adresă› a murit"))
        assertTrue(text.startsWith("Fir: main"))
    }
}
