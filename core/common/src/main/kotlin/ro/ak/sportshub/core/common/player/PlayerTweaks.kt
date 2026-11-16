package ro.ak.sportshub.core.common.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Întârzierea sunetului pe canal (B2): unele canale au sunetul defazat față de imagine. Se reglează în pași de
 * [STEP_MS], între [MIN_MS] și [MAX_MS]; pozitiv = sunetul vine mai târziu (imaginea era în urmă), negativ =
 * sunetul vine mai devreme.
 */
object AudioDelay {
    const val STEP_MS = 50
    const val MIN_MS = -1_000
    const val MAX_MS = 1_000

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /** Valorile din fereastra de alegere, din 50 în 50 ms. */
    val options: List<Int> = (MIN_MS..MAX_MS step STEP_MS).toList()

    fun label(ms: Int): String = when {
        ms == 0 -> "Fără întârziere"
        ms > 0 -> "+$ms ms (sunetul mai târziu)"
        else -> "$ms ms (sunetul mai devreme)"
    }

    fun short(ms: Int): String = if (ms == 0) "0 ms" else (if (ms > 0) "+" else "") + "$ms ms"
}

/** Cum umple imaginea ecranul (B5), pe canal. */
enum class VideoFit(val label: String, val hint: String) {
    FIT("Încadrat", "toată imaginea, cu benzi negre dacă e nevoie"),
    FILL("Umple ecranul", "imaginea întinsă pe tot ecranul (bun la 4:3 pus în 16:9)"),
    CROP("Decupat", "mărită până dispar benzile negre, marginile se taie"),
    ;

    companion object {
        fun of(name: String?): VideoFit = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/**
 * Pista audio aleasă pe un canal (B3): limba și eticheta ei, ca s-o alegem singuri data viitoare. Stream-urile
 * IPTV nu păstrează ordinea pistelor între porniri, deci se caută după limbă și etichetă, nu după poziție.
 */
data class AudioChoice(val language: String?, val label: String?) {

    /**
     * Poziția pistei potrivite din [tracks] (limbă, etichetă) sau null. Întâi limba și eticheta, apoi doar limba
     * (dacă e una reală, nu „und”), apoi doar eticheta.
     */
    fun pick(tracks: List<Pair<String?, String?>>): Int? {
        val lang = norm(language)
        val lab = label?.trim()?.lowercase()?.ifEmpty { null }
        tracks.indexOfFirst { (l, b) -> norm(l) == lang && b?.trim()?.lowercase()?.ifEmpty { null } == lab }
            .takeIf { it >= 0 && (lang != null || lab != null) }?.let { return it }
        if (lang != null) tracks.indexOfFirst { norm(it.first) == lang }.takeIf { it >= 0 }?.let { return it }
        if (lab != null) tracks.indexOfFirst { it.second?.trim()?.lowercase() == lab }.takeIf { it >= 0 }?.let { return it }
        return null
    }

    /** Pentru preferințe: „limbă\u0001etichetă”. */
    fun encode(): String = (language ?: "") + SEP + (label ?: "")

    companion object {
        private const val SEP = '\u0001'

        fun decode(text: String?): AudioChoice? {
            if (text.isNullOrEmpty() || SEP !in text) return null
            val l = text.substringBefore(SEP).ifEmpty { null }
            val b = text.substringAfter(SEP).ifEmpty { null }
            return if (l == null && b == null) null else AudioChoice(l, b)
        }

        /** „ro”, „ron”, „rum” → „ro”; „und”, gol → null. */
        fun norm(language: String?): String? {
            val l = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.ifEmpty { null } ?: return null
            if (l == "und" || l == "mul" || l == "zxx") return null
            return THREE[l] ?: l
        }

        private val THREE = mapOf(
            "ron" to "ro", "rum" to "ro", "eng" to "en", "spa" to "es", "ita" to "it", "ger" to "de", "deu" to "de",
            "fre" to "fr", "fra" to "fr", "por" to "pt", "hun" to "hu", "pol" to "pl", "tur" to "tr", "ara" to "ar",
            "rus" to "ru", "gre" to "el", "ell" to "el", "dut" to "nl", "nld" to "nl", "srp" to "sr", "hrv" to "hr",
            "bul" to "bg", "cze" to "cs", "ces" to "cs", "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi",
        )
    }
}

/**
 * Modul de noapte (B4): comprimă dinamica sunetului (comentatorul tare coboară, publicul și vocile încete urcă),
 * pe PCM 16 biți intercalat. Anvelopa e comună tuturor canalelor (stereo rămâne echilibrat), atacul e rapid,
 * revenirea lentă, iar vârfurile nu trec de [CEILING] (fără distorsiuni prin tăiere).
 */
class NightCompressor(sampleRate: Int, private val channels: Int) {

    private val attack = coefficient(sampleRate, ATTACK_MS)
    private val release = coefficient(sampleRate, RELEASE_MS)
    private var envelope = 0.0

    /** Prelucrează [count] eșantioane din [samples] (toate canalele), pe loc. */
    fun process(samples: ShortArray, count: Int = samples.size) {
        val ch = channels.coerceAtLeast(1)
        var i = 0
        while (i + ch <= count) {
            var peak = 0.0
            for (c in 0 until ch) peak = maxOf(peak, abs(samples[i + c].toDouble()) / FULL)
            envelope = if (peak > envelope) attack * envelope + (1 - attack) * peak else release * envelope + (1 - release) * peak
            val gain = gainFor(envelope, peak)
            for (c in 0 until ch) {
                val v = samples[i + c] * gain
                samples[i + c] = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            }
            i += ch
        }
    }

    fun reset() {
        envelope = 0.0
    }

    companion object {
        const val THRESHOLD_DB = -30.0
        const val RATIO = 4.0
        const val MAKEUP_DB = 9.0
        /** Vârful maxim după câștig (−1 dBFS). */
        const val CEILING = 0.89
        const val ATTACK_MS = 5.0
        const val RELEASE_MS = 250.0
        private const val FULL = 32_768.0
        /** Sub −70 dBFS e liniște: nu se ridică (fără zgomot de fond amplificat). */
        private const val SILENCE = 0.000316

        private fun coefficient(rate: Int, ms: Double): Double = exp(-1.0 / (rate.coerceAtLeast(8_000) * ms / 1_000.0))

        /** Câștigul (liniar) pentru anvelopa [env] și vârful curent [peak], ambele în 0..1. */
        fun gainFor(env: Double, peak: Double): Double {
            if (env < SILENCE) return 1.0
            val level = 20 * log10(env)
            val over = level - THRESHOLD_DB
            val db = (if (over > 0) -over * (1 - 1 / RATIO) else 0.0) + MAKEUP_DB
            val gain = 10.0.pow(db / 20)
            return if (peak * gain > CEILING) CEILING / peak else gain
        }
    }
}
