package ro.ak.sportshub.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    private val skyUk = EpgChannelRef(1, "own", "SkySportsMainEvent.uk", listOf("Sky Sports Main Event"))
    private val skyExt = EpgChannelRef(2, "ext", "sky.main.event", listOf("Sky Sports Main Event"))
    private val sport1De = EpgChannelRef(3, "ext", "Sport1.de", listOf("Sport 1"))
    private val digi = EpgChannelRef(4, "ext", "digisport1.ro", listOf("Digi Sport 1 HD"))
    private val bbc = EpgChannelRef(5, "ext", "bbc1", listOf("BBC One"))

    private val priority = mapOf("own" to 0, "ext" to 1000)
    private val matcher = EpgMatcher(listOf(skyUk, skyExt, sport1De, digi, bbc)) { priority[it] ?: Int.MAX_VALUE }

    private fun ch(name: String, tvgId: String? = null, tvgName: String? = null, group: String? = null) =
        ChannelForEpg(0, tvgId, tvgName, name, group)

    @Test
    fun `tvg-id are prioritate, fără să conteze literele mari`() {
        assertEquals(bbc, matcher.match(ch("Ceva Oarecare", tvgId = "BBC1")))
        assertEquals(digi, matcher.match(ch("RO: Digi Sport 1 FHD", tvgId = "digisport1.ro")))
    }

    @Test
    fun `numele se potrivește fără calitate, țară și spații`() {
        assertEquals(digi, matcher.match(ch("RO: Digi Sport 1 FHD")))
        assertEquals(digi, matcher.match(ch("Digi Sport 1 ᴴᴰ")))
        assertEquals(bbc, matcher.match(ch("UK: BBC ONE HD")))
    }

    @Test
    fun `aceeași țară întâi, apoi sursa cu prioritate mai bună`() {
        // „SkySportsMainEvent.uk” are țara uk din id; canalul are UK din prefix.
        assertEquals(skyUk, matcher.match(ch("UK: Sky Sports Main Event UHD")))
        // Fără țară: ambele candidate, câștigă sursa proprie a playlistului.
        assertEquals(skyUk, matcher.match(ch("Sky Sports Main Event")))
        val externalFirst = EpgMatcher(listOf(skyUk, skyExt)) { if (it == "ext") 0 else 1 }
        assertEquals(skyExt, externalFirst.match(ch("Sky Sports Main Event")))
    }

    @Test
    fun `țări diferite nu se potrivesc`() {
        assertNull(matcher.match(ch("UK: Sport 1")))
        assertEquals(sport1De, matcher.match(ch("DE: Sport 1 HD")))
        assertEquals(sport1De, matcher.match(ch("Sport 1")))
    }

    @Test
    fun `tvg-name înaintea numelui afișat`() {
        assertEquals(bbc, matcher.match(ch("Canal 1 VIP", tvgName = "BBC One")))
    }

    @Test
    fun `cheile dorite păstrează doar canalele EPG folosite`() {
        val wanted = EpgMatcher.wantedKeys(
            sequenceOf(ch("RO: Digi Sport 1 FHD"), ch("Orice", tvgId = "BBC1")),
        )
        assertTrue(wanted.wants(XmltvChannel("digisport1.ro", listOf("Digi Sport 1"))))
        assertTrue(wanted.wants(XmltvChannel("bbc1", emptyList())))
        assertTrue(wanted.wantsId("bbc1"))
        assertFalse(wanted.wants(XmltvChannel("x.fr", listOf("TF1"))))
    }

    @Test
    fun `id-ul XMLTV ca nume`() {
        assertEquals(EpgNames.Key("skysportsmainevent", "uk"), EpgNames.ofXmltvId("SkySportsMainEvent.uk"))
        assertEquals(EpgNames.Key("digisport1", null), EpgNames.ofXmltvId("Digi.Sport.1"))
    }
}
