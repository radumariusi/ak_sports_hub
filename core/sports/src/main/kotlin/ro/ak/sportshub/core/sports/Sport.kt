package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import ro.ak.sportshub.core.common.ChannelNameParser

/**
 * Sporturile cunoscute: numele afișat, numele din TheSportsDB, cuvintele după care le recunoaștem
 * în EPG și cât durează de obicei un meci (pentru „LIVE” când furnizorul nu dă starea).
 */
enum class Sport(
    val label: String,
    val sportsDbName: String,
    private val minutes: Int,
    /** Cuvinte (fără diacritice, litere mici) care indică sportul în titlu, categorie sau competiție. */
    val keywords: List<String>,
) {
    FOOTBALL(
        "Fotbal", "Soccer", 120,
        listOf(
            "fotbal", "football", "soccer", "futbol", "fussball", "calcio", "voetbal", "premier league",
            "champions league", "europa league", "conference league", "la liga", "laliga", "serie a",
            "bundesliga", "ligue 1", "liga 1", "superliga", "superliga romaniei", "eredivisie", "primeira liga",
            "fa cup", "efl", "championship", "copa del rey", "coppa italia", "dfb pokal", "uefa", "fifa",
            "liga i", "cupa romaniei", "mls", "saudi pro league",
        ),
    ),
    BASKETBALL("Baschet", "Basketball", 150, listOf("baschet", "basketball", "basket", "nba", "euroleague", "eurocup", "wnba")),
    TENNIS("Tenis", "Tennis", 180, listOf("tenis", "tennis", "atp", "wta", "wimbledon", "roland garros", "us open", "australian open", "davis cup", "billie jean king")),
    ICE_HOCKEY("Hochei", "Ice Hockey", 160, listOf("hochei", "hockey", "nhl", "khl")),
    HANDBALL("Handbal", "Handball", 100, listOf("handbal", "handball", "ehf")),
    VOLLEYBALL("Volei", "Volleyball", 120, listOf("volei", "volleyball", "cev")),
    RUGBY("Rugby", "Rugby", 120, listOf("rugby", "six nations", "premiership rugby", "top 14", "urc")),
    AMERICAN_FOOTBALL("Fotbal american", "American Football", 210, listOf("nfl", "american football", "fotbal american", "ncaaf", "super bowl")),
    BASEBALL("Baseball", "Baseball", 180, listOf("baseball", "mlb")),
    // Înaintea Motorsportului: „ISU Grand Prix of Figure Skating” nu e o cursă.
    WINTER("Sporturi de iarnă", "Winter Sports", 180, listOf("figure skating", "skating", "skate", "patinaj", "isu", "biathlon", "biatlon", "ski", "schi", "ski jumping", "sarituri cu schiurile", "bobsleigh", "luge", "curling")),
    MOTORSPORT("Motorsport", "Motorsport", 120, listOf("formula 1", "formula1", "f1", "motogp", "moto gp", "nascar", "indycar", "wrc", "formula e", "grand prix")),
    FIGHTING("Lupte", "Fighting", 240, listOf("ufc", "box", "boxing", "mma", "kickbox", "kickboxing", "glory", "bellator", "lupte")),
    DARTS("Darts", "Darts", 240, listOf("darts", "pdc")),
    SNOOKER("Snooker", "Snooker", 240, listOf("snooker")),
    CYCLING("Ciclism", "Cycling", 300, listOf("ciclism", "cycling", "tour de france", "giro", "vuelta")),
    GOLF("Golf", "Golf", 360, listOf("golf", "pga", "ryder cup")),
    CRICKET("Cricket", "Cricket", 360, listOf("cricket", "ipl", "test match")),
    ;

    /** Cât ținem meciul „LIVE” după ora de start, dacă furnizorul nu spune altceva. */
    val typicalDurationMs: Long get() = TimeUnit.MINUTES.toMillis(minutes.toLong())

    companion object {
        val DEFAULT_DURATION_MS: Long = TimeUnit.MINUTES.toMillis(150)

        /** Sporturile cerute implicit de la furnizori (setare): toate cele cu un furnizor deschis (ESPN, EuroLeague), plus handbalul. */
        val DEFAULT_FETCH: List<Sport> = listOf(
            FOOTBALL, BASKETBALL, TENNIS, ICE_HOCKEY, HANDBALL, MOTORSPORT, FIGHTING, RUGBY, GOLF, AMERICAN_FOOTBALL, BASEBALL,
        )

        /** Sporturile adăugate la setările vechi odată cu ESPN extins (etapa 14). */
        val ADDED_WITH_ESPN: List<Sport> = listOf(FIGHTING, RUGBY, GOLF, AMERICAN_FOOTBALL, BASEBALL)

        fun fromSportsDb(name: String?): Sport? {
            if (name.isNullOrBlank()) return null
            val n = name.trim().lowercase()
            return entries.firstOrNull { it.sportsDbName.lowercase() == n }
                ?: if (n == "fighting" || n == "boxing") FIGHTING else null
        }

        fun fromName(name: String?): Sport? = name?.let { n -> entries.firstOrNull { it.name == n } }

        /** Sportul după cuvinte din text (titlu, categorie, competiție, nume de canal). */
        fun detect(vararg texts: String?): Sport? {
            val words = texts.filterNotNull().joinToString(" ") { fold(it) }
            if (words.isBlank()) return null
            val padded = " $words "
            return entries.firstOrNull { s -> s.keywords.any { k -> padded.contains(" $k ") } }
        }

        private val nonAlnum = Regex("""[^a-z0-9]+""")

        fun fold(text: String): String =
            ChannelNameParser.fold(text).replace(nonAlnum, " ").trim()
    }
}
