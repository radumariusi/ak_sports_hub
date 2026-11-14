package ro.ak.sportshub.core.epg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XmltvParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="SkySportsMainEvent.uk">
            <display-name lang="en">Sky Sports Main Event</display-name>
            <display-name>Sky Sp ME</display-name>
          </channel>
          <channel id="digisport1.ro"><display-name>Digi Sport 1</display-name></channel>
          <channel id="unused.fr"><display-name>Canal Inutil</display-name></channel>
          <programme start="20261114200000 +0000" stop="20261114220000 +0000" channel="SkySportsMainEvent.uk">
            <title lang="de">Fußball</title>
            <title lang="en">Arsenal v Chelsea</title>
            <sub-title>Premier League</sub-title>
            <desc lang="en">Live   coverage
              of the match.</desc>
            <category>Sport</category>
          </programme>
          <programme start="20261114220000 +0000" channel="SkySportsMainEvent.uk">
            <title>Highlights &amp; analiză</title>
          </programme>
          <programme start="20261114190000 +0200" stop="20261114210000 +0200" channel="digisport1.ro">
            <title lang="ro">Fotbal: FCSB – CFR Cluj</title>
          </programme>
          <programme start="20261114200000 +0000" stop="20261114210000 +0000" channel="unused.fr">
            <title>Rien</title>
          </programme>
          <programme start="20261201200000 +0000" stop="20261201210000 +0000" channel="digisport1.ro">
            <title>Prea departe</title>
          </programme>
        </tv>
    """.trimIndent()

    private class Collect(val wanted: Set<String>) : XmltvSink {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        override fun onChannel(channel: XmltvChannel) { channels += channel }
        override fun wantsProgrammes(channelId: String) = channelId in wanted
        override fun onProgramme(programme: XmltvProgramme) { programmes += programme }
    }

    private val day = XmltvTime.parse("20261114000000 +0000")!!..XmltvTime.parse("20261116000000 +0000")!!

    @Test
    fun `citește canale și emisiuni, filtrează canalele și fereastra`() {
        val sink = Collect(setOf("SkySportsMainEvent.uk", "digisport1.ro"))
        val stats = XmltvParser(window = day, preferredLanguages = listOf("ro", "en"))
            .parse(ByteArrayInputStream(sample.toByteArray()), sink)

        assertEquals(3, stats.channels)
        assertEquals(3, stats.programmes)
        assertEquals(2, stats.skipped)
        assertNull(stats.partialError)
        assertEquals(listOf("Sky Sports Main Event", "Sky Sp ME"), sink.channels[0].displayNames)

        val match = sink.programmes[0]
        assertEquals("Arsenal v Chelsea", match.title) // engleza e preferată față de germană
        assertEquals("Premier League", match.subtitle)
        assertEquals("Live coverage of the match.", match.description)
        assertEquals("Sport", match.category)
        assertEquals(XmltvTime.parse("20261114220000 +0000"), match.stop)

        assertEquals("Highlights & analiză", sink.programmes[1].title)
        assertNull(sink.programmes[1].stop)

        // 19:00 +0200 = 17:00 UTC
        assertEquals(XmltvTime.parse("20261114170000 +0000"), sink.programmes[2].start)
    }

    @Test
    fun `decalajul se adună la ore`() {
        val sink = Collect(setOf("digisport1.ro"))
        XmltvParser(window = day, shiftMs = 3_600_000).parse(ByteArrayInputStream(sample.toByteArray()), sink)
        assertEquals(XmltvTime.parse("20261114180000 +0000"), sink.programmes.single().start)
    }

    @Test
    fun `fișierele gz sunt dezarhivate automat`() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(sample.toByteArray()) } }
        val sink = Collect(setOf("digisport1.ro"))
        val stats = XmltvParser(window = day).parse(ByteArrayInputStream(bytes.toByteArray()), sink)
        assertEquals(1, stats.programmes)
    }

    @Test
    fun `fișier tăiat la mijloc păstrează ce s-a citit`() {
        val cut = sample.substring(0, sample.indexOf("<programme start=\"20261114190000"))  + "<programme start="
        val sink = Collect(setOf("SkySportsMainEvent.uk"))
        val stats = XmltvParser(window = day).parse(ByteArrayInputStream(cut.toByteArray()), sink)
        assertEquals(2, stats.programmes)
        assertNotNull(stats.partialError)
    }

    @Test
    fun `o pagină de eroare nu e EPG`() {
        try {
            XmltvParser().parse(ByteArrayInputStream("<html><body>403</body></html>".toByteArray()), Collect(emptySet()))
            fail()
        } catch (e: EpgException) {
            assertTrue(e.message!!.contains("XMLTV"))
        }
        try {
            XmltvParser().parse(ByteArrayInputStream("nu e xml".toByteArray()), Collect(emptySet()))
            fail()
        } catch (e: EpgException) {
            assertTrue(e.message!!.contains("XMLTV"))
        }
    }

    @Test
    fun `orele XMLTV`() {
        assertEquals(1_794_686_400_000, XmltvTime.parse("20261114200000 +0000"))
        assertEquals(1_794_686_400_000, XmltvTime.parse("20261114210000 +0100"))
        assertEquals(1_794_686_400_000, XmltvTime.parse("20261114213000+01:30"))
        assertEquals(1_794_686_400_000, XmltvTime.parse("202611142000"))
        assertEquals(1_794_686_400_000, XmltvTime.parse("20261114200000 UTC"))
        assertNull(XmltvTime.parse("mâine"))
        assertNull(XmltvTime.parse("20261340200000"))
    }
}
