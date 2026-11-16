package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * F1: căutarea (cu vocea sau scrisă) în meciuri: „Arsenal”, „Liga Campionilor”, „meciul lui Barcelona”. Cuvintele de
 * umplutură ale unei fraze spuse („meciul”, „pe ce canal”) se scot; apoi echipa (cu alias-urile ei), competiția (cu
 * prescurtările ei) sau cuvintele din titlu, competiție și sport.
 */
object MatchSearch {

    const val LIMIT = 40

    private val FILLER = setOf(
        "meciul", "meciului", "meci", "meciuri", "meciurile", "cauta", "gaseste", "canalul", "canal", "canale", "pe", "ce", "cu",
        "de", "la", "si", "lui", "vreau", "sa", "vad", "pune", "porneste", "arata", "deschide", "azi", "astazi", "acum",
        "live", "direct", "unde", "e", "este", "show", "watch", "the", "game", "match", "play", "on", "channel", "find", "search",
    )

    private val SPACES = Regex("\\s+")

    /** Fraza fără cuvintele de umplutură („pune meciul lui Arsenal” → „Arsenal”); fraza întreagă dacă nu rămâne nimic. */
    fun clean(query: String): String {
        val words = query.trim().split(SPACES).filter { it.isNotEmpty() }
        val kept = words.filter { Sport.fold(it) !in FILLER }
        return (kept.ifEmpty { words }).joinToString(" ")
    }

    /** Meciurile găsite, cele mai potrivite întâi (echipa, apoi competiția, apoi cuvintele), LIVE și cele apropiate întâi. */
    fun find(matches: List<Match>, query: String, limit: Int = LIMIT): List<Match> {
        val q = clean(query)
        val tokens = Sport.fold(q).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val scored = matches.mapNotNull { m ->
            val score = when {
                TeamNames.covers(q, m.home) || (m.away.isNotBlank() && TeamNames.covers(q, m.away)) -> 3
                m.competition != null && NameSeeds.sameCompetition(q, m.competition) -> 2
                words(m).let { w -> tokens.all { t -> w.any { it.startsWith(t) } } } -> 1
                else -> 0
            }
            if (score == 0) null else m to score
        }
        val order = compareByDescending<Pair<Match, Int>> { it.second }
            .thenBy { statusRank(it.first.status) }
            .thenBy { it.first.start }
            .thenBy { it.first.title }
        return scored.sortedWith(order).map { it.first }.distinctBy { it.id }.take(limit)
    }

    private fun statusRank(s: MatchStatus): Int = when (s) {
        MatchStatus.LIVE -> 0
        MatchStatus.SCHEDULED -> 1
        MatchStatus.FINISHED -> 2
    }

    private fun words(m: Match): List<String> =
        Sport.fold(listOfNotNull(m.home, m.away, m.competition, m.sport?.label).joinToString(" ")).split(' ')
}

/**
 * F2: meciurile din rândul „Continuă vizionarea” / „Urmează” al ecranului principal Google TV: doar favoritele live
 * sau care încep în [AHEAD_MS], cel mult [LIMIT] (rândul e al tuturor aplicațiilor, nu doar al nostru).
 */
object WatchNextPick {

    const val LIMIT = 4
    val AHEAD_MS: Long = TimeUnit.MINUTES.toMillis(30)

    fun pick(matches: List<Match>, isFavorite: (Match) -> Boolean, now: Long, limit: Int = LIMIT): List<Match> =
        matches.asSequence()
            .filter { m -> m.end > now && (m.isLive || (m.status == MatchStatus.SCHEDULED && m.start - now in 0..AHEAD_MS)) }
            .filter(isFavorite)
            .sortedWith(compareBy<Match> { if (it.isLive) 0 else 1 }.thenBy { it.start }.thenBy { it.title })
            .distinctBy { it.id }
            .take(limit)
            .toList()
}

/**
 * F3: ce arată economizorul de ecran: scorurile meciurilor favorite live și următoarele meciuri favorite (azi, mâine,
 * în zilele din program). Fără favorite de arătat, meciurile live și de azi cu surse găsite. Scorul se ascunde când
 * [hideScore] o cere („Fără spoilere”). Nicio cerere: doar lista Sports Hub deja calculată.
 */
object ScreensaverBoard {

    const val LIMIT = 6
    val HORIZON_MS: Long = TimeUnit.HOURS.toMillis(48)

    data class Line(
        val id: String,
        val title: String,
        /** „2–1”; null dacă meciul n-a început sau scorul e ascuns. */
        val score: String?,
        /** Competiția la meciurile live; ziua, ora și competiția la cele care urmează. */
        val detail: String,
        val live: Boolean,
    )

    data class Board(val live: List<Line>, val next: List<Line>, val favorites: Boolean) {
        val isEmpty: Boolean get() = live.isEmpty() && next.isEmpty()
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ro"))
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ro"))

    fun build(
        matches: List<Match>,
        isFavorite: (Match) -> Boolean,
        hasSources: (Match) -> Boolean,
        hideScore: (Match) -> Boolean,
        now: Long,
        zone: ZoneId,
        limit: Int = LIMIT,
    ): Board {
        val open = matches.filter { it.status != MatchStatus.FINISHED && it.end > now }.distinctBy { it.id }
        val byTime = compareBy<Match> { it.start }.thenBy { it.title }
        fun upcoming(m: Match) = m.status == MatchStatus.SCHEDULED && m.start >= now - TimeUnit.MINUTES.toMillis(5) && m.start - now <= HORIZON_MS
        val favorites = open.filter(isFavorite)
        val favLive = favorites.filter { it.isLive }.sortedWith(byTime).take(limit)
        val favNext = favorites.filter(::upcoming).sortedWith(byTime).take(limit)
        if (favLive.isNotEmpty() || favNext.isNotEmpty()) {
            return Board(favLive.map { line(it, hideScore(it), now, zone) }, favNext.map { line(it, false, now, zone) }, favorites = true)
        }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val withSources = open.filter(hasSources)
        val live = withSources.filter { it.isLive }.sortedWith(byTime).take(limit)
        val next = withSources.filter { upcoming(it) && MatchLists.day(it, zone) == today }.sortedWith(byTime).take(limit)
        return Board(live.map { line(it, hideScore(it), now, zone) }, next.map { line(it, false, now, zone) }, favorites = false)
    }

    private fun line(m: Match, hide: Boolean, now: Long, zone: ZoneId): Line {
        val score = if (m.isLive && !hide && m.homeScore != null && m.awayScore != null) "${m.homeScore}–${m.awayScore}" else null
        val detail = if (m.isLive) {
            listOfNotNull(m.competition, m.sport?.label).firstOrNull().orEmpty()
        } else {
            listOfNotNull(when_(m.start, now, zone), m.competition).joinToString(" · ")
        }
        return Line(m.id, m.title, score, detail, m.isLive)
    }

    /** „azi 21:00”, „mâine 18:30”, „joi 20:45”. */
    fun when_(start: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(start).atZone(zone)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> "azi"
            1L -> "mâine"
            else -> at.format(DAY)
        }
        return "$day ${at.format(TIME)}"
    }
}
