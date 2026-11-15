package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit

/**
 * „Acum pe canalele tale”: fiecare canal de sport (pe cheia de grupare, cu toate variantele lui) cu meciul care
 * rulează acum pe el. Totul vine din lista de meciuri deja calculată: nicio cerere în plus.
 */
object NowOnChannels {

    /** Un canal cu meciul de acum: [sources] sunt variantele canalului printre sursele meciului. */
    data class Row<T>(val groupKey: String, val match: Match, val confidence: Confidence, val sources: List<MatchSource<T>>)

    /** Un canal e „pe meci” cu câteva minute înainte de start (studioul dinainte). */
    val LEAD_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /**
     * Canalele cu un meci acum (de la [LEAD_MS] înainte de start până la final), fără sursele respinse.
     * Pe un canal cu mai multe meciuri deodată rămâne cel cu sursa cea mai sigură (apoi cel live, apoi cel început
     * mai recent). Ordinea: sursele găsite efectiv înaintea presupunerilor, apoi meciurile live, apoi ora de start.
     */
    fun <T> build(entries: List<Pair<Match, List<MatchSource<T>>>>, now: Long, includeGuesses: Boolean = true): List<Row<T>> {
        val best = HashMap<String, Row<T>>()
        for ((match, sources) in entries) {
            if (now < match.start - LEAD_MS || now > match.end || match.status == MatchStatus.FINISHED) continue
            sources.filterNot { it.rejected }.groupBy { it.groupKey }.forEach { (key, variants) ->
                val confidence = variants.minOf { it.confidence.ordinal }.let { Confidence.entries[it] }
                if (!includeGuesses && confidence.guess) return@forEach
                val row = Row(key, match, confidence, variants)
                val old = best[key]
                if (old == null || better(row, old)) best[key] = row
            }
        }
        return best.values.sortedWith(
            compareBy<Row<T>> { if (it.confidence.guess) 1 else 0 }
                .thenBy { if (it.match.isLive) 0 else 1 }
                .thenBy { it.match.start }
                .thenBy { it.match.title },
        )
    }

    private fun <T> better(a: Row<T>, b: Row<T>): Boolean = when {
        a.confidence.ordinal != b.confidence.ordinal -> a.confidence.ordinal < b.confidence.ordinal
        a.match.isLive != b.match.isLive -> a.match.isLive
        else -> a.match.start > b.match.start
    }
}

/**
 * Rezumate și magazine în arhivă (catch-up), găsite în EPG după titlu, subtitlu sau categorie:
 * „Highlights”, „Rezumat”, „Match of the Day”, „Goals”, „Resumen”, „Zusammenfassung”… Doar emisiunile terminate,
 * de pe canalele care au arhivă.
 */
object Highlights {

    private val words = listOf(
        "highlights", "highlight", "rezumat", "rezumatul", "rezumate", "golurile", "goals show", "goal show", "all the goals",
        "match of the day", "motd", "football focus", "the debrief", "review show", "weekend review", "magazine", "magazin",
        "recap", "resumen", "resumenes", "zusammenfassung", "sintesi", "gli highlights", "resume", "resumes",
        "extended highlights", "best of", "goals of the", "the goals", "golazo show", "rezumatul etapei", "goluri",
        "sportsnight", "inside the nfl", "nba tv highlights", "nhl tonight", "mlb tonight", "quick highlights",
    )

    /** Emisiunea e un rezumat sau un magazin sportiv? */
    fun isHighlights(title: String, subtitle: String? = null, category: String? = null): Boolean {
        val text = " " + Sport.fold(listOfNotNull(title, subtitle, category).joinToString(" ")) + " "
        if (words.none { text.contains(" $it ") }) return false
        // „Live: …” e o transmisiune, nu un rezumat.
        return !Regex("""^\s*(live|in direct|direct|en vivo|en directo)\b""").containsMatchIn(Sport.fold(title))
    }

    /** Echipa sau competiția apare în titlu (pentru a pune întâi rezumatele favoritelor). */
    fun mentions(title: String, subtitle: String?, names: Collection<String>): Boolean {
        val text = " " + Sport.fold(listOfNotNull(title, subtitle).joinToString(" ")) + " "
        return names.any { n -> Sport.fold(n).takeIf { it.length >= 3 }?.let { text.contains(" $it ") } == true }
    }
}
