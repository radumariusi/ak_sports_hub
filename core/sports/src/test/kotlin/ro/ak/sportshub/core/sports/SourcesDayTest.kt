package ro.ak.sportshub.core.sports

import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

/** Calupul 3: de ce e aici sursa (C3), raportul zilei (C4), pauzele furnizorilor (E4), zapping la pauză (A1), suprapuneri (A2), banda de scoruri (A3). */
class SourcesDayTest {

    private val zone = ZoneOffset.UTC
    private val h = TimeUnit.HOURS.toMillis(1)
    private val min = TimeUnit.MINUTES.toMillis(1)

    // 16.11.2026 00:00 UTC
    private val day = 1_794_787_200_000L

    private fun match(id: String, home: String, away: String, start: Long, status: MatchStatus = MatchStatus.SCHEDULED, competition: String? = "Premier League") =
        Match(id, Sport.FOOTBALL, competition, home, away, start, start + 2 * h, status)

    private fun src(c: Confidence, station: String? = null, airing: String? = null, origin: Confidence = c, rejected: Boolean = false) =
        MatchSource("ch", "key", "en", Quality.FHD, c, station = station, rejected = rejected, origin = origin, airing = airing)

    @Test
    fun de_ce_e_aici_sursa() {
        val b = EpgBroadcast(1, 7, day + 20 * h, day + 22 * h, "Premier League: Arsenal v Chelsea")
        val m = match("m", "Arsenal", "Chelsea", day + 20 * h).copy(
            broadcasts = listOf(b),
            stations = listOf(TvStation("Sky Sports Main Event", "United Kingdom")),
        )
        assertEquals("EPG-ul canalului: „Premier League: Arsenal v Chelsea” la 20:00.", SourceReasons.explain(m, src(Confidence.EPG), "Sky", b, zone))
        assertTrue(SourceReasons.explain(m, src(Confidence.EPG), "Sky", null, zone).startsWith("EPG-ul altei variante"))
        assertEquals(
            "Post anunțat de LiveSoccerTV: Sky Sports Main Event (United Kingdom).",
            SourceReasons.explain(m, src(Confidence.STATION, "Sky Sports Main Event"), "UK: Sky Main Event", null, zone, "LiveSoccerTV"),
        )
        assertEquals(
            "EPG-ul canalului arată la ora meciului: „Snooker”. Anunțat: Sky Sports Main Event.",
            SourceReasons.explain(m, src(Confidence.ELSEWHERE, "Sky Sports Main Event", "Snooker"), "x", null, zone),
        )
        assertTrue(SourceReasons.explain(m, src(Confidence.CONFIRMED, origin = Confidence.RIGHTS, station = "DAZN"), "x", null, zone).startsWith("Confirmat de tine. Găsit inițial: Drepturi TV"))
        assertTrue(SourceReasons.explain(m, src(Confidence.EVENT, rejected = true), "EVENT 3: Arsenal v Chelsea", null, zone).startsWith("Ai spus că nu e meciul"))
    }

    @Test
    fun raportul_zilei() {
        val now = day + 9 * h
        val items = listOf(
            match("a", "Arsenal", "Chelsea", day + 20 * h) to SourceSummary(3, setOf("en"), Quality.FHD, sure = 2),
            match("b", "Barcelona", "Real Madrid", day + 21 * h) to SourceSummary(1, setOf("es"), Quality.HD, sure = 0),
            match("c", "Milan", "Inter", day + 19 * h) to SourceSummary.NONE,
            match("d", "Ajax", "PSV", day + 30 * h) to SourceSummary(2, emptySet(), Quality.SD, sure = 2),
        )
        val r = DailyReports.build(items, { it.first }, { it.second }, { true }, now, zone)!!
        assertEquals(3, r.total)
        assertEquals("c", r.focus!!.first.id)
        assertEquals("Azi: 3 meciuri favorite · 1 cu surse sigure · 1 doar presupuse · 1 fără surse · 3 se suprapun", r.text)
        assertNull(DailyReports.build(items, { it.first }, { it.second }, { false }, now, zone))
    }

    @Test
    fun suprapunerile_si_ordinea_propusa() {
        val a = match("a", "Arsenal", "Chelsea", day + 20 * h)
        val b = match("b", "Barcelona", "Real Madrid", day + 21 * h)
        val c = match("c", "Ajax", "PSV", day + 23 * h)
        assertEquals(listOf("b"), FavoriteConflicts.overlapping(a, listOf(a, b, c)).map { it.id })
        assertEquals(
            "Propunere: Arsenal – Chelsea până la pauză (~20:47), apoi Barcelona – Real Madrid; la pauza lui (~21:47) te poți întoarce.",
            FavoriteConflicts.advice(b, a, zone),
        )
        val d = match("d", "Lazio", "Roma", day + 20 * h + 10 * min)
        assertTrue(FavoriteConflicts.advice(a, d, zone).contains("iar la pauză (~20:47) treci pe Lazio – Roma"))
    }

