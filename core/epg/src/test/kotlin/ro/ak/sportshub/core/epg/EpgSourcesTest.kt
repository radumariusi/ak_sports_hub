package ro.ak.sportshub.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourcesTest {

    private val m3u = PlaylistEpgInfo(
        playlistId = 1, name = "Acasă", enabled = true, url = "http://prov.example:8080/get.php?u=a",
        xtream = false, epgUrls = listOf("http://prov.example:8080/xmltv.php?u=a", "https://epg.other.example/ro.xml.gz"),
    )
    private val xtream = PlaylistEpgInfo(
        playlistId = 2, name = "Sport", enabled = true, url = "http://x.example",
        xtream = true, epgUrls = listOf("http://x.example/xmltv.php?username=u&password=p"),
    )
    private val off = PlaylistEpgInfo(3, "Oprit", false, "http://o.example", false, listOf("http://o.example/e.xml"))

    @Test
    fun `sursele din playlisturi și cele externe`() {
        val ext = ExternalEpg(id = "a", url = "https://epg.example/all.xml.gz", name = "")
        val sources = EpgSources.resolve(listOf(m3u, xtream, off), EpgSettings(external = listOf(ext)))
        assertEquals(4, sources.size)

        val (own, other, provider, external) = sources
        assertTrue("același server cu playlistul: trece prin poarta de conexiune", own.gated)
        assertFalse("alt server: nu ocupă conexiunea playlistului", other.gated)
        assertTrue(provider.gated)
        assertEquals("EPG furnizor · Sport", provider.label)
        assertFalse("eticheta nu conține adresa", provider.label.contains("password"))
        assertEquals("Acasă · epg.other.example", other.label)
        assertEquals("epg.example", external.label)
        assertNull(external.playlistId)
        assertFalse(external.gated)
    }

    @Test
    fun `setările unei surse din playlist se păstrează după cheie`() {
        val key = EpgSources.playlistKey(1, m3u.epgUrls[1])
        val settings = EpgSettings(overrides = mapOf(key to EpgOverride(enabled = false, shiftMinutes = 60)))
        val s = EpgSources.resolve(listOf(m3u), settings).single { it.key == key }
        assertFalse(s.enabled)
        assertEquals(60, s.shiftMinutes)
    }

    @Test
    fun `prioritatea - EPG-ul propriu, apoi extern, apoi alte playlisturi`() {
        val ext = ExternalEpg(id = "a", url = "https://epg.example/all.xml", name = "Extern")
        val sources = EpgSources.resolve(listOf(m3u, xtream), EpgSettings(external = listOf(ext)))
        val p = EpgSources.priorityFor(2, sources)
        val own = sources.first { it.playlistId == 2L }.key
        val foreign = sources.first { it.playlistId == 1L }.key
        assertTrue(p(own) < p(EpgSources.externalKey("a")))
        assertTrue(p(EpgSources.externalKey("a")) < p(foreign))
    }

    @Test
    fun `acum și urmează`() {
        val a = Programme(0, 100, "A")
        val b = Programme(100, 200, "B")
        val c = Programme(250, 300, "C")
        assertEquals(NowNext(a, b), NowNext.of(listOf(a, b, c), 50))
        assertEquals(NowNext(null, c), NowNext.of(listOf(a, b, c), 220))
        assertEquals(0.5f, b.progress(150))
    }

    @Test
    fun `când e nevoie de actualizare`() {
        val p = EpgUpdatePolicy(intervalHours = 12)
        val h = 3_600_000L
        assertTrue(p.isDue(null, null, false, 100 * h))
        assertFalse(p.isDue(95 * h, 95 * h, false, 100 * h))
        assertTrue(p.isDue(80 * h, 80 * h, false, 100 * h))
        assertFalse("după o eroare, cel mult o dată pe oră", p.isDue(null, 100 * h - 10, true, 100 * h))
        assertFalse(EpgUpdatePolicy(intervalHours = 0).isDue(1, 1, false, 100 * h))
    }
}
