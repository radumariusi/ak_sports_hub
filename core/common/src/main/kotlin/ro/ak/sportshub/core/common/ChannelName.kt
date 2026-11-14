package ro.ak.sportshub.core.common

import java.text.Normalizer
import ro.ak.sportshub.core.model.Quality

/**
 * Rezultatul analizei numelui unui canal din playlist.
 *
 * [groupKey] servește doar la gruparea variantelor aceluiași canal („Sky Sport Main Event”
 * UHD / FHD / HD / SD) și nu se afișează. În interfață apar [displayName] (numele original)
 * și eticheta [quality], ca să vezi mereu ce calitate alegi.
 */
data class ChannelName(
    val displayName: String,
    val groupKey: String,
    val quality: Quality,
    val country: String?,
)

object ChannelNameParser {

    private val countryPrefix = Regex("""^\s*\|?\s*([A-Za-z]{2,3})\s*[|:]\s*""")

    private val superscripts = mapOf(
        "ᵁᴴᴰ" to " UHD ", "ᶠᴴᴰ" to " FHD ", "ᴴᴰ" to " HD ", "ˢᴰ" to " SD ",
        "ᴿᴬᵂ" to " RAW ", "⁴ᴷ" to " 4K ", "ᴴᴱⱽᶜ" to " HEVC ", "ᵛᴵᴾ" to " VIP ",
    )

    private val qualityTokens: List<Pair<Regex, Quality>> = listOf(
        Regex("""\b(4k|uhd|2160p?)\b""") to Quality.UHD,
        Regex("""\b(fhd|1080[pi]?|full ?hd)\b""") to Quality.FHD,
        Regex("""\b(hd|720p?)\b""") to Quality.HD,
        Regex("""\b(sd|576[pi]?|480[pi]?)\b""") to Quality.SD,
    )

    /** Cuvinte care nu deosebesc canalele între ele (codec, cadre, marcaje de furnizor). */
    private val noise = Regex(
        """\b(4k|uhd|2160p?|fhd|full ?hd|1080[pi]?|hd|720p?|sd|576[pi]?|480[pi]?|hevc|h\.?26[45]|""" +
            """\d{2,3} ?fps|raw|vip|backup|multi ?audio|hq|lq)\b""",
    )

    fun parse(rawName: String, groupTitle: String? = null): ChannelName {
        var name = rawName.trim()
        superscripts.forEach { (k, v) -> name = name.replace(k, v) }

        var country: String? = null
        countryPrefix.find(name)?.let {
            country = it.groupValues[1].uppercase()
            name = name.substring(it.range.last + 1)
        }
        if (country == null && groupTitle != null) {
            countryPrefix.find(groupTitle)?.let { country = it.groupValues[1].uppercase() }
        }

        val lower = fold(name)
        val quality = qualityTokens.firstOrNull { (re, _) -> re.containsMatchIn(lower) }?.second
            ?: Quality.UNKNOWN

        val core = lower
            .replace(Regex("""\(\d+\)|\[\d+]"""), " ")
            .replace(noise, " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        val key = listOfNotNull(country?.lowercase(), core.ifEmpty { lower.trim() }).joinToString(":")
        return ChannelName(displayName = rawName.trim(), groupKey = key, quality = quality, country = country)
    }

    /** Fără diacritice, litere mici. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
}
