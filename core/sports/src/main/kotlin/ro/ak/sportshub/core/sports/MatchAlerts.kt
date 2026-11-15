package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit

/**
 * Comutarea automată pe meciul favorit (F1): cu [LEAD_MS] înainte de start apare numărătoarea inversă
 * ([COUNTDOWN_MS], timp în care o poți anula), apoi pornește sursa recomandată a meciului.
 * O singură dată pe meci; nu pentru meciul la care te uiți deja.
 */
object AutoSwitch {

    val LEAD_MS: Long = TimeUnit.MINUTES.toMillis(1)
    const val COUNTDOWN_MS: Long = 10_000L

    /** Cât după start mai are rost comutarea (după aceea rămâne doar anunțul). */
    val LATE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    /**
     * Meciul favorit pe care trebuie comutat acum (cel mai devreme), sau null. [handled]: meciurile deja comutate,
     * anulate sau anunțate; [currentMatchId]: meciul la care te uiți.
     */
    fun due(matches: List<Match>, favorites: SportsFavorites, now: Long, handled: Set<String>, currentMatchId: String?): Match? =
        matches.asSequence()
            .filter { m ->
                m.id !in handled && m.id != currentMatchId && m.status != MatchStatus.FINISHED &&
                    now >= m.start - LEAD_MS && now <= m.start + LATE_MS && favorites.isFavorite(m) &&
                    // O emisiune urmărită doar pentru că descrierea pomenește numele (un magazin, un interviu) nu merită comutarea.
                    (m.providers != setOf(FollowedNames.PROVIDER) || FollowedNames.mentionsAny(favorites.names, m.home))
            }
            .sortedWith(compareBy<Match> { it.start }.thenBy { it.title })
            .firstOrNull()

    /** Secundele rămase din numărătoare (10, 9, … 1). */
    fun secondsLeft(startedAt: Long, now: Long): Int =
        (((startedAt + COUNTDOWN_MS - now) + 999) / 1_000).toInt().coerceIn(0, (COUNTDOWN_MS / 1_000).toInt())
}

/** Scorul și starea unui meci la o citire (pentru alertele de gol). */
data class ScoreState(val home: Int?, val away: Int?, val status: MatchStatus?)

/** O alertă pentru un meci favorit: gol (sau punct, la sporturile cu scor mic) ori rezultatul final. */
data class MatchAlert(val matchId: String, val kind: Kind, val text: String) {
    enum class Kind { GOAL, CORRECTION, FINAL }
}

/**
 * Alertele de gol și „Final” pentru meciurile favorite (F2), din două citiri succesive ale scorului.
 * Prima citire a unui meci nu dă nicio alertă (nu știm ce s-a schimbat). Golurile se anunță doar la sporturile cu
 * scor mic (fotbal, hochei; la baschet sau handbal ar fi prea dese), la celelalte doar finalul. Cu „Fără spoilere” pornit,
 * aplicația nu cere și nu arată nimic (vezi [shouldWatch]).
 */
object GoalAlerts {

    /** Câte meciuri live favorite se urmăresc cel mult (câte o cerere mică pe minut fiecare). */
    const val MAX_WATCHED = 3

    val REFRESH_MS: Long = TimeUnit.SECONDS.toMillis(60)

    /** Sporturile la care fiecare gol e anunțat (la celelalte doar finalul). */
    val GOAL_SPORTS: Set<Sport> = setOf(Sport.FOOTBALL, Sport.ICE_HOCKEY)

    /** Meciul merită urmărit pentru alerte: favorit, în desfășurare (sau abia terminat), cu două echipe. */
    fun shouldWatch(m: Match, favorites: SportsFavorites, now: Long, noSpoilers: Boolean, revealed: Boolean): Boolean =
        !m.isSingleEvent && (!noSpoilers || revealed) && favorites.isFavorite(m) &&
            now >= m.start && now <= m.end + TimeUnit.MINUTES.toMillis(30)

    fun diff(match: Match, before: ScoreState?, after: ScoreState): List<MatchAlert> {
        if (before == null) return emptyList()
        val h0 = before.home
        val a0 = before.away
        val h1 = after.home ?: return emptyList()
        val a1 = after.away ?: return emptyList()
        val out = ArrayList<MatchAlert>()
        fun score() = "${match.home} $h1–$a1 ${match.away}"
        val goals = match.sport in GOAL_SPORTS
        if (goals && h0 != null && a0 != null && before.status != MatchStatus.FINISHED) {
            when {
                h1 > h0 || a1 > a0 -> {
                    val scorer = when {
                        h1 > h0 && a1 > a0 -> null
                        h1 > h0 -> match.home
                        else -> match.away
                    }
                    out += MatchAlert(match.id, MatchAlert.Kind.GOAL, (if (scorer != null) "⚽ GOL $scorer! " else "⚽ GOL! ") + score())
                }
                h1 < h0 || a1 < a0 -> out += MatchAlert(match.id, MatchAlert.Kind.CORRECTION, "Gol anulat: " + score())
            }
        }
        if (after.status == MatchStatus.FINISHED && before.status != null && before.status != MatchStatus.FINISHED) {
            out += MatchAlert(match.id, MatchAlert.Kind.FINAL, "Final: " + score())
        }
        return out
    }
}
