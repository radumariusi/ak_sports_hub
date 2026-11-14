package ro.ak.sportshub.core.model

/**
 * Preferințele de limbă și calitate pentru sursele unui meci (setări în aplicație).
 *
 * Nicio sursă nu e ascunsă; preferințele decid doar ordinea.
 * - Meciuri favorite: limbile preferate la calitate bună ([goodQuality] sau mai mult), apoi alte limbi
 *   la calitate bună, apoi limbile preferate sub prag, apoi restul.
 * - Alte meciuri: primează rezoluția, în orice limbă.
 */
data class SourcePreferences(
    val preferredLanguages: List<String> = listOf("en", "ro"),
    val goodQuality: Quality = Quality.FHD,
)
