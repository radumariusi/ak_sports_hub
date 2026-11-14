package ro.ak.sportshub.core.sports

import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPicksTest {

    private val h = TimeUnit.HOURS.toMillis(1)
    private val min = TimeUnit.MINUTES.toMillis(1)

    // 15.11.2026 12:00 UTC
    private val now = 1_794_744_000_000L

    private fun match(
        id: String,
        home: String,
        away: String,
        start: Long,
        status: MatchStatus = MatchStatus.SCHEDULED,
        competition: String? = "Premier League",
    ) = Match(id, Sport.FOOTBALL, competition, home, away, start, start + 2 * h, status)

    @Test
    fun remindersOnlyForUpcomingFavorites() {
        val matches = listOf(
            match("a", "Arsenal", "Chelsea", now + 2 * h),
            match("b", "Everton", "Fulham", now + 3 * h),
            match("c", "Arsenal", "Spurs", now - h, MatchStatus.LIVE),
            match("d", "Liverpool", "Arsenal", now + 5 * min),
            match("e", "Arsenal", "Leeds", now + 3 * TimeUnit.DAYS.toMillis(1)),
        )
        val plan = MatchReminders.plan(matches, { it.home == "Arsenal" || it.away == "Arsenal" }, now)
        assertEquals(listOf("d", "a"), plan.map { it.matchId })
        // Meciul care începe în 5 minute primește notificarea acum, nu în trecut.
        assertEquals(now, plan[0].at)
        assertEquals(now + 2 * h - 10 * min, plan[1].at)
    }

    @Test
    fun homeRowFavoritesFirstThenMatchesWithSources() {
        val items = listOf(
            match("late", "Everton", "Fulham", now + 6 * h) to 3,
            match("nosrc", "Leeds", "Burnley", now + h) to 0,
            match("fav", "Arsenal", "Chelsea", now + 4 * h) to 0,
            match("live", "Milan", "Inter", now - h, MatchStatus.LIVE) to 2,
            match("done", "Arsenal", "Spurs", now - 3 * h, MatchStatus.FINISHED) to 5,
            match("tomorrow", "Roma", "Lazio", now + 20 * h) to 4,
        )
        val picked = HomeRow.pick(items, { it.first }, { it.second }, { it.home == "Arsenal" }, now, ZoneOffset.UTC)
        assertEquals(listOf("fav", "live", "late"), picked.map { it.first.id })
    }

    @Test
    fun calendarPerTeam() {
        val matches = (1..7).map { match("m$it", if (it % 2 == 0) "Arsenal FC" else "Chelsea", if (it % 2 == 0) "Chelsea" else "Arsenal", now + it * 24 * h) } +
            match("old", "Arsenal", "Leeds", now - 5 * h, MatchStatus.FINISHED)
        val cal = TeamCalendar.build(listOf("Arsenal", "Bayern"), matches, now)
        assertEquals("Arsenal", cal[0].first)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), cal[0].second.map { it.id })
        assertTrue(cal[1].second.isEmpty())
    }
}
