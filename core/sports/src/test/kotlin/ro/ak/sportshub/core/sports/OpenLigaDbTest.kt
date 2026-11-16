package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class OpenLigaDbTest {

    private val body = """
        [{"matchID":85772,"matchDateTime":"2026-11-06T18:30:00","timeZoneID":"W. Europe Standard Time","leagueId":5000,
          "leagueName":"1. Frauen Fußballbundesliga 2026/2027","leagueSeason":2026,"leagueShortcut":"ffb1",
          "matchDateTimeUTC":"2026-11-06T17:30:00Z","group":{"groupName":"9. Spieltag","groupOrderID":9,"groupID":1},
          "team1":{"teamId":1,"teamName":"Eintracht Frankfurt Frauen","shortName":"Frankfurt","teamIconUrl":"x"},
          "team2":{"teamId":2,"teamName":"VfL Wolfsburg Frauen","shortName":"Wolfsburg","teamIconUrl":"y"},
          "lastUpdateDateTime":"2026-11-06T20:30:00","matchIsFinished":true,
          "matchResults":[{"resultID":1,"resultName":"Halbzeit","pointsTeam1":1,"pointsTeam2":1,"resultOrderID":1,"resultTypeID":1},
                          {"resultID":2,"resultName":"Endergebnis","pointsTeam1":1,"pointsTeam2":3,"resultOrderID":2,"resultTypeID":2}],
          "goals":[{"goalID":1,"scoreTeam1":0,"scoreTeam2":1,"matchMinute":13,"goalGetterName":"Ella Peddemors"}],
          "location":{"locationID":1,"locationCity":"Frankfurt","locationStadium":"Stadion am Brentanobad"},"numberOfViewers":null},
         {"matchID":85780,"matchDateTimeUTC":"2026-11-20T17:30:00","team1":{"teamName":"Bayern München Frauen"},
          "team2":{"teamName":"SGS Essen"},"matchIsFinished":false,"matchResults":[],"goals":[],"location":null}]
    """.trimIndent()

    @Test
    fun parsesSeason() {
        val l = OpenLigaDb.league("ffb1")!!
        val events = OpenLigaDb.parse(body, l)
        assertEquals(2, events.size)
        val e = events[0]
        assertEquals("ffb1-85772", e.providerId)
        assertEquals("Eintracht Frankfurt Frauen", e.home)
        assertEquals(Instant.parse("2026-11-06T17:30:00Z").toEpochMilli(), e.start)
        assertEquals(MatchStatus.FINISHED, e.status)
        assertEquals(1, e.homeScore)
        assertEquals(3, e.awayScore)
        assertEquals("Stadion am Brentanobad", e.venue)
        assertEquals(listOf("Frankfurt"), e.homeAlt)
        assertEquals("German Frauen-Bundesliga", e.competition)
        // Fără „Z”: tot UTC; nejucat: fără scor.
        assertEquals(Instant.parse("2026-11-20T17:30:00Z").toEpochMilli(), events[1].start)
        assertNull(events[1].homeScore)
        assertNull(events[1].status)
    }

    @Test
    fun brokenBodyIsEmptyAndSeasonStartsInJuly() {
        assertTrue(OpenLigaDb.parse("<html>", OpenLigaDb.leagues[0]).isEmpty())
        assertEquals("https://api.openligadb.de/getmatchdata/del/2026", OpenLigaDb.url(OpenLigaDb.league("del")!!, LocalDate.of(2027, 2, 1)))
        assertEquals(2026, OpenLigaDb.seasonYear(LocalDate.of(2026, 7, 1)))
    }

    @Test
    fun hockeyLeaguesPassTheFilter() {
        for (l in OpenLigaDb.leagues.filter { !it.forFavorites }) {
            assertEquals(l.competition, CompetitionFilter.Verdict.KEEP, CompetitionFilter.verdict(l.sport, l.competition))
        }
    }
}
