package ro.ak.sportshub.core.sports

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialStatsTest {

    @Test
    fun `liga dupa competitie`() {
        assertEquals(OfficialStats.League.NBA, OfficialStats.leagueOf("NBA", Sport.BASKETBALL))
        assertEquals(OfficialStats.League.NHL, OfficialStats.leagueOf("NHL", null))
        assertEquals(OfficialStats.League.MLB, OfficialStats.leagueOf("MLB", Sport.BASEBALL))
        assertNull(OfficialStats.leagueOf("Premier League", Sport.FOOTBALL))
        assertNull(OfficialStats.leagueOf("NBA", Sport.ICE_HOCKEY))
    }

    @Test
    fun `programul NBA si statisticile echipelor`() {
        val board = """{"scoreboard":{"games":[{"gameId":"0022500250","gameStatus":2,"gameTimeUTC":"2026-11-17T00:30:00Z",
            "homeTeam":{"teamCity":"Boston","teamName":"Celtics","teamTricode":"BOS"},
            "awayTeam":{"teamCity":"LA","teamName":"Clippers","teamTricode":"LAC"}}]}}"""
        val games = OfficialStats.parseNbaScoreboard(board)
        assertEquals(1, games.size)
        assertEquals("Boston Celtics", games[0].home)
        assertEquals("0022500250", games[0].providerId)
        assertEquals(MatchStatus.LIVE, games[0].status)

        val box = """{"game":{"homeTeam":{"statistics":{"fieldGoalsMade":38,"fieldGoalsAttempted":85,"fieldGoalsPercentage":0.447,
              "reboundsTotal":44,"assists":25,"turnoversTotal":11},
              "players":[{"name":"Jayson Tatum","jerseyNum":"0","position":"SF","starter":"1"},{"name":"Bench Guy","jerseyNum":"9","starter":"0"}]},
            "awayTeam":{"statistics":{"fieldGoalsMade":40,"fieldGoalsAttempted":90,"fieldGoalsPercentage":0.444444,
              "reboundsTotal":41,"assists":22,"turnoversTotal":14},"players":[]}}}"""
        val d = OfficialStats.parseStats(OfficialStats.League.NBA, box)
        assertNotNull(d)
        val byName = d!!.stats.associateBy { it.name }
        assertEquals("38 din 85", byName.getValue("Aruncări reușite").home)
        assertEquals("44.7%", byName.getValue("Aruncări (%)").home)
        assertEquals("44.4%", byName.getValue("Aruncări (%)").away)
        assertEquals("14", byName.getValue("Mingi pierdute").away)
        assertEquals(38.0, StatGroups.number(byName.getValue("Aruncări reușite").home))
        assertEquals(2, d.lineup.size)
        assertEquals(false, d.lineup[0].substitute)
    }

    @Test
    fun `NHL right-rail`() {
        val body = """{"teamGameStats":[{"category":"sog","awayValue":25,"homeValue":31},
            {"category":"faceoffWinningPctg","awayValue":0.475,"homeValue":0.525},
            {"category":"powerPlay","awayValue":"1/3","homeValue":"0/2"},{"category":"unknown","awayValue":1,"homeValue":2}]}"""
        val d = OfficialStats.parseStats(OfficialStats.League.NHL, body)!!
        assertEquals(3, d.stats.size)
        assertEquals("31", d.stats[0].home)
        assertEquals("52.5%", d.stats[1].home)
        assertEquals("1/3", d.stats[2].away)
    }

    @Test
    fun `MLB boxscore cu ordinea la bataie`() {
        val body = """{"teams":{
            "home":{"teamStats":{"batting":{"hits":9,"homeRuns":2,"avg":".265"},"fielding":{"errors":1}},
              "battingOrder":[1,2],"pitchers":[3],
              "players":{"ID1":{"person":{"fullName":"A One"},"jerseyNumber":"7","position":{"abbreviation":"SS"}},
                         "ID2":{"person":{"fullName":"B Two"},"position":{"abbreviation":"CF"}},
                         "ID3":{"person":{"fullName":"C Pitcher"},"position":{"abbreviation":"P"}}}},
            "away":{"teamStats":{"batting":{"hits":5,"homeRuns":0,"avg":".231"},"fielding":{"errors":0}},"players":{}}}}"""
        val d = OfficialStats.parseStats(OfficialStats.League.MLB, body)!!
        assertEquals(listOf("Lovituri (hits)", "Home run-uri", "Medie la bătaie", "Erori"), d.stats.map { it.name })
        assertEquals(listOf("A One", "B Two", "C Pitcher"), d.lineup.map { it.name })
        assertEquals(true, d.lineup[2].substitute)
    }

    @Test
    fun `ziua meciului in fusul SUA Est`() {
        // 00:30 UTC pe 17 = 19:30 pe 16 la New York.
        assertEquals(LocalDate.of(2026, 11, 16), OfficialStats.gameDay(java.time.Instant.parse("2026-11-17T00:30:00Z").toEpochMilli()))
        assertNull(OfficialStats.parseStats(OfficialStats.League.NHL, "{}"))
    }
}
