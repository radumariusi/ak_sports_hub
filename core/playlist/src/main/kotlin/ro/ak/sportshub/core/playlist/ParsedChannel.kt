package ro.ak.sportshub.core.playlist

/**
 * O intrare live, așa cum vine din playlist (M3U sau Xtream), înainte de îmbogățire.
 * Conține date de acces în [streamUrl]: nu se loghează.
 */
data class ParsedChannel(
    val name: String,
    val group: String?,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val tvgLanguage: String? = null,
    val tvgCountry: String? = null,
    val catchupDays: Int? = null,
    val userAgent: String? = null,
    /** Numărul canalului declarat de playlist (`tvg-chno` / `num` la Xtream). */
    val number: Int? = null,
)

/** Informații din antetul `#EXTM3U` sau din contul Xtream. */
data class PlaylistHeader(
    /** EPG-urile declarate de playlist (`url-tvg`, `x-tvg-url`). Se folosesc în etapa 4. */
    val epgUrls: List<String> = emptyList(),
)
