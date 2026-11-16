package ro.ak.sportshub.core.sports

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesWindowStreamingTest {

    private val zone = ZoneId.of("Europe/Bucharest")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun match(start: Long, status: MatchStatus = MatchStatus.SCHEDULED, stations: List<TvStation> = emptyList()) =
        Match("m1", Sport.FOOTBALL, "English Premier League", "Arsenal", "Chelsea", start, start + 7_200_000, status, stations = stations)

    @Test
    fun `surse doar pentru live, azi si maine`() {
        val now = at("2026-11-15T23:50:00")
        assertTrue(SourceWindow.searches(match(at("2026-11-16T21:00:00")), now, zone))
        assertTrue(SourceWindow.searches(match(at("2026-11-16T23:59:00")), now, zone))
        assertFalse(SourceWindow.searches(match(at("2026-11-17T00:30:00")), now, zone))
        assertFalse(SourceWindow.searches(match(at("2026-11-20T20:00:00")), now, zone))
        assertTrue(SourceWindow.searches(match(at("2026-11-15T22:00:00"), MatchStatus.LIVE), now, zone))
    }

    @Test
    fun `retelele de streaming din numele posturilor si ale canalelor`() {
        assertEquals("peacock", StreamingServices.ofStation("Peacock"))
        assertEquals("paramount", StreamingServices.ofStation("Paramount+"))
        assertEquals("max", StreamingServices.ofStation("HBO Max (USA)"))
        assertEquals("fubo", StreamingServices.ofStation("fuboTV"))
        assertEquals("stan", StreamingServices.ofStation("Stan Sport"))
        assertNull(StreamingServices.ofStation("Sky Sports Main Event"))

        assertEquals("peacock" to 5, StreamingServices.ofChannel("peacock 05"))
        assertEquals("stan" to 12, StreamingServices.ofChannel("stan sport 12"))
        assertEquals("max" to 3, StreamingServices.ofChannel("max event 3"))
        assertNull(StreamingServices.ofChannel("max sport 1 bulgaria"))
        assertNull(StreamingServices.ofChannel("paramount network"))
        assertNull(StreamingServices.ofChannel("peacock arsenal v chelsea"))
    }

    @Test
    fun `canalele retelei devin presupuneri, fara cele cu alt meci in nume`() {
        val index = StationIndex(listOf("us:peacock 01", "us:peacock 02", "us:peacock 03", "au:stan sport 1"))
        val m = match(at("2026-11-16T21:00:00"), stations = listOf(TvStation("Peacock", "USA")))
        val found = SourceFinder(index, SportsKnowledge(presetRights = false)).find(m, emptyList(), otherEventKeys = setOf("us:peacock 02"))
        assertEquals(
            listOf("us:peacock 01|DEDICATED|Peacock", "us:peacock 03|DEDICATED|Peacock"),
            found.map { "${it.groupKey}|${it.confidence}|${it.station}" },
        )
    }

    @Test
    fun `reteaua recunoscuta dupa grupul din playlist`() {
        assertEquals("peacock", StreamingServices.ofGroup("USA | PEACOCK PPV"))
        assertEquals("dazn", StreamingServices.ofGroup("UK: DAZN EVENTS"))
        assertNull(StreamingServices.ofGroup("PRIME SPORTS"))
        assertNull(StreamingServices.ofGroup("USA SPORTS"))
        assertEquals(5, StreamingServices.genericNumber("event 05"))
        assertEquals(12, StreamingServices.genericNumber("ppv 12"))
        assertNull(StreamingServices.genericNumber("espn 2"))
        val index = StationIndex(
            listOf("us:event 01", "us:event 02", "us:espn 2"),
            mapOf("us:event 01" to setOf("USA | PEACOCK PPV"), "us:event 02" to setOf("USA | PEACOCK PPV"), "us:espn 2" to setOf("USA | PEACOCK PPV")),
        )
        assertEquals(listOf("us:event 01", "us:event 02"), index.streamingChannels(TvStation("Peacock", "USA")))
    }

    @Test
    fun `nu transmite meciul - ramane sursa nesigura, fara invatare`() {
        val index = StationIndex(listOf("uk:sky sport main event"))
        val m = match(at("2026-11-16T21:00:00"), stations = listOf(TvStation("Sky Sports Main Event", "UK")))
        val finder0 = SourceFinder(index, SportsKnowledge(presetRights = false))
        val src = finder0.find(m, emptyList()).single()
        val k = SportsKnowledge(presetRights = false).decide(m, src.groupKey, Verdict.NOT_AIRING, src, 0L)
        assertTrue(k.stationBlocks.isEmpty() && k.stationAliases.isEmpty())
        val after = SourceFinder(index, k).find(m, emptyList()).single()
        assertEquals(Confidence.ELSEWHERE, after.confidence)
        assertEquals(Verdict.NOT_AIRING_NOTE, after.airing)
        assertFalse(after.rejected)
        // Alt meci pe același post: neatins.
        val other = m.copy(id = "m2")
        assertEquals(Confidence.STATION, SourceFinder(index, k).find(other, emptyList()).single().confidence)
    }

    @Test
    fun `ligile de tineret FotMob - competitia e numele ligii`() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"fixtures":{"allMatches":[
            {"id":"4801","home":{"name":"Manchester United U21"},"away":{"name":"Chelsea U21"},"status":{"utcTime":"2026-11-16T19:00:00Z","finished":false,"started":false}}
            ]}}}}</script></html>"""
        val league = FotMob.youthLeagues.first { it.id == 9084 }
        val events = FotMob.parseLeague(html, league)
        assertEquals(1, events.size)
        assertEquals("English Premier League 2 U21", events[0].competition)
        assertEquals(CompetitionFilter.Verdict.KEEP, CompetitionFilter.verdict(Sport.FOOTBALL, events[0].competition))
        assertTrue(TeamNames.covers("Manchester United", events[0].home))
        assertEquals(league, FotMob.youthLeague("lg9084"))
        assertEquals(CompetitionFilter.Verdict.KEEP, CompetitionFilter.verdict(Sport.FOOTBALL, "English U18 Premier League"))
        assertEquals(CompetitionFilter.Verdict.DROP, CompetitionFilter.verdict(Sport.FOOTBALL, "UEFA Youth League U19"))
    }
}
