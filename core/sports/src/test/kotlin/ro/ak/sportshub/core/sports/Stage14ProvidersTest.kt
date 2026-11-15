package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnTest {

    private val soccer = """
        {"leagues":[{"id":"740","slug":"esp.1","calendarType":"day","calendarIsWhitelist":true,
          "calendar":["2026-11-21T07:00Z","2026-11-22T07:00Z","2026-11-29T07:00Z"]}],
         "events":[{"id":"7001","date":"2026-11-21T20:00Z","status":{"type":{"state":"pre","name":"STATUS_SCHEDULED"}},
           "competitions":[{"date":"2026-11-21T20:00Z","competitors":[
             {"homeAway":"home","score":"0","team":{"id":"86","displayName":"Real Madrid"}},
             {"homeAway":"away","score":"0","team":{"id":"85","displayName":"Celta Vigo"}}],
             "broadcasts":[{"market":"national","names":["ESPN Deportes","ESPN+"]}],
             "venue":{"fullName":"Santiago Bernabeu"}}]},
          {"id":"7002","date":"2026-11-21T13:00Z","status":{"type":{"state":"post","name":"STATUS_POSTPONED"}},
           "competitions":[{"competitors":[{"homeAway":"home","team":{"displayName":"A"}},{"homeAway":"away","team":{"displayName":"B"}}]}]}]}
    """.trimIndent()

    @Test
    fun `fotbal - echipele, ora, posturile, amanatele lipsesc`() {
        val league = Espn.league("LALIGA")!!
        val e = Espn.parse(soccer, league).single()
        assertEquals("espn", e.provider)
        assertEquals("LALIGA-7001", e.providerId)
        assertEquals("Real Madrid", e.home)
        assertEquals("Celta Vigo", e.away)
        assertEquals("Spanish LaLiga", e.competition)
        assertEquals(Instant.parse("2026-11-21T20:00:00Z").toEpochMilli(), e.start)
        assertEquals(listOf("ESPN Deportes", "ESPN+"), e.stations.map { it.name })
        assertEquals(league to "7001", Espn.splitId(e.providerId))
        assertTrue(Espn.scoreboardUrl(league, LocalDate.of(2026, 11, 21)).endsWith("/soccer/esp.1/scoreboard?dates=20261121"))
    }

    @Test
    fun `calendarul - zilele cu meciuri, perioade si necunoscut`() {
        val cal = Espn.calendar(soccer)!!
        assertTrue(cal.has(LocalDate.of(2026, 11, 21)))
        assertFalse(cal.has(LocalDate.of(2026, 11, 23)))
        assertEquals(cal, Espn.Calendar.decode(cal.encode()))

        val f1 = """{"leagues":[{"calendarType":"list","calendar":[
            {"label":"Las Vegas GP","startDate":"2026-11-20T04:30Z","endDate":"2026-11-22T07:00Z"}]}],"events":[]}"""
        val list = Espn.calendar(f1)!!
        // 20.11 04:30Z = 19.11 seara în SUA; 22.11 07:00Z = 22.11 dimineața.
        assertTrue(list.has(LocalDate.of(2026, 11, 19)))
        assertTrue(list.has(LocalDate.of(2026, 11, 22)))
        assertFalse(list.has(LocalDate.of(2026, 11, 24)))
        assertEquals(list, Espn.Calendar.decode(list.encode()))

        assertTrue(Espn.calendar("""{"leagues":[{"calendar":[]}],"events":[]}""")!!.unknown)
        assertNull(Espn.calendar("<html>"))
        assertTrue(Espn.Calendar.decode("unknown")!!.has(LocalDate.of(2030, 1, 1)))
    }

    @Test
    fun `tenis - doar simplu, in jurul zilei cerute`() {
        val body = """
            {"events":[{"id":"339-2026","name":"Nitto ATP Finals","date":"2026-11-14T05:00Z","endDate":"2026-11-23T04:59Z",
              "groupings":[
                {"grouping":{"slug":"mens-singles","displayName":"Men's Singles"},"competitions":[
                  {"id":"1","date":"2026-11-15T13:00Z","status":{"type":{"state":"pre"}},
                   "competitors":[{"order":2,"athlete":{"displayName":"Carlos Alcaraz"}},{"order":1,"athlete":{"displayName":"Jannik Sinner"}}]},
                  {"id":"2","date":"2026-11-07T10:00Z","status":{"type":{"state":"post"}},
                   "competitors":[{"order":1,"athlete":{"displayName":"A"}},{"order":2,"athlete":{"displayName":"B"}}]}]},
                {"grouping":{"slug":"mens-doubles"},"competitions":[
                  {"id":"3","date":"2026-11-15T15:00Z","competitors":[{"order":1,"athlete":{"displayName":"C"}},{"order":2,"athlete":{"displayName":"D"}}]}]}]}]}
        """.trimIndent()
        val list = Espn.parse(body, Espn.league("ATP")!!, LocalDate.of(2026, 11, 15))
        assertEquals(1, list.size)
        assertEquals("Jannik Sinner", list[0].home)
        assertEquals("Carlos Alcaraz", list[0].away)
        assertEquals("ATP · Nitto ATP Finals", list[0].competition)
        assertEquals(CompetitionFilter.Verdict.KEEP, CompetitionFilter.verdict(Sport.TENNIS, list[0].competition))
    }

    @Test
    fun `F1 - calificarile si cursa, fara antrenamente`() {
        val body = """
            {"events":[{"id":"600057449","name":"Heineken Las Vegas Grand Prix","date":"2026-11-20T00:30Z","endDate":"2026-11-22T04:00Z",
              "status":{"type":{"state":"pre"}},
              "competitions":[
                {"id":"1","date":"2026-11-20T00:30Z","type":{"id":"1","abbreviation":"FP1"},"status":{"type":{"state":"pre"}}},
                {"id":"4","date":"2026-11-21T04:00Z","type":{"id":"4","abbreviation":"Qual"},"status":{"type":{"state":"pre"}},
                 "broadcasts":[{"market":"national","names":["Apple TV"]}]},
                {"id":"5","date":"2026-11-22T04:00Z","type":{"id":"5","abbreviation":"Race"},"status":{"type":{"state":"pre"}}}]}]}
        """.trimIndent()
        val list = Espn.parse(body, Espn.league("F1")!!)
        assertEquals(listOf("Heineken Las Vegas Grand Prix Qualifying", "Heineken Las Vegas Grand Prix"), list.map { it.home })
        assertTrue(list.all { it.away.isEmpty() && it.sport == "Motorsport" })
        assertEquals(listOf("Apple TV"), list[0].stations.map { it.name })
    }

    @Test
    fun `UFC si golf - un eveniment, golful apare in fiecare zi a turneului`() {
        val ufc = """{"events":[{"id":"600061400","name":"UFC 334: Gane vs. Hokit","date":"2026-11-14T22:00Z","status":{"type":{"state":"pre"}},
            "competitions":[{"id":"1","date":"2026-11-14T22:00Z","broadcasts":[{"market":"national","names":["Paramount+"]}]}]}]}"""
        val e = Espn.parse(ufc, Espn.league("UFC")!!).single()
        assertEquals("UFC 334: Gane vs. Hokit", e.home)
        assertEquals("", e.away)
        assertEquals(listOf("Paramount+"), e.stations.map { it.name })

        val golf = """{"events":[{"id":"401","name":"RSM Classic","date":"2026-11-19T12:00Z","endDate":"2026-11-22T23:00Z","status":{"type":{"state":"in"}}}]}"""
        val day3 = Espn.parse(golf, Espn.league("PGA")!!, LocalDate.of(2026, 11, 21)).single()
        assertEquals(ZonedDateTime.of(2026, 11, 21, 7, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli(), day3.start)
        assertEquals(MatchStatus.LIVE, day3.status)
        assertTrue(day3.providerId.endsWith("2026-11-21"))
    }

    @Test
    fun `pagina meciului - stare, formatii, faze, statistici, clasament, forma`() {
        val body = """
            {"header":{"league":{"name":"English Premier League"},"season":{"year":2026},
              "competitions":[{"date":"2026-11-21T15:00Z",
                "status":{"displayClock":"67'","period":2,"type":{"state":"in","name":"STATUS_SECOND_HALF"}},
                "competitors":[{"homeAway":"home","score":"2","team":{"id":"359","displayName":"Arsenal"}},
                               {"homeAway":"away","score":"1","team":{"id":"363","displayName":"Chelsea"}}]}]},
             "gameInfo":{"venue":{"fullName":"Emirates Stadium","address":{"city":"London"}},"attendance":60100,
               "officials":[{"displayName":"Michael Oliver"}]},
             "rosters":[{"homeAway":"home","team":{"id":"359"},"roster":[
                 {"starter":true,"jersey":"1","athlete":{"displayName":"David Raya"},"position":{"name":"Goalkeeper","abbreviation":"G"}},
                 {"starter":false,"jersey":"9","athlete":{"displayName":"Gabriel Jesus"},"position":{"name":"Forward","abbreviation":"F"}}]},
               {"homeAway":"away","team":{"id":"363"},"roster":[
                 {"starter":true,"jersey":"1","athlete":{"displayName":"Robert Sanchez"},"position":{"name":"Goalkeeper"}}]}],
             "keyEvents":[
               {"type":{"text":"Kickoff"},"clock":{"displayValue":"0'"}},
               {"type":{"text":"Goal"},"clock":{"displayValue":"45'+2'"},"team":{"id":"359"},
                "participants":[{"athlete":{"displayName":"Bukayo Saka"}},{"athlete":{"displayName":"Martin Odegaard"}}]},
               {"type":{"text":"Yellow Card"},"clock":{"displayValue":"12'"},"team":{"id":"363"},"participants":[{"athlete":{"displayName":"Enzo Fernandez"}}]}],
             "boxscore":{"teams":[
                 {"homeAway":"home","team":{"id":"359"},"statistics":[{"name":"possessionPct","label":"Possession","displayValue":"58.2"},{"name":"wonCorners","label":"Corners","displayValue":"6"}]},
                 {"homeAway":"away","team":{"id":"363"},"statistics":[{"name":"possessionPct","label":"Possession","displayValue":"41.8"},{"name":"wonCorners","label":"Corners","displayValue":"2"}]}],
               "form":[{"team":{"id":"359","displayName":"Arsenal"},"events":[
                 {"id":"9","gameDate":"2026-11-08T15:00Z","atVs":"vs","opponent":{"displayName":"Spurs"},"homeTeamId":"359","homeTeamScore":"3","awayTeamScore":"0","gameResult":"W"}]}]},
             "standings":{"groups":[{"standings":{"entries":[
                 {"team":"Arsenal","id":"359","stats":[{"name":"rank","value":1},{"name":"gamesPlayed","value":11},{"name":"wins","value":8},{"name":"ties","value":2},{"name":"losses","value":1},{"name":"pointsFor","value":22},{"name":"pointsAgainst","value":7},{"name":"points","value":26}]},
                 {"team":"Chelsea","id":"363","stats":[{"name":"rank","value":4},{"name":"points","value":20}]}]}}]}}
        """.trimIndent()
        val d = Espn.parseSummary(body, "401")!!
        val ev = d.event!!
        assertEquals("Arsenal", ev.home)
        assertEquals(MatchStatus.LIVE, ev.status)
        assertEquals("2H", ev.statusCode)
        assertEquals("67", ev.progress)
        assertEquals(2, ev.homeScore)
        assertEquals("Michael Oliver", ev.referee)
        assertEquals(60100, ev.spectators)
        assertEquals("Repriza 2 · 67'", MatchInsights.liveLabel(ev.status, ev.statusCode, ev.progress))

        assertEquals(3, d.lineup.size)
        assertTrue(d.lineup.first { it.name == "Gabriel Jesus" }.substitute)
        assertFalse(d.lineup.first { it.name == "Robert Sanchez" }.home)

        assertEquals(listOf(TimelineKind.YELLOW, TimelineKind.GOAL), d.timeline.map { it.kind })
        val goal = d.timeline[1]
        assertEquals(47, goal.minute)
        assertTrue(goal.home)
        assertEquals("Bukayo Saka", goal.player)
        assertEquals("Martin Odegaard", goal.other)
        assertFalse(d.timeline[0].home)

        assertEquals(StatLine("Posesie (%)", "58.2", "41.8"), d.stats[0])
        assertEquals("Cornere", d.stats[1].name)
        assertEquals(listOf(1, 4), d.table.map { it.rank })
        assertEquals(26, d.table[0].points)
        assertEquals("359", d.table[0].teamId)
        assertEquals(1, d.homeRecent.size)
        assertEquals("Arsenal", d.homeRecent[0].home)
        assertEquals(3, d.homeRecent[0].homeScore)
        assertEquals(listOf(FormResult.WIN), MatchInsights.form(d.homeRecent, "Arsenal"))
        assertNull(Espn.parseSummary("nu e json", "1"))
        assertEquals(47, Espn.minute("45'+2'"))
        assertEquals(90, Espn.minute("90'"))
    }

    @Test
    fun `liga necunoscuta sau id gresit`() {
        assertNull(Espn.splitId("XYZ-1"))
        assertNull(Espn.splitId("EPL-"))
        assertTrue(Espn.leagues.map { it.key }.let { it.size == it.toSet().size })
        assertTrue(Espn.leagues.none { '-' in it.key })
    }
}

class EuroLeagueTest {

    @Test
    fun `sezonul - echipele, ora UTC, scorul jucat`() {
        val body = """
            {"data":[
              {"identifier":"E2026_99","gameCode":99,"round":10,"played":true,"utcDate":"2026-11-12T19:30:00Z","gameStatus":"Confirmed",
               "local":{"club":{"name":"FC Bayern Munich","editorialName":"Bayern"},"score":74},
               "road":{"club":{"name":"FC Barcelona"},"score":75},"venue":{"name":"SAP GARDEN"}},
              {"identifier":"E2026_120","gameCode":120,"played":false,"utcDate":"2026-11-20T19:45:00Z","gameStatus":"Confirmed",
               "local":{"club":{"name":"Real Madrid"},"score":0},"road":{"club":{"name":"Olympiacos Piraeus"},"score":0}}],"total":2}
        """.trimIndent()
        val list = EuroLeague.parse(body, EuroLeague.Competition.EUROLEAGUE)
        assertEquals(2, list.size)
        assertEquals("FC Bayern Munich", list[0].home)
        assertEquals(74, list[0].homeScore)
        assertEquals(MatchStatus.FINISHED, list[0].status)
        assertNull(list[1].homeScore)
        assertNull(list[1].status)
        assertEquals("EuroLeague", list[1].competition)
        assertEquals(Instant.parse("2026-11-20T19:45:00Z").toEpochMilli(), list[1].start)
        assertTrue(EuroLeague.parse("{}", EuroLeague.Competition.EUROCUP).isEmpty())
        assertEquals(2026, EuroLeague.seasonYear(LocalDate.of(2026, 11, 15)))
        assertEquals(2025, EuroLeague.seasonYear(LocalDate.of(2026, 3, 1)))
        assertTrue(EuroLeague.url(EuroLeague.Competition.EUROCUP, LocalDate.of(2026, 11, 15)).endsWith("/competitions/U/seasons/U2026/games"))
    }
}

class SisterSitesTest {

    private val html = """
        <html><body><img src="https://static.futbolenlatv.com/img/x.webp">
        <table>
          <tr class="cabeceraTabla"><td>Football on TV today sunday, 11/15/2026</td></tr>
          <tr>
            <td class="hora">19:45</td>
            <td class="detalles"><ul><li><label>UEFA Nations League</label></li></ul></td>
            <td class="local">Spain</td><td class="visitante">England</td>
            <td class="canales"><ul><li>ITV1</li><li>STV</li></ul></td>
          </tr>
          <tr>
            <td class="hora">14:30</td>
            <td class="detalles"><ul><li><span title="American Football"></span><label>NFL Regular Season</label></li></ul></td>
            <td class="local">Detroit Lions</td><td class="visitante">New England Patriots</td>
            <td class="canales"><ul><li>DAZN</li></ul></td>
          </tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun `alt fus, alta tara a posturilor, data cu luna intai`() {
        val us = FutbolEnLaTv.site("us")!!
        val list = FutbolEnLaTv.parse(html, LocalDate.of(2026, 11, 14), us)
        assertEquals(2, list.size)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 19, 45, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli(), list[0].start)
        assertEquals("USA", list[0].stations.first().country)
        assertEquals("American Football", list[1].sport)
        assertTrue(list[0].providerId.startsWith("us "))

        val gb = FutbolEnLaTv.site("gb")!!
        // Data „11/15/2026” citită zi/lună nu e validă: rămâne ziua paginii.
        val uk = FutbolEnLaTv.parse(html, LocalDate.of(2026, 11, 15), gb)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 19, 45, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli(), uk[0].start)
        assertEquals("United Kingdom", uk[0].stations.first().country)
        assertTrue(FutbolEnLaTv.looksValid(html))
    }

    @Test
    fun `site-urile alese dupa tarile canalelor`() {
        assertEquals(listOf("gb", "us", "de"), FutbolEnLaTv.sitesFor(setOf("uk", "us", "de", "it", "ro")).map { it.code })
        assertEquals(listOf("it"), FutbolEnLaTv.sitesFor(setOf("it", "ro")).map { it.code })
        assertTrue(FutbolEnLaTv.sitesFor(setOf("ro")).isEmpty())
        assertTrue(FutbolEnLaTv.isSite("https://www.ukfootballontv.co.uk/sport"))
        assertTrue(FutbolEnLaTv.isSite(FutbolEnLaTv.URL))
        assertFalse(FutbolEnLaTv.isSite("https://www.livesoccertv.com/"))
        assertNotNull(FutbolEnLaTv.site("es"))
    }
}

class Stage14MatchingTest {

    private fun match(id: String, start: Long) = Match(id, Sport.FOOTBALL, "Premier League", "A", "B", start, start + 7_200_000, MatchStatus.SCHEDULED)

    @Test
    fun `C3 - postul anuntat care arata alt meci coboara, presupunerile dispar`() {
        val start = 1_800_000_000_000L
        val m = match("m1", start)
        val found = listOf(
            FoundSource("sky", Confidence.STATION, "Sky Sports"),
            FoundSource("tnt", Confidence.RIGHTS, "TNT"),
            FoundSource("epg", Confidence.EPG),
            FoundSource("free", Confidence.STATION, "Free"),
            FoundSource("own", Confidence.STATION, "Own"),
        )
        val busy = mapOf(
            "sky" to listOf(BusyChannels.Slot(start - 600_000, start + 6_000_000, "other")),
            "tnt" to listOf(BusyChannels.Slot(start, start + 6_000_000, "other")),
            "epg" to listOf(BusyChannels.Slot(start, start + 6_000_000, "other")),
            "own" to listOf(BusyChannels.Slot(start, start + 6_000_000, "other"), BusyChannels.Slot(start, start + 6_000_000, "m1")),
        )
        val out = BusyChannels.adjust(m, found, busy).associateBy { it.groupKey }
        assertEquals(Confidence.RIGHTS, out.getValue("sky").confidence)
        assertEquals(Confidence.STATION, out.getValue("sky").origin)
        assertFalse("tnt" in out)
        assertEquals(Confidence.EPG, out.getValue("epg").confidence)
        assertEquals(Confidence.STATION, out.getValue("free").confidence)
        assertEquals(Confidence.STATION, out.getValue("own").confidence)
    }

    @Test
    fun `C4 - categoria fara eveniment, studioul si prima linie a descrierii`() {
        val t = 1_800_000_000_000L
        fun b(title: String, category: String? = null, description: String? = null) =
            EpgBroadcast(1, 1, t, t + 7_200_000, title, null, description, category)
        assertNull(EpgMatchExtractor.extract(b("Arsenal v Chelsea", category = "Sports non-event"), true))
        assertNull(EpgMatchExtractor.extract(b("Pre-Match: Arsenal v Chelsea"), true))
        assertNotNull(EpgMatchExtractor.extract(b("Arsenal v Chelsea", category = "Football"), true))
        val x = EpgMatchExtractor.extract(b("Premier League Football", description = "Arsenal v Chelsea\nLive coverage from the Emirates."), true)!!
        assertEquals("Arsenal", x.home)
        assertEquals("Chelsea", x.away)
        // O frază lungă din descriere nu e un meci; pe un canal care nu e de sport, descrierea nu se citește.
        assertNull(EpgMatchExtractor.extract(b("Premier League Football", description = "Live coverage of the game between Arsenal v Chelsea at the Emirates Stadium in London"), true))
        assertNull(EpgMatchExtractor.extract(b("Premier League Football", description = "Arsenal v Chelsea"), false))
    }
}
