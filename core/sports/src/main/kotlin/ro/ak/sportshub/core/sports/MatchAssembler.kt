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
 * 3. emisiunile rămase devin meciuri „doar din EPG”, strânse pe echipe;
 * 4. canalele EVENT / PPV din playlist ([EventSlot]) se leagă de meciul lor; cele rămase devin meciuri
 *    „doar din playlist”.
 *
 * Evenimentele fără două echipe (curse, etape, gale) se compară după nume cu [EventNames].
 */
object MatchAssembler {

    val DEDUP_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(15)
    private val BROADCAST_LEAD_MS = TimeUnit.HOURS.toMillis(3)
    private val BROADCAST_LATE_MS = TimeUnit.MINUTES.toMillis(30)
    private val EPG_CLUSTER_MS = TimeUnit.HOURS.toMillis(3)

    /** Cât timp credem o stare „LIVE” veche din cache, după ora de start. */
    private val STALE_LIVE_MS = TimeUnit.HOURS.toMillis(6)

    /** O emisiune cu aceleași echipe până la 36 de ore după meci e o reluare. */
    val REPLAY_LOOKBACK_MS: Long = TimeUnit.HOURS.toMillis(36)

    /** Cât mai rămâne „LIVE” un meci după durata obișnuită a sportului (prelungiri, pauze). */
    private val LIVE_GRACE_MS = TimeUnit.MINUTES.toMillis(90)

    fun assemble(
        events: List<SportEvent>,
        broadcasts: List<MatchBroadcast>,
        now: Long,
        tv: List<TheSportsDb.TvListing> = emptyList(),
        slots: List<EventSlot> = emptyList(),
    ): List<Match> {
        val stationsById = tv.groupBy({ it.eventId }, { it.station })
        val merged = dedupe(events.map { e ->
            val extra = stationsById[e.providerId].orEmpty()
            if (extra.isEmpty()) e else e.copy(stations = (e.stations + extra).distinct())
        })

        val attached = HashMap<Int, MutableList<MatchBroadcast>>()
        val loose = ArrayList<MatchBroadcast>()
        // [merged] e în ordinea orei: pentru fiecare emisiune se verifică doar meciurile din fereastra ei de timp
        // (căutare binară), nu toate meciurile; cu zeci de mii de emisiuni comparația completă dura minute.
        val starts = LongArray(merged.size) { merged[it].start }
        for (mb in broadcasts) {
            val b = mb.broadcast
            val x = mb.extracted
            val first = lowerBound(starts, b.start - BROADCAST_LATE_MS)
            var last = first
            while (last < starts.size && starts[last] <= b.start + BROADCAST_LEAD_MS) last++
            val best = (first until last)
                .filter { i ->
                    val e = merged[i]
                    b.start >= e.start - BROADCAST_LEAD_MS && b.start <= e.start + BROADCAST_LATE_MS && b.stop > e.start &&
                        if (x.away.isBlank()) {
                            e.away.isBlank() && sameEvent(e.home, sportOf(e), e.start, x.home, x.sport, b.start, BROADCAST_LEAD_MS) &&
                                EventNames.same(e.home, x.home) != false
                        } else {
                            e.away.isNotBlank() && TeamNames.samePair(e.home, e.away, x.home, x.away)
                        }
                }
                .minByOrNull { abs(merged[it].start - b.start) }
            if (best != null) {
                attached.getOrPut(best) { ArrayList() } += mb
                continue
            }
            // Reluarea unui meci jucat deja (aceleași echipe, început cu 30 de minute până la 36 de ore înainte):
            // emisiunea devine o sursă a meciului, nu un meci nou „LIVE”.
            val earlier = if (x.away.isBlank()) null else {
                val from = lowerBound(starts, b.start - REPLAY_LOOKBACK_MS)
                val to = lowerBound(starts, b.start - BROADCAST_LATE_MS)
                (from until to).lastOrNull { i -> merged[i].away.isNotBlank() && TeamNames.samePair(merged[i].home, merged[i].away, x.home, x.away) }
            }
            if (earlier != null) attached.getOrPut(earlier) { ArrayList() } += mb else loose += mb
        }

        val fromEvents = merged.mapIndexed { i, e -> fromEvent(e, attached[i].orEmpty(), now) }
        val fromEpg = joinReruns(cluster(loose)).map { fromBroadcasts(it, now) }
        return attachSlots(fromEvents + fromEpg, slots, now).sortedWith(compareBy<Match> { it.start }.thenBy { it.title })
    }

