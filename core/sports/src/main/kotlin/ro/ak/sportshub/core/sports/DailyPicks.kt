package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** O notificare de dat înainte de un meci favorit. */
data class Reminder(val matchId: String, val at: Long, val start: Long)

/**
 * Notificările pentru meciurile favorite: cu [leadMs] înainte de start (implicit 10 minute),
 * doar pentru meciurile care n-au început și încep în următoarele [horizonMs].
 */
object MatchReminders {

    const val DEFAULT_LEAD_MINUTES = 10
    val DEFAULT_HORIZON_MS: Long = TimeUnit.HOURS.toMillis(24)
    const val MAX_REMINDERS = 30

    fun plan(
        matches: List<Match>,
        isFavorite: (Match) -> Boolean,
        now: Long,
        leadMs: Long = TimeUnit.MINUTES.toMillis(DEFAULT_LEAD_MINUTES.toLong()),
        horizonMs: Long = DEFAULT_HORIZON_MS,
    ): List<Reminder> = matches.asSequence()
        .filter { it.status == MatchStatus.SCHEDULED && it.start > now && it.start <= now + horizonMs && isFavorite(it) }
        .map { Reminder(it.id, maxOf(it.start - leadMs, now), it.start) }
        .sortedBy { it.at }
        .take(MAX_REMINDERS)
        .toList()
}

/**
 * Rândul „Meciurile tale de azi” de pe ecranul principal Android TV: întâi meciurile favorite
 * (live, apoi după oră), apoi celelalte meciuri de azi care au surse găsite, cel mult [limit].
 */
object HomeRow {

    const val DEFAULT_LIMIT = 12

    fun <T> pick(
        items: List<T>,
        match: (T) -> Match,
        sources: (T) -> Int,
        isFavorite: (Match) -> Boolean,
        now: Long,
        zone: ZoneId,
        limit: Int = DEFAULT_LIMIT,
    ): List<T> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val candidates = items.filter { t ->
            val m = match(t)
            m.status != MatchStatus.FINISHED && m.end > now && (m.isLive || MatchLists.day(m, zone) == today)
        }
        val order = compareBy<T> { if (match(it).isLive) 0 else 1 }.thenBy { match(it).start }.thenBy { match(it).title }
        val favorites = candidates.filter { isFavorite(match(it)) }.sortedWith(order)
        val others = candidates.filter { !isFavorite(match(it)) && sources(it) > 0 }.sortedWith(order)
        return (favorites + others).take(limit)
    }
}

/** Calendarul echipelor favorite: următoarele meciuri ale fiecărei echipe, în ordinea favoritelor. */
object TeamCalendar {

    const val PER_TEAM = 5

    fun build(teams: List<String>, matches: List<Match>, now: Long, perTeam: Int = PER_TEAM): List<Pair<String, List<Match>>> =
        teams.map { team ->
            team to matches
                .filter { m ->
                    m.status != MatchStatus.FINISHED && m.end > now &&
                        (TeamNames.covers(team, m.home) || TeamNames.covers(team, m.away))
                }
                .sortedWith(compareBy<Match> { it.start }.thenBy { it.title })
                .distinctBy { it.id }
                .take(perTeam)
        }
}
