package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

class EpgMatchExtractorTest {

    private fun x(title: String, sportChannel: Boolean = true, category: String? = null) =
        EpgMatchExtractor.extract(title, category, sportChannel)

    @Test
    fun `titlurile din plan`() {
        x("Live: Arsenal v Chelsea")!!.let {
            assertEquals("Arsenal", it.home); assertEquals("Chelsea", it.away); assertTrue(it.explicitLive)
        }
        x("Premier League: Arsenal - Chelsea")!!.let {
            assertEquals("Arsenal", it.home); assertEquals("Chelsea", it.away)
            assertEquals("Premier League", it.competition); assertEquals(Sport.FOOTBALL, it.sport)
        }
        x("Fotbal: FCSB – CFR Cluj (live)")!!.let {
            assertEquals("FCSB", it.home); assertEquals("CFR Cluj", it.away)
            assertEquals(Sport.FOOTBALL, it.sport); assertNull(it.competition); assertTrue(it.explicitLive)
        }
    }

    @Test
    fun `alte forme`() {
        x("NBA: Lakers @ Celtics")!!.let { assertEquals("Celtics", it.home); assertEquals("Lakers", it.away); assertEquals(Sport.BASKETBALL, it.sport) }
        x("Liga 1 | FCSB vs. Rapid")!!.let { assertEquals("FCSB", it.home); assertEquals("Rapid", it.away); assertEquals("Liga 1", it.competition) }
        x("Fotbal - Liga 1 - Dinamo - Farul")!!.let { assertEquals("Dinamo", it.home); assertEquals("Farul", it.away) }
        x("LIVE Serie A: Inter v Milan, etapa 12")!!.let { assertEquals("Milan", it.away); assertEquals("Serie A", it.competition) }
    }

    @Test
    fun `reluările și emisiunile nu sunt meciuri`() {
        assertNull(x("Rezumat: Arsenal v Chelsea"))
        assertNull(x("Premier League Highlights"))
        assertNull(x("Arsenal v Chelsea (R)"))
        assertNull(x("Știri - ediția de seară", sportChannel = false))
        assertNull(x("Kramer vs Kramer", sportChannel = false))
    }

    @Test
    fun `liniuța e acceptată doar cu o urmă de sport`() {
        assertNull(x("Arsenal - Chelsea", sportChannel = false))
        assertNotNull(x("Arsenal - Chelsea", sportChannel = false, category = "Sport"))
        assertNotNull(x("Arsenal - Chelsea", sportChannel = true))
    }

    @Test
    fun `meciul poate fi în subtitlu`() {
        val b = EpgBroadcast(1, 1, 0, 1, title = "Premier League", subtitle = "Arsenal v Chelsea")
        val m = EpgMatchExtractor.extract(b, channelIsSport = true)!!
        assertEquals("Chelsea", m.away); assertEquals("Premier League", m.competition)
    }
}

class TeamNamesTest {
    @Test
    fun `alias-uri și prescurtări`() {
        assertTrue(TeamNames.same("Man Utd", "Manchester United FC"))
        assertTrue(TeamNames.same("PSG", "Paris Saint-Germain"))
        assertTrue(TeamNames.same("FC FCSB", "Steaua București"))
        assertTrue(TeamNames.same("Arsenal", "ARSENAL FC"))
        assertTrue(TeamNames.same("Bayern München", "Bayern Munich"))
        assertFalse(TeamNames.same("Manchester City", "Manchester United"))
        assertFalse(TeamNames.same("Inter Milan", "AC Milan"))
        assertFalse(TeamNames.same("Arsenal Women", "Arsenal"))
        assertTrue(TeamNames.samePair("Chelsea", "Arsenal", "Arsenal", "Chelsea"))
    }
}

class TheSportsDbTest {
    private val events = """
        {"events":[
          {"idEvent":"1","strEvent":"Arsenal vs Chelsea","strSport":"Soccer","strLeague":"English Premier League",
           "strHomeTeam":"Arsenal","strAwayTeam":"Chelsea","strTimestamp":"2026-11-14T20:00:00","dateEvent":"2026-11-14",
           "strTime":"20:00:00","intHomeScore":null,"intAwayScore":null,"strStatus":"NS","strVenue":"Emirates Stadium"},
          {"idEvent":"2","strEvent":"FCSB vs CFR Cluj","strSport":"Soccer","strLeague":"Romanian Liga I",
           "strHomeTeam":"FCSB","strAwayTeam":"CFR Cluj","strTimestamp":"2026-11-14T18:30:00+00:00",
           "intHomeScore":"1","intAwayScore":"0","strStatus":"2H"},
          {"idEvent":"3","strHomeTeam":"A","strAwayTeam":"B","dateEvent":"2026-11-14","strTime":"10:00:00","strStatus":"Postponed"}
        ]}
    """.trimIndent()

