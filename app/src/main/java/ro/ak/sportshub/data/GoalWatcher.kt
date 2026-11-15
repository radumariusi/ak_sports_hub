package ro.ak.sportshub.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import ro.ak.sportshub.core.sports.GoalAlerts
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.ScoreState

/**
 * Alertele de gol și „Final” pentru meciurile favorite (F2), cât aplicația e pe ecran (și peste player).
 *
 * - Doar meciurile favorite în desfășurare care sunt în programul ESPN (o cerere `summary` pe minut pe meci, cea a
 *   tabului Live), cel mult [GoalAlerts.MAX_WATCHED] deodată; nu și meciul la care te uiți (îl vezi oricum).
 * - Cu „Fără spoilere” pornit nu se cere și nu se arată nimic (doar pentru meciurile pe care le-ai dezvăluit).
 * - Nu sunt cereri la furnizorul de IPTV: nu folosesc conexiunea playlistului.
 */
class GoalWatcher(
    private val sports: SportsRepository,
    private val details: MatchDetailsRepository,
    private val settings: SportsSettingsStore,
    private val watching: StateFlow<String?>,
    private val notices: MutableSharedFlow<String>,
) {
    /** Ultimul scor citit, pe meci (doar în memorie). */
    private val last = HashMap<String, ScoreState>()

    /** Rulează cât e apelat (aplicația e pe ecran); se oprește odată cu corutina. */
    suspend fun run() {
        while (true) {
            runCatching { tick() }
            delay(GoalAlerts.REFRESH_MS)
        }
    }

    private suspend fun tick() {
        val s = settings.current
        if (!s.goalAlerts) {
            last.clear()
            return
        }
        val now = System.currentTimeMillis()
        val revealed = details.revealed.value
        val current = watching.value
        val candidates = sports.snapshot.value.matches.asSequence().map { it.match }
            .filter { m ->
                m.id != current && sports.espnEventFor(m) != null && (
                    GoalAlerts.shouldWatch(m, s.favorites, now, s.noSpoilers, m.id in revealed) ||
                        // Prelungiri, întârzieri: cât ESPN spune că se joacă încă, până la două ore după sfârșitul estimat.
                        (last[m.id]?.status == MatchStatus.LIVE && now < m.end + LATE_MS && (!s.noSpoilers || m.id in revealed))
                    )
            }
            .sortedBy { it.start }
            .take(GoalAlerts.MAX_WATCHED)
            .toList()
        last.keys.retainAll(candidates.map { it.id }.toSet())
        for (m in candidates) {
            val stillLive = last[m.id]?.status != MatchStatus.FINISHED
            details.refreshLive(if (stillLive) m.copy(status = MatchStatus.LIVE, end = maxOf(m.end, now + TimeUnit.MINUTES.toMillis(2))) else m)
            val e = details.states.value[m.id]?.details?.event ?: continue
            val state = ScoreState(e.homeScore, e.awayScore, e.status)
            // Numele echipelor ca la ESPN (gazdele și oaspeții din răspuns, nu din unirea furnizorilor).
            val named = m.copy(home = e.home.ifBlank { m.home }, away = e.away.ifBlank { m.away })
            GoalAlerts.diff(named, last[m.id], state).forEach { notices.emit(it.text) }
            last[m.id] = state
        }
    }

    private companion object {
        val LATE_MS: Long = TimeUnit.HOURS.toMillis(2)
    }
}
