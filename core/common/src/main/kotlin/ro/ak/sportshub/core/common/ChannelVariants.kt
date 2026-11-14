package ro.ak.sportshub.core.common

import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

/** Variantele aceluiași canal („Sky Sport Main Event” 4K / FHD / HD / SD, din toate playlisturile). */
data class ChannelFamily<T>(val key: String, val members: List<T>)

object ChannelFamilies {

    /**
     * Strânge elementele cu aceeași cheie de grupare, în ordinea primei apariții.
     * Cheia vine din `ChannelNameParser.groupKey` (sau dintr-o corecție manuală) și nu se afișează.
     */
    fun <T> collapse(items: List<T>, key: (T) -> String): List<ChannelFamily<T>> {
        val map = LinkedHashMap<String, MutableList<T>>()
        for (item in items) map.getOrPut(key(item)) { ArrayList(2) } += item
        return map.map { (k, v) -> ChannelFamily(k, v) }
    }
}

object VariantOrder {

    /**
     * Ordinea variantelor unui canal, folosită în liste, la OK pe un canal grupat și la failover:
     * 1. varianta preferată (aleasă de tine);
     * 2. cele care n-au pornit ultima oară coboară la final (nu dispar);
     * 3. rezoluția, de la 4K în jos;
     * 4. la aceeași rezoluție, limbile preferate (în ordinea din setări).
     * La egalitate rămâne ordinea primită (playlisturi, apoi poziția din playlist).
     */
    fun <T> sort(
        items: List<T>,
        quality: (T) -> Quality,
        language: (T) -> String?,
        isPreferred: (T) -> Boolean = { false },
        failedRecently: (T) -> Boolean = { false },
        prefs: SourcePreferences = SourcePreferences(),
    ): List<T> {
        val preferred = prefs.preferredLanguages.map { it.lowercase() }
        fun langIndex(item: T): Int =
            preferred.indexOf(language(item)?.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
        return items.sortedWith(
            compareBy<T> { if (isPreferred(it)) 0 else 1 }
                .thenBy { if (failedRecently(it)) 1 else 0 }
                .thenByDescending { quality(it).rank }
                .thenBy(::langIndex),
        )
    }
}

/**
 * Failover-ul secvențial: când varianta curentă nu pornește, playerul trece la următoarea variantă
 * a aceluiași canal, una câte una (stream-ul vechi oprit înainte), fără să încerce de două ori aceeași.
 */
class FailoverPlan<T>(order: List<T>, current: T) {

    private val order: List<T> = order.distinct()
    private val tried = linkedSetOf(current)

    /** Câte variante are canalul (cu cea curentă). */
    val size: Int get() = (order + tried).distinct().size

    /** Varianta următoare neîncercată, sau null dacă s-au încercat toate. */
    fun next(): T? = order.firstOrNull { it !in tried }

    /** Marchează varianta pornită acum. */
    fun moveTo(item: T) {
        tried += item
    }

    /** Câte variante s-au încercat până acum (inclusiv cea curentă). */
    val triedCount: Int get() = tried.size

    companion object {
        /** După câte reîncercări eșuate ale aceleiași variante se trece la următoarea. */
        const val ATTEMPTS_BEFORE_SWITCH = 2
    }
}