    @Test
    fun furnizorii_in_pauza() {
        val now = day + 20 * h
        assertNull(PausedProviders.notice(listOf("FotMob" to now - 1), now, zone))
        assertEquals(
            "LiveSoccerTV în pauză până la 22:10 (a refuzat cererile): sursele și meciurile pot fi mai puține",
            PausedProviders.notice(listOf("LiveSoccerTV" to now + 2 * h + 10 * min), now, zone),
        )
        assertEquals(
            "LiveSoccerTV până la 22:00, FotMob până mâine la 08:00 în pauză (au refuzat cererile): sursele și meciurile pot fi mai puține",
            PausedProviders.notice(listOf("FotMob" to now + 12 * h, "LiveSoccerTV" to now + 2 * h), now, zone),
        )
    }

    @Test
    fun pauza_se_recunoaste_din_eticheta() {
        assertTrue(HalftimeZap.isBreak(MatchInsights.liveLabel(MatchStatus.LIVE, "HT", "45"), Sport.FOOTBALL))
        assertFalse(HalftimeZap.isBreak(MatchInsights.liveLabel(MatchStatus.LIVE, "2H", "46"), Sport.FOOTBALL))
        assertTrue(HalftimeZap.isBreak(MatchInsights.liveLabel(MatchStatus.LIVE, "EP", "20:00"), Sport.ICE_HOCKEY))
        assertFalse(HalftimeZap.isBreak(MatchInsights.liveLabel(MatchStatus.LIVE, "EP", "12:00"), Sport.BASKETBALL))
        assertFalse(HalftimeZap.isBreak(MatchInsights.liveLabel(MatchStatus.LIVE, "BT", null), Sport.FOOTBALL))
        assertEquals("PAUZĂ", Scoreboard.clock(MatchInsights.liveLabel(MatchStatus.LIVE, "EP", null)))
    }

    @Test
    fun zapping_la_pauza() {
        val cur = match("cur", "Arsenal", "Chelsea", day + 20 * h, MatchStatus.LIVE)
        val fav = match("fav", "Barcelona", "Real Madrid", day + 20 * h, MatchStatus.LIVE, "LaLiga")
        val same = match("same", "Spurs", "Everton", day + 20 * h, MatchStatus.LIVE)
        val guess = match("g", "Lazio", "Roma", day + 20 * h, MatchStatus.LIVE, "Serie A")
        val later = match("l", "Ajax", "PSV", day + 22 * h)
        val items = listOf(guess to 0, same to 2, fav to 1, later to 3)
        val pick = HalftimeZap.pick(cur, items, { it.first }, { it.id == "fav" }, { it.second }, { true })
        assertEquals("fav", pick!!.first.id)
        val noFav = HalftimeZap.pick(cur, items, { it.first }, { false }, { it.second }, { true })
        assertEquals("same", noFav!!.first.id)
        assertNull(HalftimeZap.pick(cur, listOf(guess to 0), { it.first }, { false }, { it.second }, { true }))
        val seen = day + 20 * h + 47 * min
        assertFalse(HalftimeZap.returnDue(Sport.FOOTBALL, seen, seen + 5 * min, resumed = false))
        assertTrue(HalftimeZap.returnDue(Sport.FOOTBALL, seen, seen + 5 * min, resumed = true))
        assertTrue(HalftimeZap.returnDue(Sport.FOOTBALL, seen, seen + 14 * min, resumed = false))
        assertEquals(10, HalftimeZap.secondsLeft(0, 0))
    }

    @Test
    fun banda_de_scoruri() {
        val a = match("a", "Arsenal", "Chelsea", day + 20 * h, MatchStatus.LIVE)
        val b = match("b", "Ajax", "PSV", day + 19 * h, MatchStatus.LIVE)
        val c = match("c", "Milan", "Inter", day + 19 * h)
        assertEquals(listOf("b"), ScoreBand.pick(listOf(a, b, c), { true }, "a").map { it.id })
        assertEquals("ARS 1–0 CHE 67′", ScoreBand.text(a, 1, 0, "67′", hidden = false))
        assertEquals("ARS – CHE · live", ScoreBand.text(a, 1, 0, "67′", hidden = true))
    }
}
