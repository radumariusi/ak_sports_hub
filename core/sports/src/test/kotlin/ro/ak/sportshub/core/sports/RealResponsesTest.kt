package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Răspunsuri reale (16.11.2026), micșorate: doar câmpurile citite, câteva elemente din fiecare listă.
 * Formatele marcate înainte „neverificate” sunt verificate aici (calupul 1, E1). Fișierele sunt în
 * `src/test/resources/reale`; nu conțin nimic din playlisturi sau EPG.
 */
class RealResponsesTest {

    private fun text(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/reale/$name")) { name }.bufferedReader().use { it.readText() }

    // ---------- FotMob ----------

    @Test
    fun fotmobFinishedMatchHasStatsLineupMomentumShotsAndVenue() {
        val d = requireNotNull(FotMobDetails.parse(text("fotmob-meci-final.html")))
        val possession = d.stats.first { it.name == "Posesie (%)" }
        assertEquals("64" to "36", possession.home to possession.away)
        assertTrue(d.stats.any { it.name == "Goluri așteptate (xG)" && it.home == "1.88" })
        assertTrue(d.stats.any { it.name == "Șuturi pe poartă" })
        assertEquals(22, d.lineup.count { !it.substitute })
        assertEquals("David Raya", d.lineup.first().name)

        assertEquals(94, d.momentum.size)
        assertEquals(-27, d.momentum[1].value)

        assertEquals(8, d.shots.size)
        val first = d.shots.first()
        assertEquals("Christos Tzolis", first.player)
        assertEquals(ShotOutcome.MISSED, first.outcome)
        assertTrue(first.home)
        assertEquals(89.7f / 105f, first.x, 0.001f)
        assertTrue(d.shots.count { it.outcome == ShotOutcome.GOAL } >= 2)
        assertTrue(d.shots.any { !it.home })

        val v = requireNotNull(d.venue)
        assertEquals("Emirates Stadium", v.stadium)
        assertEquals("Thomas Bramall", v.referee)
        assertEquals(60098, v.attendance)
        assertEquals(Weather(15, "Senin", null), v.weather)
    }

    @Test
    fun fotmobCompactPageKeepsEverythingRead() {
        val page = text("fotmob-meci-final.html")
        val small = requireNotNull(FotMobDetails.compact(page))
        assertTrue(small.length < page.length)
        assertEquals(FotMobDetails.parse(page), FotMobDetails.parse(small))
    }

    @Test
    fun fotmobBeforeKickoffHasAbsentPlayersRefereeAndWeather() {
        val d = requireNotNull(FotMobDetails.parse(text("fotmob-meci-inainte.html")))
        assertTrue(d.stats.isEmpty())
        assertTrue(d.lineup.isEmpty())
        assertEquals(listOf("Marc Guéhi", "Antoine Semenyo", "Tom Cairney"), d.absent.map { it.name })
        assertEquals(listOf(true, true, false), d.absent.map { it.home })
        assertEquals(AbsenceKind.INJURED, d.absent.first().kind)
        assertEquals("revine: sfârșitul lui noiembrie 2026", d.absent.first().detail)
        val v = requireNotNull(d.venue)
        assertEquals("Etihad Stadium", v.stadium)
        assertEquals(61470, v.capacity)
        assertEquals("Thomas Bramall", v.referee)
        assertEquals("7 °C, Parțial noros", v.weather?.label)
    }

    @Test
    fun fotmobLeaguePageReadsScoresAndIgnoresTableForm() {
        val events = FotMob.parseLeague(text("fotmob-liga-tineret.html"), FotMob.youthLeagues[0])
        // 2 jucate + 2 viitoare; forma din clasament (aceleași meciuri, fără id) nu le mai dublează.
        assertEquals(4, events.size)
        val played = events.filter { it.status == MatchStatus.FINISHED }
        assertEquals(2, played.size)
        assertTrue(played.all { it.homeScore != null && it.awayScore != null })
        assertTrue(events.filter { it.status == null }.all { it.homeScore == null })
    }

    // ---------- ESPN summary ----------

    @Test
    fun espnNbaFinalHasWinProbabilityInjuriesAndReferee() {
        val d = requireNotNull(Espn.parseSummary(text("espn-nba-final.json"), "x"))
        assertEquals("Sean Corbin", d.event?.referee)
        val wp = requireNotNull(d.winProbability)
        assertTrue(!wp.forecast)
        assertTrue(wp.series.size > 5)
        assertEquals(1f, wp.home, 0.001f)
        assertTrue(d.absent.isNotEmpty())
        assertEquals(21024, d.venue?.attendance)
    }

    @Test
    fun espnNflBeforeKickoffHasPredictorAndWeather() {
        val d = requireNotNull(Espn.parseSummary(text("espn-nfl-inainte.json"), "x"))
        val wp = requireNotNull(d.winProbability)
        assertTrue(wp.forecast)
        assertEquals(0.435f, wp.home, 0.001f)
        assertEquals(0.561f, wp.away, 0.001f)
        assertNull(wp.draw)
        assertEquals(7, d.venue?.weather?.celsius)
        assertTrue(d.absent.any { it.name == "Lamar Jackson" && it.home })
    }

    @Test
    fun espnNflRefereeIsTheRefereeNotTheFirstOfficial() {
        val d = requireNotNull(Espn.parseSummary(text("espn-nfl-final.json"), "x"))
        assertEquals("Alan Eck", d.event?.referee)
        assertNotNull(d.winProbability)
    }

    // ---------- sursele oficiale ----------

    @Test
    fun nhlRightRailStats() {
        val d = requireNotNull(OfficialStats.parseStats(OfficialStats.League.NHL, text("nhl-right-rail.json")))
        val sog = d.stats.first { it.name == "Șuturi pe poartă" }
        assertEquals("36" to "20", sog.home to sog.away)
        assertTrue(d.stats.any { it.name.startsWith("Powerplay") })
    }

    // ---------- traducerile ----------

    @Test
    fun translations() {
        assertEquals("mijlocul lui decembrie 2026", MatchExtras.returnText("Mid December 2026"))
        assertEquals("Unknown", MatchExtras.returnText("Unknown"))
        assertEquals("Parțial noros", MatchExtras.weatherText("Partly Cloudy"))
        assertEquals(7, MatchExtras.celsius(44.0))
        assertEquals(AbsenceKind.DOUBTFUL, MatchExtras.espnKind("Questionable", null))
        assertEquals(AbsenceKind.SUSPENDED, MatchExtras.fotmobKind("red_card"))
        assertEquals("genunchi", MatchExtras.injuryText("Knee"))
        assertNull(MatchExtras.injuryText("Not Specified"))
    }
}
