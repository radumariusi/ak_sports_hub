package ro.ak.sportshub.core.epg

import java.util.concurrent.TimeUnit

/** O emisiune, așa cum o arată interfața. */
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
) {
    fun isOnAir(now: Long) = start <= now && now < stop

    /** Cât s-a scurs din emisiune, între 0 și 1. */
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** Emisiunea curentă și cea următoare ale unui canal. */
data class NowNext(val now: Programme?, val next: Programme?) {
    companion object {
        /** [programmes] sortate după început; se aleg cea în desfășurare și prima de după ea. */
        fun of(programmes: List<Programme>, at: Long): NowNext {
            val current = programmes.firstOrNull { it.isOnAir(at) }
            val next = programmes.firstOrNull { it.start >= (current?.stop ?: at) && it !== current }
            return NowNext(current, next)
        }
    }
}

/** Setările de actualizare ale EPG-ului. */
data class EpgUpdatePolicy(
    /** La câte ore se descarcă din nou; 0 = doar manual. */
    val intervalHours: Int = 12,
    /** Câte zile înainte se păstrează. */
    val daysAhead: Int = 3,
    /** Câte ore în urmă (pentru „ce a fost”). */
    val hoursBack: Int = 24,
) {
    fun window(now: Long): LongRange =
        (now - TimeUnit.HOURS.toMillis(hoursBack.toLong()))..(now + TimeUnit.DAYS.toMillis(daysAhead.toLong()))

    /**
     * Trebuie descărcată sursa? Da dacă nu a reușit niciodată sau a trecut intervalul;
     * după o eroare, cel mult o încercare pe oră.
     */
    fun isDue(lastUpdated: Long?, lastAttempt: Long?, failed: Boolean, now: Long): Boolean {
        if (intervalHours <= 0 && lastUpdated != null) return false
        if (failed && lastAttempt != null && now - lastAttempt < TimeUnit.HOURS.toMillis(1)) return false
        return lastUpdated == null || now - lastUpdated >= TimeUnit.HOURS.toMillis(intervalHours.toLong())
    }

    companion object {
        val INTERVALS = listOf(6, 12, 24, 48, 0)
        val DAYS = listOf(1, 2, 3, 5, 7)
    }
}
