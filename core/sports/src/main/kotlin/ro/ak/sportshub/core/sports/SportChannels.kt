package ro.ak.sportshub.core.sports

/**
 * Canalele de sport, după nume și grup (din playlist). Pe ele, emisiunile „Echipa A - Echipa B”
 * se citesc drept meciuri; pe celelalte e nevoie de „v” / „vs” și de o urmă de sport.
 */
object SportChannels {

    private val words = listOf(
        "sport", "sports", "sporturi", "deportes", "esporte", "arena", "espn", "eurosport", "dazn", "bein",
        "tnt sports", "sky sports", "sky sport", "premier sports", "digi sport", "prima sport", "orange sport",
        "look sport", "telekom sport", "nova sport", "polsat sport", "canal+ sport", "movistar", "laliga",
        "football", "fotbal", "futbol", "calcio", "soccer", "nba", "nfl", "nhl", "mlb", "f1", "motogp", "tennis",
        "golf", "fight", "ufc", "boxing", "ppv", "event", "match", "racing", "ssc", "supersport", "setanta",
        "mutv", "lfc tv", "chelsea tv", "barca tv", "real madrid tv", "sportklub", "sport klub", "arena sport",
        "viaplay", "ziggo sport", "rtl sport", "fox sports", "cbs sports", "nbc sports", "bt sport", "s sport",
        "elevensports", "eleven sports", "sportdigital", "magenta sport", "servus",
    )

    fun isSport(name: String, group: String?): Boolean {
        val text = " " + Sport.fold(name + " " + group.orEmpty()) + " "
        return words.any { w -> text.contains(" ${Sport.fold(w)} ") } || text.contains("sport")
    }
}
