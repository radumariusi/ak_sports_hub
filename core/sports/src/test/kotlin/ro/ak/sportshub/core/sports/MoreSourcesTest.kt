package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val Bucharest: ZoneId = ZoneId.of("Europe/Bucharest")
private val London: ZoneId = ZoneId.of("Europe/London")

/** Sâmbătă 14.11.2026, 20:00 la București (18:00 UTC). */
private val NOW = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, Bucharest).toInstant().toEpochMilli()

private fun at(zone: ZoneId, day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

class EventChannelsTest {

    private fun p(name: String, group: String? = null, zone: ZoneId = Bucharest) = EventChannels.parse(1, name, group, NOW, zone)

    @Test
    fun `recunoaște canalele de eveniment`() {
        assertTrue(EventChannels.isEventChannel("EVENT 03: UFC 310 20:00", null))
        assertTrue(EventChannels.isEventChannel("NBA League Pass 03", "USA SPORTS"))
        assertTrue(EventChannels.isEventChannel("ESPN+ 12 : Team A vs Team B", null))
        assertTrue(EventChannels.isEventChannel("Canal 4", "PPV EVENTS"))
        assertTrue(EventChannels.isEventChannel("Arsenal vs Chelsea 20:45", null))
        assertFalse(EventChannels.isEventChannel("Sky Sports Main Event", "UK SPORTS"))
        assertFalse(EventChannels.isEventChannel("Digi Sport 1", "RO"))
        assertFalse(EventChannels.isEventChannel("Eventi TV", "IT"))
    }

    @Test
    fun `meci cu oră`() {
        p("EVENT 03: Arsenal vs Chelsea 20:45").let {
            assertEquals("Arsenal", it.home); assertEquals("Chelsea", it.away)
            assertEquals(at(Bucharest, 14, 20, 45), it.start)
        }
        // „@”: gazdele sunt după.
        p("NBA League Pass 03: Lakers @ Celtics 01:30").let {
            assertEquals("Celtics", it.home); assertEquals("Lakers", it.away)
            assertEquals(Sport.BASKETBALL, it.sport)
            assertEquals("NBA", it.competition)
            // 01:30 la 20:00 e mâine.
            assertEquals(at(Bucharest, 15, 1, 30), it.start)
        }
    }

    @Test
    fun `fusul și data din nume`() {
        p("PPV 1 | Arsenal vs Chelsea | 15/11 20:45 UK").let {
            assertEquals("Arsenal", it.home)
            assertEquals(at(London, 15, 20, 45), it.start)
        }
        p("USA | ESPN+ 12 : Duke vs UNC @ 7:30 PM ET").let {
            assertEquals("Duke", it.home); assertEquals("UNC", it.away)
            assertEquals(ZonedDateTime.of(2026, 11, 14, 19, 30, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli(), it.start)
        }
        p("EVENT 5: Sat 21 Nov 22:00 - Fury vs Usyk").let {
            assertEquals("Fury", it.home); assertEquals("Usyk", it.away)
            assertEquals(at(Bucharest, 21, 22), it.start)
        }
        // O oră de azi, trecută de 3 ore, rămâne azi.
        assertEquals(at(Bucharest, 14, 17), p("EVENT 7: Dinamo vs Rapid 17:00").start)
    }

    @Test
    fun `eveniment fără echipe`() {
        p("EVENT 03: UFC 310 21:00").let {
            assertEquals("UFC 310", it.home); assertNull(it.away)
            assertEquals(Sport.FIGHTING, it.sport)
            assertEquals(at(Bucharest, 14, 21), it.start)
        }
        p("EVENT 05: UFC 310: Pantoja vs Asakura").let {
            assertEquals("Pantoja", it.home); assertEquals("Asakura", it.away); assertEquals("UFC 310", it.competition)
            assertNull(it.start)
        }
    }

    @Test
    fun `canale goale`() {
        assertNull(p("EVENT 03").title)
        assertNull(p("NBA League Pass 03").title)
        assertNull(p("PPV 12: No Event").title)
        assertNull(p("EVENT 4 | OFF AIR").title)
        assertNull(p("ESPN+ 08").title)
    }
}

class EventNamesTest {

    @Test
    fun `aceleași curse scrise diferit`() {
        assertEquals(true, EventNames.same("Abu Dhabi Grand Prix", "Formula 1: Marele Premiu din Abu Dhabi - Cursa"))
        assertEquals(true, EventNames.same("Qatar Grand Prix", "Marele Premiu al Qatarului"))
        assertEquals(false, EventNames.same("Qatar Grand Prix", "Abu Dhabi Grand Prix"))
        assertEquals(false, EventNames.same("UFC 310", "UFC 311"))
        assertEquals(true, EventNames.same("UFC 310", "UFC 310: Main Card"))
        assertNull(EventNames.same("Formula 1 - Cursa", "Abu Dhabi Grand Prix"))
    }

    @Test
    fun `fără nume care să decidă contează sportul și ora`() {
        val t = NOW
        assertTrue(EventNames.match("Formula 1 - Cursa", Sport.MOTORSPORT, t, "Abu Dhabi Grand Prix", Sport.MOTORSPORT, t + 600_000, 3_600_000))
        assertFalse(EventNames.match("Formula 1 - Cursa", null, t, "Abu Dhabi Grand Prix", Sport.MOTORSPORT, t, 3_600_000))
        assertFalse(EventNames.match("Abu Dhabi Grand Prix", Sport.MOTORSPORT, t, "Abu Dhabi Grand Prix", Sport.MOTORSPORT, t + 7_200_000, 3_600_000))
    }
}

class SingleEventEpgTest {

    private fun b(title: String, category: String? = null, subtitle: String? = null) =
        EpgBroadcast(1, 1, NOW, NOW + 7_200_000, title, subtitle, null, category)

    @Test
    fun `curse, etape și gale din EPG`() {
        EpgMatchExtractor.extractEvent(b("Formula 1: Marele Premiu al Qatarului - Cursa (live)"), channelIsSport = true)!!.let {
            assertEquals("", it.away); assertEquals(Sport.MOTORSPORT, it.sport); assertTrue(it.explicitLive)
            assertTrue(it.home.contains("Qatarului"))
        }
        assertNotNull(EpgMatchExtractor.extractEvent(b("Ciclism: Turul Franței, etapa 12"), channelIsSport = true))
        assertNotNull(EpgMatchExtractor.extractEvent(b("UFC 310: Main Card"), channelIsSport = true))
    }

    @Test
    fun `nu ia emisiunile despre sport`() {
        assertNull(EpgMatchExtractor.extractEvent(b("Formula 1: Rezumat"), channelIsSport = true))
        assertNull(EpgMatchExtractor.extractEvent(b("F1 Show"), channelIsSport = true))
        assertNull(EpgMatchExtractor.extractEvent(b("Știri sportive"), channelIsSport = true))
        assertNull(EpgMatchExtractor.extractEvent(b("Formula 1: Marele Premiu - Cursa"), channelIsSport = false))
        // Fotbalul are echipe: nu e eveniment fără echipe.
        assertNull(EpgMatchExtractor.extractEvent(b("Premier League live"), channelIsSport = true))
    }
}

class MoreSourcesAssemblerTest {

    private fun ev(provider: String, id: String, home: String, away: String, start: Long, sport: String? = null, competition: String? = null) =
        SportEvent(provider, id, sport, competition, home, away, start)

    @Test
    fun `evenimentele fără echipe se deduplică și primesc EPG-ul`() {
        val start = at(Bucharest, 15, 15)
        val events = listOf(
            ev("thesportsdb", "1", "Abu Dhabi Grand Prix", "", start, "Motorsport", "Formula 1"),
            ev("futbolenlatv", "x", "GP de Abu Dabi - Carrera", "", start + 300_000, "Motorsport", "Formula 1"),
        )
        val epg = EpgMatchExtractor.extractEvent(
            EpgBroadcast(7, 70, start - 3_600_000, start + 7_200_000, "Formula 1: Marele Premiu din Abu Dhabi - Cursa (live)"),
            channelIsSport = true,
        )!!
        val b = MatchBroadcast(EpgBroadcast(7, 70, start - 3_600_000, start + 7_200_000, "x"), epg)
        val matches = MatchAssembler.assemble(events, listOf(b), NOW)
        assertEquals(1, matches.size)
        assertEquals(1, matches[0].broadcasts.size)
        assertTrue(matches[0].isSingleEvent)
    }

    @Test
    fun `canalele eveniment se leagă de meci sau devin meciuri noi`() {
        val start = at(Bucharest, 14, 22)
        val events = listOf(ev("thesportsdb", "1", "Arsenal", "Chelsea", start, "Soccer", "Premier League"))
        val slots = listOf(
            EventChannels.parse(11, "EVENT 1: Arsenal vs Chelsea 22:00", null, NOW, Bucharest),
            EventChannels.parse(12, "EVENT 2: UFC 310 23:00", null, NOW, Bucharest),
            EventChannels.parse(13, "PPV 3: UFC 310 23:00", null, NOW, Bucharest),
            EventChannels.parse(14, "EVENT 4", null, NOW, Bucharest),
        )
        val matches = MatchAssembler.assemble(events, emptyList(), NOW, slots = slots)
        val arsenal = matches.single { it.home == "Arsenal" }
        assertEquals(listOf(11L), arsenal.eventChannels)
        val ufc = matches.single { it.home == "UFC 310" }
        assertEquals(listOf(12L, 13L), ufc.eventChannels.sorted())
        assertEquals(setOf(MatchAssembler.EVENT_PROVIDER), ufc.providers)
        assertEquals(2, matches.size)
    }
}

class MoreSourcesFinderTest {

    private val index = StationIndex(listOf("uk:sky sport f1", "es:dazn f1", "event 1 arsenal vs chelsea 22 00", "es:m laliga"))

    private fun match(competition: String? = "Formula 1", home: String = "Abu Dhabi Grand Prix", away: String = "", stations: List<TvStation> = emptyList()) =
        Match("m", Sport.MOTORSPORT, competition, home, away, NOW, NOW + 7_200_000, MatchStatus.SCHEDULED, stations = stations)

    @Test
    fun `canale dedicate`() {
        assertEquals("f1", DedicatedChannels.seriesOfChannel("DAZN F1 FHD"))
        assertEquals("f1", DedicatedChannels.seriesOfChannel("F1 TV Pro"))
        assertEquals("formula e", DedicatedChannels.seriesOfChannel("Formula E TV"))
        assertNull(DedicatedChannels.seriesOfChannel("Digi Sport 1"))
        assertEquals("f1", DedicatedChannels.seriesOfMatch(match()))
        assertNull(DedicatedChannels.seriesOfMatch(match(competition = "Premier League", home = "Arsenal", away = "Chelsea").copy(sport = Sport.FOOTBALL)))
    }

    @Test
    fun `eveniment sigur, dedicat presupus`() {
        val finder = SourceFinder(index, SportsKnowledge(rules = emptyList()))
        val found = finder.find(match(), epgKeys = emptyList(), eventKeys = listOf("event 1"), dedicatedKeys = listOf("es:dazn f1"))
        assertEquals(Confidence.EVENT, found.first { it.groupKey == "event 1" }.confidence)
        assertEquals(Confidence.DEDICATED, found.first { it.groupKey == "es:dazn f1" }.confidence)
        assertTrue(Confidence.DEDICATED.guess)
        assertFalse(Confidence.EVENT.guess)
    }

    @Test
    fun `de ce nu are sursă`() {
        val m = match(
            competition = "English Premier League", home = "Arsenal", away = "Chelsea",
            stations = listOf(TvStation("M+ LaLiga", "Spain"), TvStation("Sky Sports Main Event", "GB"), TvStation("DAZN F1", "Italy")),
        )
        val k = SportsKnowledge()
        val d = SourceDiagnostics.diagnose(m, index, k)
        val byName = d.stations.associate { it.station.name to it.outcome }
        assertEquals(StationOutcome.FOUND, byName["M+ LaLiga"])
        assertEquals(StationOutcome.MISSING, byName["Sky Sports Main Event"])
        assertEquals(StationOutcome.OTHER_COUNTRY, byName["DAZN F1"])
        assertEquals(2, d.linkable.size)
        // Regula implicită pentru Premier League (UK) nu găsește posturile în playlisturi.
        assertTrue(d.rules.isNotEmpty())
        assertTrue(d.rules.first().missing.isNotEmpty())

        val linked = k.addStationAlias("Sky Sports Main Event", "uk:sky sport f1")
        val after = SourceDiagnostics.diagnose(m, index, linked).stations.first { it.station.name == "Sky Sports Main Event" }
        assertEquals(StationOutcome.FROM_DICTIONARY, after.outcome)
    }
}

class SofascoreTvTest {

    @Test
    fun `posturi pe țară, cu id-uri sau cu nume`() {
        val refs = Sofascore.parseCountryChannels(
            """{"countryChannels":{"GB":[101,102],"RO":[{"id":201,"name":"Digi Sport 1"}],"ES":[]}}""",
        )
        assertEquals(setOf("GB", "RO"), refs.keys)
        val names = Sofascore.parseChannelNames(
            """{"channels":[{"id":101,"name":"Sky Sports Main Event"},{"id":102,"name":"TNT Sports 1"}]}""",
        )
        assertEquals("TNT Sports 1", names["102"])
        val wantedRo: (String) -> Boolean = { cc -> Sofascore.prefixes(cc).any { it in setOf("ro") } }
        assertEquals(listOf(TvStation("Digi Sport 1", "RO")), Sofascore.stations(refs, names, wantedRo))
        val all = Sofascore.stations(refs, names) { true }
        assertEquals(3, all.size)
        assertTrue(Sofascore.prefixes("GB").contains("uk"))
        assertEquals(mapOf("GB" to setOf("101", "102")), Sofascore.unnamed(refs, emptyMap()) { it == "GB" })
    }

    @Test
    fun `numele postului din programul lui, fără echipe`() {
        val names = Sofascore.parseChannelNames(
            """{"channel":{"id":55,"name":"Eurosport 1"},"events":[{"id":9,"homeTeam":{"id":3,"name":"Arsenal"},"startTimestamp":1}]}""",
        )
        assertEquals(mapOf("55" to "Eurosport 1"), names)
        assertTrue(Sofascore.parseCountryChannels("<html>").isEmpty())
    }

    @Test
    fun `alege puține meciuri, favoritele întâi`() {
        fun e(id: String, home: String, start: Long, status: MatchStatus? = MatchStatus.SCHEDULED) =
            SportEvent(Sofascore.PROVIDER, id, "Soccer", "Liga", home, "B", start, status)
        val events = listOf(
            e("1", "A", NOW + 3_600_000),
            e("2", "Fav", NOW + 20 * 3_600_000L),
            e("3", "Done", NOW - 3_600_000, MatchStatus.FINISHED),
            e("4", "Far", NOW + 40 * 3_600_000L),
            e("5", "Covered", NOW + 1_800_000),
        )
        val picked = Sofascore.pickForTv(events, NOW, max = 3, favorite = { it.home == "Fav" }, covered = { it.home == "Covered" }, important = { false })
        assertEquals(listOf("2", "1", "5"), picked.map { it.providerId })
    }
}

class FutbolEnLaTvTest {

    private val html = """
        <html><head><title>futbolenlatv</title></head><body>
        <table class="tablaPrincipal">
          <tr class="cabeceraTabla"><td colspan="5">Partidos de hoy sábado, 14/11/2026</td></tr>
          <tr>
            <td class="hora">21:00</td>
            <td class="detalles"><ul><li><span class="ico_futbol" title="Fútbol"></span><label title="LaLiga EA Sports">LaLiga EA Sports</label></li></ul></td>
            <td class="local"><span>Real Madrid</span></td>
            <td class="visitante"><span>Barcelona</span></td>
            <td class="canales"><ul class="listaCanales"><li title="M+ LaLiga">M+ LaLiga (54 y 104)</li><li>DAZN LaLiga</li></ul></td>
          </tr>
          <tr class="cabeceraTabla"><td colspan="5">Partidos de mañana domingo, 15/11/2026</td></tr>
          <tr>
            <td class="hora">14:00</td>
            <td class="detalles"><ul><li><span class="ico_formula1" title="Fórmula 1"></span><label>Mundial de Fórmula 1</label></li></ul></td>
            <td colspan="2" class="evento">GP de Abu Dabi - Carrera</td>
            <td class="canales"><ul><li>DAZN F1</li><li>Ver en directo</li></ul></td>
          </tr>
          <tr>
            <td class="hora">01:00</td>
            <td class="detalles"><ul><li><span title="Baloncesto"></span><label>NCAA</label></li></ul></td>
            <td class="local">Michigan State</td>
            <td class="visitante">Northern Iowa</td>
            <td class="canales"><ul><li>DAZN (Ver en directo)</li></ul></td>
          </tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun `citește rândurile, zilele și posturile`() {
        assertTrue(FutbolEnLaTv.looksValid(html))
        assertFalse(FutbolEnLaTv.looksValid("<html>Just a moment... captcha</html>"))
        val events = FutbolEnLaTv.parse(html, LocalDate.of(2026, 11, 14))
        assertEquals(3, events.size)
        val clasico = events[0]
        assertEquals("Real Madrid", clasico.home); assertEquals("Barcelona", clasico.away)
        assertEquals("Soccer", clasico.sport)
        assertEquals("LaLiga EA Sports", clasico.competition)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 21, 0, 0, 0, FutbolEnLaTv.ZONE).toInstant().toEpochMilli(), clasico.start)
        assertEquals(listOf("M+ LaLiga", "DAZN LaLiga"), clasico.stations.map { it.name })
        assertEquals("Spain", clasico.stations.first().country)

        val f1 = events[1]
        assertEquals("GP de Abu Dabi - Carrera", f1.home); assertEquals("", f1.away)
        assertEquals("Motorsport", f1.sport)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 14, 0, 0, 0, FutbolEnLaTv.ZONE).toInstant().toEpochMilli(), f1.start)
        assertEquals(listOf("DAZN F1"), f1.stations.map { it.name })

        assertEquals("Basketball", events[2].sport)
        assertEquals(listOf("DAZN"), events[2].stations.map { it.name })
    }

    @Test
    fun `pagină stricată`() {
        assertTrue(FutbolEnLaTv.parse("<html><body>nimic</body></html>", LocalDate.of(2026, 11, 14)).isEmpty())
    }
}
