package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.sports.CompetitionFilter.Verdict

class CompetitionFilterTest {

    private fun football(c: String) = CompetitionFilter.verdict(Sport.FOOTBALL, c)

    @Test
    fun `fotbal - ligile mari raman, cele exotice ies`() {
        listOf(
            "English Premier League", "England - Premier League", "Championship", "EFL League One", "League Two",
            "National League", "FA Cup", "Carabao Cup", "LaLiga EA Sports", "LaLiga Hypermotion", "Serie A", "Serie B",
            "Bundesliga", "2. Bundesliga", "Ligue 1", "Ligue 2", "Liga Portugal Betclic", "Eredivisie", "Superliga",
            "Romania - Liga 1", "Liga II", "UEFA Champions League", "Europa League", "Conference League",
            "World Cup Qualification UEFA", "UEFA Nations League", "MLS", "Liga MX", "Saudi Pro League",
            "Copa Libertadores", "Brasileirão Série A", "Premier League 2", "U18 Premier League", "Women's Super League",
            "FA WSL", "England - Women's Championship",
        ).forEach { assertEquals(it, Verdict.KEEP, football(it)) }
        listOf(
            "Egyptian Premier League", "Premier League Egipto", "Kenya - Premier League", "Indian Super League",
            "Botola Pro", "Liga 3", "Serie C", "Regionalliga West", "Primera RFEF", "Serie A Femminile",
            "Primavera 1", "Liga MX Femenil", "Bundesliga U19", "Brazil Serie B", "Thai League 1", "Persian Gulf Pro League",
            "Some Unknown League",
        ).forEach { assertEquals(it, Verdict.DROP, football(it)) }
        assertEquals(Verdict.UNKNOWN, football("Club Friendlies"))
        assertEquals(Verdict.UNKNOWN, football(""))
    }

    @Test
    fun `alte sporturi - doar competitiile mari si turneele majore`() {
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.BASKETBALL, "NBA"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.BASKETBALL, "Euroleague"))
        assertEquals(Verdict.DROP, CompetitionFilter.verdict(Sport.BASKETBALL, "Philippine Cup"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.TENNIS, "ATP Finals"))
        assertEquals(Verdict.DROP, CompetitionFilter.verdict(Sport.TENNIS, "ATP Challenger Lima"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.VOLLEYBALL, "FIVB World Championship"))
        assertEquals(Verdict.DROP, CompetitionFilter.verdict(Sport.VOLLEYBALL, "Thailand League"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(null, "World Aquatics Championships"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(null, "European Gymnastics Championships"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.ICE_HOCKEY, "NHL"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.MOTORSPORT, "Formula 1"))
    }

    @Test
    fun `amicalele si meciurile fara competitie se judeca dupa echipe`() {
        val known = setOf(TeamNames.key("Arsenal"), TeamNames.key("Barcelona"))
        assertTrue(CompetitionFilter.keepMatch(Sport.FOOTBALL, "Club Friendlies", "Arsenal", "Barcelona", known, true))
        assertFalse(CompetitionFilter.keepMatch(Sport.FOOTBALL, "Club Friendlies", "Arsenal", "Al Ahly", known, true))
        // Din EPG, fără competiție: ajunge o echipă cunoscută.
        assertTrue(CompetitionFilter.keepMatch(Sport.FOOTBALL, null, "Arsenal", "Al Ahly", known, false))
        assertFalse(CompetitionFilter.keepMatch(Sport.FOOTBALL, null, "Zamalek", "Al Ahly", known, false))
        // Evenimentele fără echipe rămân.
        assertTrue(CompetitionFilter.keepMatch(Sport.MOTORSPORT, null, "Grand Prix of Brazil", "", known, false))
    }

    @Test
    fun `catalogul ESPN si EuroLeague trec filtrul (catalogul e el insusi filtrul)`() {
        Espn.leagues.filter { "friendly" !in it.competition.lowercase() && !it.forFavorites }.forEach {
            assertEquals(it.competition, Verdict.KEEP, CompetitionFilter.verdict(it.sport, it.competition))
        }
        EuroLeague.Competition.entries.forEach {
            assertEquals(it.label, Verdict.KEEP, CompetitionFilter.verdict(Sport.BASKETBALL, it.label))
        }
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.BASKETBALL, "Euroliga Liga Regular"))
        assertEquals(Verdict.KEEP, CompetitionFilter.verdict(Sport.TENNIS, "ATP · Nitto ATP Finals"))
    }
}
