package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Unește meciurile furnizorilor cu emisiunile EPG:
 * 1. meciurile furnizorilor se deduplică (aceleași echipe, ora ± 15 minute);
 * 2. fiecare emisiune EPG recunoscută se leagă de meciul cu aceleași echipe și o oră compatibilă
 *    (emisiunea poate începe cu până la 3 ore înainte, cu studioul, și trebuie să prindă începutul);
 * 3. emisiunile rămase devin meciuri „doar din EPG”, strânse pe echipe.
 */
object MatchAssembler {

    val DEDUP_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(15)
    private val BROADCAST_LEAD_MS = TimeUnit.HOURS.toMillis(3)
    private val BROADCAST_LATE_MS = TimeUnit.MINUTES.toMillis(30)
    private val EPG_CLUSTER_MS = TimeUnit.HOURS.toMillis(3)

    /** Cât timp credem o stare „LIVE” veche din cache, după ora de start. */
    private val STALE_LIVE_MS = TimeUnit.HOURS.toMillis(6)

    fun assemble(
        events: List<SportEvent>,
        broadcasts: List<MatchBroadcast>,
        now: Long,
        tv: List<TheSportsDb.TvListing> = emptyList(),
    ): List<Match> {
        val stationsById = tv.groupBy({ it.eventId }, { it.station })
        val merged = dedupe(events.map { e ->
            val extra = stationsById[e.providerId].orEmpty()
            if (extra.isEmpty()) e else e.copy(stations = (e.stations + extra).distinct())
        })

        val attached = HashMap<Int, MutableList<MatchBroadcast>>()
        val loose = ArrayList<MatchBroadcast>()
        for (mb in broadcasts) {
            val b = mb.broadcast
            val x = mb.extracted
            val best = merged.indices
                .filter { i ->
                    val e = merged[i]
                    e.away.isNotBlank() &&
                        TeamNames.samePair(e.home, e.away, x.home, x.away) &&
                        b.start >= e.start - BROADCAST_LEAD_MS && b.start <= e.start + BROADCAST_LATE_MS &&
                        b.stop > e.start
                }
                .minByOrNull { abs(merged[it].start - b.start) }
            if (best != null) attached.getOrPut(best) { ArrayList() } += mb else loose += mb
        }

        val fromEvents = merged.mapIndexed { i, e -> fromEvent(e, attached[i].orEmpty(), now) }
        val fromEpg = cluster(loose).map { fromBroadcasts(it, now) }
        return (fromEvents + fromEpg).sortedWith(compareBy<Match> { it.start }.thenBy { it.title })
    }

    /** Aceleași echipe și ora ± 15 minute: același meci (de la furnizori diferiți sau dublat). */
    fun dedupe(events: List<SportEvent>): List<SportEvent> {
        val out = ArrayList<SportEvent>()
        for (e in events.sortedBy { it.start }) {
            val i = out.indexOfFirst { o ->
                abs(o.start - e.start) <= DEDUP_WINDOW_MS && (
                    (o.provider == e.provider && o.providerId == e.providerId) ||
                        (o.away.isNotBlank() && e.away.isNotBlank() && TeamNames.samePair(o.home, o.away, e.home, e.away))
                    )
            }
            if (i < 0) out += e else out[i] = mergeEvents(out[i], e)
        }
        return out
    }

    private fun mergeEvents(a: SportEvent, b: SportEvent) = a.copy(
        sport = a.sport ?: b.sport,
        competition = a.competition ?: b.competition,
        status = listOfNotNull(a.status, b.status).maxByOrNull { it.ordinal },
        homeScore = a.homeScore ?: b.homeScore,
        awayScore = a.awayScore ?: b.awayScore,
        venue = a.venue ?: b.venue,
        stations = (a.stations + b.stations).distinct(),
    )

    private fun fromEvent(e: SportEvent, mbs: List<MatchBroadcast>, now: Long): Match {
        val sport = Sport.fromSportsDb(e.sport) ?: mbs.firstNotNullOfOrNull { it.extracted.sport }
            ?: Sport.detect(e.competition)
        val duration = sport?.typicalDurationMs ?: Sport.DEFAULT_DURATION_MS
        val end = e.start + duration
        val byTime = when {
            now < e.start -> MatchStatus.SCHEDULED
            now < end -> MatchStatus.LIVE
            else -> MatchStatus.FINISHED
        }
        val status = when (e.status) {
            MatchStatus.FINISHED -> MatchStatus.FINISHED
            MatchStatus.LIVE -> if (now < e.start + STALE_LIVE_MS) MatchStatus.LIVE else MatchStatus.FINISHED
            else -> byTime
        }
        return Match(
            id = "${e.provider}:${e.providerId}",
            sport = sport,
            competition = e.competition ?: mbs.firstNotNullOfOrNull { it.extracted.competition },
            home = e.home,
            away = e.away,
            start = e.start,
            end = end,
            status = status,
            homeScore = e.homeScore,
            awayScore = e.awayScore,
            venue = e.venue,
            stations = e.stations,
            broadcasts = mbs.map { it.broadcast }.sortedBy { it.start },
            providers = setOf(e.provider) + if (mbs.isEmpty()) emptySet() else setOf(EPG_PROVIDER),
        )
    }

    private fun cluster(loose: List<MatchBroadcast>): List<List<MatchBroadcast>> {
        val groups = ArrayList<MutableList<MatchBroadcast>>()
        for (mb in loose.sortedBy { it.broadcast.start }) {
            val g = groups.firstOrNull { g ->
                val first = g.first()
                abs(first.broadcast.start - mb.broadcast.start) <= EPG_CLUSTER_MS &&
                    TeamNames.samePair(first.extracted.home, first.extracted.away, mb.extracted.home, mb.extracted.away)
            }
            if (g == null) groups += mutableListOf(mb) else g += mb
        }
        return groups
    }

    private fun fromBroadcasts(mbs: List<MatchBroadcast>, now: Long): Match {
        // Emisiunile marcate „live” dau ora cea mai apropiată de start; altfel cea mai devreme.
        val lead = mbs.filter { it.extracted.explicitLive }.ifEmpty { mbs }
        val start = lead.minOf { it.broadcast.start }
        val end = mbs.maxOf { it.broadcast.stop }
        val first = lead.first().extracted
        val status = when {
            mbs.any { now >= it.broadcast.start && now < it.broadcast.stop } -> MatchStatus.LIVE
            now >= end -> MatchStatus.FINISHED
            else -> MatchStatus.SCHEDULED
        }
        val keys = listOf(TeamNames.key(first.home), TeamNames.key(first.away)).sorted()
        val day = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate()
        return Match(
            id = "$EPG_PROVIDER:${keys.joinToString("|")}:$day",
            sport = mbs.firstNotNullOfOrNull { it.extracted.sport },
            competition = mbs.firstNotNullOfOrNull { it.extracted.competition },
            home = first.home,
            away = first.away,
            start = start,
            end = end,
            status = status,
            broadcasts = mbs.map { it.broadcast }.sortedBy { it.start },
            providers = setOf(EPG_PROVIDER),
        )
    }

    const val EPG_PROVIDER = "epg"
}
