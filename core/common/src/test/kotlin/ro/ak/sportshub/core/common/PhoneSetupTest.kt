package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupTest {

    @Test
    fun `formularul Xtream trimis din telefon`() {
        val form = PhoneSetup.parseForm("type=xtream&name=Acasa&server=http%3A%2F%2Fexemplu.com%3A8080&username=ion&password=p%40ss+word&epg=")
        val s = PhoneSetup.read(form).getOrThrow() as PhoneSetup.Submission.Xtream
        assertEquals("http://exemplu.com:8080", s.server)
        assertEquals("ion", s.username)
        assertEquals("p@ss word", s.password)
        assertEquals(null, s.epgUrl)
        assertEquals("Acasa", s.name)
    }

    @Test
    fun `formularul M3U si erorile`() {
        val ok = PhoneSetup.read(mapOf("type" to "m3u", "url" to "https://x/get.php?a=1", "epg" to "https://e/guide.xml.gz")).getOrThrow()
        assertTrue(ok is PhoneSetup.Submission.M3u)
        assertEquals("https://e/guide.xml.gz", ok.epgUrl)
        assertTrue(PhoneSetup.read(mapOf("type" to "m3u", "url" to "ftp://x")).isFailure)
        assertTrue(PhoneSetup.read(mapOf("type" to "xtream", "server" to "x.com", "username" to "a")).isFailure)
        assertTrue(PhoneSetup.read(mapOf("type" to "xtream", "server" to "x.com", "username" to "a", "password" to "b", "epg" to "guide.xml")).isFailure)
        assertTrue(PhoneSetup.read(emptyMap()).isFailure)
    }

    @Test
    fun `pagina nu pastreaza parola si scapa textul`() {
        val page = PhoneSetup.formPage("Eroare <b>", mapOf("type" to "xtream", "server" to "a\"><script>", "password" to "secret"))
        assertFalse(page.contains("secret"))
        assertFalse(page.contains("<script>\""))
        assertTrue(page.contains("Eroare &lt;b&gt;"))
        assertEquals(8, PhoneSetup.newToken().length)
    }
}
