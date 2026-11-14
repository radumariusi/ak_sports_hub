package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

class StationNamesTest {
    private val index = StationIndex(listOf("uk:tnt sport 1", "ro:digi sport 1", "ro:prima sport 1", "uk:sky sport main event", "it:sky sport 1"))

    @Test
    fun `țara de la finalul numelui`() {
        assertEquals("digi sport 1" to "romania", StationNames.splitCountry("Digi Sport 1 Romania"))
        assertEquals("sky sports main event" to "uk", StationNames.splitCountry("Sky Sports Main Event (UK)"))
        assertEquals("Sky Sports Main Event" to null, StationNames.splitCountry("Sky Sports Main Event"))
        assertEquals("Romania" to null, StationNames.splitCountry("Romania"))
    }

    @Test
    fun `nume vechi și prescurtări`() {
        assertEquals(listOf("uk:tnt sport 1"), index.find(TvStation("BT Sport 1", "United Kingdom")))
        assertEquals(listOf("ro:prima sport 1"), index.find(TvStation("Look Sport", "Romania")))
        assertEquals(listOf("uk:sky sport main event"), index.find(TvStation("Sky Sp ME")))
        assertEquals(listOf("ro:digi sport 1"), index.find(TvStation("Digi Sport 1 Romania")))
        assertTrue(index.find(TvStation("Digi Sport 1 Italy")).isEmpty())
        assertTrue(index.knows("tntsport1"))
    }
}

class RightsRuleTest {
    @Test
    fun `competiția se potrivește pe cuvinte întregi, în ordine`() {
        val pl = DefaultRights.rules.first()
        assertTrue(pl.appliesTo("English Premier League"))
        assertTrue(pl.appliesTo("England - Premier League"))
        assertFalse(pl.appliesTo("Egyptian Premier League"))
        assertFalse(pl.appliesTo(null))
        assertFalse(pl.copy(enabled = false).appliesTo("English Premier League"))
        val ro = DefaultRights.rules.first { it.country == "RO" && it.stations.contains("Digi Sport 1") }
        assertTrue(ro.appliesTo("Romania - Superliga"))
        assertTrue(ro.appliesTo("Romanian Liga I"))
        assertFalse(ro.appliesTo("Denmark - Superliga"))
    }
}

class SourceFinderTest {
    private val h = TimeUnit.HOURS.toMillis(1)
    private val index = StationIndex(
        listOf("uk:sky sport main event", "uk:sky sport premier league", "uk:tnt sport 1", "ro:digi sport 1", "ro:digi sport 2", "ro:prima sport 1", "bbc 1"),
    )

    private fun match(id: String, comp: String? = "English Premier League", stations: List<TvStation> = emptyList()) =
        Match(id, Sport.FOOTBALL, comp, "Arsenal", "Chelsea", 10 * h, 12 * h, MatchStatus.SCHEDULED, stations = stations)

    @Test
    fun `ordinea încrederii și fără dubluri`() {
        val m = match("a", stations = listOf(TvStation("Sky Sports Main Event", "United Kingdom")))
        val found = SourceFinder(index, SportsKnowledge()).find(m, epgKeys = listOf("bbc 1"))
        val byKey = found.associateBy { it.groupKey }
        assertEquals(Confidence.EPG, byKey.getValue("bbc 1").confidence)
        // Sky Main Event e și post anunțat, și în regula de drepturi: rămâne „post anunțat”.
        assertEquals(Confidence.STATION, byKey.getValue("uk:sky sport main event").confidence)
        assertEquals(Confidence.RIGHTS, byKey.getValue("uk:sky sport premier league").confidence)
        assertEquals(Confidence.RIGHTS, byKey.getValue("uk:tnt sport 1").confidence)
        assertFalse(byKey.containsKey("ro:digi sport 1"))
        assertEquals(found.size, byKey.size)
    }

    @Test
    fun `confirmarea unui post îl pune în dicționar, respingerea îl blochează`() {
        val st = TvStation("Sky Sp Main Ev") // nume pe care indexul nu-l știe
        val m = match("a", comp = null, stations = listOf(st))
        assertTrue(SourceFinder(index, SportsKnowledge()).find(m, emptyList()).isEmpty())

        // Îl legi de mână în dicționar → apare la orice meci cu postul ăsta.
        var k = SportsKnowledge().addStationAlias("Sky Sp Main Ev", "uk:sky sport main event")
        val m2 = match("b", comp = null, stations = listOf(st))
        val found = SourceFinder(index, k).find(m2, emptyList()).single()
        assertEquals(Confidence.STATION, found.confidence)
        assertEquals("Sky Sp Main Ev", found.station)

        // „Nu e meciul” pe o sursă găsită după post: postul nu se mai leagă de canal.
        k = k.decide(m2, found.groupKey, Verdict.REJECTED, found, now = 1)
        assertTrue(k.stationAliases.isEmpty())
        val again = SourceFinder(index, k).find(match("c", comp = null, stations = listOf(st)), emptyList())
        assertTrue(again.isEmpty())
        // La meciul la care ai respins, sursa nu mai există (blocată), deci nimic marcat.
        assertTrue(SourceFinder(index, k).find(m2, emptyList()).isEmpty())
    }

