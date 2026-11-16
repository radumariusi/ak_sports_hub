package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit

/**
 * Zapping la pauză (A1): când meciul de pe ecran intră la pauză (starea furnizorului, cu întârzierea stream-ului
 * aplicată), playerul propune alt meci live favorit sau cu surse sigure; la începutul reprizei a doua, numărătoare
 * de 10 s și înapoi. Totul pe calea schimbării canalului: un singur stream, cel vechi oprit întâi.
 */
object HalftimeZap {

    /** Cât ține de obicei pauza (minute), pe sport: dacă furnizorul nu spune la timp că a reînceput, ne întoarcem atunci. */
    private val breakMinutes = mapOf(
        Sport.FOOTBALL to 15L,
        Sport.BASKETBALL to 15L,
        Sport.ICE_HOCKEY to 18L,
        Sport.AMERICAN_FOOTBALL to 13L,
        Sport.RUGBY to 12L,
        Sport.HANDBALL to 15L,
    )

    /** Cât rămâne propunerea pe ecran. */
    val OFFER_MS: Long = TimeUnit.SECONDS.toMillis(30)

    /** Numărătoarea dinaintea întoarcerii (Back: rămâi pe meciul celălalt). */
    const val COUNTDOWN_MS: Long = 10_000L

    /** Cu cât înainte de sfârșitul estimat al pauzei se întoarce, dacă furnizorul tace (repriza nu se pierde). */
    private val EARLY_MS: Long = TimeUnit.MINUTES.toMillis(1)

    fun supports(sport: Sport?): Boolean = sport in breakMinutes

    /**
     * Meciul e la pauză după eticheta stării („Pauză”, „Între perioade” la hochei). La baschet, pauza dintre sferturi
     * (două minute) nu contează: doar pauza mare.
     */
    fun isBreak(label: String?, sport: Sport?): Boolean {
        val last = label?.substringAfterLast(" · ")?.trim()?.lowercase() ?: return false
        return when {
            last.startsWith("pauză înainte de prelungiri") -> false
            last.startsWith("pauză") -> true
            last.startsWith("între perioade") -> sport == Sport.ICE_HOCKEY
            else -> false
        }
    }

    /**
     * Ce meci se propune la pauză: live, cu altă pereche de echipe, cu o sursă de pornit; întâi favoritele, apoi cele
     * din aceeași competiție, apoi cele mai recent începute. Doar favorite sau cu surse sigure (nu presupuneri).
     */
    fun <T> pick(
        current: Match,
        items: List<T>,
        match: (T) -> Match,
        isFavorite: (Match) -> Boolean,
        sureSources: (T) -> Int,
        playable: (T) -> Boolean,
    ): T? = items.asSequence()
        .filter { t ->
            val m = match(t)
            m.isLive && m.id != current.id && playable(t) && (isFavorite(m) || sureSources(t) > 0) &&
                !(TeamNames.same(m.home, current.home) && TeamNames.same(m.away, current.away))
        }
        .sortedWith(
            compareBy<T> { if (isFavorite(match(it))) 0 else 1 }
                .thenBy { if (current.competition != null && match(it).competition == current.competition) 0 else 1 }
                .thenBy { if (sureSources(it) > 0) 0 else 1 }
                .thenByDescending { match(it).start },
        )
        .firstOrNull()

    /**
     * E timpul să ne întoarcem: furnizorul arată că meciul tău a reînceput ([resumed]), sau a trecut pauza obișnuită a
     * sportului de la [breakSeenAt] (cu un minut mai devreme). Un meci terminat nu mai are la ce să se întoarcă.
     */
    fun returnDue(sport: Sport?, breakSeenAt: Long, now: Long, resumed: Boolean): Boolean {
        if (resumed) return true
        val minutes = breakMinutes[sport] ?: return false
        return now >= breakSeenAt + TimeUnit.MINUTES.toMillis(minutes) - EARLY_MS
    }

    /** Secundele rămase din numărătoare (10, 9, … 0). */
    fun secondsLeft(startedAt: Long, now: Long): Int =
        (((startedAt + COUNTDOWN_MS - now) + 999) / 1_000).toInt().coerceIn(0, (COUNTDOWN_MS / 1_000).toInt())
}

/** Un meci din banda de scoruri a playerului (A3). */
data class BandItem(val matchId: String, val text: String)

/**
 * Banda de scoruri din player (A3, tasta galbenă): o linie cu meciurile favorite live, cu scorul întârziat cât
 * stream-ul și ascuns la „Fără spoilere”. Datele vin din lista Sports Hub și din citirile live care există deja.
 */
object ScoreBand {

    const val MAX_ITEMS = 6

    /** Meciurile din bandă: favorite, live, fără cel de pe ecran; întâi cele începute mai devreme. */
    fun pick(matches: List<Match>, isFavorite: (Match) -> Boolean, currentId: String?): List<Match> =
        matches.filter { it.isLive && it.id != currentId && isFavorite(it) }
            .sortedWith(compareBy<Match> { it.start }.thenBy { it.title })
            .take(MAX_ITEMS)

    /**
     * Textul unui meci: „ARS 1–0 CHE 67′”; cu scorul ascuns: „ARS – CHE · live”. [clock] e ceasul din eticheta stării
     * ([Scoreboard.clock]), dacă îl știm.
     */
    fun text(m: Match, home: Int?, away: Int?, clock: String?, hidden: Boolean): String {
        if (m.isSingleEvent) return m.home + (clock?.let { " $it" } ?: " · live")
        val h = TeamCodes.of(m.home)
        val a = TeamCodes.of(m.away)
        return if (hidden || home == null || away == null) {
            "$h – $a · live"
        } else {
            "$h $home–$away $a" + (clock?.let { " $it" } ?: "")
        }
    }
}
