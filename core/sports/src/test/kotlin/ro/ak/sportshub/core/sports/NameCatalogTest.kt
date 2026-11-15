package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameCatalogTest {

    private fun ev(home: String, away: String, homeAlt: List<String> = emptyList(), competition: String = "English Premier League") =
        SportEvent(Espn.PROVIDER, "$home-$away", "Soccer", competition, home, away, 1_800_000_000_000L, homeAlt = homeAlt)

    private val catalog = NameCatalog.EMPTY.learn(
        listOf(
            ev("Manchester United", "Arsenal", listOf("Man United", "MUN")),
            ev("Manchester City", "Chelsea", listOf("Man City", "MNC")),
            ev("Carolina Panthers", "New Orleans Saints", listOf("Panthers", "CAR"), competition = "NFL"),
            ev("Florida Panthers", "Boston Bruins", listOf("Panthers", "FLA"), competition = "NHL"),
        ),
    )

    @Test
    fun cauta_dupa_inceputul_cuvintelor_si_formele_scurte() {
        assertEquals("Manchester United", catalog.search("man u").first().name)
        assertEquals("Manchester United", catalog.search("Man. United").first().name)
        assertEquals("Manchester United", catalog.search("mun").first().name)
        assertEquals("Manchester City", catalog.search("man city").first().name)
        assertEquals("English Premier League", catalog.search("premier", setOf(NameCatalog.Kind.COMPETITION)).first().name)
    }

    @Test
    fun formele_scurte_unice_devin_aliasuri() {
        val aliases = catalog.teamAliases()
        assertEquals(TeamNames.key("Manchester United"), aliases["man united"])
        // „Panthers” e și Carolina, și Florida: nu se ia.
        assertTrue("panthers" !in aliases)
        // Codurile scurte nu devin aliasuri.
        assertTrue("mun" !in aliases)
    }

    @Test
    fun se_pastreaza_in_fisier() {
        val back = NameCatalog.decode(catalog.encode())
        assertEquals(catalog.size, back.size)
        assertEquals("Manchester United", back.search("man u").first().name)
    }
}
