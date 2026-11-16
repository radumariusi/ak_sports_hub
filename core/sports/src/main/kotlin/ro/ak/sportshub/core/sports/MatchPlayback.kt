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

    /** [at] = de când se arată (0: știut de la început); [observedAt] = când a sosit de fapt (pentru ceas). */
    private data class Seen(val at: Long, val details: MatchDetails, val observedAt: Long = at)

    private val seen = ArrayList<Seen>()
    private val firstSeen = LinkedHashMap<TimelineEntry, Long>()

    @Synchronized
    fun observe(at: Long, details: MatchDetails) {
        val last = seen.lastOrNull()?.details
        val baseline = seen.isEmpty()
        details.timeline.forEach { t -> if (t !in firstSeen) firstSeen[t] = if (baseline) 0L else at }
        if (last == null || last.event != details.event || last.timeline != details.timeline || last.stats != details.stats ||
            last.winProbability != details.winProbability || last.momentum != details.momentum || last.shots != details.shots
        ) {
            seen += Seen(if (baseline) 0L else at, details, at)
            // Păstrăm puțin: doar cât e nevoie pentru întârzierea maximă.
            while (seen.size > MAX_SNAPSHOTS) seen.removeAt(0)
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = seen.isEmpty()

    /**
     * Detaliile de arătat la [now] (tabul Live, Statistici, alertele): scorul și starea de acum [delayMs] în urmă,
     * doar evenimentele văzute până atunci; restul (echipe, formații) cele mai noi. Null dacă nu s-a văzut nimic.
     */
    @Synchronized
    fun details(now: Long, delayMs: Long): MatchDetails? {
        val cutoff = now - delayMs.coerceAtLeast(0)
        val visible = seen.lastOrNull { it.at <= cutoff } ?: return null
        val latest = seen.last().details
        if (visible.details === latest && latest.timeline.all { (firstSeen[it] ?: 0L) <= cutoff }) return latest
        return latest.copy(
            event = visible.details.event,
            timeline = latest.timeline.filter { (firstSeen[it] ?: 0L) <= cutoff },
            stats = visible.details.stats,
            // Șansele, presiunea și șuturile spun și ele ce s-a întâmplat: tot cu întârzierea stream-ului.
            winProbability = visible.details.winProbability,
            momentum = visible.details.momentum,
            shots = visible.details.shots,
        )
    }

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
            label = MatchInsights.liveLabel(status, e?.statusCode, LiveClock.advance(e?.progress, e?.statusCode, match.sport, cutoff - visible.observedAt)),
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
            // ESPN dă și abrevieri cu latura după cratimă („CD-L”, „CM-R”).
            val p = position?.lowercase()?.trim().orEmpty().let { if (it.length <= 5) it.substringBefore('-') else it }
            return when {
                p.isEmpty() -> null
                p == "g" -> PitchLine.GOALKEEPER
                p in setOf("cd", "sw", "lcb", "rcb", "wb") || "sweeper" in p -> PitchLine.DEFENCE
                p in setOf("cf", "lf", "rf", "s", "ss") -> PitchLine.ATTACK
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

/**
 * Scorul și starea meciurilor din listă întârziate cât stream-ul (setarea „întârzierea stream-ului”), ca un gol să nu
 * apară în Sports Hub înainte să-l vezi. Ce se știa la prima vedere se arată imediat; o schimbare apare abia după
 * întârziere. Doar în memorie, doar pentru meciurile care și-au schimbat scorul sau starea.
 */
class ScoreDelay {

    private data class Score(val home: Int?, val away: Int?, val status: MatchStatus, val end: Long)

    /** Pe meci: (când s-a văzut, scorul), în ordine; prima intrare are ora 0 (știută de la început). */
    private val history = HashMap<String, ArrayList<Pair<Long, Score>>>()

    /** Notează scorurile din [matches] (lista de acum). Meciurile care nu mai sunt în listă se uită. */
    @Synchronized
    fun observe(matches: List<Match>, now: Long) {
        val ids = HashSet<String>(matches.size * 2)
        for (m in matches) {
            ids += m.id
            val score = Score(m.homeScore, m.awayScore, m.status, m.end)
            val h = history[m.id]
            if (h == null) {
                history[m.id] = arrayListOf(0L to score)
                continue
            }
            val last = h.last().second
            if (last.home != score.home || last.away != score.away || last.status != score.status) {
                h += now to score
                while (h.size > MAX_ENTRIES) h.removeAt(0)
            }
        }
        if (history.size > ids.size) history.keys.retainAll(ids)
    }

    /** [m] cu scorul și starea de acum [delayMs] în urmă; același obiect dacă nu e nimic de întârziat. */
    @Synchronized
    fun apply(m: Match, now: Long, delayMs: Long): Match {
        if (delayMs <= 0) return m
        val h = history[m.id] ?: return m
        if (h.size == 1) return m
        val cutoff = now - delayMs
        val shown = h.lastOrNull { it.first <= cutoff }?.second ?: h.first().second
        if (shown.home == m.homeScore && shown.away == m.awayScore && shown.status == m.status) return m
        // Terminat de puțin (încă în întârziere): rămâne LIVE și ora de final nu e încă trecută.
        val end = if (shown.status != MatchStatus.FINISHED && m.status == MatchStatus.FINISHED) maxOf(m.end, now + delayMs) else m.end
        return m.copy(homeScore = shown.home, awayScore = shown.away, status = shown.status, end = end)
    }

    /** Mai e vreo schimbare care încă așteaptă întârzierea (ca ecranul să se reîmprospăteze)? */
    @Synchronized
    fun pending(now: Long, delayMs: Long): Boolean =
        delayMs > 0 && history.values.any { h -> h.size > 1 && h.last().first > now - delayMs }

    private companion object {
        const val MAX_ENTRIES = 12
    }
}

/**
 * Meciul pe care îl arată acum EPG-ul canalului din player: dacă emisiunea curentă e un meci din listă (aceleași
 * echipe, în desfășurare sau pe cale să înceapă), canalul se leagă de el, oricum ar fi fost găsit ca sursă.
 */
object OnAirMatch {

    private val LEAD_MS = TimeUnit.MINUTES.toMillis(15)

    fun find(title: String, subtitle: String?, category: String?, at: Long, matches: List<Match>): Match? {
        val x = EpgMatchExtractor.extract(title, category, channelIsSport = true)?.takeIf { it.away.isNotBlank() }
            ?: subtitle?.let { EpgMatchExtractor.extract(it, category, channelIsSport = true, context = title) }?.takeIf { it.away.isNotBlank() }
            ?: return null
        return matches.firstOrNull { m ->
            m.away.isNotBlank() && at >= m.start - LEAD_MS && at <= m.end && TeamNames.samePair(x.home, x.away, m.home, m.away)
        }
    }
}

/**
 * Minutul din tabela de scor merge mai departe între cereri (o cerere pe minut, minutul ESPN de atunci):
 * la fotbal, în reprize, se adaugă minutele trecute de la cerere; peste 45/90/120 devine „45+2”.
 */
object LiveClock {

    fun advance(progress: String?, code: String?, sport: Sport?, elapsedMs: Long): String? {
        val p = progress?.trim() ?: return progress
        if (sport != Sport.FOOTBALL || elapsedMs < 60_000) return progress
        val c = code?.trim()?.uppercase().orEmpty()
        val cap = when (c) {
            "1H" -> 45
            "2H" -> 90
            "ET" -> 120
            else -> return progress
        }
        val base = p.substringBefore('+').trim().toIntOrNull() ?: return progress
        val extra = p.substringAfter('+', "").trim().toIntOrNull() ?: 0
        val total = base + extra + (elapsedMs / 60_000).toInt()
        return if (total > cap) "$cap+${total - cap}" else total.toString()
    }
}
