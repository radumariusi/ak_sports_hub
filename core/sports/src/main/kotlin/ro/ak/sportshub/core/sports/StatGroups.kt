package ro.ak.sportshub.core.sports

/**
 * Statisticile meciului pe grupuri, ca în aplicațiile de statistici: posesia sus, apoi atacul, pasele, apărarea și
 * disciplina; fiecare rând cu partea fiecărei echipe (pentru bara comparativă). Fără rețea, doar din ce s-a primit.
 */
object StatGroups {

    data class Row(val line: StatLine, val homeValue: Double?, val awayValue: Double?) {
        /** Partea gazdelor din total (0–1), pentru bară; null dacă valorile nu sunt numere. */
        val homeShare: Float?
            get() {
                val h = homeValue ?: return null
                val a = awayValue ?: return null
                if (h < 0 || a < 0 || !h.isFinite() || !a.isFinite()) return null
                val total = h + a
                return if (total <= 0.0) 0.5f else (h / total).toFloat()
            }
    }

    data class Group(val title: String, val rows: List<Row>)

    private val order: List<Pair<String, List<String>>> = listOf(
        "Posesie" to listOf("posesie", "possession"),
        "Atac" to listOf("gol", "șut", "sut", "xg", "cornere", "corner", "ocazii", "aruncări", "puncte", "powerplay", "face-off", "pase de gol", "pase decisive"),
        "Pase" to listOf("pase", "pass", "centrări", "cross"),
        "Apărare" to listOf("parade", "deposedări", "intercepții", "blocate", "degajări", "recuperări", "dueluri", "fără gol primit", "goluri primite"),
        "Disciplină" to listOf("faulturi", "cartonașe", "ofsaid", "penalty", "mingi pierdute"),
    )

    private val leaderWords = listOf("golgheter", "cele mai multe")

    /** Grupează rândurile; ce nu se potrivește nicăieri ajunge la „Altele”, jucătorii la „Jucători”. */
    fun group(stats: List<StatLine>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<Row>>()
        order.forEach { buckets[it.first] = ArrayList() }
        buckets["Jucători"] = ArrayList()
        buckets["Altele"] = ArrayList()
        for (s in stats) {
            val name = s.name.lowercase()
            val title = when {
                leaderWords.any { it in name } || s.home.contains('(') -> "Jucători"
                // „Pase de gol” e atac, nu pase.
                "pase de gol" in name || "pase decisive" in name -> "Atac"
                "primit" in name -> "Apărare"
                else -> order.firstOrNull { (_, words) -> words.any { it in name } }?.first ?: "Altele"
            }
            buckets.getValue(title) += Row(s, number(s.home), number(s.away))
        }
        return buckets.filterValues { it.isNotEmpty() }.map { (t, rows) -> Group(t, rows) }
    }

    /** „58.2”, „58%”, „1,5”, „12 (4)” → număr; altfel null. */
    fun number(text: String): Double? {
        val t = text.trim().removeSuffix("%").substringBefore(' ').replace(',', '.')
        return t.toDoubleOrNull()
    }
}
