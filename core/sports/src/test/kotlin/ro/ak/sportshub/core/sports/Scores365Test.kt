package ro.ak.sportshub.core.sports

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Scores365Test {

    @Test
    fun `programul zilei`() {
        val body = """{"games":[{"id":4893625,"sportId":1,"competitionDisplayName":"LPR Pro - Apertura",
            "startTime":"2026-11-16T00:30:00+00:00","statusGroup":4,
            "homeCompetitor":{"id":86812,"name":"Taurinos","score":2.0},"awayCompetitor":{"id":86811,"name":"Ponce","score":4.0}}]}"""
        val games = Scores365.parseSchedule(body)
        assertEquals(1, games.size)
        assertEquals("4893625", games[0].providerId)
        assertEquals("Taurinos", games[0].home)
        assertEquals(MatchStatus.FINISHED, games[0].status)
        assertTrue(Scores365.scheduleUrl(1, LocalDate.of(2026, 11, 16)).contains("startDate=16/11/2026"))
    }

    @Test
    fun `statisticile unite pe echipe`() {
        val body = """{"statistics":[
            {"id":10,"name":"Possession","competitorId":86812,"value":"41%","order":0},
            {"id":8,"name":"Corners","competitorId":86812,"value":"1","order":5},
            {"id":10,"name":"Possession","competitorId":86811,"value":"59%","order":0},
            {"id":8,"name":"Corners","competitorId":86811,"value":"8","order":5},
            {"id":99,"name":"Only home","competitorId":86812,"value":"3","order":1}],
            "competitors":[{"id":86812,"name":"Taurinos","competitorNum":1},{"id":86811,"name":"Ponce","competitorNum":2}]}"""
        val stats = Scores365.parseStats(body)
        assertEquals(2, stats.size)
        assertEquals("41%", stats[0].home)
        assertEquals("59%", stats[0].away)
        assertEquals("8", stats[1].away)
    }

    @Test
    fun `formatiile cu numele din members`() {
        val body = """{"game":{"homeCompetitor":{"id":1,"lineups":{"members":[
                {"id":11,"status":1,"position":{"name":"Goalkeeper"}},{"id":12,"status":2}]}},
            "awayCompetitor":{"id":2,"lineups":{"members":[{"id":21,"status":1,"position":{"name":"Attacker"}}]}},
            "members":[{"id":11,"name":"Keeper One","jerseyNumber":1},{"id":12,"name":"Sub Two","jerseyNumber":14},{"id":21,"name":"Striker","jerseyNumber":9}]}}"""
        val l = Scores365.parseLineup(body)
        assertEquals(listOf("Keeper One", "Sub Two", "Striker"), l.map { it.name })
        assertEquals("Goalkeeper", l[0].position)
        assertEquals(true, l[1].substitute)
        assertEquals("Forward", l[2].position)
        assertEquals("9", l[2].number)
    }

    @Test
    fun `programul ca meciuri de handbal`() {
        val body = """{"games":[{"id":1,"competitionDisplayName":"Bundesliga - Round 12","startTime":"2026-11-16T18:00:00+00:00",
            "statusGroup":2,"homeCompetitor":{"id":5,"name":"SC Magdeburg"},"awayCompetitor":{"id":6,"name":"SG Flensburg-Handewitt"}}]}"""
        val e = Scores365.parseScheduleFor(body, Sport.HANDBALL).single()
        assertEquals("Bundesliga", e.competition)
        assertEquals(Sport.HANDBALL.sportsDbName, e.sport)
        assertEquals(MatchStatus.SCHEDULED, e.status)
        assertEquals(listOf(Sport.HANDBALL), ProviderPolicy.scores365Sports(listOf(Sport.HANDBALL, Sport.FOOTBALL, Sport.DARTS)))
        assertEquals(false, Sport.HANDBALL in ProviderPolicy.sportsDbSports(listOf(Sport.HANDBALL, Sport.DARTS)))
    }

    @Test
    fun `sporturile cunoscute`() {
        assertEquals(3, Scores365.sportId(Sport.TENNIS))
        assertNull(Scores365.sportId(null))
    }
}
