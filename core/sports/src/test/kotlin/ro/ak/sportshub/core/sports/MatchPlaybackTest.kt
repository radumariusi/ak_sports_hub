package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPlaybackTest {

    private val kickoff = 1_800_000_000_000L
    private fun min(m: Long) = TimeUnit.MINUTES.toMillis(m)
    private fun sec(s: Long) = TimeUnit.SECONDS.toMillis(s)

    private val match = Match(
        id = "thesportsdb:1", sport = Sport.FOOTBALL, competition = "Premier League",
        home = "Arsenal", away = "Chelsea", start = kickoff, end = kickoff + min(115),
        status = MatchStatus.FINISHED, homeScore = 2, awayScore = 1,
    )

    private fun goal(minute: Int, home: Boolean, kind: TimelineKind = TimelineKind.GOAL) =
        TimelineEntry(minute, kind, home, "Jucător $minute", null)

    @Test
    fun `ora estimată a unui eveniment`() {
        // Minutul 23: între 22:00 și 23:00 de la start, la mijloc.
        assertEquals(kickoff + min(22) + sec(30), MatchClock.estimate(kickoff, 23, Sport.FOOTBALL))
        // Repriza a doua: + 2 minute de prelungiri și 15 de pauză.
        assertEquals(kickoff + min(66 + 17) + sec(30), MatchClock.estimate(kickoff, 67, Sport.FOOTBALL))
        // „90+3” dat ca 93 continuă repriza a doua.
        assertEquals(kickoff + min(92 + 17) + sec(30), MatchClock.estimate(kickoff, 93, null))
        assertNull(MatchClock.estimate(kickoff, 23, Sport.TENNIS))
        assertNull(MatchClock.estimate(kickoff, null, Sport.FOOTBALL))
    }

    @Test
    fun `saltul pornește cu un minut înainte, cu corecția playlistului`() {
        val at = MatchClock.estimate(kickoff, 30, Sport.FOOTBALL)!!
        assertEquals(at - sec(60), MatchClock.jumpStart(kickoff, 30, Sport.FOOTBALL, 0))
        assertEquals(at - sec(60) + sec(20), MatchClock.jumpStart(kickoff, 30, Sport.FOOTBALL, 20))
        assertEquals(at - sec(60) - sec(15), MatchClock.jumpStart(kickoff, 30, Sport.FOOTBALL, -15))
        // Un gol din primul minut nu pornește cu mult înainte de start.
        assertEquals(kickoff - sec(60), MatchClock.jumpStart(kickoff, 1, Sport.FOOTBALL, -120))
    }

    @Test
    fun `minutul la o oră dată`() {
        assertNull(MatchClock.minuteAt(kickoff, kickoff - 1, Sport.FOOTBALL))
        assertEquals(1, MatchClock.minuteAt(kickoff, kickoff + sec(10), Sport.FOOTBALL))
        assertEquals(30, MatchClock.minuteAt(kickoff, kickoff + min(29) + sec(5), Sport.FOOTBALL))
        assertTrue(MatchClock.isHalfTime(kickoff, kickoff + min(50), Sport.FOOTBALL))
        assertEquals(45, MatchClock.minuteAt(kickoff, kickoff + min(50), Sport.FOOTBALL))
        assertFalse(MatchClock.isHalfTime(kickoff, kickoff + min(70), Sport.FOOTBALL))
        // Ora estimată a minutului 67 dă înapoi minutul 67.
        assertEquals(67, MatchClock.minuteAt(kickoff, MatchClock.estimate(kickoff, 67, Sport.FOOTBALL)!!, Sport.FOOTBALL))
    }

    @Test
    fun `scorul din goluri, cu autogolul citit după scorul final`() {
        val tl = listOf(goal(10, true), goal(40, false, TimelineKind.OWN_GOAL), goal(70, false))
        // Autogol al unui jucător al oaspeților (home = false): golul e al gazdelor.
        assertEquals(2 to 1, GoalCount.score(tl))
        // Dacă scorul final spune altceva, autogolul e citit invers.
        assertEquals(1 to 2, GoalCount.score(tl, tl, finalHome = 1, finalAway = 2))
        assertEquals(1 to 0, GoalCount.score(tl.take(1), tl, finalHome = 2, finalAway = 1))
    }

    @Test
    fun `reluarea arată doar ce s-a întâmplat până unde te uiți`() {
        val details = MatchDetails(
            timeline = listOf(goal(10, true), goal(60, false), goal(80, true)),
            stats = listOf(StatLine("Posesie (%)", "55", "45")),
        )
        val at = kickoff + min(30)
        val v = ReplayView.at(match, details, at)
        assertEquals(1, v.timeline.size)
        assertEquals(1, v.homeScore)
        assertEquals(0, v.awayScore)
        assertEquals("Reluare · 31'", v.label)
        assertTrue(v.stats.isEmpty())
        assertTrue(v.statsHidden)

        val end = ReplayView.at(match, details, match.end + 1)
        assertEquals(3, end.timeline.size)
        assertEquals(2, end.homeScore)
        assertEquals(1, end.awayScore)
        assertEquals(1, end.stats.size)
        assertEquals("Reluare · final", end.label)

        assertEquals("Reluare · înainte de start", ReplayView.at(match, details, kickoff - min(1)).label)
        assertEquals("Reluare · pauză", ReplayView.at(match, details, kickoff + min(52)).label)
    }

    @Test
    fun `evenimentele noi apar după întârzierea stream-ului`() {
        val live = match.copy(status = MatchStatus.LIVE, homeScore = null, awayScore = null)
        val delay = LiveDelay()
        val first = MatchDetails(
            event = EventDetails("1", "Arsenal", "Chelsea", homeScore = 1, awayScore = 0, status = MatchStatus.LIVE, statusCode = "1H", progress = "20"),
            timeline = listOf(goal(12, true)),
        )
        val t0 = kickoff + min(20)
        delay.observe(t0, first)
        // Ce exista deja la deschidere se vede imediat.
        val v0 = delay.view(t0, sec(30), live)!!
        assertEquals(1, v0.homeScore)
        assertEquals(1, v0.timeline.size)

        val second = first.copy(
            event = first.event!!.copy(awayScore = 1, progress = "21"),
            timeline = first.timeline + goal(21, false),
        )
        val t1 = t0 + sec(60)
        delay.observe(t1, second)
        val hidden = delay.view(t1 + sec(10), sec(30), live)!!
        assertEquals(0, hidden.awayScore)
        assertEquals(1, hidden.timeline.size)
        assertEquals(1, hidden.pending)
        val shown = delay.view(t1 + sec(30), sec(30), live)!!
        assertEquals(1, shown.awayScore)
        assertEquals(2, shown.timeline.size)
        assertEquals(0, shown.pending)
        // Fără întârziere, imediat.
        assertEquals(1, delay.view(t1, 0, live)!!.awayScore)
    }

    @Test
    fun `anunțul pentru meciul favorit care a început`() {
        val fav = SportsFavorites(teams = listOf("Arsenal"))
        val now = kickoff + min(3)
        val live = match.copy(status = MatchStatus.LIVE)
        val other = live.copy(id = "x", home = "Inter", away = "Milan")
        assertEquals(listOf(live), KickoffNotices.due(listOf(live, other), fav, now, emptySet(), null))
        assertTrue(KickoffNotices.due(listOf(live), fav, now, setOf(live.id), null).isEmpty())
        assertTrue(KickoffNotices.due(listOf(live), fav, now, emptySet(), live.id).isEmpty())
        assertTrue(KickoffNotices.due(listOf(live), fav, kickoff + min(20), emptySet(), null).isEmpty())
        assertTrue(KickoffNotices.due(listOf(live), fav, kickoff - min(1), emptySet(), null).isEmpty())
    }

    @Test
    fun `formația din pozițiile titularilor`() {
        fun p(pos: String, sub: Boolean = false) = LineupPlayer("J $pos", pos, null, home = true, substitute = sub)
        val players = listOf(p("Goalkeeper")) +
            List(4) { p("Defender") } + List(3) { p("Midfielder") } +
            listOf(p("Left Wing"), p("Centre-Forward"), p("Right Winger")) + p("Goalkeeper", sub = true)
        val f = Formation.of(players)!!
        assertEquals("4-3-3", f.label)
        assertEquals(4, f.lines.size)
        assertEquals(PitchLine.DEFENCE, Formation.line("Centre-Back"))
        assertEquals(PitchLine.MIDFIELD, Formation.line("Defensive Midfield"))
        assertNull(Formation.of(players.drop(1)))
        assertNull(Formation.of(players.map { it.copy(position = null) }))
    }

    @Test
    fun scorul_din_lista_apare_dupa_intarziere() {
        val d = ScoreDelay()
        val live = match.copy(status = MatchStatus.LIVE, homeScore = 0, awayScore = 0, end = kickoff + min(115))
        val t0 = kickoff + min(10)
        d.observe(listOf(live), t0)
        // Ce se știa de la început apare imediat.
        assertEquals(0, d.apply(live, t0, sec(30)).homeScore)
        val goal = live.copy(homeScore = 1)
        d.observe(listOf(goal), t0 + sec(60))
        assertTrue(d.pending(t0 + sec(70), sec(30)))
        assertEquals(0, d.apply(goal, t0 + sec(70), sec(30)).homeScore)
        assertEquals(1, d.apply(goal, t0 + sec(91), sec(30)).homeScore)
        assertFalse(d.pending(t0 + sec(91), sec(30)))
        // Fără întârziere: scorul de acum.
        assertEquals(1, d.apply(goal, t0 + sec(61), 0).homeScore)
        // Finalul tot după întârziere: meciul rămâne LIVE până atunci.
        val end = goal.copy(status = MatchStatus.FINISHED, end = t0 + sec(120))
        d.observe(listOf(end), t0 + sec(120))
        assertEquals(MatchStatus.LIVE, d.apply(end, t0 + sec(130), sec(30)).status)
        assertEquals(MatchStatus.FINISHED, d.apply(end, t0 + sec(151), sec(30)).status)
    }

    @Test
    fun emisiunea_de_acum_alege_meciul() {
        val belgium = match.copy(id = "a", home = "Belgium", away = "Italy", status = MatchStatus.LIVE)
        val spain = match.copy(id = "b", home = "Spain", away = "England", status = MatchStatus.LIVE)
        val found = OnAirMatch.find("UEFA Nations League - Spain vs. England", null, null, kickoff + min(20), listOf(belgium, spain))
        assertEquals("b", found?.id)
        assertNull(OnAirMatch.find("LaLiga Goals Galore", null, null, kickoff + min(20), listOf(belgium, spain)))
    }

    @Test
    fun minutul_merge_intre_cereri() {
        assertEquals("25", LiveClock.advance("22", "1H", Sport.FOOTBALL, min(3)))
        assertEquals("45+2", LiveClock.advance("44", "1H", Sport.FOOTBALL, min(3)))
        assertEquals("22", LiveClock.advance("22", "HT", Sport.FOOTBALL, min(3)))
        assertEquals("22", LiveClock.advance("22", "1H", Sport.BASKETBALL, min(3)))
    }
}
