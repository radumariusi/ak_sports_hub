package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

class ChannelNameParserTest {

    @Test
    fun `variantele aceluiasi canal au aceeasi cheie dar calitati diferite`() {
        val names = listOf(
            "UK: Sky Sport Main Event UHD",
            "UK: Sky Sport Main Event FHD",
            "UK | Sky Sport Main Event ᴴᴰ",
            "|UK| Sky Sport Main Event SD (1)",
            "UK: Sky Sport Main Event 1080p HEVC 50FPS",
        ).map { ChannelNameParser.parse(it) }

        assertEquals(1, names.map { it.groupKey }.toSet().size)
        assertEquals("uk:sky sport main event", names.first().groupKey)
        assertEquals(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD, Quality.FHD),
            names.map { it.quality },
        )
    }

    @Test
    fun `numele afisat ramane cel original, cu eticheta de calitate`() {
        val c = ChannelNameParser.parse("RO| Digi Sport 1 FHD")
        assertEquals("RO| Digi Sport 1 FHD", c.displayName)
        assertEquals(Quality.FHD, c.quality)
        assertEquals("RO", c.country)
        assertEquals("ro:digi sport 1", c.groupKey)
    }

    @Test
    fun `tara din grup daca numele nu are prefix`() {
        val c = ChannelNameParser.parse("Prima Sport 2 HD", groupTitle = "RO | Sport")
        assertEquals("RO", c.country)
        assertEquals("ro:prima sport 2", c.groupKey)
    }

    @Test
    fun `aceeasi denumire din tari diferite nu se amesteca`() {
        assertNotEquals(
            ChannelNameParser.parse("UK: Eurosport 1 HD").groupKey,
            ChannelNameParser.parse("DE: Eurosport 1 HD").groupKey,
        )
    }

    @Test
    fun `diacriticele nu conteaza`() {
        assertEquals(
            ChannelNameParser.parse("RO: Antena Stars HD").groupKey,
            ChannelNameParser.parse("RO: Antenă Stars").groupKey,
        )
    }

    @Test
    fun `fara eticheta calitatea e necunoscuta`() {
        assertEquals(Quality.UNKNOWN, ChannelNameParser.parse("DAZN 1").quality)
    }

    @Test
    fun `numerele din numele canalului raman`() {
        assertNotEquals(
            ChannelNameParser.parse("Digi Sport 1 HD").groupKey,
            ChannelNameParser.parse("Digi Sport 2 HD").groupKey,
        )
    }
}

class SeparatorChannelTest {
    @org.junit.Test
    fun `separatoarele din lista nu sunt canale`() {
        listOf("#### DAZN LALIGA VIP SD ####", "##### SPORTS", "===== UK =====", "--- ES ---", "★★ USA ★★", "*****", "  ").forEach {
            org.junit.Assert.assertTrue(it, ChannelNameParser.isSeparator(it))
        }
        listOf("#0 Movistar", "#Vamos FHD", "|UK| Sky Sports Main Event", "DAZN LaLiga 1 FHD", "Sport 1 *", "ESPN 2 -").forEach {
            org.junit.Assert.assertFalse(it, ChannelNameParser.isSeparator(it))
        }
    }
}
