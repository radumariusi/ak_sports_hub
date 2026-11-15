package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

class ObservedVideoTest {

    @Test
    fun `calitatea vine din rezolutia decodata`() {
        assertEquals(Quality.UHD, ObservedVideo(3840, 2160).quality)
        assertEquals(Quality.FHD, ObservedVideo(1920, 1080).quality)
        assertEquals(Quality.FHD, ObservedVideo(1440, 1080).quality)
        assertEquals(Quality.HD, ObservedVideo(1280, 720).quality)
        assertEquals(Quality.SD, ObservedVideo(720, 576).quality)
        assertEquals("FHD 50p", ObservedVideo(1920, 1080, 50f).label)
        assertEquals("FHD 60p", ObservedVideo(1920, 1080, 59.94f).label)
        assertEquals("HD", ObservedVideo(1280, 720, null).label)
    }

    @Test
    fun `eticheta care bate primeste verificat`() {
        val v = ObservedQuality.view(Quality.FHD, manual = false, observed = ObservedVideo(1920, 1080, 50f))
        assertTrue(v.verified)
        assertEquals("FHD 50p", v.label)
        assertNull(v.playlistLabel)
    }

    @Test
    fun `eticheta gresita e inlocuita, cea din playlist ramane taiata`() {
        val v = ObservedQuality.view(Quality.UHD, manual = false, observed = ObservedVideo(1920, 1080, 50f))
        assertFalse(v.verified)
        assertEquals(Quality.FHD, v.quality)
        assertEquals("FHD 50p", v.label)
        assertEquals("4K", v.playlistLabel)
    }

    @Test
    fun `fara eticheta in playlist primeste eticheta reala`() {
        val v = ObservedQuality.view(Quality.UNKNOWN, manual = false, observed = ObservedVideo(1280, 720, 25f))
        assertEquals(Quality.HD, v.quality)
        assertNull(v.playlistLabel)
    }

    @Test
    fun `corectia manuala are prioritate`() {
        val v = ObservedQuality.view(Quality.UHD, manual = true, observed = ObservedVideo(1920, 1080))
        assertEquals(Quality.UHD, v.quality)
        assertEquals("4K", v.label)
        assertFalse(v.verified)
    }

    @Test
    fun `ordinea variantelor foloseste rezolutia reala`() {
        data class V(val id: String, val label: Quality, val seen: ObservedVideo?)
        val items = listOf(V("fals4k", Quality.UHD, ObservedVideo(1920, 1080)), V("fhd", Quality.FHD, null), V("uhd", Quality.FHD, ObservedVideo(3840, 2160)))
        val sorted = VariantOrder.sort(items, quality = { ObservedQuality.effective(it.label, false, it.seen) }, language = { null }).map { it.id }
        assertEquals(listOf("uhd", "fals4k", "fhd"), sorted)
    }

    @Test
    fun `frecventa ecranului se alege dupa cadre`() {
        val modes = listOf(
            DisplayModeInfo(1, 1920, 1080, 60f), DisplayModeInfo(2, 1920, 1080, 50f),
            DisplayModeInfo(3, 1920, 1080, 59.94f), DisplayModeInfo(4, 3840, 2160, 50f),
        )
        val current = modes[0]
        assertEquals(2, RefreshRateMatch.pick(modes, current, 25f)?.id)
        assertEquals(2, RefreshRateMatch.pick(modes, current, 50f)?.id)
        assertEquals(3, RefreshRateMatch.pick(modes, current, 29.97f)?.id)
        assertNull(RefreshRateMatch.pick(modes, current, 30f)) // 60 Hz merge deja
        assertNull(RefreshRateMatch.pick(modes, current, null))
        assertNull(RefreshRateMatch.pick(modes, current, 24f)) // niciun mod potrivit
        assertFalse(RefreshRateMatch.fits(60f, 59.94f))
        // Doar 4K are 50 Hz: se trece pe el.
        val only4k = listOf(DisplayModeInfo(1, 1920, 1080, 60f), DisplayModeInfo(5, 3840, 2160, 60f), DisplayModeInfo(6, 3840, 2160, 50f))
        assertEquals(6, RefreshRateMatch.pick(only4k, only4k[0], 25f)?.id)
    }
}
