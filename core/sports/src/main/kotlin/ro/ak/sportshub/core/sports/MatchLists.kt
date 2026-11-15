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
    /** Numele urmărite (jucător, pilot, luptător): orice meci sau emisiune care le pomenește e favorit. */
    val names: List<String> = emptyList(),
) {
    fun isFavorite(m: Match): Boolean =
        (m.sport != null && m.sport in sports) ||
            teams.any { t -> TeamNames.same(t, m.home) || (m.away.isNotBlank() && TeamNames.same(t, m.away)) } ||
            competitions.any { c -> m.competition != null && NameSeeds.sameCompetition(c, m.competition) } ||
            (names.isNotEmpty() && FollowedNames.mentionsAny(names, m.home, m.away, m.competition))

    fun isName(name: String): Boolean = names.any { Sport.fold(it) == Sport.fold(name) }

    fun isTeam(name: String): Boolean = teams.any { TeamNames.same(it, name) }

    fun isCompetition(name: String?): Boolean = name != null && competitions.any { NameSeeds.sameCompetition(it, name) }
}

/** Ce știm despre sursele găsite pentru un meci (pentru filtre și pentru listă). */
data class SourceSummary(
    /** Sursele găsite (fără cele respinse de tine), inclusiv presupunerile din drepturile TV. */
    val count: Int = 0,
    val languages: Set<String> = emptySet(),
    val best: Quality = Quality.UNKNOWN,
    /** Câte din [count] sunt găsite efectiv (EPG, post anunțat, confirmate), nu presupuse. */
    val sure: Int = count,
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
    /** Postul anunțat de furnizor sau din regula de drepturi, dacă sursa vine din potrivirea pe nume. */
    val station: String? = null,
    /** Ai spus că nu e meciul: apare la final, marcată. */
    val rejected: Boolean = false,
    /** Cum fusese găsită înainte de alegerea ta. */
    val origin: Confidence = confidence,
    /** Ce arată EPG-ul canalului la ora meciului, când e altceva ([Confidence.ELSEWHERE]). */
    val airing: String? = null,
)

object MatchSources {

    /**
     * Sursele unui meci, grupate pe canal și apoi pe variante, în ordinea preferințelor
     * ([SourceRanking]: la favorite limba contează peste prag, la rest primează rezoluția).
     * Întâi sursele găsite efectiv (confirmate, EPG, post anunțat), apoi presupunerile (învățat, drepturi TV),
     * apoi cele respinse de tine. În fiecare parte, la egalitate, potrivirea mai sigură vine prima. Nimic nu e ascuns.
     */
    fun <T> order(
        sources: List<MatchSource<T>>,
        isFavorite: Boolean,
        prefs: SourcePreferences,
    ): List<ChannelFamily<MatchSource<T>>> {
        val tiers = sources.groupBy {
            when {
                it.rejected -> 3
                it.confidence == Confidence.ELSEWHERE -> 2
                it.confidence.guess -> 1
                else -> 0
            }
        }
        return (0..3).flatMap { tier ->
            val part = tiers[tier].orEmpty().sortedBy { it.confidence.ordinal }
            val ranked = SourceRanking.rank(
                part.mapIndexed { i, s -> SourceCandidate(i.toString(), s.language, s.quality) },
                isFavorite = isFavorite,
                prefs = prefs,
            ).map { part[it.id.toInt()] }
            ChannelFamilies.collapse(ranked) { it.groupKey }
        }
    }

    fun <T> summary(all: List<MatchSource<T>>): SourceSummary {
        // Sursele al căror EPG arată altceva nu contează ca surse (rămân doar afișate, la final).
        val sources = all.filterNot { it.rejected || it.confidence == Confidence.ELSEWHERE }
        return if (sources.isEmpty()) SourceSummary.NONE else SourceSummary(
            count = sources.size,
            languages = sources.mapNotNull { it.language?.lowercase() }.toSet(),
            best = sources.maxByOrNull { it.quality.rank }?.quality ?: Quality.UNKNOWN,
            sure = sources.count { !it.confidence.guess },
        )
    }
}

/** Grupurile de limbi din lista surselor unui meci (cererea lui Radu): limbile tale, apoi cele europene mari, apoi restul. */
enum class LanguageGroup { PREFERRED, EUROPEAN, OTHER }

object LanguageGroups {

    /** Limbile europene mari (după engleză și română): spaniolă, italiană, germană, franceză. */
    val EUROPEAN = setOf("es", "it", "de", "fr")

    private val names = mapOf(
        "en" to "engleză", "ro" to "română", "es" to "spaniolă", "it" to "italiană", "de" to "germană", "fr" to "franceză",
        "pt" to "portugheză", "nl" to "olandeză", "hu" to "maghiară", "pl" to "poloneză", "tr" to "turcă", "el" to "greacă",
        "ar" to "arabă", "ru" to "rusă", "sr" to "sârbă", "hr" to "croată", "bg" to "bulgară", "cs" to "cehă",
    )

    fun name(code: String): String = names[code.lowercase()] ?: code.uppercase()

    fun of(language: String?, preferred: List<String>): LanguageGroup {
        val l = language?.lowercase() ?: return LanguageGroup.OTHER
        return when {
            preferred.any { it.lowercase() == l } -> LanguageGroup.PREFERRED
            l in EUROPEAN -> LanguageGroup.EUROPEAN
            else -> LanguageGroup.OTHER
        }
    }

    /** Titlul grupului: „Engleză și română”, „Limbi europene (spaniolă, italiană, germană, franceză)”, „Alte limbi”. */
    fun label(group: LanguageGroup, preferred: List<String>): String = when (group) {
        LanguageGroup.PREFERRED -> preferred.map { name(it) }.let { n ->
            if (n.size <= 1) n.firstOrNull().orEmpty() else n.dropLast(1).joinToString(", ") + " și " + n.last()
        }.replaceFirstChar { it.uppercase() }.ifEmpty { "Limbile tale" }
        LanguageGroup.EUROPEAN -> "Limbi europene (" + (EUROPEAN.map { it } - preferred.map { it.lowercase() }.toSet()).joinToString(", ") { name(it) } + ")"
        LanguageGroup.OTHER -> "Alte limbi (și necunoscute)"
    }

    /**
     * Familiile de surse (deja în ordinea [MatchSources.order]) pe grupuri de limbi, în ordinea grupurilor; în fiecare
     * grup ordinea rămâne (găsite, presupuse, „EPG-ul arată altceva”). Cele respinse de tine stau separat, la final.
     */
    fun <T> split(
        families: List<ChannelFamily<MatchSource<T>>>,
        preferred: List<String>,
    ): Pair<List<Pair<LanguageGroup, List<ChannelFamily<MatchSource<T>>>>>, List<ChannelFamily<MatchSource<T>>>> {
        val (rejected, kept) = families.partition { it.members.first().rejected }
        val groups = kept.groupBy { of(it.members.first().language, preferred) }
        return LanguageGroup.entries.mapNotNull { g -> groups[g]?.let { g to it } } to rejected
    }
}
