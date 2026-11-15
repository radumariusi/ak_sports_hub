package ro.ak.sportshub.core.epg

import java.util.concurrent.TimeUnit

/**
 * Ghidul în grilă din Live TV (F6): o fereastră de timp ([HOURS] ore), care începe la jumătatea de oră de dinainte
 * de acum și se mută cu câte o oră (◀ ▶). Fiecare canal e un rând, iar emisiunile sunt blocuri cu lățimea după durată.
 */
object GuideGrid {

    const val HOURS = 3
    private val HALF_HOUR = TimeUnit.MINUTES.toMillis(30)
    val STEP_MS: Long = TimeUnit.HOURS.toMillis(1)
    val SPAN_MS: Long = TimeUnit.HOURS.toMillis(HOURS.toLong())

    /** Câte ore poți merge înainte (cât ține de obicei EPG-ul păstrat). */
    const val MAX_SHIFT_HOURS = 48

    /** Începutul ferestrei: jumătatea de oră dinainte de [now], plus [shiftHours] ore. */
    fun windowStart(now: Long, shiftHours: Int): Long = now - Math.floorMod(now, HALF_HOUR) + shiftHours * STEP_MS

    /** Marcajele de timp din capul grilei (din jumătate în jumătate de oră). */
    fun ticks(from: Long): List<Long> = (0 until HOURS * 2).map { from + it * HALF_HOUR }

    /** Un bloc din rând: [start] și [end] ca fracțiuni din fereastră (0–1); [programme] null = gol în program. */
    data class Block(val start: Float, val end: Float, val programme: Programme?)

    /**
     * Blocurile unui canal în fereastra [from, from + SPAN_MS): emisiunile tăiate la margini, golurile dintre ele ca
     * blocuri goale. [programmes] în orice ordine; suprapunerile se rezolvă în favoarea celei care începe prima.
     */
    fun blocks(programmes: List<Programme>, from: Long, span: Long = SPAN_MS): List<Block> {
        val until = from + span
        val out = ArrayList<Block>()
        var cursor = from
        for (p in programmes.sortedBy { it.start }) {
            if (p.stop <= cursor || p.start >= until) continue
            val s = maxOf(p.start, cursor)
            if (s > cursor) out += Block(frac(cursor, from, span), frac(s, from, span), null)
            val e = minOf(p.stop, until)
            out += Block(frac(s, from, span), frac(e, from, span), p)
            cursor = e
            if (cursor >= until) break
        }
        if (cursor < until) out += Block(frac(cursor, from, span), 1f, null)
        return out
    }

    private fun frac(t: Long, from: Long, span: Long): Float = ((t - from).toFloat() / span).coerceIn(0f, 1f)
}
