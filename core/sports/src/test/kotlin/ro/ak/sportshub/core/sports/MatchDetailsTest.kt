package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchDetailsParseTest {

    @Test
    fun `detaliile meciului`() {
        val body = """{"events":[{"idEvent":"441613","strHomeTeam":"Arsenal","strAwayTeam":"Chelsea",
            "idHomeTeam":"133604","idAwayTeam":"133610","idLeague":"4328","strLeague":"English Premier League",
            "strSeason":"2026-2027","intRound":"12","strTimestamp":"2026-11-14T17:30:00","intHomeScore":"2",
            "intAwayScore":"1","strStatus":"2H","strProgress":"67","strVenue":"Emirates Stadium",
            "strOfficial":"Michael Oliver","intSpectators":"60000"}]}"""
        val e = TheSportsDbDetails.parseEvent(body)!!
        assertEquals("441613", e.eventId)
        assertEquals("133604", e.homeTeamId)
        assertEquals("2026-2027", e.season)
        assertEquals(MatchStatus.LIVE, e.status)
        assertEquals(2, e.homeScore)
        assertEquals("Michael Oliver", e.referee)
        assertEquals(60000, e.spectators)
        assertEquals("Repriza 2 · 67'", MatchInsights.liveLabel(e.status, e.statusCode, e.progress))
    }

    @Test
    fun `raspunsuri goale sau stricate`() {
        assertNull(TheSportsDbDetails.parseEvent("""{"events":null}"""))
        assertNull(TheSportsDbDetails.parseEvent("<html>"))
        assertTrue(TheSportsDbDetails.parseTable("""{"table":null}""").isEmpty())
        assertTrue(TheSportsDbDetails.parseTimeline("").isEmpty())
        assertTrue(TheSportsDbDetails.parseStats("""{"eventstats":"Patreon only"}""").isEmpty())
    }

    @Test
    fun `clasament, echipe, evenimente, statistici`() {
        val table = TheSportsDbDetails.parseTable(
            """{"table":[{"intRank":"2","strTeam":"Chelsea","idTeam":"133610","intPlayed":"11","intPoints":"22"},
            {"intRank":"1","strTeam":"Arsenal","idTeam":"133604","intPlayed":"11","intWin":"8","intDraw":"1","intLoss":"2","intPoints":"25"}]}""",
        )
        assertEquals(listOf("Arsenal", "Chelsea"), table.map { it.team })
        assertEquals(25, table[0].points)

        val lineup = TheSportsDbDetails.parseLineup(
            """{"lineup":[{"strPlayer":"Saka","strPosition":"Winger","intSquadNumber":"7","strHome":"Yes","strSubstitute":"No"},
            {"strPlayer":"Palmer","strHome":"No","strSubstitute":"Yes"}]}""",
        )
        assertTrue(lineup[0].home && !lineup[0].substitute)
        assertTrue(!lineup[1].home && lineup[1].substitute)

        val timeline = TheSportsDbDetails.parseTimeline(
            """{"timeline":[{"strTimeline":"Card","strTimelineDetail":"Yellow Card","strHome":"No","strPlayer":"Caicedo","intTime":"40"},
            {"strTimeline":"Goal","strTimelineDetail":"Normal Goal","strHome":"Yes","strPlayer":"Saka","strAssist":"Odegaard","intTime":"12"},
            {"strTimeline":"Goal","strTimelineDetail":"Penalty","strHome":"No","strPlayer":"Palmer","intTime":"55"},
            {"strTimeline":"subst","strTimelineDetail":"Substitution","strHome":"Yes","strPlayer":"Havertz","strAssist":"Jesus","intTime":"70"},
            {"strTimeline":"Card","strTimelineDetail":"Red Card","strHome":"No","strPlayer":"James","intTime":"88"}]}""",
        )
        assertEquals(listOf(12, 40, 55, 70, 88), timeline.map { it.minute })
        assertEquals(
            listOf(TimelineKind.GOAL, TimelineKind.YELLOW, TimelineKind.PENALTY, TimelineKind.SUBSTITUTION, TimelineKind.RED),
            timeline.map { it.kind },
        )
        assertEquals("Odegaard", timeline[0].other)

        val stats = TheSportsDbDetails.parseStats(
            """{"eventstats":[{"strStat":"Ball Possession","intHome":"58","intAway":"42"},{"strStat":"Weird Stat","intHome":"1","intAway":"2"}]}""",
        )
        assertEquals("Posesie (%)", stats[0].name)
        assertEquals("58", stats[0].home)
        assertEquals("Weird Stat", stats[1].name)
    }

    @Test
    fun `adrese`() {
        assertEquals(
            "https://www.thesportsdb.com/api/v1/json/123/searchevents.php?e=Man_Utd_vs_FC_Porto",
            TheSportsDbDetails.searchUrl("", "Man Utd", " FC  Porto"),
        )
        assertEquals("441613", TheSportsDbDetails.eventIdOf("thesportsdb:441613"))
        assertNull(TheSportsDbDetails.eventIdOf("epg:arsenal|chelsea:2026-11-14"))
    }
}

class MatchInsightsTest {

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L

    private fun ev(id: String, home: String, away: String, start: Long, hs: Int? = null, aw: Int? = null) =
        SportEvent("thesportsdb", id, "Soccer", "Premier League", home, away, start, homeScore = hs, awayScore = aw)

