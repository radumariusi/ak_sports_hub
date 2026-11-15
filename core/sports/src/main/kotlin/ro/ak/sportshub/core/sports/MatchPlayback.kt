package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit

/**
 * Ceasul unui meci de fotbal: ora aproximativă (ms UTC) la care s-a întâmplat un eveniment, din minutul lui,
 * și minutul din meci la o oră dată (pentru reluări). Furnizorii dau doar minutul, deci totul e estimat:
 * pauza dintre reprize are [HALF_TIME_BREAK_MIN] minute, iar prima repriză [FIRST_HALF_STOPPAGE_MIN] minute
 * de prelungiri. Saltul la eveniment pornește oricum cu un minut înainte, iar corecția pe playlist reglează restul.
 */
object MatchClock {

    const val HALF_MINUTES = 45
    const val HALF_TIME_BREAK_MIN = 15
    const val FIRST_HALF_STOPPAGE_MIN = 2

    /** Cât înainte de eveniment pornește saltul. */
    val JUMP_LEAD_MS: Long = TimeUnit.SECONDS.toMillis(60)

    /** Ceasul pe minute are sens doar la fotbal (și la meciurile al căror sport nu-l știm, dar au minute). */
    fun supports(sport: Sport?): Boolean = sport == null || sport == Sport.FOOTBALL

    /**
     * Minutele scurse de la start până la începutul minutului [minute] (pauza inclusă). Minutele de după 90
     * (prelungirile reprizei a doua, „90+3” dat ca 93) continuă repriza a doua; prelungirile meciului ies
     * mai devreme cu câteva minute, iar corecția pe playlist nu le acoperă (rar, acceptat).
     */
    private fun elapsedMinutes(minute: Int): Int {
        val m = (minute - 1).coerceAtLeast(0)
        return if (minute <= HALF_MINUTES) m else m + FIRST_HALF_STOPPAGE_MIN + HALF_TIME_BREAK_MIN
    }

    /** Ora estimată a evenimentului din minutul [minute]; null dacă sportul nu are ceas pe minute. */
    fun estimate(kickoff: Long, minute: Int?, sport: Sport?): Long? {
        if (minute == null || minute < 0 || !supports(sport)) return null
        return kickoff + TimeUnit.MINUTES.toMillis(elapsedMinutes(minute).toLong()) + HALF_MINUTE_MS
    }

    /**
     * De unde pornește arhiva ca să vezi evenimentul: cu [JUMP_LEAD_MS] înainte, plus corecția playlistului
     * ([offsetSeconds], pozitivă dacă arhiva furnizorului e în urmă). Niciodată înainte de start.
     */
    fun jumpStart(kickoff: Long, minute: Int?, sport: Sport?, offsetSeconds: Int): Long? {
        val at = estimate(kickoff, minute, sport) ?: return null
        val start = at - JUMP_LEAD_MS + TimeUnit.SECONDS.toMillis(offsetSeconds.toLong())
        return start.coerceAtLeast(kickoff - JUMP_LEAD_MS)
    }

    /** Minutul din meci la ora [at] (pentru „Reluare · 67'”); null înainte de start sau fără ceas. */
    fun minuteAt(kickoff: Long, at: Long, sport: Sport?): Int? {
        if (!supports(sport) || at < kickoff) return null
        val elapsed = TimeUnit.MILLISECONDS.toMinutes(at - kickoff).toInt()
        val firstHalfEnd = HALF_MINUTES + FIRST_HALF_STOPPAGE_MIN
        val secondHalfStart = firstHalfEnd + HALF_TIME_BREAK_MIN
        return when {
            elapsed < firstHalfEnd -> (elapsed + 1).coerceAtMost(HALF_MINUTES)
            elapsed < secondHalfStart -> HALF_MINUTES
            else -> (elapsed - FIRST_HALF_STOPPAGE_MIN - HALF_TIME_BREAK_MIN + 1).coerceAtMost(120)
        }
    }

    /** Între reprize (pentru „Pauză” la reluare). */
    fun isHalfTime(kickoff: Long, at: Long, sport: Sport?): Boolean {
        if (!supports(sport)) return false
        val elapsed = TimeUnit.MILLISECONDS.toMinutes(at - kickoff).toInt()
        val firstHalfEnd = HALF_MINUTES + FIRST_HALF_STOPPAGE_MIN
        return elapsed in firstHalfEnd until firstHalfEnd + HALF_TIME_BREAK_MIN
    }

    private val HALF_MINUTE_MS = TimeUnit.SECONDS.toMillis(30)
}

