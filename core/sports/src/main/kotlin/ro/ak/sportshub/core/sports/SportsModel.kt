package ro.ak.sportshub.core.sports

/** Starea unui meci, după furnizor sau după ora din program. */
enum class MatchStatus { SCHEDULED, LIVE, FINISHED }

/** Un post TV anunțat de un furnizor pentru un meci („Sky Sports Main Event”, United Kingdom). */
data class TvStation(val name: String, val country: String? = null)

/**
 * Un meci, așa cum îl dă un furnizor de program (de exemplu TheSportsDB).
 * [start] e în milisecunde UTC.
 */
data class SportEvent(
    val provider: String,
    val providerId: String,
    val sport: String?,
    val competition: String?,
    val home: String,
    val away: String,
    val start: Long,
    val status: MatchStatus? = null,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val venue: String? = null,
    val stations: List<TvStation> = emptyList(),
)

/** O emisiune din EPG care poate fi un meci (vine din baza EPG, pe canalul EPG [epgChannelId]). */
data class EpgBroadcast(
    val programmeId: Long,
    val epgChannelId: Long,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)

/** Ce s-a putut citi dintr-un titlu EPG: echipele, competiția și sportul, dacă apar. */
data class ExtractedMatch(
    val home: String,
    val away: String,
    val competition: String?,
    val sport: Sport?,
    /** Titlul spune explicit „live” / „direct”. */
    val explicitLive: Boolean,
)

/** O emisiune EPG recunoscută ca meci. */
data class MatchBroadcast(val broadcast: EpgBroadcast, val extracted: ExtractedMatch)

/**
 * Cât de sigură e legătura dintre un meci și un canal. Ordinea contează: întâi cea mai sigură.
 * [guess] înseamnă o presupunere (drepturi TV): sursele astea vin după cele găsite efectiv.
 */
enum class Confidence(val label: String, val guess: Boolean = false) {
    /** Ai confirmat tu că pe canalul ăsta e meciul (sau l-ai adăugat de mână). */
    CONFIRMED("Confirmat de tine"),

    /** Programul EPG al canalului conține meciul. */
    EPG("Sigur (EPG)"),

    /** Un canal EVENT / PPV al playlistului are meciul în nume („EVENT 03: Arsenal v Chelsea 20:45”). */
    EVENT("Sigur (canal eveniment)"),

    /** Un furnizor anunță postul, iar canalul are același nume (sau e în dicționarul de posturi). */
    STATION("Probabil (post anunțat)"),

    /** Ai confirmat canalul la alt meci din aceeași competiție. */
    LEARNED("Învățat (aceeași competiție)", guess = true),

    /** Un canal dedicat competiției („DAZN F1”, „MotoGP TV”): transmite de obicei toate sesiunile. */
    DEDICATED("Presupus (canal dedicat)", guess = true),

    /** O regulă de drepturi TV: competiția → posturile care o transmit de obicei. */
    RIGHTS("Presupus (drepturi TV)", guess = true),
}

/**
 * Un meci din Sports Hub, după unirea furnizorilor și a EPG-ului.
 * [id] e stabil între recalculări (furnizor + id, sau echipe + zi pentru meciurile doar din EPG).
 */
data class Match(
    val id: String,
    val sport: Sport?,
    val competition: String?,
    val home: String,
    val away: String,
    val start: Long,
    val end: Long,
    val status: MatchStatus,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val venue: String? = null,
    val stations: List<TvStation> = emptyList(),
    val broadcasts: List<EpgBroadcast> = emptyList(),
    val providers: Set<String> = emptySet(),
    /** Canalele EVENT / PPV din playlist (id-urile lor) care au meciul în nume. */
    val eventChannels: List<Long> = emptyList(),
) {
    val title: String get() = if (away.isBlank()) home else "$home – $away"

    /** Un eveniment fără două echipe (o cursă, o etapă, o gală, un turneu): [home] e numele lui. */
    val isSingleEvent: Boolean get() = away.isBlank()
    val isLive: Boolean get() = status == MatchStatus.LIVE
}
