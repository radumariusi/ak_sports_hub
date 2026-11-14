package ro.ak.sportshub.core.common.player

import java.util.Locale

/** Numele pistelor audio și de subtitrare din player, din ce declară stream-ul. */
object TrackLabels {

    private val romanian = Locale.forLanguageTag("ro")

    fun audio(label: String?, language: String?, channelCount: Int, mimeType: String?): String {
        val parts = buildList {
            add(label?.trim()?.ifEmpty { null } ?: languageName(language) ?: "Pistă necunoscută")
            channels(channelCount)?.let(::add)
            codec(mimeType)?.let(::add)
        }
        return parts.distinct().joinToString(" · ")
    }

    fun text(label: String?, language: String?): String =
        label?.trim()?.ifEmpty { null } ?: languageName(language) ?: "Subtitrare"

    fun languageName(code: String?): String? {
        val c = code?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        if (c == "und" || c == "unknown") return null
        val name = Locale.forLanguageTag(twoLetter(c)).getDisplayLanguage(romanian)
        return if (name.isBlank() || name.equals(c, ignoreCase = true)) c.uppercase()
        else name.replaceFirstChar { it.titlecase(romanian) }
    }

    /** Codurile din trei litere (`ron`, `rum`, `ger`…) devin coduri din două litere. */
    private fun twoLetter(code: String): String {
        val base = code.substringBefore('-').substringBefore('_')
        if (base.length != 3) return base
        return bibliographic[base] ?: iso3.getOrPut(base) {
            Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language }.getOrNull() == base } ?: base
        }
    }

    private val iso3 = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val bibliographic = mapOf(
        "rum" to "ro", "ger" to "de", "fre" to "fr", "dut" to "nl", "cze" to "cs", "slo" to "sk",
        "gre" to "el", "chi" to "zh", "per" to "fa", "alb" to "sq", "arm" to "hy", "baq" to "eu",
        "geo" to "ka", "ice" to "is", "mac" to "mk", "may" to "ms", "wel" to "cy", "bur" to "my",
    )

    fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "mono"
        count == 2 -> "stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count canale"
    }

    fun codec(mimeType: String?): String? = when (mimeType?.lowercase()) {
        null -> null
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC3"
        "audio/ac4" -> "AC4"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS"
        "audio/mpeg", "audio/mpeg-l2" -> "MPEG"
        "audio/opus" -> "Opus"
        "audio/true-hd" -> "TrueHD"
        "audio/flac" -> "FLAC"
        else -> null
    }
}
