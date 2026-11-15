package ro.ak.sportshub.core.sports

/**
 * Rețelele de streaming anunțate de furnizori ca post („Peacock”, „HBO Max”, „fuboTV”, „Stan Sport”…), pe care
 * playlisturile le au ca șiruri de canale PPV numerotate („US: Peacock 05”, „AU: Stan Sport 12”). Meciul anunțat pe
 * rețea nu spune pe care canal din șir e; dacă furnizorul IPTV redenumește canalul cu meciul, e deja sursă sigură
 * (canal EVENT), altfel canalele rețelei din țara postului devin presupuneri ([Confidence.DEDICATED]), după
 * sursele găsite, iar cele care au acum alt meci în nume nu se pun.
 */
object StreamingServices {

    /** Rețeaua → formele numelui (cuvinte fără diacritice, „+” scris „plus”). */
    private val services: Map<String, List<String>> = mapOf(
        "peacock" to listOf("peacock", "peacock tv", "peacock premium"),
        "paramount" to listOf("paramount plus", "paramount"),
        "max" to listOf("hbo max", "max"),
        "fubo" to listOf("fubo", "fubotv", "fubo tv", "fubo sports"),
        "stan" to listOf("stan", "stan sport"),
        "dazn" to listOf("dazn"),
        "espnplus" to listOf("espn plus"),
        "prime" to listOf("prime video", "amazon prime video", "amazon prime", "amazon", "prime"),
        "appletv" to listOf("apple tv", "apple tv plus", "mls season pass"),
        "kayo" to listOf("kayo", "kayo sports"),
        "optus" to listOf("optus sport"),
        "tsnplus" to listOf("tsn plus"),
        "sportsnetplus" to listOf("sportsnet plus"),
        "viaplay" to listOf("viaplay"),
        "discoveryplus" to listOf("discovery plus"),
        "flosports" to listOf("flosports", "flo sports"),
        "skyshowtime" to listOf("skyshowtime"),
        "voyo" to listOf("voyo"),
        "fightpass" to listOf("ufc fight pass", "fight pass"),
    )

    private val byForm: Map<String, String> = services.flatMap { (k, forms) -> forms.map { it to k } }.toMap()

    /** Cuvintele care pot sta între rețea și număr („Peacock Event 05”, „Stan Sport 3”, „DAZN PPV 1”). */
    private val fillers = setOf("sport", "sports", "event", "events", "ppv", "live", "premium", "tv", "plus", "channel", "ch", "hd", "fhd", "uhd", "4k", "us", "usa", "uk")

    private fun words(text: String): List<String> =
        Sport.fold(text.replace("+", " plus ")).split(' ').filter { it.isNotEmpty() }

    /** Rețeaua anunțată ca post („Peacock”, „Paramount+”, „HBO Max (USA)”), sau null dacă e un post obișnuit. */
    fun ofStation(name: String): String? {
        val w = words(StationNames.splitCountry(name).first)
        if (w.isEmpty()) return null
        return byForm[w.joinToString(" ")] ?: w.takeIf { it.size >= 2 && it.last() in fillers }?.let { byForm[it.dropLast(1).joinToString(" ")] }
    }

    /**
     * Rețeaua unui canal din playlist (partea de după țară a cheii de grupare: „peacock 05”, „stan sport 12”) și
     * numărul lui (0 fără număr); null dacă e alt fel de canal („Paramount Network”, „Max Sport 1” rămân canale obișnuite
     * doar dacă după rețea vine altceva decât cuvinte de umplutură și număr).
     */
    fun ofChannel(core: String): Pair<String, Int>? {
        val w = words(core)
        if (w.isEmpty()) return null
        for (n in minOf(4, w.size) downTo 1) {
            val service = byForm[w.take(n).joinToString(" ")] ?: continue
            val rest = w.drop(n)
            var number = 0
            for ((i, t) in rest.withIndex()) {
                if (t.all { it.isDigit() } && t.length <= 3 && i == rest.lastIndex) number = t.toInt()
                else if (t !in fillers) return null
            }
            // „Max” singur e prea des (Max Sport din Bulgaria are cuvântul „sport”): doar „Max 05”, „Max Event 3”.
            if (service == "max" && w.first() == "max" && number == 0) return null
            return service to number
        }
        return null
    }

    const val MAX_CHANNELS = 12
}