    @Test
    fun `citește meciurile, ora UTC și starea`() {
        val list = TheSportsDb.parseEvents(events)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("Arsenal", a.home)
        assertEquals(LocalDate.of(2026, 11, 14).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), a.start)
        assertEquals(MatchStatus.SCHEDULED, a.status)
        assertEquals(MatchStatus.LIVE, list[1].status)
        assertEquals(1, list[1].homeScore)
    }

    @Test
    fun `răspuns gol sau stricat`() {
        assertTrue(TheSportsDb.parseEvents("""{"events":null}""").isEmpty())
        assertTrue(TheSportsDb.parseEvents("<html>").isEmpty())
        assertTrue(TheSportsDb.parseTv("").isEmpty())
    }

    @Test
    fun `posturile TV`() {
        val tv = TheSportsDb.parseTv("""{"tvevents":[{"idEvent":"1","strChannel":"Sky Sports Main Event","strCountry":"United Kingdom"}]}""")
        assertEquals(listOf(TheSportsDb.TvListing("1", TvStation("Sky Sports Main Event", "United Kingdom"))), tv)
    }
}

class MatchAssemblerTest {
    private val h = TimeUnit.HOURS.toMillis(1)
    private val kickoff = 1_000 * h

    private fun ev(id: String, home: String, away: String, start: Long = kickoff, status: MatchStatus? = null) =
        SportEvent(TheSportsDb.PROVIDER, id, "Soccer", "Premier League", home, away, start, status)

    private fun br(id: Long, title: String, start: Long, stop: Long, channel: Long = id): MatchBroadcast {
        val b = EpgBroadcast(id, channel, start, stop, title)
        return MatchBroadcast(b, EpgMatchExtractor.extract(b, channelIsSport = true)!!)
    }

    @Test
    fun `dublurile se unesc`() {
        val list = MatchAssembler.dedupe(listOf(ev("1", "Man Utd", "Spurs"), ev("2", "Manchester United", "Tottenham Hotspur", kickoff + 10 * 60_000)))
        assertEquals(1, list.size)
        assertEquals(2, MatchAssembler.dedupe(listOf(ev("1", "A Team", "B Team"), ev("2", "A Team", "B Team", kickoff + h))).size)
    }

    @Test
    fun `emisiunea EPG se leagă de meciul furnizorului`() {
        val matches = MatchAssembler.assemble(
            events = listOf(ev("1", "Arsenal", "Chelsea")),
            broadcasts = listOf(
                br(10, "Live: Arsenal v Chelsea", kickoff - h / 2, kickoff + 2 * h),
                br(11, "Fotbal: Arsenal - Chelsea", kickoff, kickoff + 2 * h),
                br(12, "Premier League: Arsenal v Chelsea", kickoff + 26 * h, kickoff + 28 * h), // reluare a doua zi
                br(13, "Liga 1: FCSB - Rapid", kickoff + 3 * h, kickoff + 5 * h),
                br(14, "Superliga: FCSB vs Rapid", kickoff + 3 * h, kickoff + 5 * h),
            ),
            now = kickoff - 5 * h,
        )
        val arsenal = matches.first { it.id == "thesportsdb:1" }
        // Reluarea de a doua zi devine o sursă a meciului, nu un meci nou.
        assertEquals(listOf(10L, 11L, 12L), arsenal.broadcasts.map { it.programmeId })
        val epgOnly = matches.filter { it.providers == setOf(MatchAssembler.EPG_PROVIDER) }
        assertEquals(1, epgOnly.size) // FCSB – Rapid (din două canale)
        assertEquals(2, epgOnly.first { it.home == "FCSB" }.broadcasts.size)
    }

    @Test
    fun `starea după oră și după furnizor`() {
        fun status(now: Long, s: MatchStatus? = null) =
            MatchAssembler.assemble(listOf(ev("1", "A Team", "B Team", status = s)), emptyList(), now).single().status
        assertEquals(MatchStatus.SCHEDULED, status(kickoff - h))
        assertEquals(MatchStatus.LIVE, status(kickoff + h, MatchStatus.SCHEDULED))
        assertEquals(MatchStatus.FINISHED, status(kickoff + 3 * h))
        assertEquals(MatchStatus.LIVE, status(kickoff + 2 * h + 50 * 60_000L, MatchStatus.LIVE)) // prelungiri
        // „LIVE” vechi din fișier: după durata obișnuită plus 30 de minute, s-a terminat.
        assertEquals(MatchStatus.FINISHED, status(kickoff + 3 * h + 60_000L, MatchStatus.LIVE))
        assertEquals(MatchStatus.FINISHED, status(kickoff + h, MatchStatus.FINISHED))
    }
}

class StationIndexTest {
    private val index = StationIndex(listOf("uk:sky sport main event", "de:sky sport main event", "ro:digi sport 1", "bbc 1", "uk:tnt sport 1"))

    @Test
    fun `numele postului, cu țara`() {
        assertEquals(listOf("uk:sky sport main event"), index.find(TvStation("Sky Sports Main Event", "United Kingdom")))
        assertEquals(listOf("ro:digi sport 1"), index.find(TvStation("Digisport 1", "Romania")))
        assertEquals(listOf("uk:tnt sport 1"), index.find(TvStation("TNT Sports 1 HD", "UK")))
        assertEquals(listOf("bbc 1"), index.find(TvStation("BBC One", "United Kingdom")))
        assertEquals(2, index.find(TvStation("Sky Sports Main Event")).size)
        assertTrue(index.find(TvStation("Sky Sports Main Event", "Italy")).isEmpty())
    }
}

class MatchListsTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val now = LocalDate.of(2026, 11, 14).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
    private val h = TimeUnit.HOURS.toMillis(1)

    private fun m(start: Long, status: MatchStatus = MatchStatus.SCHEDULED, home: String = "Arsenal", comp: String? = "Premier League") =
        Match("x$start", Sport.FOOTBALL, comp, home, "Chelsea", start, start + 2 * h, status)

    @Test
    fun `taburile`() {
        val live = m(now - h, MatchStatus.LIVE)
        val tonight = m(now + 5 * h)
        val tomorrow = m(now + 24 * h)
        val nextWeek = m(now + 8 * 24 * h)
        assertTrue(MatchLists.inTab(live, MatchTab.LIVE, now, zone, false))
        assertFalse(MatchLists.inTab(tonight, MatchTab.LIVE, now, zone, false))
        assertTrue(MatchLists.inTab(tonight, MatchTab.TODAY, now, zone, false))
        assertTrue(MatchLists.inTab(tomorrow, MatchTab.TOMORROW, now, zone, false))
        assertTrue(MatchLists.inTab(tomorrow, MatchTab.WEEK, now, zone, false))
        assertFalse(MatchLists.inTab(nextWeek, MatchTab.WEEK, now, zone, false))
        assertTrue(MatchLists.inTab(tomorrow, MatchTab.FAVORITES, now, zone, true))
    }

    @Test
    fun `filtrele`() {
        val match = m(now)
        val s = SourceSummary(2, setOf("en"), Quality.FHD)
        assertTrue(MatchFilter(sport = Sport.FOOTBALL, team = "arse", language = "en", minQuality = Quality.FHD).accepts(match, s))
        assertFalse(MatchFilter(minQuality = Quality.UHD).accepts(match, s))
        assertFalse(MatchFilter(language = "ro").accepts(match, s))
        assertFalse(MatchFilter(onlyWithSources = true).accepts(match, SourceSummary.NONE))
        assertFalse(MatchFilter(competition = "Liga 1").accepts(match, s))
    }

    @Test
    fun `favorite`() {
        val fav = SportsFavorites(teams = listOf("Man Utd"), competitions = listOf("Liga 1"))
        assertTrue(fav.isFavorite(m(now, home = "Manchester United")))
        assertTrue(fav.isFavorite(m(now, home = "FCSB", comp = "Liga 1")))
        assertFalse(fav.isFavorite(m(now)))
    }

    @Test
    fun `sursele grupate pe canal, în ordinea preferințelor`() {
        val sources = listOf(
            MatchSource("de4k", "de:sky", "de", Quality.UHD, Confidence.STATION),
            MatchSource("enhd", "uk:sky", "en", Quality.HD, Confidence.EPG),
            MatchSource("enfhd", "uk:sky", "en", Quality.FHD, Confidence.EPG),
            MatchSource("rofhd", "ro:digi", "ro", Quality.FHD, Confidence.EPG),
        )
        val fav = MatchSources.order(sources, isFavorite = true, prefs = SourcePreferences())
        assertEquals(listOf("uk:sky", "ro:digi", "de:sky"), fav.map { it.key })
        assertEquals(listOf("enfhd", "enhd"), fav[0].members.map { it.channel })
        val other = MatchSources.order(sources, isFavorite = false, prefs = SourcePreferences())
        assertEquals("de:sky", other.first().key)
        assertEquals(SourceSummary(4, setOf("de", "en", "ro"), Quality.UHD), MatchSources.summary(sources))
    }
}

class Stage13Test {
    private val h = 3_600_000L
    private val now = java.time.LocalDateTime.of(2026, 11, 15, 12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @org.junit.Test
    fun `un titlu cu data de ieri e o reluare, nu un meci live`() {
        val b = EpgBroadcast(1, 1, now, now + 2 * h, "NORWAY - WALES 14/11/26", category = "Football")
        org.junit.Assert.assertNull(EpgMatchExtractor.extract(b, true))
        val today = EpgBroadcast(2, 1, now, now + 2 * h, "Norway - Wales 15.11.2026", category = "Football")
        val x = EpgMatchExtractor.extract(today, true)!!
        org.junit.Assert.assertEquals("Wales", x.away)
    }

    @org.junit.Test
    fun `meciurile au id-uri unice`() {
        val a = EpgBroadcast(1, 1, now - h, now + h, "Live: Alpha v Beta", category = "Football")
        val b = EpgBroadcast(2, 2, now + 5 * h, now + 7 * h, "Alpha v Beta (women)", category = "Football")
        val c = EpgBroadcast(3, 3, now + 9 * h, now + 11 * h, "Alpha v Beta", category = "Football")
        val list = listOf(a, b, c).mapNotNull { x -> EpgMatchExtractor.extract(x, true)?.let { MatchBroadcast(x, it) } }
        val matches = MatchAssembler.assemble(emptyList(), list, now)
        org.junit.Assert.assertEquals(matches.size, matches.map { it.id }.toSet().size)
    }
}