    private fun sportOf(e: SportEvent): Sport? = Sport.fromSportsDb(e.sport) ?: Sport.detect(e.competition, e.home)

    /** Evenimente fără echipe: același sport, ora la cel mult [window], nume compatibile. */
    private fun sameEvent(a: String, sa: Sport?, ta: Long, b: String, sb: Sport?, tb: Long, window: Long): Boolean =
        EventNames.match(a, sa, ta, b, sb, tb, window)

    /**
     * Leagă canalele EVENT / PPV de meciuri: aceleași echipe (sau același eveniment) și ora din nume
     * cu cel mult o oră înainte de start sau 30 de minute după. Un canal fără oră se leagă doar dacă
     * un singur meci din următoarele 24 de ore se potrivește. Cele rămase devin meciuri „doar din playlist”.
     */
    private fun attachSlots(matches: List<Match>, slots: List<EventSlot>, now: Long): List<Match> {
        if (slots.isEmpty()) return matches
        // Mii de canale EVENT × mii de meciuri: comparația completă dura minute pe TV. Canalele cu oră caută doar
        // meciurile din fereastra lor (căutare binară), cele fără oră doar meciurile cu aceeași cheie de echipe.
        val order = matches.indices.sortedBy { matches[it].start }
        val starts = LongArray(order.size) { matches[order[it]].start }
        val byKey = HashMap<String, MutableList<Int>>()
        matches.forEachIndexed { i, m -> byKey.getOrPut(slotKey(m.home, m.away)) { ArrayList() } += i }
        val extra = HashMap<Int, MutableList<Long>>()
        val loose = ArrayList<EventSlot>()
        for (slot in slots) {
            val home = slot.home ?: continue
            val away = slot.away.orEmpty()
            val start = slot.start
            val pool: List<Int> = if (start != null) {
                val from = lowerBound(starts, start - BROADCAST_LATE_MS)
                var to = from
                while (to < starts.size && starts[to] <= start + SLOT_LEAD_MS) to++
                (from until to).map { order[it] }
            } else {
                byKey[slotKey(home, away)].orEmpty()
            }
            val candidates = pool.filter { i ->
                val m = matches[i]
                val timeOk = start?.let { it >= m.start - SLOT_LEAD_MS && it <= m.start + BROADCAST_LATE_MS }
                    ?: (m.end > now - SLOT_LEAD_MS && m.start < now + SLOT_UNTIMED_MS)
                timeOk && sameAs(m, home, away, slot.sport, start ?: m.start)
            }
            val best = when {
                candidates.isEmpty() -> null
                start != null -> candidates.minBy { abs(matches[it].start - start) }
                candidates.size == 1 -> candidates.single()
                else -> null
            }
            if (best != null) extra.getOrPut(best) { ArrayList() } += slot.channelId else if (start != null) loose += slot
        }
        val updated = matches.mapIndexed { i, m ->
            val ids = extra[i] ?: return@mapIndexed m
            m.copy(eventChannels = (m.eventChannels + ids).distinct(), providers = m.providers + EVENT_PROVIDER)
        }
        return updated + clusterSlots(loose).map { fromSlots(it, now) }
    }

    /** Cheia unui meci sau eveniment: echipele (în orice ordine) sau numele evenimentului. */
    private fun slotKey(home: String, away: String): String =
        if (away.isBlank()) "e:" + EventNames.key(home)
        else listOf(TeamNames.key(home), TeamNames.key(away)).sorted().joinToString("|")

    private fun sameAs(m: Match, home: String, away: String, sport: Sport?, start: Long): Boolean =
        if (away.isBlank()) {
            m.away.isBlank() && EventNames.same(m.home, home) != false &&
                sameEvent(m.home, m.sport, m.start, home, sport, start, SLOT_LEAD_MS)
        } else {
            m.away.isNotBlank() && TeamNames.samePair(m.home, m.away, home, away)
        }

