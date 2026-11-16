package ro.ak.sportshub.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchFilterSportTest {
    private fun m(id: String, sport: Sport, comp: String, home: String, away: String = "") =
        Match(id = id, sport = sport, competition = comp, home = home, away = away, start = 0L, end = 1L, status = MatchStatus.SCHEDULED)

    private val matches = listOf(
        m("1", Sport.FOOTBALL, "UEFA Champions League", "Arsenal", "Bayern Munich"),
        m("2", Sport.FOOTBALL, "Premier League", "Chelsea", "Arsenal"),
        m("3", Sport.MOTORSPORT, "Formula 1", "Ferrari"),
        m("4", Sport.GOLF, "PGA Tour", "RSM Classic"),
    )

    @Test
    fun competitionsOnlyOfChosenSport() {
        assertEquals(listOf("PGA Tour"), MatchLists.competitions(matches, Sport.GOLF))
        assertEquals(4, MatchLists.competitions(matches).size)
    }

    @Test
    fun teamsOnlyOfChosenSport() {
        assertEquals(listOf("Arsenal", "Bayern Munich", "Chelsea"), MatchLists.teams(matches, Sport.FOOTBALL))
    }

    @Test
    fun changingSportDropsForeignCompetitionAndTeam() {
        val f = MatchFilter(competition = "UEFA Champions League", team = "Ferrari")
        val football = MatchLists.withSport(f, Sport.FOOTBALL, matches)
        assertEquals("UEFA Champions League", football.competition)
        assertNull(football.team)
        val golf = MatchLists.withSport(f, Sport.GOLF, matches)
        assertNull(golf.competition)
        assertEquals(Sport.GOLF, golf.sport)
        assertEquals(f.copy(sport = null), MatchLists.withSport(f, null, matches))
    }
}