    @Test
    fun `confirmarea unei presupuneri învață competiția`() {
        val noRules = SportsKnowledge(rules = emptyList())
        val m1 = match("a", comp = "Romania - Superliga")
        assertTrue(SourceFinder(index, noRules).find(m1, emptyList()).isEmpty())

        // Adaugi de mână Digi Sport 2 la un meci: confirmat aici, învățat pentru competiție.
        val k = noRules.decide(m1, "ro:digi sport 2", Verdict.CONFIRMED, source = null, now = 1)
        assertEquals(Confidence.CONFIRMED, SourceFinder(index, k).find(m1, emptyList()).single().confidence)
        val m2 = match("b", comp = "Romania - Superliga")
        val learned = SourceFinder(index, k).find(m2, emptyList()).single()
        assertEquals("ro:digi sport 2", learned.groupKey)
        assertEquals(Confidence.LEARNED, learned.confidence)

        // Respingerea unei presupuneri o oprește pentru competiție.
        val withRules = SportsKnowledge()
        val guess = SourceFinder(index, withRules).find(m1, emptyList()).first { it.groupKey == "ro:prima sport 1" }
        assertEquals(Confidence.RIGHTS, guess.confidence)
        val k2 = withRules.decide(m1, guess.groupKey, Verdict.REJECTED, guess, now = 1)
        assertTrue(SourceFinder(index, k2).find(m2, emptyList()).none { it.groupKey == "ro:prima sport 1" })
        assertTrue(SourceFinder(index, k2).find(m1, emptyList()).none { it.groupKey == "ro:prima sport 1" && !it.rejected })
    }

    @Test
    fun `respingerea unei surse EPG e doar pentru meci, iar anularea o readuce`() {
        val m = match("a", comp = null)
        val epg = SourceFinder(index, SportsKnowledge()).find(m, listOf("bbc 1")).single()
        var k = SportsKnowledge().decide(m, "bbc 1", Verdict.REJECTED, epg, now = 1)
        assertTrue(SourceFinder(index, k).find(m, listOf("bbc 1")).single().rejected)
        assertFalse(SourceFinder(index, k).find(match("b", comp = null), listOf("bbc 1")).single().rejected)
        k = k.decide(m, "bbc 1", null, null, now = 2)
        assertNull(k.verdict(m.id, "bbc 1"))
        assertTrue(k.decisions.isEmpty())
    }

    @Test
    fun `alegerile vechi se uită`() {
        val k = SportsKnowledge()
            .decide(match("a"), "bbc 1", Verdict.CONFIRMED, null, now = 100)
            .decide(match("b"), "bbc 1", Verdict.CONFIRMED, null, now = 200)
            .prune(before = 150)
        assertEquals(setOf("b"), k.decisions.keys)
        assertTrue(k.forgetLearning().learnedRights.isEmpty())
    }
}

class SourceTiersTest {
    @Test
    fun `presupunerile vin după sursele găsite, cele respinse la final`() {
        val sources = listOf(
            MatchSource("guess4k", "uk:sky", "en", Quality.UHD, Confidence.RIGHTS),
            MatchSource("rejected", "ro:x", "ro", Quality.UHD, Confidence.EPG, rejected = true),
            MatchSource("epgsd", "ro:digi", "ro", Quality.SD, Confidence.EPG),
            MatchSource("mine", "ro:prima", "ro", Quality.HD, Confidence.CONFIRMED),
        )
        val order = MatchSources.order(sources, isFavorite = false, prefs = SourcePreferences()).map { it.members.first().channel }
        assertEquals(listOf("mine", "epgsd", "guess4k", "rejected"), order)
        val summary = MatchSources.summary(sources)
        assertEquals(3, summary.count)
        assertEquals(2, summary.sure)
        assertEquals(setOf("en", "ro"), summary.languages)
    }
}

class TeamAliasesTest {
    @After
    fun reset() = TeamNames.setUserAliases(emptyMap())

    @Test
    fun `alias-urile tale`() {
        assertFalse(TeamNames.same("Sfantu Gheorghe", "Sepsi OSK"))
        TeamNames.setUserAliases(mapOf("Sfântu Gheorghe" to "Sepsi OSK"))
        assertTrue(TeamNames.same("Sfantu Gheorghe", "Sepsi OSK"))
        assertTrue(TeamNames.same("FC Sfantu Gheorghe", "Sepsi"))
        TeamNames.setUserAliases(mapOf("Utd" to "Man Utd"))
        assertTrue(TeamNames.same("Utd", "Manchester United"))
    }
}

class LiveSoccerTvTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val day = LocalDate.of(2026, 11, 14)

    private val html = """
        <html><body><table class="schedules">
        <tr class="sortable_comp"><td colspan="3"><a href="/competitions/england/premier-league/">England - Premier League</a></td></tr>
        <tr class="matchrow">
          <td class="timecell"><span class="ts" dv="1794150000000">17:00</span></td>
          <td id="match"><a href="/match/5001/arsenal-vs-chelsea/">Arsenal vs Chelsea</a></td>
          <td id="channels"><a href="/channels/sky-sports-main-event/" title="Sky Sports Main Event">Sky Sports Main Event</a>,
             <a href="/channels/digi-sport-1-romania/">Digi Sport 1 Romania</a>, <a href="/channels/">+3 more</a></td>
        </tr>
        <tr class="matchrow livematch">
          <td class="timecell"><span class="narrow live">63'</span></td>
          <td id="match"><a href="/match/5002/liverpool-vs-everton/">Liverpool 2 - 1 Everton</a></td>
          <td id="channels"><a href="/channels/tnt-sports-1/">TNT Sports 1</a></td>
        </tr>
        <tr class="sortable_comp"><td><a href="/competitions/romania/liga-i/">Romania - Superliga</a></td></tr>
        <tr class="matchrow">
          <td class="timecell"><span>21:30</span><span>FT</span></td>
          <td id="match"><a href="/match/5003/fcsb-vs-rapid/">FCSB 3 - 0 Rapid</a></td>
          <td id="channels"><a href="/channels/prima-sport-1/">Prima Sport 1</a></td>
        </tr>
        <tr class="matchrow"><td><span>19:00</span></td><td><a href="/match/5004/x/">Postponed</a></td></tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun `citește meciurile, competiția, posturile și starea`() {
        val events = LiveSoccerTv.parse(html, day, zone)
        assertEquals(listOf("5001", "5002", "5003"), events.map { it.providerId })
        val a = events[0]
        assertEquals("Arsenal", a.home)
        assertEquals("Chelsea", a.away)
        assertEquals("England - Premier League", a.competition)
        assertEquals(1794150000000L, a.start)
        assertNull(a.status)
        assertEquals(listOf("Sky Sports Main Event", "Digi Sport 1 Romania"), a.stations.map { it.name })
        assertEquals(Sport.FOOTBALL, Sport.fromSportsDb(a.sport))

        val live = events[1]
        assertEquals(MatchStatus.LIVE, live.status)
        assertEquals("Liverpool", live.home)
        assertEquals(2, live.homeScore)
        assertEquals(1, live.awayScore)
        assertEquals(a.start, live.start) // fără oră (doar minutul): ora rândului de dinainte

        val ft = events[2]
        assertEquals(MatchStatus.FINISHED, ft.status)
        assertEquals("Romania - Superliga", ft.competition)
        assertEquals(day.atTime(21, 30).atZone(zone).toInstant().toEpochMilli(), ft.start)
    }

    @Test
    fun `pagina stricată sau de verificare dă o listă goală`() {
        assertTrue(LiveSoccerTv.parse("<html>Just a moment...</html>", day, zone).isEmpty())
        assertFalse(LiveSoccerTv.looksValid("<html>Just a moment...</html>"))
        assertTrue(LiveSoccerTv.looksValid(html))
    }
}

class SofascoreTest {
    @Test
    fun `citește programul`() {
        val body = """
            {"events":[
              {"id":11,"startTimestamp":1794150000,"status":{"type":"notstarted"},
               "tournament":{"name":"Premier League","category":{"name":"England"},"uniqueTournament":{"name":"Premier League"}},
               "homeTeam":{"name":"Arsenal"},"awayTeam":{"name":"Chelsea"},"homeScore":{},"awayScore":{}},
              {"id":12,"startTimestamp":1794150000,"status":{"type":"inprogress"},
               "tournament":{"name":"UEFA Champions League","category":{"name":"Europe"}},
               "homeTeam":{"name":"Real Madrid"},"awayTeam":{"name":"Milan"},"homeScore":{"current":1},"awayScore":{"current":0}},
              {"id":13,"startTimestamp":1794150000,"status":{"type":"postponed"},"homeTeam":{"name":"A"},"awayTeam":{"name":"B"}}
            ]}
        """.trimIndent()
        val events = Sofascore.parse(body, Sport.FOOTBALL)
        assertEquals(2, events.size)
        assertEquals("England - Premier League", events[0].competition)
        assertEquals(1794150000000L, events[0].start)
        assertNull(events[0].homeScore)
        assertEquals("UEFA Champions League", events[1].competition)
        assertEquals(MatchStatus.LIVE, events[1].status)
        assertEquals(1, events[1].homeScore)
        assertTrue(Sofascore.parse("not json", Sport.FOOTBALL).isEmpty())
        assertNull(Sofascore.scheduleUrl(Sport.GOLF, LocalDate.of(2026, 11, 14)))
    }
}
