package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

/** C1 (sănătatea sursei din redare) și C2 (limba reală a sunetului). */
class PlaybackHealthTest {

    private fun play(h: PlaybackHealth?, startMs: Long = 2_000, minutes: Long = 30, stalls: Int = 0) =
        SourceHealth.after(h, PlaybackSession(true, startMs, minutes * 60_000, stalls), 1L)

    private fun fail(h: PlaybackHealth?) = SourceHealth.after(h, PlaybackSession(false), 1L)

    @Test
    fun doua_porniri_bune_fac_varianta_stabila() {
        val one = play(null)
        assertEquals(PlaybackHealth.Verdict.UNKNOWN, one.verdict)
        val two = play(one)
        assertEquals(PlaybackHealth.Verdict.STABLE, two.verdict)
        assertEquals(0, two.rank)
        assertTrue(two.summary!!.startsWith("Stabilă la tine: 2 porniri"))
    }

    @Test
    fun opririle_dese_si_esecurile_o_fac_instabila() {
        val stalls = play(play(null, stalls = 20), stalls = 20)
        assertEquals(PlaybackHealth.Verdict.UNSTABLE, stalls.verdict)
        val fails = fail(fail(play(null)))
        assertEquals(PlaybackHealth.Verdict.UNSTABLE, fails.verdict)
        assertEquals(2, fails.rank)
    }

    @Test
    fun zappingul_scurt_nu_strica_media_si_numaratorile_imbatranesc() {
        var h = play(play(null))
        h = SourceHealth.after(h, PlaybackSession(true, 3_000, 10_000, 0), 1L)
        assertEquals(0f, h.stallsPerHour, 0.01f)
        repeat(20) { h = play(h) }
        assertTrue(h.plays <= 12)
        // O variantă reparată își revine: după multe porniri bune, două eșecuri vechi nu mai contează.
        var bad = fail(fail(play(null)))
        repeat(10) { bad = play(bad) }
        assertEquals(PlaybackHealth.Verdict.STABLE, bad.verdict)
    }

    @Test
    fun limba_pistelor_audio() {
        assertEquals(listOf("en", "es", "ro"), SourceHealth.audioLanguages(listOf("eng", "und", "spa", null, "rum", "en-US")))
        assertEquals("ro", Languages.code("ron"))
        assertNull(Languages.code("qaa"))
        // Playlistul spune „es”, stream-ul are doar engleză: limba sursei devine engleză.
        assertEquals("en", SourceHealth.language("es", listOf("en")))
        // Limba din playlist e printre piste: rămâne.
        assertEquals("ro", SourceHealth.language("ro", listOf("en", "ro")))
        assertEquals("de", SourceHealth.language(null, listOf("de")))
        assertEquals("fr", SourceHealth.language("fr", emptyList()))
    }

    @Test
    fun la_calitate_egala_sursa_stabila_urca() {
        val c = listOf(
            SourceCandidate("instabil", "en", Quality.FHD, stability = 2),
            SourceCandidate("necunoscut", "en", Quality.FHD),
            SourceCandidate("stabil", "en", Quality.FHD, stability = 0),
            SourceCandidate("4k", "en", Quality.UHD, stability = 2),
        )
        assertEquals(listOf("4k", "stabil", "necunoscut", "instabil"), SourceRanking.rank(c, isFavorite = false).map { it.id })
        val v = VariantOrder.sort(listOf("a", "b"), quality = { Quality.HD }, language = { null }, stability = { if (it == "b") 0 else 1 })
        assertEquals(listOf("b", "a"), v)
    }
}
