package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.ChannelCorrection
import ro.ak.sportshub.core.model.ChannelLabels
import ro.ak.sportshub.core.model.Quality

class ChannelVariantsTest {

    private data class V(val id: String, val key: String, val quality: Quality, val lang: String?)

    private fun sort(items: List<V>, preferred: String? = null, failed: Set<String> = emptySet()) =
        VariantOrder.sort(
            items,
            quality = { it.quality },
            language = { it.lang },
            isPreferred = { it.id == preferred },
            failedRecently = { it.id in failed },
        ).map { it.id }

    @Test
    fun `douazeci de variante Sky Sport Main Event devin un singur canal`() {
        val names = listOf("UHD", "FHD", "HD", "SD", "1080p HEVC", "ᴴᴰ", "SD (1)", "FHD 50FPS")
            .flatMap { q -> listOf("UK: Sky Sport Main Event $q", "UK| SKY SPORT MAIN EVENT $q") }
            .plus(listOf("UK: Sky Sport Main Event RAW", "UK: Sky Sport Main Event BACKUP", "UK: Sky Sport Main Event VIP", "UK: Sky Sport Main Event"))
        val parsed = (names + "UK: Sky Sports Premier League HD").map { ChannelNameParser.parse(it) }
        val families = ChannelFamilies.collapse(parsed) { it.groupKey }
        assertEquals(2, families.size)
        assertEquals(20, families.first().members.size)
        // Numele original și calitatea rămân la fiecare variantă.
        assertEquals("UK: Sky Sport Main Event UHD", families.first().members.first().displayName)
        assertEquals(Quality.UHD, families.first().members.first().quality)
    }

    @Test
    fun `familiile pastreaza ordinea primei aparitii`() {
        val items = listOf(V("1", "a", Quality.SD, null), V("2", "b", Quality.SD, null), V("3", "a", Quality.HD, null))
        val families = ChannelFamilies.collapse(items) { it.key }
        assertEquals(listOf("a", "b"), families.map { it.key })
        assertEquals(listOf("1", "3"), families.first().members.map { it.id })
    }

    @Test
    fun `variantele se ordoneaza dupa rezolutie apoi limba preferata`() {
        val items = listOf(
            V("sd-en", "k", Quality.SD, "en"),
            V("fhd-de", "k", Quality.FHD, "de"),
            V("fhd-ro", "k", Quality.FHD, "ro"),
            V("4k-fr", "k", Quality.UHD, "fr"),
            V("fhd-en", "k", Quality.FHD, "en"),
            V("necunoscut", "k", Quality.UNKNOWN, null),
        )
        assertEquals(listOf("4k-fr", "fhd-en", "fhd-ro", "fhd-de", "sd-en", "necunoscut"), sort(items))
    }

    @Test
    fun `varianta preferata e prima, cele care n-au pornit coboara dar raman`() {
        val items = listOf(
            V("4k", "k", Quality.UHD, "en"),
            V("fhd", "k", Quality.FHD, "en"),
            V("sd", "k", Quality.SD, "en"),
        )
        assertEquals(listOf("sd", "4k", "fhd"), sort(items, preferred = "sd"))
        assertEquals(listOf("fhd", "sd", "4k"), sort(items, failed = setOf("4k")))
    }

    @Test
    fun `failover incearca fiecare varianta o singura data, in ordine`() {
        val plan = FailoverPlan(listOf("4k", "fhd", "hd", "sd"), current = "fhd")
        assertEquals("4k", plan.next())
        plan.moveTo("4k")
        assertEquals("hd", plan.next())
        plan.moveTo("hd")
        plan.moveTo("sd")
        assertNull(plan.next())
        assertEquals(4, plan.triedCount)
        assertEquals(4, plan.size)
    }

    @Test
    fun `failover fara alte variante nu are unde sa treaca`() {
        assertNull(FailoverPlan(listOf("unic"), current = "unic").next())
    }

    @Test
    fun `corectia schimba doar ce are completat`() {
        val labels = ChannelLabels("uk:sky sport main event", Quality.HD, "en")
        assertEquals(labels.copy(quality = Quality.UHD), ChannelCorrection(quality = Quality.UHD).applyTo(labels))
        assertEquals(labels.copy(language = null), ChannelCorrection(language = "").applyTo(labels))
        assertEquals(labels.copy(groupKey = "uk:sky sports main event"), ChannelCorrection(groupKey = "uk:sky sports main event").applyTo(labels))
        assertTrue(ChannelCorrection().isEmpty)
        assertEquals(labels, ChannelCorrection().applyTo(labels))
    }
}
