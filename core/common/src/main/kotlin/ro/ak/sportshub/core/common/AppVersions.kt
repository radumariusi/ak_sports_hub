package ro.ak.sportshub.core.common

/**
 * Versiunile APK-ului: „1.1.N” (înainte: „1.0.N”), unde N e numărul build-ului din GitHub Actions (crește la fiecare build).
 * Release-ul unic „ultima-versiune” are titlul „AK Sports Hub v1.1.N (build N)” (mai vechi: „v1.0.N” sau „(build N)”); „build N” îl citesc și versiunile 1.0.
 */
object AppVersions {
    private val patterns = listOf(Regex("""v?1\.\d+\.(\d+)"""), Regex("""build\s+(\d+)""", RegexOption.IGNORE_CASE))

    /** Numărul build-ului din titlul release-ului, sau null. */
    fun fromTitle(title: String?): Int? =
        title?.let { t -> patterns.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.toIntOrNull() } }

    fun name(code: Int): String = "1.1.$code"
}
