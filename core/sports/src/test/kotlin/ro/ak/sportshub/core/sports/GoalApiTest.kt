package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forme scurtate din răspunsurile reale GOAL API (16.11.2026). */
class GoalApiTest {

    @Test
    fun `programul zilei pe pagini`() {
        val body = """{"success":true,"date":"2026-11-15","data":[
            {"id":"cmu74i61sonaxqu07msfpu5ms","leagueName":"UEFA Nations League - League A","kickoffUtc":"2026-11-15T19:45:00.000Z",
             "matchStatus":"FINISHED","homeTeamName":"Belgium","awayTeamName":"Italy","homeTeamScore":"2"},
            {"id":"x2","leagueName":"Primera B","kickoffUtc":"2026-11-15T23:30:00.000Z","matchStatus":"POSTPONED","homeTeamName":"A","awayTeamName":"B"}],
            "pagination":{"total":800,"limit":100,"offset":0,"hasMore":true},"source":"database"}"""
        val g = GoalApi.parseFixtures(body)
        assertEquals(1, g.size)
        assertEquals("cmu74i61sonaxqu07msfpu5ms", g[0].providerId)
        assertEquals("Italy", g[0].away)
        assertEquals(java.time.Instant.parse("2026-11-15T19:45:00Z").toEpochMilli(), g[0].start)
        assertTrue(GoalApi.hasMore(body))
        assertTrue(GoalApi.fixturesUrl(java.time.LocalDate.of(2026, 11, 15), 2).endsWith("/fixtures/date/2026-11-15?limit=100&offset=200"))
    }

    @Test
    fun `statisticile, ultima valoare dublata castiga`() {
        val body = """{"success":true,"data":{"match":{"fullTime":[
            {"type":"Corners","home":"7","away":"4"},{"type":"Throw In","home":"0","away":"0"},
            {"type":"Ball Possession","home":"56%","away":"44%"},{"type":"Shots On Goal","home":"10","away":"4"},
            {"type":"Ball Possession","home":"48%","away":"52%"},{"type":"Saves","home":"3","away":"6"}],"firstHalf":[]},
            "players":{},"hasStatistics":true}}"""
        val s = GoalApi.parseStatistics(body)
        assertEquals(4, s.size)
        val pos = s.first { "48%" == it.home }
        assertEquals("52%", pos.away)
        assertTrue(s.none { it.home == "56%" })
    }

    @Test
    fun `formatiile`() {
        val body = """{"success":true,"data":{"home":{"startingLineups":[
            {"lineupPlayer":"Senne Lammens","lineupNumber":"1","lineupPosition":"1","playerPosition":"Goalkeepers","player":null}],
            "substitutes":[{"lineupPlayer":"Hans Vanaken","lineupNumber":"20","playerPosition":"Midfielders","player":null}],"coach":[]},
            "away":{"startingLineups":[{"lineupPlayer":"Gianluigi Donnarumma","lineupNumber":"1","playerPosition":"Goalkeepers","player":null}],"substitutes":[]},
            "homeFormation":"4-2-3-1","hasLineups":true}}"""
        val l = GoalApi.parseLineups(body)
        assertEquals(listOf("Senne Lammens", "Hans Vanaken", "Gianluigi Donnarumma"), l.map { it.name })
        assertEquals("Goalkeeper", l[0].position)
        assertEquals(true, l[1].substitute)
        assertEquals(false, l[2].home)
        assertEquals("1", l[2].number)
    }
}
