package ro.ak.sportshub.core.common

/**
 * Versiunile APK-ului: „1.0.N”, unde N e numărul build-ului din GitHub Actions (crește la fiecare build).
 * Release-ul unic „ultima-versiune” are titlul „AK Sports Hub v1.0.N” (sau, mai vechi, „… (build N)”).
 */
object AppVersions {
    private val patterns = listOf(Regex("""v?1\.0\.(\d+)"""), Regex("""build\s+(\d+)""", RegexOption.IGNORE_CASE))

    /** Numărul build-ului din titlul release-ului, sau null. */
    fun fromTitle(title: String?): Int? =
        title?.let { t -> patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.toIntOrNull() } }

    fun name(code: Int): String = "1.0.$code"
}
