package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ro.ak.sportshub.core.common.ChannelFamilies
import ro.ak.sportshub.core.common.ChannelFamily
import ro.ak.sportshub.core.common.SourceCandidate
import ro.ak.sportshub.core.common.SourceRanking
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

/** Taburile Sports Hub. */
enum class MatchTab(val label: String) {
    LIVE("LIVE"), TODAY("Azi"), TOMORROW("Mâine"), WEEK("Săptămâna asta"), FAVORITES("Favorite"),
}

/** Echipele, competițiile și sporturile favorite (setări). */
data class SportsFavorites(
    val teams: List<String> = emptyList(),
    val competitions: List<String> = emptyList(),
    val sports: Set<Sport> = emptySet(),
) {
    fun isFavorite(m: Match): Boolean =
        (m.sport != null && m.sport in sports) ||
            teams.any { t -> TeamNames.same(t, m.home) || (m.away.isNotBlank() && TeamNames.same(t, m.away)) } ||
            competitions.any { c -> m.competition != null && Sport.fold(c) == Sport.fold(m.competition) }

    fun isTeam(name: String): Boolean = teams.any { TeamNames.same(it, name) }

    fun isCompetition(name: String?): Boolean = name != null && competitions.any { Sport.fold(it) == Sport.fold(name) }
}

/** Ce știm despre sursele găsite pentru un meci (pentru filtre și pentru listă). */
data class SourceSummary(
    val count: Int = 0,
    val languages: Set<String> = emptySet(),
    val best: Quality = Quality.UNKNOWN,
) {
    companion object {
        val NONE = SourceSummary()
    }
}

/** Filtrele listei de meciuri. Null înseamnă „toate”. */
data class MatchFilter(
    val sport: Sport? = null,
    val competition: String? = null,
    /** Text liber: o parte din numele unei echipe. */
    val team: String? = null,
    /** Doar meciuri cu cel puțin o sursă în această limbă. */
    val language: String? = null,
    /** Doar meciuri cu cel puțin o sursă de această calitate sau mai bună. */
    val minQuality: Quality? = null,
    val onlyWithSources: Boolean = false,
) {
    val isEmpty: Boolean
        get() = sport == null && competition == null && team.isNullOrBlank() && language == null && minQuality == null && !onlyWithSources

    fun accepts(m: Match, s: SourceSummary): Boolean {
        if (sport != null && m.sport != sport) return false
        if (competition != null && (m.competition == null || Sport.fold(m.competition) != Sport.fold(competition))) return false
        if (!team.isNullOrBlank()) {
            val t = Sport.fold(team)
            if (!Sport.fold(m.home).contains(t) && !Sport.fold(m.away).contains(t)) return false
        }
        if (onlyWithSources && s.count == 0) return false
        if (language != null && language.lowercase() !in s.languages) return false
        if (minQuality != null && (s.count == 0 || !s.best.isAtLeast(minQuality))) return false
        return true
    }
}

object MatchLists {

    /** Ziua locală a meciului. */
    fun day(m: Match, zone: ZoneId): LocalDate = Instant.ofEpochMilli(m.start).atZone(zone).toLocalDate()

    fun inTab(m: Match, tab: MatchTab, now: Long, zone: ZoneId, favorite: Boolean): Boolean {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val d = day(m, zone)
        return when (tab) {
            MatchTab.LIVE -> m.isLive
            MatchTab.TODAY -> m.isLive || d.isEqual(today)
            MatchTab.TOMORROW -> d.isEqual(today.plusDays(1))
            MatchTab.WEEK -> m.isLive || (m.status != MatchStatus.FINISHED && !d.isBefore(today) && d.isBefore(today.plusDays(7)))
            MatchTab.FAVORITES -> favorite && m.status != MatchStatus.FINISHED && d.isBefore(today.plusDays(7))
        }
    }

    /** Ordinea din listă: live întâi, apoi după oră; cele încheiate la final. */
    fun sort(matches: List<Match>): List<Match> = matches.sortedWith(
        compareBy<Match> {
            when (it.status) {
                MatchStatus.LIVE -> 0
                MatchStatus.SCHEDULED -> 1
                MatchStatus.FINISHED -> 2
            }
        }.thenBy { it.start }.thenBy { it.title },
    )

    /** Competițiile prezente (pentru filtrul de competiție), cele mai dese întâi. */
    fun competitions(matches: List<Match>): List<String> =
        matches.mapNotNull { it.competition }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}

/**
 * O sursă găsită pentru un meci: o variantă a unui canal, cu încrederea potrivirii.
 * [T] e rândul de canal al aplicației (în teste, orice).
 */
data class MatchSource<T>(
    val channel: T,
    val groupKey: String,
    val language: String?,
    val quality: Quality,
    val confidence: Confidence,
    /** Postul anunțat de furnizor, dacă sursa vine din potrivirea pe nume. */
    val station: String? = null,
)

object MatchSources {

    /**
     * Sursele unui meci, grupate pe canal și apoi pe variante, în ordinea preferințelor
     * ([SourceRanking]: la favorite limba contează peste prag, la rest primează rezoluția).
     * La egalitate, potrivirea mai sigură (EPG) vine prima. Nimic nu e ascuns.
     */
    fun <T> order(
        sources: List<MatchSource<T>>,
        isFavorite: Boolean,
        prefs: SourcePreferences,
    ): List<ChannelFamily<MatchSource<T>>> {
        val byConfidence = sources.sortedBy { it.confidence.ordinal }
        val ranked = SourceRanking.rank(
            byConfidence.mapIndexed { i, s -> SourceCandidate(i.toString(), s.language, s.quality) },
            isFavorite = isFavorite,
            prefs = prefs,
        ).map { byConfidence[it.id.toInt()] }
        return ChannelFamilies.collapse(ranked) { it.groupKey }
    }

    fun <T> summary(sources: List<MatchSource<T>>): SourceSummary =
        if (sources.isEmpty()) SourceSummary.NONE else SourceSummary(
            count = sources.size,
            languages = sources.mapNotNull { it.language?.lowercase() }.toSet(),
            best = sources.maxByOrNull { it.quality.rank }?.quality ?: Quality.UNKNOWN,
        )
}