    private fun clusterSlots(slots: List<EventSlot>): List<List<EventSlot>> {
        val groups = ArrayList<MutableList<EventSlot>>()
        // Se compară doar grupurile cu aceeași cheie (nu toate cu toate).
        val byKey = HashMap<String, MutableList<MutableList<EventSlot>>>()
        for (s in slots.sortedBy { it.start }) {
            val key = slotKey(s.home.orEmpty(), s.away.orEmpty())
            val same = byKey.getOrPut(key) { ArrayList() }
            val g = same.firstOrNull { g ->
                val f = g.first()
                val fs = f.start ?: return@firstOrNull false
                val ss = s.start ?: return@firstOrNull false
                if (f.away.isNullOrBlank() != s.away.isNullOrBlank()) return@firstOrNull false
                if (s.away.isNullOrBlank()) {
                    sameEvent(f.home.orEmpty(), f.sport, fs, s.home.orEmpty(), s.sport, ss, DEDUP_WINDOW_MS)
                } else {
                    abs(fs - ss) <= DEDUP_WINDOW_MS && TeamNames.samePair(f.home.orEmpty(), f.away.orEmpty(), s.home.orEmpty(), s.away.orEmpty())
                }
            }
            if (g == null) mutableListOf(s).also { groups += it; same += it } else g += s
        }
        return groups
    }

    private fun fromSlots(slots: List<EventSlot>, now: Long): Match {
        val first = slots.first()
        val start = slots.minOf { it.start ?: Long.MAX_VALUE }
        val sport = slots.firstNotNullOfOrNull { it.sport }
        val end = start + (sport?.typicalDurationMs ?: Sport.DEFAULT_DURATION_MS)
        val status = when {
            now < start -> MatchStatus.SCHEDULED
            now < end -> MatchStatus.LIVE
            else -> MatchStatus.FINISHED
        }
        val home = first.home.orEmpty()
        val away = first.away.orEmpty()
        val key = if (away.isBlank()) EventNames.key(home) else listOf(TeamNames.key(home), TeamNames.key(away)).sorted().joinToString("|")
        val day = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate()
        return Match(
            id = "$EVENT_PROVIDER:$key:$day",
            sport = sport,
            competition = slots.firstNotNullOfOrNull { it.competition },
            home = home,
            away = away,
            start = start,
            end = end,
            status = status,
            providers = setOf(EVENT_PROVIDER),
            eventChannels = slots.map { it.channelId }.distinct(),
        )
    }

    /** Aceleași echipe și ora ± 15 minute: același meci (de la furnizori diferiți sau dublat). */
    fun dedupe(events: List<SportEvent>): List<SportEvent> {
        val out = ArrayList<SportEvent>()
        // [out] rămâne în ordinea orei (o unire păstrează ora primului): se caută doar în ultimele 15 minute.
        val outStarts = ArrayList<Long>()
        for (e in events.sortedBy { it.start }) {
            val from = lowerBound(outStarts, e.start - DEDUP_WINDOW_MS)
            val i = (from until out.size).firstOrNull { idx ->
                val o = out[idx]
                abs(o.start - e.start) <= DEDUP_WINDOW_MS && (
                    (o.provider == e.provider && o.providerId == e.providerId) ||
                        (o.away.isNotBlank() && e.away.isNotBlank() && TeamNames.samePair(o.home, o.away, e.home, e.away)) ||
                        (o.away.isBlank() && e.away.isBlank() && EventNames.same(o.home, e.home) == true &&
                            sameEvent(o.home, sportOf(o), o.start, e.home, sportOf(e), e.start, DEDUP_WINDOW_MS))
                    )
            }
            if (i == null) {
                out += e
                outStarts += e.start
            } else {
                out[i] = mergeEvents(out[i], e)
            }
        }
        return out
    }

    /**
     * Același meci de la doi furnizori: datele (id, nume, competiție) vin de la cel mai de încredere
     * ([ProviderPolicy.rank]), ora rămâne a primului (lista e în ordinea orei), posturile se adună.
     */
    private fun mergeEvents(first: SportEvent, other: SportEvent): SportEvent {
        val swap = ProviderPolicy.rank(other.provider) < ProviderPolicy.rank(first.provider)
        val a = if (swap) other.copy(start = first.start) else first
        val b = if (swap) first else other
        return mergeInto(a, b)
    }

