package ro.ak.sportshub.core.model

/** Ce deducem din playlist despre o variantă: cheia de grupare, calitatea și limba. */
data class ChannelLabels(
    val groupKey: String,
    val quality: Quality,
    val language: String?,
)

/**
 * O corecție manuală a unei variante (de exemplu eticheta de calitate e greșită în playlist sau
 * două canale trebuie unite / separate). Câmpurile null păstrează valoarea din playlist.
 * Corecțiile se păstrează și se aplică din nou la fiecare actualizare a playlistului.
 */
data class ChannelCorrection(
    /** Altă cheie de grupare: cheia canalului cu care se unește, sau o cheie proprie (separare). */
    val groupKey: String? = null,
    val quality: Quality? = null,
    /** Codul limbii (ISO 639-1); șirul gol înseamnă „limbă necunoscută”. */
    val language: String? = null,
) {
    val isEmpty: Boolean get() = groupKey == null && quality == null && language == null

    fun applyTo(labels: ChannelLabels): ChannelLabels = ChannelLabels(
        groupKey = groupKey ?: labels.groupKey,
        quality = quality ?: labels.quality,
        language = when (language) {
            null -> labels.language
            "" -> null
            else -> language
        },
    )

    companion object {
        /** Cheia proprie a unei variante scoase din grupul ei. */
        fun separateKey(playlistId: Long, name: String): String = "manual:$playlistId:$name"
    }
}
