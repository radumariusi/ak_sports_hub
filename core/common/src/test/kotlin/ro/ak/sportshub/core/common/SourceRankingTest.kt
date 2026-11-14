package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

class SourceRankingTest {

    private fun s(id: String, lang: String?, q: Quality) = SourceCandidate(id, lang, q)

    @Test
    fun `favorit - doar engleza sau romana`() {
        val r = SourceRanking.rank(
            listOf(s("de4k", "de", Quality.UHD), s("roHd", "ro", Quality.HD), s("enSd", "en", Quality.SD)),
            isFavorite = true,
        )
        assertEquals(listOf("roHd", "enSd"), r.map { it.id })
    }

    @Test
    fun `favorit fara surse en sau ro - arata restul`() {
        val r = SourceRanking.rank(listOf(s("de", "de", Quality.HD)), isFavorite = true)
        assertEquals(listOf("de"), r.map { it.id })
    }

    @Test
    fun `nefavorit cu sursa preferata FHD - doar limbile preferate`() {
        val r = SourceRanking.rank(
            listOf(s("de4k", "de", Quality.UHD), s("enFhd", "en", Quality.FHD), s("roHd", "ro", Quality.HD)),
            isFavorite = false,
        )
        assertEquals(listOf("enFhd", "roHd"), r.map { it.id })
    }

    @Test
    fun `nefavorit fara FHD in limbile preferate - intra si alte limbi de la FHD in sus`() {
        val r = SourceRanking.rank(
            listOf(s("roHd", "ro", Quality.HD), s("deFhd", "de", Quality.FHD), s("plHd", "pl", Quality.HD)),
            isFavorite = false,
        )
        assertEquals(listOf("deFhd", "roHd"), r.map { it.id })
    }
}
