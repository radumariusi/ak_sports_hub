package ro.ak.sportshub.core.epg

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Calea rapidă de citire a orelor și a textelor dă exact ce dădea calea completă. */
class XmltvSpeedTest {

    @Test
    fun `ora citita rapid e aceeasi cu cea calculata cu java time`() {
        val rnd = Random(42)
        val zones = listOf("", " +0000", " +0100", "+0200", " -0500", " +05:30", " UTC", " GMT", "  +1245")
        repeat(20_000) {
            val y = 1990 + rnd.nextInt(60)
            val m = 1 + rnd.nextInt(12)
            val d = 1 + rnd.nextInt(28)
            val h = rnd.nextInt(24)
            val mi = rnd.nextInt(60)
            val s = rnd.nextInt(60)
            val z = zones[rnd.nextInt(zones.size)]
            val text = "%04d%02d%02d%02d%02d%02d%s".format(y, m, d, h, mi, s, z)
            val offset = z.trim().let { t ->
                if (t.isEmpty() || t[0].isLetter()) ZoneOffset.UTC
                else ZoneOffset.of(t.substring(0, 3) + ":" + t.replace(":", "").substring(3, 5))
            }
            val expected = LocalDateTime.of(y, m, d, h, mi, s).toInstant(offset).toEpochMilli()
            assertEquals(text, expected, XmltvTime.parse(text))
        }
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), XmltvTime.parse("20240229000000"))
        assertNull(XmltvTime.parse("20230229000000"))
        assertNull(XmltvTime.parse("20231301000000 +0100"))
        assertNull(XmltvTime.parse("abc"))
        // Formele scurte trec pe calea completă.
        assertEquals(LocalDateTime.of(2026, 11, 14, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), XmltvTime.parse("202611142000"))
    }

    @Test
    fun `spatiile se strang ca inainte`() {
        assertEquals("a b c", XmltvParser.collapse("  a   b\n\tc  "))
        assertEquals("abc", XmltvParser.collapse("abc"))
        assertEquals("a b", XmltvParser.collapse("a b"))
        assertEquals("", XmltvParser.collapse("   "))
        assertEquals("a b", XmltvParser.collapse("a\nb"))
    }
}
