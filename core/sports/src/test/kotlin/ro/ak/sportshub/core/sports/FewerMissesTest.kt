package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.model.Quality

/** Mai puține surse greșite: țara postului, lista pe țări a LiveSoccerTV, verificarea în EPG, grupurile de limbi. */
class FewerMissesTest {

    @Test
    fun tara_din_adresa_postului() {
        assertEquals("romania", Countries.fromSlug("/channels/digi-sport-4-romania/"))
        assertEquals("australia", Countries.fromSlug("/channels/bein-sports-2-australia/"))
        assertEquals("united kingdom", Countries.fromSlug("/channels/sky-sports-main-event-united-kingdom/"))
        assertNull(Countries.fromSlug("/channels/fox/"))
    }

    @Test
    fun pagina_zilei_pune_tara_pe_post() {
        val html = """
            <table><tr><td class="timecell"><span>20:45</span></td>
            <td><a href="/match/latvia-vs-armenia/abc#77">Latvia vs Armenia</a>
            <div><a href="/channels/look-tv-romania/" title="Prima Sport 2">Prima Sport 2</a></div></td></tr></table>
        """.trimIndent()
        val e = LiveSoccerTv.parse(html, LocalDate.of(2026, 11, 15), ZoneOffset.UTC).single()
        assertEquals(TvStation("Prima Sport 2", "romania"), e.stations.single())
        assertEquals("https://www.livesoccertv.com/match/latvia-vs-armenia/abc", e.url)
    }

    @Test
    fun pagina_meciului_pe_tari() {
        val html = """
            <h3>International Coverage</h3>
            <table id="wc_channels">
            <tr><td><img alt="flag"> <span>Algeria</span></td><td><a href="/channels/bein-sports-connect-algeria/">beIN SPORTS CONNECT</a></td></tr>
            <tr><td>Andorra</td><td><a href="/channels/dazn-spain/">DAZN Spain</a> <a href="/channels/lequipe-live-foot/">L'Équipe Live Foot</a></td></tr>
            <tr><td>Australia</td><td><a href="/channels/bein-sports-2-australia/">beIN SPORTS 2</a></td></tr>
            <tr><td>Bangladesh</td><td><a href="/channels/sony-ten-2/">SONY TEN 2</a></td></tr>
            <tr><td>United Kingdom</td><td><a href="/channels/premier-sports-1/">Premier Sports 1</a></td></tr>
            </table>
        """.trimIndent()
        val st = LiveSoccerTv.parseMatchStations(html)
        assertTrue(LiveSoccerTv.looksValidMatch(html))
        assertEquals("Algeria", st.first { it.name == "beIN SPORTS CONNECT" }.country)
        assertEquals("Andorra", st.first { it.name == "L'Équipe Live Foot" }.country)
        assertEquals("Australia", st.first { it.name == "beIN SPORTS 2" }.country)
        assertEquals("Bangladesh", st.first { it.name == "SONY TEN 2" }.country)
        assertEquals("United Kingdom", st.first { it.name == "Premier Sports 1" }.country)
    }

    @Test
    fun postul_din_alta_tara_nu_se_leaga_de_canalele_mele() {
        val index = StationIndex(listOf("fr:bein sport 2", "au:bein sport 2", "uk:premier sport 1", "bein sport 2 extra"))
        assertEquals(listOf("au:bein sport 2"), index.find(TvStation("beIN SPORTS 2", "Australia")))
        // O țară necunoscută nu mai prinde canalele tuturor țărilor.
        assertTrue(index.find(TvStation("beIN SPORTS 2", "Bangladesh")).none { ':' in it })
        assertEquals(listOf("uk:premier sport 1"), index.find(TvStation("Premier Sports 1", "United Kingdom")))
    }

    private fun match(home: String = "Arsenal", away: String = "Chelsea", comp: String = "Premier League", start: Long = 1_000_000_000L) =
        Match("m", Sport.FOOTBALL, comp, home, away, start, start + 7_200_000, MatchStatus.SCHEDULED)

    @Test
    fun epg_arata_altceva_coboara_sursa() {
        val m = match()
        val found = listOf(
            FoundSource("uk:sky main", Confidence.STATION, "Sky Sports Main Event"),
            FoundSource("uk:tnt 1", Confidence.RIGHTS, "TNT Sports 1"),
            FoundSource("uk:sky football", Confidence.STATION, "Sky Sports Football"),
            FoundSource("uk:sky arena", Confidence.STATION, "Sky Sports Arena"),
            FoundSource("uk:bbc", Confidence.EPG),
        )
        val s = m.start
        val airing = mapOf(
            "uk:sky main" to listOf(AiringCheck.Airing(s - 600_000, s + 7_000_000, "Snooker: UK Championship", "Snooker: UK Championship Day 3")),
            "uk:tnt 1" to listOf(AiringCheck.Airing(s - 600_000, s + 7_000_000, "Live: Arsenal v Chelsea", "Live: Arsenal v Chelsea")),
            "uk:sky football" to listOf(AiringCheck.Airing(s - 600_000, s + 7_000_000, "Live Football", "Live Football")),
            // Sky Arena: EPG doar a doua zi: nu se știe nimic la ora meciului.
            "uk:sky arena" to listOf(AiringCheck.Airing(s + 86_400_000, s + 90_000_000, "News", "News")),
        )
        val out = AiringCheck.adjust(m, found) { airing[it] }.associateBy { it.groupKey }
        assertEquals(Confidence.ELSEWHERE, out.getValue("uk:sky main").confidence)
        assertEquals("Snooker: UK Championship", out.getValue("uk:sky main").airing)
        assertEquals(Confidence.STATION, out.getValue("uk:sky main").origin)
        assertEquals(Confidence.RIGHTS, out.getValue("uk:tnt 1").confidence)
        assertEquals(Confidence.STATION, out.getValue("uk:sky football").confidence)
        assertEquals(Confidence.STATION, out.getValue("uk:sky arena").confidence)
        assertEquals(Confidence.EPG, out.getValue("uk:bbc").confidence)
    }

