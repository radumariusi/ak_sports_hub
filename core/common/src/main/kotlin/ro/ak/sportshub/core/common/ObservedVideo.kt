package ro.ak.sportshub.core.common

import kotlin.math.abs
import kotlin.math.roundToInt
import ro.ak.sportshub.core.model.Quality

/**
 * Ce a văzut playerul la ultima redare a unei variante: rezoluția și cadrele pe secundă, citite din decodor
 * (stream-ul se reda oricum, deci nicio cerere în plus). [fps] e null când stream-ul nu-l declară.
 */
data class ObservedVideo(val width: Int, val height: Int, val fps: Float? = null, val at: Long = 0) {

    /** Calitatea după rezoluție (și după lățime, pentru imaginile anamorfice: 1440×1080 tot FHD e). */
    val quality: Quality
        get() = when {
            height >= 2000 || width >= 3600 -> Quality.UHD
            height >= 1000 || width >= 1800 -> Quality.FHD
            height >= 700 || width >= 1200 -> Quality.HD
            height > 0 -> Quality.SD
            else -> Quality.UNKNOWN
        }

    /** „50p”, „60p” (59,94 → 60p), sau null. */
    val fpsLabel: String? get() = fps?.takeIf { it in 5f..240f }?.let { "${it.roundToInt()}p" }

    /** „FHD 50p” sau „FHD”. */
    val label: String get() = listOfNotNull(quality.label, fpsLabel).joinToString(" ")
}

/** Cum se arată eticheta de calitate a unei variante, după ce s-a văzut la redare. */
data class QualityView(
    /** Calitatea folosită în ordine și în etichetă. */
    val quality: Quality,
    /** Textul etichetei („FHD 50p”). */
    val label: String,
    /** Rezoluția redată bate cu eticheta din playlist. */
    val verified: Boolean = false,
    /** Eticheta din playlist, când nu bate cu ce s-a văzut (se arată tăiată lângă cea reală). */
    val playlistLabel: String? = null,
)

/**
 * Rezoluția reală văzută la redare, fără verificări separate: se notează doar ce redă playerul oricum.
 * - O corecție manuală de calitate are prioritate: eticheta rămâne cea aleasă de tine.
 * - Dacă rezoluția bate cu eticheta din playlist, eticheta primește „verificat”.
 * - Dacă nu bate, eticheta reală înlocuiește eticheta din playlist (care apare tăiată), iar ordinea variantelor
 *   folosește rezoluția reală. Variantele fără etichetă în playlist primesc eticheta reală.
 */
object ObservedQuality {

    fun view(label: Quality, manual: Boolean, observed: ObservedVideo?): QualityView {
        if (manual || observed == null || observed.quality == Quality.UNKNOWN) return QualityView(label, label.label)
        val real = observed.quality
        return when {
            real == label -> QualityView(real, observed.label, verified = true)
            label == Quality.UNKNOWN -> QualityView(real, observed.label)
            else -> QualityView(real, observed.label, playlistLabel = label.label)
        }
    }

    /** Calitatea pentru ordine: cea aleasă de tine, altfel cea văzută, altfel cea din playlist. */
    fun effective(label: Quality, manual: Boolean, observed: ObservedVideo?): Quality = view(label, manual, observed).quality

    /**
     * Cadrele pe secundă din formatul decodat: valorile ciudate (0, negative, „necunoscut”) se ignoră,
     * iar 59,94 / 29,97 / 23,976 rămân așa (contează la potrivirea frecvenței ecranului).
     */
    fun cleanFps(fps: Float?): Float? = fps?.takeIf { it.isFinite() && it in 5f..240f }

    /** Două observații ale aceleiași variante sunt practic la fel (nu merită rescrise). */
    fun same(a: ObservedVideo?, b: ObservedVideo): Boolean =
        a != null && a.width == b.width && a.height == b.height &&
            ((a.fps == null && b.fps == null) || (a.fps != null && b.fps != null && abs(a.fps - b.fps) < 0.5f))
}

/** Un mod de afișare al ecranului (pentru potrivirea frecvenței de cadre). */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Potrivirea frecvenței ecranului cu cadrele stream-ului (50 Hz pentru 25/50p, 60 Hz pentru 30/60p,
 * 59,94 pentru 29,97…), ca mișcarea să fie fluidă la sport. Rezoluția ecranului nu se schimbă.
 */
object RefreshRateMatch {

    /**
     * Modul ales pentru [fps] dintre [modes], cu aceeași rezoluție ca [current]: frecvențele care sunt un
     * multiplu întreg al cadrelor; dintre ele, cea mai apropiată de frecvența actuală. Null dacă modul curent
     * se potrivește deja sau nu există niciunul potrivit.
     */
    fun pick(modes: List<DisplayModeInfo>, current: DisplayModeInfo, fps: Float?): DisplayModeInfo? {
        val f = ObservedQuality.cleanFps(fps) ?: return null
        if (fits(current.refreshRate, f)) return null
        return modes.asSequence()
            .filter { it.width == current.width && it.height == current.height && fits(it.refreshRate, f) }
            .minByOrNull { abs(it.refreshRate - current.refreshRate) }
    }

    /** Frecvența [refresh] e un multiplu întreg al cadrelor [fps] (cu o toleranță de 0,05%: 59,94 ≠ 60). */
    fun fits(refresh: Float, fps: Float): Boolean {
        if (refresh <= 0f || fps <= 0f) return false
        val ratio = refresh / fps
        val k = ratio.roundToInt()
        return k >= 1 && abs(ratio - k) / k < 0.0005f
    }
}
