package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOnSatTest {

    private val html = """
        <html><body>
        <div><select><option>(GMT +00:00) Europe/London</option><option>(GMT +01:00) Europe/Paris *</option></select></div>
        <h2>Saturday, 14th November</h2>
        <div class="blockfix">
          <span class="comp_head">UEFA Nations League - League C Group 2 Round 6</span>
          <div class="fix_text"><img src="a.png"> Latvia v Armenia</div>
          <div class="fLeft_time_live">ST: <b>20:00</b></div>
          <table><tr><td><a href="/c/1">LTV 7 Latvia</a></td></tr>
                 <tr><td><a href="#">Sport TV6 Portugal HD 📺</a></td></tr>
                 <tr><td><a href="#">Fubo Sports USA (${'$'}/geo/R) 📺</a></td></tr></table>
        </div>
        <h2>Sunday, 15th November</h2>
        <div class="blockfix">
          <span class="comp_head">English Premier League</span>
          <div class="fix_text">Arsenal v Chelsea</div>
          <div class="fLeft_time_live">ST: 16:30</div>
          <table><tr><td><a href="#">Sky Sports Main Event</a></td></tr></table>
        </div>
        <p>Website Last updated on Sat 14/11/2026 8:24 PM (GMT +01:00)</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `meciurile, competitia, ora si posturile din pagina zilei`() {
        val events = LiveOnSat.parse(html, LocalDate.of(2026, 11, 14))
        assertEquals(2, events.size)
        val a = events[0]
        assertEquals("Latvia", a.home)
        assertEquals("Armenia", a.away)
        assertEquals("UEFA Nations League - League C Group 2 Round 6", a.competition)
        val expected = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, ZoneOffset.ofHours(1)).toInstant().toEpochMilli()
        assertEquals(expected, a.start)
        assertEquals(listOf("LTV 7 Latvia", "Sport TV6 Portugal HD", "Fubo Sports USA"), a.stations.map { it.name })
        val b = events[1]
        assertEquals("Arsenal", b.home)
        assertEquals("English Premier League", b.competition)
        assertEquals(LocalDate.of(2026, 11, 15), java.time.Instant.ofEpochMilli(b.start).atZone(ZoneOffset.ofHours(1)).toLocalDate())
        assertEquals(listOf("Sky Sports Main Event"), b.stations.map { it.name })
    }

    @Test
    fun `fara subsol ora Marii Britanii iar anul e cel mai apropiat`() {
        assertEquals(LiveOnSat.DEFAULT_ZONE, LiveOnSat.zoneOf("<p>nimic</p>"))
        assertEquals(LocalDate.of(2027, 1, 2), LiveOnSat.dateOf(2, "january", LocalDate.of(2026, 12, 31)))
        assertTrue(LiveOnSat.parse("<html>Cloudflare</html>", LocalDate.of(2026, 11, 14)).isEmpty())
    }
}

class FotMobTest {

    private val page = """
        <html><head></head><body><div>Azi</div>
        <script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"tvGuide":{"leagues":[
          {"name":"Premier League","id":47,"matches":[
            {"id":4813600,"home":{"name":"Arsenal"},"away":{"name":"Chelsea"},
             "status":{"utcTime":"2026-11-15T16:30:00Z","started":false},
             "channels":[{"name":"Sky Sports Premier League"},{"name":"Sky Sports Main Event"}]}
          ]},
          {"name":"Liga I","matches":[
            {"id":"9","homeTeam":{"name":"FCSB"},"awayTeam":{"name":"CFR Cluj"},"time":"2026-11-15T18:00:00.000Z",
             "tvChannels":["Digi Sport 1","Prima Sport 1"]}
          ]}
        ]}}}}
        </script></body></html>
    """.trimIndent()

    @Test
    fun `meciurile din JSON-ul paginii, cu competitia si posturile tarii`() {
        val events = FotMob.parse(page, "ro")
        assertEquals(2, events.size)
        val a = events.first { it.home == "Arsenal" }
        assertEquals("Chelsea", a.away)
        assertEquals("Premier League", a.competition)
        assertEquals(java.time.Instant.parse("2026-11-15T16:30:00Z").toEpochMilli(), a.start)
        assertEquals(listOf("Sky Sports Premier League", "Sky Sports Main Event"), a.stations.map { it.name })
        assertEquals("ro", a.stations.first().country)
        val b = events.first { it.home == "FCSB" }
        assertEquals("Liga I", b.competition)
        assertEquals(listOf("Digi Sport 1", "Prima Sport 1"), b.stations.map { it.name })
    }

    @Test
    fun `fara JSON nu se citeste nimic, tarile dupa canalele tale`() {
        assertTrue(FotMob.parse("<html>19:45 Belgia – Italia</html>", "ro").isEmpty())
        assertEquals(listOf("ro", "gb", "de"), FotMob.countriesFor(setOf("de", "uk", "ro")))
        assertEquals(listOf("gb"), FotMob.countriesFor(emptySet()))
        assertNull(isoMillis("19:45"))
        assertEquals(java.time.Instant.parse("2026-11-15T18:00:00Z").toEpochMilli(), isoMillis("2026-11-15T18:00Z"))
    }
}

class UsLeaguesTest {

    @Test
    fun `ESPN da echipele, ora si posturile din SUA`() {
        val body = """
            {"events":[{"id":"401","date":"2026-11-16T00:30Z","name":"Boston Celtics at New York Knicks",
              "status":{"type":{"state":"pre","name":"STATUS_SCHEDULED"}},
              "competitions":[{"competitors":[
                {"homeAway":"home","score":"0","team":{"displayName":"New York Knicks"}},
                {"homeAway":"away","score":"0","team":{"displayName":"Boston Celtics"}}],
                "broadcasts":[{"market":"national","names":["ESPN","ESPN2"]}],
                "geoBroadcasts":[{"type":{"shortName":"TV"},"media":{"shortName":"NBA TV"}},{"type":{"shortName":"Streaming"},"media":{"shortName":"ESPN+"}}],
                "venue":{"fullName":"Madison Square Garden"}}]}]}
        """.trimIndent()
        val e = UsLeagues.parseEspn(body, UsLeagues.League.NBA).single()
        assertEquals("New York Knicks", e.home)
        assertEquals("Boston Celtics", e.away)
        assertEquals("NBA", e.competition)
        assertEquals(MatchStatus.SCHEDULED, e.status)
        assertNull(e.homeScore)
        assertEquals(listOf("ESPN", "ESPN2", "NBA TV"), e.stations.map { it.name })
        assertEquals("USA", e.stations.first().country)
    }

    @Test
    fun `NHL da posturile din SUA si Canada`() {
        val body = """
            {"gameWeek":[{"date":"2026-11-15","games":[{"id":2026020250,"startTimeUTC":"2026-11-16T00:00:00Z","gameState":"FUT","gameScheduleState":"OK",
              "homeTeam":{"placeName":{"default":"Toronto"},"commonName":{"default":"Maple Leafs"}},
              "awayTeam":{"placeName":{"default":"Boston"},"commonName":{"default":"Bruins"}},
              "tvBroadcasts":[{"market":"N","countryCode":"CA","network":"Sportsnet"},{"market":"N","countryCode":"US","network":"ESPN+"}]}]},
              {"date":"2026-11-16","games":[{"id":1,"startTimeUTC":"2026-11-17T00:00:00Z","homeTeam":{"placeName":{"default":"A"},"commonName":{"default":"B"}},"awayTeam":{"placeName":{"default":"C"},"commonName":{"default":"D"}}}]}]}
        """.trimIndent()
        val e = UsLeagues.parseNhl(body, LocalDate.of(2026, 11, 15)).single()
        assertEquals("Toronto Maple Leafs", e.home)
        assertEquals("Boston Bruins", e.away)
        assertEquals(TvStation("Sportsnet", "Canada"), e.stations.first())
    }

    @Test
    fun `MLB da doar posturile TV`() {
        val body = """
            {"dates":[{"games":[{"gamePk":7,"gameDate":"2026-07-01T23:05:00Z","status":{"abstractGameState":"Preview","detailedState":"Scheduled"},
              "teams":{"home":{"team":{"name":"New York Yankees"}},"away":{"team":{"name":"Toronto Blue Jays"}}},
              "broadcasts":[{"name":"YES","type":"TV"},{"name":"Sportsnet","type":"TV"},{"name":"WFAN","type":"AM"}]}]}]}
        """.trimIndent()
        val e = UsLeagues.parseMlb(body).single()
        assertEquals("New York Yankees", e.home)
        assertEquals(listOf("YES", "Sportsnet"), e.stations.map { it.name })
        assertEquals("Canada", e.stations[1].country)
        assertEquals(listOf(UsLeagues.League.NBA, UsLeagues.League.NHL), UsLeagues.leaguesFor(listOf(Sport.BASKETBALL, Sport.ICE_HOCKEY)))
    }
}
