package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

class SourceRankingTest {

    private fun s(id: String, lang: String?, q: Quality) = SourceCandidate(id, lang, q)

    private val all = listOf(
        s("frSd", "fr", Quality.SD),
        s("roHd", "ro", Quality.HD),
        s("deFhd", "de", Quality.FHD),
        s("enFhd", "en", Quality.FHD),
        s("de4k", "de", Quality.UHD),
        s("ro4k", "ro", Quality.UHD),
        s("enSd", "en", Quality.SD),
        s("plHd", "pl", Quality.HD),
    )

    @Test
    fun `favorit - EN-RO 4K si FHD, apoi alte limbi 4K si FHD, apoi HD si SD`() {
        val r = SourceRanking.rank(all, isFavorite = true)
        assertEquals(
            listOf("ro4k", "enFhd", "de4k", "deFhd", "roHd", "enSd", "plHd", "frSd"),
            r.map { it.id },
        )
    }

    @Test
    fun `favorit fara EN-RO de la FHD in sus - prima e alta limba la calitate buna`() {
        val r = SourceRanking.rank(
            listOf(s("roHd", "ro", Quality.HD), s("frFhd", "fr", Quality.FHD), s("frSd", "fr", Quality.SD)),
            isFavorite = true,
        )
        assertEquals(listOf("frFhd", "roHd", "frSd"), r.map { it.id })
    }

    @Test
    fun `nefavorit - primeaza rezolutia, in orice limba`() {
        val r = SourceRanking.rank(all, isFavorite = false)
        assertEquals(
            listOf("ro4k", "de4k", "enFhd", "deFhd", "roHd", "plHd", "enSd", "frSd"),
            r.map { it.id },
        )
    }

    @Test
    fun `singura sursa SD in alta limba apare oricum`() {
        val only = listOf(s("frSd", "fr", Quality.SD))
        assertEquals(only, SourceRanking.rank(only, isFavorite = false))
        assertEquals(only, SourceRanking.rank(only, isFavorite = true))
    }

    @Test
    fun `calitate necunoscuta vine ultima`() {
        val r = SourceRanking.rank(
            listOf(s("x", "en", Quality.UNKNOWN), s("sd", "fr", Quality.SD)),
            isFavorite = false,
        )
        assertEquals(listOf("sd", "x"), r.map { it.id })
    }
}