/** Ce arată panoul meciului din player: scorul, starea, evenimentele și statisticile vizibile acum. */
data class PanelView(
    val homeScore: Int?,
    val awayScore: Int?,
    /** Starea pe scurt („Repriza 2 · 67'”, „Reluare · 23'”). */
    val label: String,
    val status: MatchStatus?,
    val timeline: List<TimelineEntry>,
    val stats: List<StatLine>,
    /** Câte evenimente noi așteaptă întârzierea (vin încă în stream). */
    val pending: Int = 0,
    /** La reluare, statisticile (de la final) rămân ascunse până la sfârșitul meciului. */
    val statsHidden: Boolean = false,
)

/** Scorul din evenimente: golurile până la un moment dat. */
object GoalCount {

    private fun isGoal(k: TimelineKind) = k == TimelineKind.GOAL || k == TimelineKind.PENALTY

    /**
     * Scorul (gazde, oaspeți) din [timeline]. La autogol, furnizorii nu spun clar echipa: dacă știm scorul
     * final ([finalHome], [finalAway]), alegem citirea care îl reproduce pe tot meciul ([full]); altfel autogolul
     * contează pentru echipa adversă celei din eveniment.
     */
    fun score(
        timeline: List<TimelineEntry>,
        full: List<TimelineEntry> = timeline,
        finalHome: Int? = null,
        finalAway: Int? = null,
    ): Pair<Int, Int> {
        val ownForOpponent = ownGoalsForOpponent(full, finalHome, finalAway)
        var h = 0
        var a = 0
        for (t in timeline) {
            when {
                isGoal(t.kind) -> if (t.home) h++ else a++
                t.kind == TimelineKind.OWN_GOAL -> if (t.home == ownForOpponent) a++ else h++
            }
        }
        return h to a
    }

    /** true = la autogol, `home` e echipa jucătorului (golul e al adversarului). */
    private fun ownGoalsForOpponent(full: List<TimelineEntry>, finalHome: Int?, finalAway: Int?): Boolean {
        if (finalHome == null || finalAway == null || full.none { it.kind == TimelineKind.OWN_GOAL }) return true
        fun count(opponent: Boolean): Pair<Int, Int> {
            var h = 0
            var a = 0
            for (t in full) {
                when {
                    isGoal(t.kind) -> if (t.home) h++ else a++
                    t.kind == TimelineKind.OWN_GOAL -> if (t.home == opponent) a++ else h++
                }
            }
            return h to a
        }
        return when (finalHome to finalAway) {
            count(true) -> true
            count(false) -> false
            else -> true
        }
    }
}

/**
 * Spoilerele sincronizate cu stream-ul: un stream live e de obicei în urmă cu zeci de secunde față de
 * realitate, deci un gol aflat de la furnizor nu apare în panou până nu trece întârzierea setată.
 *
 * Ține minte ce s-a văzut la fiecare cerere (scor, evenimente, statistici) și când; arată starea de acum
 * [delayMs] în urmă. Ce exista deja la prima cerere se arată imediat. Doar în memorie.
 */
class LiveDelay {

    private data class Seen(val at: Long, val details: MatchDetails)

    private val seen = ArrayList<Seen>()
    private val firstSeen = LinkedHashMap<TimelineEntry, Long>()

