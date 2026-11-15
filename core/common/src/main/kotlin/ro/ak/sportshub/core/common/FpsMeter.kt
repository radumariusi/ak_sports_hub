package ro.ak.sportshub.core.common

/**
 * Cadrele pe secundă ale unui stream care nu le declară (des la TS de IPTV), măsurate din orele cadrelor redate:
 * mediana intervalelor dintre [SAMPLES] cadre, rotunjită la frecvența standard cea mai apropiată (25, 50, 29,97…).
 * Fără ea, potrivirea frecvenței ecranului și avertismentul „cadrele nu se potrivesc” nu aveau ce compara.
 */
class FpsMeter {
    private val deltas = LongArray(SAMPLES)
    private var count = 0
    private var last = Long.MIN_VALUE

    /** Rezultatul, după ce s-au strâns destule cadre; null până atunci. */
    var fps: Float? = null
        private set

    @Synchronized
    fun reset() {
        count = 0
        last = Long.MIN_VALUE
        fps = null
    }

    /** Ora unui cadru (microsecunde); întoarce fps-ul exact în momentul în care s-a putut calcula, altfel null. */
    @Synchronized
    fun add(presentationTimeUs: Long): Float? {
        if (fps != null) return null
        val prev = last
        last = presentationTimeUs
        if (prev == Long.MIN_VALUE) return null
        val d = presentationTimeUs - prev
        // Salturi (discontinuități, cadre aruncate de sursă) nu intră în calcul.
        if (d <= 0 || d > 200_000) return null
        deltas[count++] = d
        if (count < SAMPLES) return null
        val median = deltas.sorted()[SAMPLES / 2]
        return snap(1_000_000f / median).also { fps = it }
    }

    companion object {
        const val SAMPLES = 60
        private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)

        fun snap(raw: Float): Float? {
            if (!raw.isFinite() || raw < 5f || raw > 240f) return null
            val near = STANDARD.minBy { kotlin.math.abs(it - raw) }
            return if (kotlin.math.abs(near - raw) <= near * 0.03f) near else raw
        }
    }
}
