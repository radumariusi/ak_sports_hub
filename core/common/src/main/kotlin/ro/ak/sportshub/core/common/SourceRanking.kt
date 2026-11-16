package ro.ak.sportshub.core.common

import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

/** O sursă candidată pentru un meci: limba și calitatea vin din metadatele playlistului. */
data class SourceCandidate(
    val id: String,
    val language: String?,
    val quality: Quality,
    /** Cum a mers la redare (C1): 0 = stabilă, 1 = nu se știe, 2 = instabilă; contează doar la calitate egală. */
    val stability: Int = 1,
)

object SourceRanking {

    /**
     * Ordonează sursele unui meci după preferințe. Nu scoate nimic din listă: dacă singura sursă
     * e SD într-o altă limbă, apare și aceea.
     *
     * Meci favorit (implicit EN/RO, prag FHD):
     * 1. limbă preferată 4K, apoi FHD
     * 2. altă limbă 4K, apoi FHD
     * 3. limbă preferată HD, SD
     * 4. altă limbă HD, SD
     *
     * Alt meci: după rezoluție, în orice limbă; la aceeași rezoluție, limbile preferate întâi.
     * Peste tot, la aceeași rezoluție, sursa stabilă la tine (C1) urcă, iar cea instabilă coboară.
     */
    fun rank(
        candidates: List<SourceCandidate>,
        isFavorite: Boolean,
        prefs: SourcePreferences = SourcePreferences(),
    ): List<SourceCandidate> {
        val preferred = prefs.preferredLanguages.map { it.lowercase() }
        fun langIndex(c: SourceCandidate): Int =
            preferred.indexOf(c.language?.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
        fun tier(c: SourceCandidate): Int {
            val good = c.quality.isAtLeast(prefs.goodQuality)
            val pref = langIndex(c) != Int.MAX_VALUE
            return when {
                good && pref -> 0
                good -> 1
                pref -> 2
                else -> 3
            }
        }

        val byQualityThenLanguage = compareByDescending<SourceCandidate> { it.quality.rank }
            .thenBy { it.stability }
            .thenBy(::langIndex)

        return if (isFavorite) {
            candidates.sortedWith(compareBy<SourceCandidate>(::tier).then(byQualityThenLanguage))
        } else {
            candidates.sortedWith(byQualityThenLanguage)
        }
    }
}