    @Synchronized
    fun observe(at: Long, details: MatchDetails) {
        val last = seen.lastOrNull()?.details
        val baseline = seen.isEmpty()
        details.timeline.forEach { t -> if (t !in firstSeen) firstSeen[t] = if (baseline) 0L else at }
        if (last == null || last.event != details.event || last.timeline != details.timeline || last.stats != details.stats) {
            seen += Seen(if (baseline) 0L else at, details)
            // Păstrăm puțin: doar cât e nevoie pentru întârzierea maximă.
            while (seen.size > MAX_SNAPSHOTS) seen.removeAt(0)
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = seen.isEmpty()

    /** Starea de arătat la [now], cu întârzierea [delayMs]; null dacă încă nu s-a văzut nimic. */
    @Synchronized
    fun view(now: Long, delayMs: Long, match: Match): PanelView? {
        val cutoff = now - delayMs.coerceAtLeast(0)
        val visible = seen.lastOrNull { it.at <= cutoff } ?: return null
        val latest = seen.last().details
        val e = visible.details.event
        val timeline = latest.timeline.filter { (firstSeen[it] ?: 0L) <= cutoff }
        val status = listOfNotNull(e?.status, match.status).maxByOrNull { it.ordinal }
        return PanelView(
            homeScore = e?.homeScore ?: match.homeScore,
            awayScore = e?.awayScore ?: match.awayScore,
            label = MatchInsights.liveLabel(status, e?.statusCode, e?.progress),
            status = status,
            timeline = timeline,
            stats = visible.details.stats,
            pending = latest.timeline.size - timeline.size,
        )
    }

    private companion object {
        const val MAX_SNAPSHOTS = 30
    }
}

/** Panoul meciului la reluare: doar ce s-a întâmplat până în punctul din meci pe care îl vezi. */
object ReplayView {

    /**
     * [watchedAt] = ora (ms UTC) din meci care se vede acum în arhivă. Evenimentele cu ora estimată
     * până acolo, scorul numărat din ele, statisticile ascunse până la final.
     */
    fun at(match: Match, details: MatchDetails, watchedAt: Long): PanelView {
        val e = details.event
        val kickoff = e?.start ?: match.start
        val sport = match.sport
        val full = details.timeline
        val finalHome = e?.homeScore ?: match.homeScore
        val finalAway = e?.awayScore ?: match.awayScore
        val timeline = full.filter { t ->
            val at = MatchClock.estimate(kickoff, t.minute, sport)
            at != null && at <= watchedAt
        }
        val ended = watchedAt >= match.end
        val clock = MatchClock.supports(sport) && full.isNotEmpty()
        val (h, a) = when {
            ended && finalHome != null && finalAway != null -> finalHome to finalAway
            ended || clock -> GoalCount.score(if (ended) full else timeline, full, finalHome, finalAway)
            else -> 0 to 0
        }
        val label = when {
            watchedAt < kickoff -> "Reluare · înainte de start"
            ended -> "Reluare · final"
            MatchClock.isHalfTime(kickoff, watchedAt, sport) -> "Reluare · pauză"
            else -> MatchClock.minuteAt(kickoff, watchedAt, sport)?.let { "Reluare · $it'" } ?: "Reluare"
        }
        return PanelView(
            homeScore = if (clock || ended) h else null,
            awayScore = if (clock || ended) a else null,
            label = label,
            status = if (ended) MatchStatus.FINISHED else MatchStatus.LIVE,
            timeline = if (ended) full else timeline,
            stats = if (ended) details.stats else emptyList(),
            statsHidden = !ended && details.stats.isNotEmpty(),
        )
    }
}

/** Anunțul din player „a început meciul favorit X”, o singură dată pe meci. */
object KickoffNotices {

    /** Cât după start mai are rost anunțul. */
    val WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(15)

    fun due(matches: List<Match>, favorites: SportsFavorites, now: Long, announced: Set<String>, currentMatchId: String?): List<Match> =
        matches.filter { m ->
            m.id !in announced && m.id != currentMatchId && m.status != MatchStatus.FINISHED &&
                m.start <= now && now - m.start <= WINDOW_MS && favorites.isFavorite(m)
        }.sortedBy { it.start }
}

/** O linie din formație (de la portar la atacanți). */
enum class PitchLine { GOALKEEPER, DEFENCE, MIDFIELD, ATTACK }

/** Formația unei echipe din echipa de start: liniile, de la portar spre atac, și eticheta („4-3-3”). */
data class Formation(val lines: List<List<LineupPlayer>>) {
    val label: String get() = lines.drop(1).joinToString("-") { it.size.toString() }

    companion object {
        /** Poziția de la TheSportsDB („Goalkeeper”, „Centre-Back”, „Defensive Midfield”, „Left Wing”…) → linia. */
        fun line(position: String?): PitchLine? {
            val p = position?.lowercase()?.trim().orEmpty()
            return when {
                p.isEmpty() -> null
                "goal" in p || p == "gk" || "keeper" in p -> PitchLine.GOALKEEPER
                // „Defensive Midfield” e mijlocaș: mijlocul se verifică înaintea apărării.
                "midfield" in p || p in setOf("cm", "dm", "am", "cdm", "cam", "lm", "rm", "m") -> PitchLine.MIDFIELD
                "back" in p || "defen" in p || p in setOf("cb", "lb", "rb", "lwb", "rwb", "d") -> PitchLine.DEFENCE
                "forward" in p || "striker" in p || "wing" in p || "attack" in p ||
                    p in setOf("cf", "st", "lw", "rw", "f", "fw") -> PitchLine.ATTACK
                else -> null
            }
        }

        /**
         * Formația titularilor unei echipe; null dacă nu sunt 11 titulari cu poziții recunoscute
         * (atunci rămâne doar lista).
         */
        fun of(players: List<LineupPlayer>): Formation? {
            val starters = players.filter { !it.substitute }
            if (starters.size != 11) return null
            val byLine = starters.groupBy { line(it.position) ?: return null }
            if (byLine[PitchLine.GOALKEEPER]?.size != 1) return null
            return Formation(PitchLine.entries.mapNotNull { byLine[it] })
        }
    }
}
