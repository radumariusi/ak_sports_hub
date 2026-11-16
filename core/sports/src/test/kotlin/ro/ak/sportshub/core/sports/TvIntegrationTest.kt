package ro.ak.sportshub.core.sports

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calupul 5: căutarea (F1), Watch Next (F2), economizorul de ecran (F3) și tablourile cupelor (A6). */
class TvIntegrationTest {

    private val zone = ZoneId.of("Europe/Bucharest")
    private val now = ZonedDateTime.of(2026, 11, 16, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val min = TimeUnit.MINUTES.toMillis(1)

    private fun match(
        id: String,
        home: String,
        away: String,
        startIn: Long,
        status: MatchStatus = MatchStatus.SCHEDULED,
        competition: String = "English Premier League",
        sport: Sport = Sport.FOOTBALL,
        score: Pair<Int, Int>? = null,
    ) = Match(
        id = id,
        sport = sport,
        competition = competition,
        home = home,
        away = away,
        start = now + startIn,
        end = now + startIn + 120 * min,
        status = status,
        homeScore = score?.first,
        awayScore = score?.second,
    )

    private fun text(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/reale/$name")) { name }.bufferedReader().use { it.readText() }

    // ---------- F1 ----------

    @Test
    fun spokenPhraseFindsTheTeamAndCompetition() {
        val arsenal = match("1", "Arsenal", "Chelsea", 60 * min)
        val ucl = match("2", "Barcelona", "Inter", 30 * min, competition = "UEFA Champions League")
        val live = match("3", "Brentford", "Arsenal", -30 * min, MatchStatus.LIVE)
        val nba = match("4", "Boston Celtics", "Miami Heat", 90 * min, competition = "NBA", sport = Sport.BASKETBALL)
        val all = listOf(arsenal, ucl, live, nba)

        assertEquals("Arsenal", MatchSearch.clean("pune meciul lui Arsenal"))
        // Echipa întâi; meciul live înaintea celui de mai târziu.
        assertEquals(listOf("3", "1"), MatchSearch.find(all, "meciul lui Arsenal").map { it.id })
        // Competiția spusă pe românește („Liga Campionilor” e UCL).
        assertEquals(listOf("2"), MatchSearch.find(all, "Liga Campionilor").map { it.id })
        // Cuvinte din titlu și sport.
        assertEquals(listOf("4"), MatchSearch.find(all, "baschet celtics").map { it.id })
        assertTrue(MatchSearch.find(all, "   ").isEmpty())
        assertTrue(MatchSearch.find(all, "Steaua").isEmpty())
    }

    // ---------- F2 ----------

    @Test
    fun watchNextHoldsOnlyLiveOrSoonFavorites() {
        val fav = { m: Match -> m.home == "Arsenal" || m.away == "Arsenal" || m.home == "Liverpool" }
        val all = listOf(
            match("soon", "Arsenal", "Chelsea", 20 * min),
            match("later", "Arsenal", "Spurs", 5 * 60 * min),
            match("live", "Liverpool", "Everton", -40 * min, MatchStatus.LIVE),
            match("other", "Leeds", "Burnley", -10 * min, MatchStatus.LIVE),
            match("done", "Brentford", "Arsenal", -200 * min, MatchStatus.FINISHED),
        )
        assertEquals(listOf("live", "soon"), WatchNextPick.pick(all, fav, now).map { it.id })
    }

    // ---------- F3 ----------

    @Test
    fun screensaverShowsFavoritesAndHidesSpoilers() {
        val fav = { m: Match -> "Arsenal" in m.title }
        val all = listOf(
            match("a", "Arsenal", "Chelsea", -30 * min, MatchStatus.LIVE, score = 2 to 1),
            match("b", "Brentford", "Arsenal", 22 * 60 * min),
            match("c", "Leeds", "Burnley", -10 * min, MatchStatus.LIVE, score = 0 to 0),
        )
        val board = ScreensaverBoard.build(all, fav, { true }, { false }, now, zone)
        assertTrue(board.favorites)
        assertEquals("2–1", board.live.single().score)
        assertEquals("mâine 18:00 · English Premier League", board.next.single().detail)

        val hidden = ScreensaverBoard.build(all, fav, { true }, { true }, now, zone)
        assertNull(hidden.live.single().score)

        // Fără favorite: meciurile live cu surse.
        val none = ScreensaverBoard.build(all, { false }, { it.id == "c" }, { false }, now, zone)
        assertFalse(none.favorites)
        assertEquals(listOf("c"), none.live.map { it.id })
        assertEquals("0–0", none.live.single().score)
    }

    // ---------- A6 ----------

    @Test
    fun championsLeagueBracketFromTheYearSchedule() {
        val b = requireNotNull(Brackets.parseCup(listOf(text("espn-ucl-an.json")), "UEFA Champions League"))
        // Faza ligii e scoasă; sezonul ales e cel mai nou cu fază eliminatorie (2025/26), nu cel început în toamnă.
        assertEquals("2025/26", b.season)
        assertEquals(listOf("Baraj", "Optimi", "Sferturi", "Semifinale", "Finala"), b.rounds.map { it.name })
        assertEquals(listOf(8, 8, 4, 2, 1), b.rounds.map { it.ties.size })
        // Dubla manșă: două meciuri, scorul general din `series`, câștigătorul.
        val r16 = b.rounds[1].ties
        assertTrue(r16.all { it.games.size == 2 })
        assertTrue(r16.all { it.winner != null && it.firstScore != null && it.secondScore != null })
        val final = b.rounds.last().ties.single()
        assertEquals(1, final.games.size)
        assertNotNull(final.winner)
        // Totul s-a jucat: se arată ultima rundă.
        assertEquals(b.rounds.size - 1, b.currentRound())
    }

    @Test
    fun worldCupBracketHasThirdPlaceAndExtraTime() {
        val b = requireNotNull(Brackets.parseCup(listOf(text("espn-mondial-an.json"), text("espn-mondial-an.json")), "FIFA World Cup"))
        assertEquals("2026", b.season)
        assertEquals(listOf("Șaisprezecimi", "Optimi", "Sferturi", "Semifinale", "Finala mică", "Finala"), b.rounds.map { it.name })
        val final = b.rounds.last().ties.single()
        assertEquals("Spain", final.first)
        assertEquals(0, final.winner)
        assertEquals("1" to "0", final.firstScore to final.secondScore)
        assertEquals("după prelungiri", final.note)
    }

    @Test
    fun grandSlamBracketFromTheDaySchedule() {
        val men = requireNotNull(Brackets.parseTennis(text("espn-wimbledon.json"), men = true))
        assertEquals("Wimbledon", men.title)
        // Calificările sunt scoase.
        assertEquals(listOf("Turul 4", "Sferturi", "Semifinale", "Finala"), men.rounds.map { it.name })
        val final = men.rounds.last().ties.single()
        assertEquals(setOf("Jannik Sinner", "Carlos Alcaraz"), setOf(final.first, final.second))
        val winner = if (final.winner == 0) final.first else final.second
        assertEquals("Jannik Sinner", winner)
        val women = requireNotNull(Brackets.parseTennis(text("espn-wimbledon.json"), men = false))
        assertEquals(listOf("Finala"), women.rounds.map { it.name })
    }

    @Test
    fun roundNamesAndYears() {
        assertEquals("Turul 3", Brackets.roundName("third-round"))
        assertEquals("Turul 2", Brackets.roundName("Round 2"))
        assertEquals("Baraj", Brackets.roundName("knockout-round-playoffs"))
        assertFalse(Brackets.knockout("league-phase"))
        assertFalse(Brackets.knockout("group-stage"))
        assertTrue(Brackets.knockout("first-round"))
        assertEquals(listOf(2026, 2027), Brackets.years(now))
        assertTrue(Brackets.CUPS.all { k -> Espn.leagues.any { it.key == k } })
    }
}
