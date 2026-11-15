package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Echipa favorită cuprinde și echipele de tineret și cea feminină (cererea lui Radu). */
class FavoriteFamilyTest {

    @Test
    fun `echipa favorita cuprinde U18, U21, a doua echipa si echipa feminina`() {
        for (n in listOf("Manchester United", "Man Utd", "Manchester United U21", "Man Utd U18", "Manchester United Women",
            "Manchester United W", "Man United U-21", "Manchester United II")) {
            assertTrue(n, TeamNames.covers("Manchester United", n))
        }
        assertTrue(TeamNames.covers("Barcelona", "FC Barcelona Femenino"))
        assertTrue(TeamNames.covers("Juventus", "Juventus Femminile"))
        assertFalse(TeamNames.covers("Manchester United", "Manchester City U21"))
        assertFalse(TeamNames.covers("Manchester United", ""))
    }

    @Test
    fun `favorita cu semn sau cu seniori tine doar echipa aceea`() {
        assertTrue(TeamNames.covers("Arsenal Women", "Arsenal W.F.C. Women"))
        assertFalse(TeamNames.covers("Arsenal Women", "Arsenal"))
        assertFalse(TeamNames.covers("Chelsea U21", "Chelsea U18"))
        assertTrue(TeamNames.covers("Manchester United seniori", "Man Utd"))
        assertFalse(TeamNames.covers("Manchester United seniori", "Manchester United Women"))
        assertFalse(TeamNames.covers("Manchester United seniori", "Manchester United U21"))
        assertTrue(TeamNames.isSeniorOnly("Manchester United Seniori"))
        assertEquals("Manchester United", TeamNames.withoutSenior("Manchester United seniori"))
        assertTrue(TeamNames.hasMarker("Chelsea U21"))
        assertFalse(TeamNames.hasMarker("Chelsea"))
    }

    @Test
    fun `potrivirea echipelor intre furnizori nu se schimba`() {
        assertFalse(TeamNames.same("Manchester United", "Manchester United Women"))
        assertTrue(TeamNames.same("Man Utd U21", "Manchester United U21"))
        assertFalse(TeamNames.same("Chelsea U21", "Chelsea U18"))
    }

    @Test
    fun `meciul echipei feminine sau de tineret e favorit`() {
        val fav = SportsFavorites(teams = listOf("Manchester United"))
        assertTrue(fav.isTeam("Manchester United U18"))
        assertTrue(fav.coversEither("Liverpool Women", "Manchester United Women"))
        assertFalse(fav.coversEither("Liverpool Women", "Arsenal Women"))
    }

    @Test
    fun `ligile feminine din afara Angliei se cer doar cu echipe favorite`() {
        val without = Espn.leaguesFor(listOf(Sport.FOOTBALL)).map { it.key }
        val with = Espn.leaguesFor(listOf(Sport.FOOTBALL), withFavoriteLeagues = true).map { it.key }
        assertFalse("LIGAF" in without)
        assertTrue("LIGAF" in with && "UWCL" in with && "WSL" in without)
        // Filtrul de competiții le scoate: rămân doar meciurile echipelor favorite.
        Espn.leagues.filter { it.forFavorites }.forEach {
            assertEquals(it.competition, CompetitionFilter.Verdict.DROP, CompetitionFilter.verdict(it.sport, it.competition))
        }
    }
}
