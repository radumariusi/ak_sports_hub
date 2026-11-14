package ro.ak.sportshub.core.model

/**
 * Preferințele de limbă și calitate pentru sursele unui meci (setări în aplicație).
 *
 * Implicit: pentru meciurile favorite doar engleză sau română; pentru restul tot engleză
 * sau română, dar dacă nu există nicio sursă în aceste limbi de cel puțin [minQualityBeforeOtherLanguages],
 * se acceptă și alte limbi.
 */
data class SourcePreferences(
    val preferredLanguages: List<String> = listOf("en", "ro"),
    val favoritesPreferredOnly: Boolean = true,
    val minQualityBeforeOtherLanguages: Quality = Quality.FHD,
)