    @Test
    fun `meciul gasit prin cautare`() {
        val c = listOf(
            ev("1", "Arsenal", "Chelsea", now - 300 * day, 1, 0),
            ev("2", "Arsenal", "Chelsea", now + hour),
            ev("3", "Arsenal", "Chelsea", now + 2 * day),
        )
        assertEquals("2", MatchInsights.pickEvent(c, "Arsenal FC", "Chelsea", now)?.providerId)
        assertNull(MatchInsights.pickEvent(c, "Arsenal", "Liverpool", now))
    }

    @Test
    fun `meciuri directe si forma`() {
        val c = listOf(
            ev("1", "Arsenal", "Chelsea", now - 300 * day, 1, 0),
            ev("2", "Chelsea", "Arsenal", now - 100 * day, 2, 2),
            ev("3", "Chelsea", "Arsenal", now - 50 * day, 3, 1),
            ev("3", "Chelsea", "Arsenal", now - 50 * day, 3, 1),
            ev("4", "Arsenal", "Chelsea", now + hour),
            ev("5", "Arsenal", "Spurs", now - 10 * day, 4, 0),
        )
        val h2h = MatchInsights.headToHead(c, "Arsenal", "Chelsea", now)
        assertEquals(listOf("3", "2", "1"), h2h.map { it.providerId })
        assertEquals(Triple(1, 1, 1), MatchInsights.headToHeadSummary(h2h, "Arsenal"))

        val recent = MatchInsights.recent(c, now)
        assertEquals("5", recent.first().providerId)
        assertEquals(
            listOf(FormResult.WIN, FormResult.LOSS, FormResult.DRAW, FormResult.WIN),
            MatchInsights.form(recent, "Arsenal"),
        )
    }

    @Test
    fun `randurile echipelor din clasament`() {
        val table = listOf(
            StandingRow(1, "Arsenal", "133604", 11, 8, 1, 2, 20, 8, 25),
            StandingRow(2, "Liverpool", "133602", 11, 7, 2, 2, 22, 10, 23),
            StandingRow(5, "Chelsea FC", null, 11, 6, 2, 3, 18, 12, 20),
        )
        assertEquals(setOf(1, 5), MatchInsights.tableRowsFor(table, null, "Arsenal", "Chelsea"))
    }

    @Test
    fun `starea live in romana`() {
        assertEquals("Pauză", MatchInsights.liveLabel(MatchStatus.LIVE, "HT", "45"))
        assertEquals("Final", MatchInsights.liveLabel(MatchStatus.FINISHED, "FT", "90"))
        assertEquals("În desfășurare · 45+2'", MatchInsights.liveLabel(MatchStatus.LIVE, "LIVE", "45+2"))
        assertEquals("Nu a început", MatchInsights.liveLabel(MatchStatus.SCHEDULED, null, null))
    }

    @Test
    fun `fara spoilere`() {
        assertTrue(Spoilers.hide(noSpoilers = true, revealed = false, status = MatchStatus.LIVE))
        assertTrue(Spoilers.hide(noSpoilers = true, revealed = false, status = MatchStatus.FINISHED))
        assertFalse(Spoilers.hide(noSpoilers = true, revealed = true, status = MatchStatus.FINISHED))
        assertFalse(Spoilers.hide(noSpoilers = true, revealed = false, status = MatchStatus.SCHEDULED))
        assertFalse(Spoilers.hide(noSpoilers = false, revealed = false, status = MatchStatus.LIVE))
    }

    @Test
    fun `cereri rare`() {
        val p = DetailsSchedule.Part.PRE_MATCH
        val l = DetailsSchedule.Part.LIVE
        val start = now + 5 * hour
        val end = start + 2 * hour
        // Departe de start: la 6 ore.
        assertTrue(DetailsSchedule.isDue(p, MatchStatus.SCHEDULED, start, end, now, 0))
        assertFalse(DetailsSchedule.isDue(p, MatchStatus.SCHEDULED, start, end, now, now - hour))
        assertTrue(DetailsSchedule.isDue(p, MatchStatus.SCHEDULED, start, end, now, now - 7 * hour))
        // Aproape de start: la 20 de minute (apar echipele de start).
        assertTrue(DetailsSchedule.isDue(p, MatchStatus.SCHEDULED, now + hour, end, now, now - hour / 2))
        // Live: nimic înainte de start, la un minut în timpul meciului, nimic după datele finale.
        assertFalse(DetailsSchedule.isDue(l, MatchStatus.SCHEDULED, start, end, now, 0))
        assertFalse(DetailsSchedule.isDue(l, MatchStatus.LIVE, now - hour, now + hour, now, now - 30_000))
        assertTrue(DetailsSchedule.isDue(l, MatchStatus.LIVE, now - hour, now + hour, now, now - 61_000))
        assertTrue(DetailsSchedule.isDue(l, MatchStatus.FINISHED, now - 3 * hour, now - hour, now, now - 2 * hour))
        assertFalse(DetailsSchedule.isDue(l, MatchStatus.FINISHED, now - 3 * hour, now - hour, now, now - hour / 2))
        assertFalse(DetailsSchedule.isDue(p, MatchStatus.FINISHED, now - 3 * hour, now - hour, now, now - hour / 2))
    }
}
