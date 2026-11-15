package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pagini inventate, cu structuri pe care parserele trebuie să le citească (nu copii ale site-urilor). */
class ProviderPagesTest {

    @Test
    fun liveSoccerTvAmPmAndTeamsFromAddress() {
        val html = """
            <table><tr><td class="timecell"><span>1:30pm</span></td>
            <td><a href="/match/123/latvia-vs-armenia/">Match</a></td>
            <td><a href="/channels/fox/">Fox Soccer Plus</a></td></tr></table>
        """.trimIndent()
        val day = LocalDate.of(2026, 11, 15)
        val e = LiveSoccerTv.parse(html, day, ZoneOffset.UTC).single()
        assertEquals("Latvia", e.home)
        assertEquals("Armenia", e.away)
        assertEquals(day.atTime(13, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), e.start)
        assertEquals(listOf("Fox Soccer Plus"), e.stations.map { it.name })
    }

    @Test
    fun liveSoccerTvAdresaNoua2026() {
        // Structura reală din jurnalul de pe TV (15.11.2026): id-ul după „#”, „livecell live” pe toate rândurile.
        val html = """
            <table class="schedules">
            <tr class="sortable_comp"><td colspan="3"><a href="/competitions/international/uefa-nations-league/"><span>Europe - UEFA Nations League</span></a></td></tr>
            <tr id="5662323" class="matchrow"><td class='timecol'><div class='meta'><span class='livecell live' title='Live Broadcast'></span>
            <span class="timecell"><span class='ts' dv='1794841200000' df='H:MM'>10:00</span></span><span class="inprogress"></span></div></td>
            <td class="matchcol"><a href="/match/faroe-islands-vs-moldova/nxd#5662323" title="Faroe Islands vs Moldova">Faroe Islands vs Moldova</a>
            <div class="mchannels"><a href="/channels/look-tv-romania/" title="Prima Sport 2 (live stream available)" class="homech">Prima Sport 2</a>,
            <a href="/channels/digi-sport-4-romania/" title="Digi Sport 4 Romania (live stream available)">Digi Sport 4 Romania</a></div></td></tr>
            </table>
        """.trimIndent()
        val e = LiveSoccerTv.parse(html, LocalDate.of(2026, 11, 16), ZoneOffset.UTC).single()
        assertEquals("5662323", e.providerId)
        assertEquals("Faroe Islands", e.home)
        assertEquals("Moldova", e.away)
        assertEquals(1794841200000L, e.start)
        assertNull(e.status)
        assertEquals("Europe - UEFA Nations League", e.competition)
        assertEquals(listOf("Prima Sport 2", "Digi Sport 4 Romania"), e.stations.map { it.name })
        assertEquals(listOf("Faroe Islands", "Moldova"), LiveSoccerTv.teamsFromSlug("/match/faroe-islands-vs-moldova/nxd#5662323"))
    }

    @Test
    fun teamsFromSlug() {
        assertEquals(listOf("Bk Hacken", "Pitea"), LiveSoccerTv.teamsFromSlug("/match/9/bk-hacken-vs-pitea/"))
        assertNull(LiveSoccerTv.teamsFromSlug("/match/9/"))
    }

    @Test
    fun doubledTeamNames() {
        assertEquals("Bolívar" to "Atlético Mineiro", FutbolEnLaTv.doubledTeams("Bolívar Bolívar Atlético Mineiro Atlético Mineiro"))
        assertEquals("Real Madrid" to "Getafe", FutbolEnLaTv.doubledTeams("LaLiga Real Madrid Real Madrid Getafe Getafe"))
        assertNull(FutbolEnLaTv.doubledTeams("Etapa 12 Tour"))
    }

    @Test
    fun futbolEnLaTvWithoutTable() {
        val html = """
            <html><body>
            <h2>Partidos de hoy sábado, 14/11/2026</h2>
            <div class="lista">
              <div class="item"><span class="h">00:00</span>
                <div class="d"><span>Copa Libertadores</span></div>
                <div class="eq"><span>Bolívar</span> Bolívar <span>Atlético Mineiro</span> Atlético Mineiro</div>
                <ul class="tv"><li>DAZN</li><li>Movistar Plus+</li></ul></div>
              <div class="item"><span class="h">21:00</span>
                <div class="d"><span>LaLiga</span></div>
                <div class="eq">Real Madrid Real Madrid Getafe Getafe</div>
                <ul class="tv"><li>M+ LaLiga (Dial 47)</li></ul></div>
            </div></body></html>
        """.trimIndent()
        val events = FutbolEnLaTv.parse(html, LocalDate.of(2026, 11, 14))
        assertEquals(2, events.size)
        assertEquals("Atlético Mineiro", events.first { it.home == "Bolívar" }.away)
        val madrid = events.first { it.home == "Real Madrid" }
        assertEquals("Getafe", madrid.away)
        assertEquals(listOf("M+ LaLiga"), madrid.stations.map { it.name })
        val start = LocalDate.of(2026, 11, 14).atTime(21, 0).atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
        assertEquals(start, madrid.start)
    }
}
