package ro.ak.sportshub.core.common

import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

/** O sursă candidată pentru un meci: limba și calitatea vin din metadatele playlistului. */
data class SourceCandidate(
    val id: String,
    val language: String?,
    val quality: Quality,
)

object SourceRanking {

    /**
     * Ordonează sursele unui meci după preferințe și le scoate pe cele care nu sunt permise.
     *
     * - Meci favorit: doar limbile preferate. Dacă nu există niciuna, se arată totuși restul,
     *   ca să nu rămână meciul fără surse.
     * - Alte meciuri: limbile preferate; dacă niciuna nu are măcar calitatea minimă (implicit FHD),
     *   intră și sursele în alte limbi care au cel puțin acea calitate.
     */
    fun rank(
        candidates: List<SourceCandidate>,
        isFavorite: Boolean,
        prefs: SourcePreferences = SourcePreferences(),
    ): List<SourceCandidate> {
        val preferred = prefs.preferredLanguages.map { it.lowercase() }
        fun langIndex(c: SourceCandidate): Int =
            preferred.indexOf(c.language?.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
        fun isPreferred(c: SourceCandidate) = langIndex(c) != Int.MAX_VALUE

        val (inPreferred, others) = candidates.partition(::isPreferred)
        val byQualityThenLanguage = compareByDescending<SourceCandidate> { it.quality.rank }
            .thenBy { if (isPreferred(it)) 0 else 1 }
            .thenBy(::langIndex)

        val min = prefs.minQualityBeforeOtherLanguages
        val allowed = when {
            inPreferred.isEmpty() -> candidates
            isFavorite && prefs.favoritesPreferredOnly -> inPreferred
            inPreferred.any { it.quality.isAtLeast(min) } -> inPreferred
            else -> inPreferred + others.filter { it.quality.isAtLeast(min) }
        }
        return allowed.sortedWith(byQualityThenLanguage)
    }
}