    private fun mergeInto(a: SportEvent, b: SportEvent) = a.copy(
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
            // „LIVE” din fișierul salvat e starea de la descărcare: după durata obișnuită plus 90 de minute, s-a terminat.
            MatchStatus.LIVE -> if (now < e.start + minOf(STALE_LIVE_MS, duration + LIVE_GRACE_MS)) MatchStatus.LIVE else MatchStatus.FINISHED
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
        // Grupurile se creează în ordinea orei primei emisiuni: se caută doar printre cele din ultimele 3 ore.
        val groupStarts = ArrayList<Long>()
        for (mb in loose.sortedBy { it.broadcast.start }) {
            val from = lowerBound(groupStarts, mb.broadcast.start - EPG_CLUSTER_MS)
            val g = (from until groups.size).asSequence().map { groups[it] }.firstOrNull { g ->
                val first = g.first()
                val a = first.extracted
                val b = mb.extracted
                abs(first.broadcast.start - mb.broadcast.start) <= EPG_CLUSTER_MS && when {
                    a.away.isBlank() != b.away.isBlank() -> false
                    a.away.isBlank() ->
                        sameEvent(a.home, a.sport, first.broadcast.start, b.home, b.sport, mb.broadcast.start, DEDUP_WINDOW_MS)
                    else -> TeamNames.samePair(a.home, a.away, b.home, b.away)
                }
            }
            if (g == null) {
                groups += mutableListOf(mb)
                groupStarts += mb.broadcast.start
            } else {
                g += mb
            }
        }
        return groups
    }

    /** Primul index cu valoarea ≥ [value] dintr-un șir crescător. */
    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lowerBound(sorted: List<Long>, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Grupurile de emisiuni cu aceleași echipe la ore diferite (în cel mult [REPLAY_LOOKBACK_MS]) sunt același meci:
     * primul grup e transmisia, celelalte reluări. Se unesc în primul (canalele reluărilor rămân surse).
     */
    private fun joinReruns(groups: List<List<MatchBroadcast>>): List<List<MatchBroadcast>> {
        val sorted = groups.sortedBy { g -> g.minOf { it.broadcast.start } }
        val out = ArrayList<MutableList<MatchBroadcast>>()
        val byKey = HashMap<String, MutableList<MatchBroadcast>>()
        for (g in sorted) {
            val x = g.first().extracted
            if (x.away.isBlank()) {
                out += g.toMutableList()
                continue
            }
            val key = listOf(TeamNames.key(x.home), TeamNames.key(x.away)).sorted().joinToString("|")
            val first = byKey[key]
            if (first != null && g.minOf { it.broadcast.start } - first.minOf { it.broadcast.start } <= REPLAY_LOOKBACK_MS) {
                first += g
            } else {
                val list = g.toMutableList()
                byKey[key] = list
                out += list
            }
        }
        return out
    }

    private fun fromBroadcasts(mbs: List<MatchBroadcast>, now: Long): Match {
        // Transmisia: emisiunile care încep cel mult 30 de minute după prima; restul sunt reluări.
        val earliest = mbs.minOf { it.broadcast.start }
        val airing = mbs.filter { it.broadcast.start <= earliest + BROADCAST_LATE_MS }
        // Emisiunile marcate „live” dau ora cea mai apropiată de start; altfel cea mai devreme.
        val lead = airing.filter { it.extracted.explicitLive }.ifEmpty { airing }
        val start = lead.minOf { it.broadcast.start }
        val sport = mbs.firstNotNullOfOrNull { it.extracted.sport }
        // Sfârșitul: emisiunea transmisiei, dar nu mai mult decât durata obișnuită a sportului plus 90 de minute
        // (un bloc EPG lung „Sky Sport Calcio” nu ține meciul „LIVE” toată ziua).
        val end = minOf(airing.maxOf { it.broadcast.stop }, start + (sport?.typicalDurationMs ?: Sport.DEFAULT_DURATION_MS) + LIVE_GRACE_MS)
        val first = lead.first().extracted
        val status = when {
            now < start -> MatchStatus.SCHEDULED
            now < end -> MatchStatus.LIVE
            else -> MatchStatus.FINISHED
        }
        val key = if (first.away.isBlank()) {
            EventNames.key(first.home)
        } else {
            listOf(TeamNames.key(first.home), TeamNames.key(first.away)).sorted().joinToString("|")
        }
        val day = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate()
        return Match(
            id = "$EPG_PROVIDER:$key:$day",
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

    /** Meciurile găsite în numele canalelor EVENT / PPV din playlist. */
    const val EVENT_PROVIDER = "playlist"
    private val SLOT_LEAD_MS = TimeUnit.HOURS.toMillis(1)
    private val SLOT_UNTIMED_MS = TimeUnit.HOURS.toMillis(24)
}
