package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTextTest {

    @Test
    fun `adresele pierd cheile, cheia gratuita ramane`() {
        assertEquals(
            "https://www.thesportsdb.com/api/v1/json/***/eventsday.php?d=2026-11-15",
            DiagnosticText.sanitizeUrl("https://www.thesportsdb.com/api/v1/json/abc987/eventsday.php?d=2026-11-15"),
        )
        assertEquals(
            "https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-11-15",
            DiagnosticText.sanitizeUrl("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-11-15"),
        )
        assertEquals("https://x.com/a?date=1&token=***&b=2", DiagnosticText.sanitizeUrl("https://x.com/a?date=1&token=secret&b=2"))
        assertEquals("https://liveonsat.com/2day.php", DiagnosticText.sanitizeUrl("https://liveonsat.com/2day.php"))
    }

    @Test
    fun `html scurtat pastreaza datele din NEXT_DATA`() {
        val html = """
            <html><head><style>body { color: red }</style>
            <script>var x = 1; trackEverything();</script>
            <script id="__NEXT_DATA__" type="application/json">{"props":{"matches":[1,2]}}</script>
            </head><body>   <!-- reclamă -->
            <div class="m">Arsenal   v   Chelsea</div></body></html>
        """.trimIndent()
        val out = DiagnosticText.condense(html)
        assertTrue(out.contains("""{"props":{"matches":[1,2]}}"""))
        assertFalse(out.contains("trackEverything"))
        assertFalse(out.contains("color: red"))
        assertFalse(out.contains("reclamă"))
        assertTrue(out.contains("Arsenal v Chelsea"))
    }

    @Test
    fun `json ramane, textul lung se taie si se imparte`() {
        val json = """{"events":[]}"""
        assertEquals(json, DiagnosticText.condense(json))
        val long = (1..5000).joinToString("\n") { "rândul $it" }
        val cut = DiagnosticText.cut(long, 100)
        assertTrue(cut.startsWith("rândul 1"))
        assertTrue(cut.contains("tăiat"))
        val parts = DiagnosticText.chunks(long, 1000)
        assertEquals(long, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1000 })
        assertTrue(parts.dropLast(1).all { it.endsWith("\n") })
    }

    @Test
    fun `refuzul in json e recunoscut`() {
        assertNotNull(DiagnosticText.jsonError("""{"error":{"code":403,"reason":"challenge"}}"""))
        assertNotNull(DiagnosticText.jsonError("""  { "error" : "Forbidden" }"""))
        assertNull(DiagnosticText.jsonError("""{"events":[{"id":1,"error":null}]}"""))
        assertNull(DiagnosticText.jsonError("""[{"error":1}]"""))
        assertNull(DiagnosticText.jsonError("<html>"))
    }
}
