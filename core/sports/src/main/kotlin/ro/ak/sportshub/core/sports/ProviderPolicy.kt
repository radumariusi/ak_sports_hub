package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit

/**
 * Cât de des și în ce ordine se întreabă furnizorii de meciuri, după cât de ușor ne lasă să le luăm datele.
 *
 * - [Tier.OPEN]: API-uri publice, fără protecție (ESPN, EuroLeague, NHL, MLB, TheSportsDB): la 3 ore, primele
 *   (EuroLeague, care dă tot sezonul dintr-o cerere, la 12 ore).
 * - [Tier.SITE]: site-uri simple, citite ca HTML (FutbolEnLaTV, LiveOnSat): azi la 3 ore, mâine la 6 ore.
 * - [Tier.GUARDED]: protejate anti-robot (Sofascore, LiveSoccerTV, FotMob): azi la 6 ore, mâine la 12 ore,
 *   abia după ce lista e refăcută din ceilalți, iar cererile pe meci (posturile Sofascore) doar pentru meciurile
 *   fără sursă găsită.
 *
 * Un furnizor care ne refuză (403, 429, pagină de verificare) intră în pauză: 6 ore, apoi dublu la fiecare
 * refuz la rând, cel mult 48 de ore; o cerere reușită șterge pauza. „Actualizează acum” trece peste pauză.
 * La unirea aceluiași meci de la mai mulți furnizori, datele celui mai de încredere ([rank]) au întâietate.
 */
object ProviderPolicy {

    enum class Tier(val label: String) {
        OPEN("API deschis"),
        SITE("site simplu, cereri rare"),
        GUARDED("protejat anti-robot, cereri foarte rare"),
    }

    // Aceleași chei ca `SportEvent.provider` (furnizorii LiveSoccerTV și Sofascore au cheile din numele lor).
    private val order = listOf(
        Espn.PROVIDER, EuroLeague.PROVIDER, UsLeagues.NHL_API, UsLeagues.MLB_API, Scores365.PROVIDER, TheSportsDb.PROVIDER,
        FutbolEnLaTv.PROVIDER, LiveOnSat.PROVIDER,
        LiveSoccerTv.PROVIDER, FotMob.PROVIDER, Sofascore.PROVIDER,
    )

    fun tier(provider: String): Tier = when (provider) {
        TheSportsDb.PROVIDER, Espn.PROVIDER, EuroLeague.PROVIDER, UsLeagues.NHL_API, UsLeagues.MLB_API, Scores365.PROVIDER -> Tier.OPEN
        FutbolEnLaTv.PROVIDER, LiveOnSat.PROVIDER -> Tier.SITE
        LiveSoccerTv.PROVIDER, FotMob.PROVIDER, Sofascore.PROVIDER -> Tier.GUARDED
        else -> Tier.SITE
    }

    // Ordinea cererilor: întâi cei care dau multe meciuri dintr-o singură cerere (o pagină), ca lista să apară repede.
    private val fetchOrder = listOf(
        FutbolEnLaTv.PROVIDER, LiveOnSat.PROVIDER, Espn.PROVIDER, EuroLeague.PROVIDER, UsLeagues.NHL_API, UsLeagues.MLB_API,
        Scores365.PROVIDER, TheSportsDb.PROVIDER, FotMob.PROVIDER, LiveSoccerTv.PROVIDER, Sofascore.PROVIDER,
    )

    /** Locul furnizorului în runda de cereri (mai mic = mai devreme). */
    fun fetchRank(provider: String): Int = fetchOrder.indexOf(provider).let { if (it < 0) fetchOrder.size else it }

    /** 0 = cel mai de încredere. Furnizorii necunoscuți (EPG, canale EVENT) vin după toți. */
    fun rank(provider: String): Int = order.indexOf(provider).let { if (it < 0) order.size else it }

    private val HOUR = TimeUnit.HOURS.toMillis(1)

    /** Cât rămâne bun un fișier al furnizorului: [dayOffset] 0 = azi, 1 = mâine. */
    fun freshMs(provider: String, dayOffset: Int): Long = when (tier(provider)) {
        Tier.OPEN -> if (provider == EuroLeague.PROVIDER) 12 * HOUR else if (dayOffset >= 2) 24 * HOUR else 3 * HOUR
        Tier.SITE -> if (dayOffset <= 0) 3 * HOUR else 6 * HOUR
        Tier.GUARDED -> if (dayOffset <= 0) 6 * HOUR else 12 * HOUR
    }

    /**
     * Sporturile pe care le acoperă ESPN și EuroLeague; TheSportsDB (cheia gratuită: câteva meciuri pe sport și zi)
     * se mai cere doar pentru celelalte (handbal, volei, darts, snooker, ciclism, cricket, sporturi de iarnă).
     */
    val COVERED_BY_OPEN_APIS: Set<Sport> = Espn.leagues.map { it.sport }.toSet() + Sport.BASKETBALL

    /** Sporturile cerute de la TheSportsDB, dintre cele alese în setări. */
    fun sportsDbSports(selected: Collection<Sport>): List<Sport> =
        selected.filter { it !in COVERED_BY_OPEN_APIS && it !in SCORES365_SPORTS }

    /**
     * Sporturile fără ESPN al căror program vine de la 365Scores (toată ziua, nu 3 meciuri ca TheSportsDB gratuit):
     * handbalul și voleiul (id-uri verificate pe răspunsuri reale).
     */
    val SCORES365_SPORTS: Set<Sport> = setOf(Sport.HANDBALL, Sport.VOLLEYBALL)

    fun scores365Sports(selected: Collection<Sport>): List<Sport> = selected.filter { it in SCORES365_SPORTS && it !in COVERED_BY_OPEN_APIS }

    val FIRST_PAUSE_MS: Long = 6 * HOUR
    val MAX_PAUSE_MS: Long = 48 * HOUR

    /** Pauza după [strikes] refuzuri la rând (1 = primul refuz). */
    fun pauseMs(strikes: Int): Long {
        if (strikes <= 0) return 0
        var p = FIRST_PAUSE_MS
        repeat(minOf(strikes - 1, 8)) { p *= 2 }
        return minOf(p, MAX_PAUSE_MS)
    }

    /** Starea de refuz a unui furnizor: câte refuzuri la rând și până când nu-l mai întrebăm. */
    data class Block(val strikes: Int = 0, val until: Long = 0) {
        fun active(now: Long): Boolean = until > now

        fun refused(now: Long): Block = (strikes + 1).let { s -> Block(s, now + pauseMs(s)) }

        /** „3|1700000000000” (în preferințe). */
        fun encode(): String = "$strikes|$until"

        companion object {
            fun decode(text: String?): Block? {
                val parts = text?.split('|') ?: return null
                val s = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val u = parts.getOrNull(1)?.toLongOrNull() ?: return null
                return Block(s, u)
            }
        }
    }
}