    @Test
    fun sursa_care_arata_altceva_nu_conteaza_si_vine_ultima() {
        val sources = listOf(
            MatchSource("a", "uk:sky", "en", Quality.UHD, Confidence.ELSEWHERE, airing = "Snooker"),
            MatchSource("b", "uk:tnt", "en", Quality.HD, Confidence.RIGHTS),
            MatchSource("c", "ro:digi", "ro", Quality.SD, Confidence.EPG),
        )
        val order = MatchSources.order(sources, isFavorite = false, prefs = ro.ak.sportshub.core.model.SourcePreferences())
        assertEquals(listOf("ro:digi", "uk:tnt", "uk:sky"), order.map { it.key })
        val sum = MatchSources.summary(sources)
        assertEquals(2, sum.count)
        assertEquals(1, sum.sure)
    }

    @Test
    fun grupuri_de_limbi() {
        val pref = listOf("en", "ro")
        val sources = listOf(
            MatchSource("1", "es:dazn", "es", Quality.FHD, Confidence.EPG),
            MatchSource("2", "ar:bein", "ar", Quality.UHD, Confidence.EPG),
            MatchSource("3", "uk:sky", "en", Quality.HD, Confidence.EPG),
            MatchSource("4", "x:unknown", null, Quality.HD, Confidence.EPG),
            MatchSource("5", "ro:digi", "ro", Quality.HD, Confidence.EPG, rejected = true),
        )
        val families = ChannelFamilies.collapse(sources) { it.groupKey }
        val (groups, rejected) = LanguageGroups.split(families, pref)
        assertEquals(listOf(LanguageGroup.PREFERRED, LanguageGroup.EUROPEAN, LanguageGroup.OTHER), groups.map { it.first })
        assertEquals(listOf("uk:sky"), groups[0].second.map { it.key })
        assertEquals(setOf("ar:bein", "x:unknown"), groups[2].second.map { it.key }.toSet())
        assertEquals(listOf("ro:digi"), rejected.map { it.key })
        assertEquals("Engleză și română", LanguageGroups.label(LanguageGroup.PREFERRED, pref))
        assertFalse(LanguageGroups.label(LanguageGroup.EUROPEAN, pref).isEmpty())
    }

    @Test
    fun `meciul feminin scris diferit de doi furnizori e unul singur`() {
        val t = 1_763_200_000_000L
        val a = SportEvent("espn", "1", "Soccer", "Women's Super League", "Arsenal", "London City", t, null)
        val b = SportEvent("livesoccertv", "x", "Soccer", null, "Arsenal Frauen", "London City Lionesses", t + 5 * 60_000L, null)
        assertEquals(1, MatchAssembler.dedupe(listOf(a, b)).size)
        // Echipa mare și cea feminină rămân diferite în afara competițiilor feminine.
        val men = SportEvent("espn", "2", "Soccer", "Premier League", "Arsenal", "Chelsea", t, null)
        val women = SportEvent("livesoccertv", "y", "Soccer", null, "Arsenal Women", "Chelsea Women", t, null)
        assertEquals(2, MatchAssembler.dedupe(listOf(men, women)).size)
    }

    @Test
    fun `naționalele scrise în alte limbi sunt același meci`() {
        val t = 1_763_230_000_000L
        val list = listOf(
            SportEvent("espn", "1", "Soccer", "UEFA Nations League", "Belarus", "Albania", t, null),
            SportEvent("futbolenlatv", "2", "Soccer", "UEFA Nations League Fase de Liga", "Bielorrusia", "Albania", t, null),
            SportEvent("livesoccertv", "3", "Soccer", "UEFA Nations League", "Weißrussland", "Albanien", t + 60_000L, null),
            SportEvent("espn", "4", "Soccer", "UEFA Nations League", "Finland", "San Marino", t, null),
            SportEvent("futbolenlatv", "5", "Soccer", null, "Finlandia", "San Marino", t, null),
            SportEvent("livesoccertv", "6", "Soccer", null, "Finnland", "San Marino", t, null),
            SportEvent("futbolenlatv", "7", "Soccer", null, "Alemania", "España", t, null),
            SportEvent("livesoccertv", "8", "Soccer", null, "Deutschland", "Spanien", t, null),
        )
        assertEquals(3, MatchAssembler.dedupe(list).size)
        assertTrue(TeamNames.same("Weißrussland", "Belarus"))
        assertTrue(TeamNames.same("Türkiye", "Turquía"))
    }
}
