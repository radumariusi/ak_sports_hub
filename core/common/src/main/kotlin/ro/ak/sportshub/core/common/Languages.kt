package ro.ak.sportshub.core.common

/**
 * Limba audio a unei variante, dedusă doar din metadatele playlistului:
 * atributul `tvg-language`, apoi prefixul de țară al canalului sau al grupului.
 * Nu se citește niciodată stream-ul.
 */
object Languages {

    private val byName = mapOf(
        "english" to "en", "engleza" to "en", "eng" to "en",
        "romanian" to "ro", "romana" to "ro", "rom" to "ro", "rum" to "ro",
        "german" to "de", "deutsch" to "de", "ger" to "de", "deu" to "de",
        "french" to "fr", "francais" to "fr", "fra" to "fr", "fre" to "fr",
        "italian" to "it", "italiano" to "it", "ita" to "it",
        "spanish" to "es", "espanol" to "es", "spa" to "es",
        "portuguese" to "pt", "portugues" to "pt", "por" to "pt",
        "dutch" to "nl", "nederlands" to "nl", "nld" to "nl", "dut" to "nl",
        "hungarian" to "hu", "magyar" to "hu", "hun" to "hu",
        "polish" to "pl", "polski" to "pl", "pol" to "pl",
        "turkish" to "tr", "turkce" to "tr", "tur" to "tr",
        "greek" to "el", "gre" to "el", "ell" to "el",
        "arabic" to "ar", "ara" to "ar",
        "russian" to "ru", "rus" to "ru",
        "serbian" to "sr", "srp" to "sr",
        "croatian" to "hr", "hrv" to "hr",
        "bulgarian" to "bg", "bul" to "bg",
        "czech" to "cs", "ces" to "cs", "cze" to "cs",
        "albanian" to "sq", "sqi" to "sq", "alb" to "sq",
        "swedish" to "sv", "swe" to "sv",
        "danish" to "da", "dan" to "da",
        "norwegian" to "no", "nor" to "no",
        "finnish" to "fi", "fin" to "fi",
    )

    /** Prefixe de țară (sau de limbă) folosite des de furnizori → limba principală. */
    private val byCountry = mapOf(
        "UK" to "en", "GB" to "en", "EN" to "en", "US" to "en", "USA" to "en", "IE" to "en",
        "CA" to "en", "AU" to "en", "NZ" to "en",
        "RO" to "ro", "MD" to "ro",
        "DE" to "de", "AT" to "de", "GER" to "de",
        "FR" to "fr", "IT" to "it", "ES" to "es", "PT" to "pt", "BR" to "pt",
        "NL" to "nl", "HU" to "hu", "PL" to "pl", "TR" to "tr", "GR" to "el",
        "AR" to "ar", "RU" to "ru", "RS" to "sr", "HR" to "hr", "BG" to "bg",
        "CZ" to "cs", "AL" to "sq", "SE" to "sv", "DK" to "da", "NO" to "no", "FI" to "fi",
    )

    /** Codurile care nu spun limba („und”, „mul”, „qaa”…): se ignoră. */
    private val unknown = setOf("und", "mul", "mis", "zxx", "qaa", "unk", "none", "nar", "qad")

    /**
     * Limba declarată de o pistă audio a stream-ului („eng”, „en-US”, „rum”, „English”) → codul ISO 639-1 („en”);
     * null pentru „und” și ce nu se recunoaște. Pistele sunt cele pe care playerul le citește oricum la redare.
     */
    fun code(raw: String?): String? {
        val t = raw?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() } ?: return null
        if (t in unknown) return null
        if (t.length == 2 && t.all { it in 'a'..'z' }) return if (t == "iw") "he" else t
        return byName[ChannelNameParser.fold(t)] ?: when (t) {
            "ron", "mol" -> "ro"
            "heb" -> "he"
            "ukr" -> "uk"
            "slv" -> "sl"
            "slk", "slo" -> "sk"
            "mkd", "mac" -> "mk"
            "bos" -> "bs"
            "kor" -> "ko"
            "jpn" -> "ja"
            "chi", "zho" -> "zh"
            "hin" -> "hi"
            "per", "fas" -> "fa"
            "cat" -> "ca"
            else -> null
        }
    }

    /**
     * @param tvgLanguage valoarea `tvg-language` (de exemplu „English”, „ro”, „Romanian;English”)
     * @param country prefixul de țară găsit de [ChannelNameParser] (de exemplu „UK”)
     * @return codul ISO 639-1 sau null dacă playlistul nu spune nimic
     */
    fun guess(tvgLanguage: String?, country: String?): String? {
        tvgLanguage
            ?.split(';', ',', '/', '|')
            ?.map { ChannelNameParser.fold(it).trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?.let { first ->
                if (first.length == 2 && first.all { it in 'a'..'z' }) return first
                byName[first]?.let { return it }
            }
        return country?.uppercase()?.let { byCountry[it] }
    }
}
