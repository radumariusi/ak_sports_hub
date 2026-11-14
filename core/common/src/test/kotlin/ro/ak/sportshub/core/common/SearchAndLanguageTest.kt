package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndLanguageTest {

    @Test
    fun `textul de cautare e fara diacritice si fara semne`() {
        assertEquals("ro digi sport 1 hd", SearchText.of("RO| Digi Sport 1 ᴴᴰ".replace("ᴴᴰ", "HD")))
        assertEquals("tvr 1 romania", SearchText.of("TVR 1 (România)"))
    }

    @Test
    fun `interogarea fts cauta inceput de cuvant si ignora caracterele speciale`() {
        assertEquals("sky* main*", SearchText.ftsQuery("  Sky \"MAIN\" "))
        assertEquals("sport* romania*", SearchText.ftsQuery("sport românia"))
        assertNull(SearchText.ftsQuery(" * - "))
    }

    @Test
    fun `potrivire in memorie pentru grupuri`() {
        assertTrue(SearchText.matches("UK | SPORTS", "sport"))
        assertTrue(SearchText.matches("RO | Sport Românesc", "ro rom"))
        assertFalse(SearchText.matches("UK | NEWS", "sport"))
        assertTrue(SearchText.matches("Orice", ""))
    }

    @Test
    fun `limba din tvg-language, apoi din prefixul de tara`() {
        assertEquals("ro", Languages.guess("Romanian", "UK"))
        assertEquals("en", Languages.guess("English;Romanian", null))
        assertEquals("de", Languages.guess("de", null))
        assertEquals("ro", Languages.guess("Română", null))
        assertEquals("en", Languages.guess(null, "UK"))
        assertEquals("ro", Languages.guess("", "RO"))
        assertNull(Languages.guess(null, null))
        assertNull(Languages.guess("Klingon", "XX"))
    }
}
