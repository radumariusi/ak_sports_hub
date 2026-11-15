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

    @Test
    fun lista_fixa_competitii_prescurtate_si_sportivi() {
        val c = NameCatalog.EMPTY
        assertEquals("UEFA Champions League", c.search("ucl", setOf(NameCatalog.Kind.COMPETITION)).first().name)
        assertEquals("English Premier League", c.search("EPL", setOf(NameCatalog.Kind.COMPETITION)).first().name)
        assertEquals("Romanian Liga I", c.search("liga 1", setOf(NameCatalog.Kind.COMPETITION)).first().name)
        assertEquals("Ronnie O'Sullivan", c.search("o sull", setOf(NameCatalog.Kind.ATHLETE)).first().name)
        assertEquals("Michael van Gerwen", c.search("mvg", setOf(NameCatalog.Kind.ATHLETE)).first().name)
        assertEquals("Manchester United", c.search("man utd", setOf(NameCatalog.Kind.TEAM)).first().name)
    }

    @Test
    fun aceeasi_echipa_apare_o_data() {
        val found = catalog.search("manchester united", setOf(NameCatalog.Kind.TEAM))
        assertEquals(1, found.count { TeamNames.key(it.name) == TeamNames.key("Manchester United") })
    }

    @Test
    fun sportivii_din_curse_intra_in_catalog() {
        val race = SportEvent(Espn.PROVIDER, "f1-1", "Motorsport", "Formula 1", "Las Vegas Grand Prix", "", 1_800_000_000_000L,
            athletes = listOf("Yuki Tsunoda" to "Y. Tsunoda"))
        val c = NameCatalog.EMPTY.learn(listOf(race))
        assertEquals("Yuki Tsunoda", c.search("tsun", setOf(NameCatalog.Kind.ATHLETE)).first().name)
        val back = NameCatalog.decode(c.encode())
        assertEquals("Yuki Tsunoda", back.search("y. tsunoda", setOf(NameCatalog.Kind.ATHLETE)).first().name)
    }

    @Test
    fun competitia_favorita_prescurtata() {
        val fav = SportsFavorites(competitions = listOf("UCL", "ATP", "Liga 1"))
        assertTrue(fav.isCompetition("UEFA Champions League"))
        assertTrue(fav.isCompetition("ATP · Paris Masters"))
        assertTrue(fav.isCompetition("Romanian Liga I"))
        assertTrue(!fav.isCompetition("UEFA Europa League"))
    }
}
