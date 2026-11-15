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

    private val bracketNumber = Regex("""\(\d+\)|\[\d+]""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")
    private val spaces = Regex("""\s+""")
    private val marks = Regex("""\p{M}+""")

    fun parse(rawName: String, groupTitle: String? = null): ChannelName {
        var name = rawName.trim()
        // Literele mici de sus (ᴴᴰ, ⁴ᴷ…) sunt toate după U+02B0: numele obișnuite sar peste înlocuiri.
        if (name.any { it.code >= 0x2B0 }) superscripts.forEach { (k, v) -> name = name.replace(k, v) }

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
            .replace(bracketNumber, " ")
            .replace(noise, " ")
            .replace(nonAlnum, " ")
            .trim()
            .replace(spaces, " ")

        val key = listOfNotNull(country?.lowercase(), core.ifEmpty { lower.trim() }).joinToString(":")
        return ChannelName(displayName = rawName.trim(), groupKey = key, quality = quality, country = country)
    }

    /** Fără diacritice, litere mici. */
    fun fold(text: String): String {
        // Textul ASCII (cel mai des) nu are diacritice: doar litere mici, fără normalizare Unicode.
        if (text.all { it.code < 0x80 }) return text.lowercase()
        return marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
    }
}
