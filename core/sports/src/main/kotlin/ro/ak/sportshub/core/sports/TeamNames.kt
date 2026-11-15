package ro.ak.sportshub.core.sports

/**
 * Normalizarea numelor de echipe, ca „Man Utd”, „Manchester United FC” și „MANCHESTER UNITED”
 * să fie aceeași echipă. Fără diacritice, fără `FC`, `CF`, `AFC`, `SC`, `CS`…, cu alias-uri cunoscute.
 */
object TeamNames {

    /** Prescurtări de club care nu deosebesc echipele între ele. */
    private val clubTokens = setOf(
        "fc", "cf", "afc", "sc", "cs", "ac", "as", "fk", "sk", "ssc", "bk", "if", "sv", "cd", "ca", "us",
        "ud", "rc", "rcd", "sd", "club", "football", "calcio", "futbol", "the", "acs", "cfc", "sad",
        "kv", "krc", "nk", "hnk", "gnk", "ofk", "pfc", "jk", "bc", "kk", "hc",
    )

    /** Ce deosebește echipa mare de echipa de tineret sau de cea feminină: trebuie să coincidă. */
    private val markers = mapOf(
        "women" to "w", "woman" to "w", "w" to "w", "feminin" to "w", "fem" to "w", "ladies" to "w", "frauen" to "w",
        "u17" to "u17", "u18" to "u18", "u19" to "u19", "u20" to "u20", "u21" to "u21", "u23" to "u23",
        "ii" to "ii", "b" to "ii", "reserves" to "ii", "youth" to "u19",
    )

    /** Alias-uri cunoscute (după normalizare) → numele complet normalizat. */
    private val aliases = mapOf(
        "man utd" to "manchester united", "man united" to "manchester united", "man u" to "manchester united",
        "man city" to "manchester city", "psg" to "paris saint germain", "paris sg" to "paris saint germain",
        "paris st germain" to "paris saint germain", "spurs" to "tottenham hotspur", "tottenham" to "tottenham hotspur",
        "wolves" to "wolverhampton wanderers", "wolverhampton" to "wolverhampton wanderers",
        "newcastle" to "newcastle united", "west ham" to "west ham united", "brighton" to "brighton hove albion",
        "brighton and hove albion" to "brighton hove albion", "nottm forest" to "nottingham forest",
        "notts forest" to "nottingham forest", "leeds" to "leeds united", "sheff utd" to "sheffield united",
        "inter" to "inter milan", "internazionale" to "inter milan", "inter milano" to "inter milan",
        "milan" to "ac milan", "juve" to "juventus", "atletico" to "atletico madrid", "atl madrid" to "atletico madrid",
        "atletico de madrid" to "atletico madrid", "real" to "real madrid", "barca" to "barcelona",
        "bayern" to "bayern munich", "bayern munchen" to "bayern munich", "fc bayern" to "bayern munich",
        "bvb" to "borussia dortmund", "dortmund" to "borussia dortmund", "gladbach" to "borussia monchengladbach",
        "leverkusen" to "bayer leverkusen", "bayer 04 leverkusen" to "bayer leverkusen",
        "steaua" to "fcsb", "steaua bucuresti" to "fcsb", "fc fcsb" to "fcsb", "cfr" to "cfr cluj",
        "u craiova" to "universitatea craiova", "craiova" to "universitatea craiova",
        "rapid" to "rapid bucuresti", "dinamo" to "dinamo bucuresti",
        "sporting" to "sporting cp", "sporting lisbon" to "sporting cp", "benfica lisbon" to "benfica",
        "porto" to "fc porto", "ajax amsterdam" to "ajax", "psv eindhoven" to "psv",
    )

    /**
     * Alias-urile tale (din dicționar), deja normalizate: alias → nume. Au prioritate față de cele încorporate.
     * Se schimbă rar (din setări), deci o simplă referință volatilă e destul.
     */
    @Volatile
    private var userAliases: Map<String, String> = emptyMap()

    /** Pune alias-urile tale (alias → numele complet, așa cum le-ai scris). */
    fun setUserAliases(map: Map<String, String>) {
        userAliases = map.entries.mapNotNull { (alias, full) ->
            val a = base(alias)
            val f = base(full)
            if (a.isEmpty() || f.isEmpty() || a == f) null else a to (aliases[f] ?: f)
        }.toMap()
        partsCache.clear()
    }

    /**
     * Cuvintele deja normalizate ale fiecărui nume: la asamblarea meciurilor același nume se compară
     * de mii de ori, iar normalizarea (diacritice, alias-uri) costă mult mai mult decât compararea.
     */
    private val partsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Set<String>, Set<String>>>()
    private const val PARTS_CACHE_MAX = 50_000

    /** Cheia unei echipe (doar pentru comparare; în UI apare numele original). */
    fun key(name: String): String {
        val folded = Sport.fold(name)
        userAliases[folded]?.let { return it }
        aliases[folded]?.let { return it }
        val joined = base(name)
        return userAliases[joined] ?: aliases[joined] ?: joined
    }

    private fun base(name: String): String {
        val folded = Sport.fold(name)
        val tokens = folded.split(' ').filter { it.isNotEmpty() && it !in clubTokens }
        return tokens.joinToString(" ").ifEmpty { folded }
    }

    private fun parts(name: String): Pair<Set<String>, Set<String>> {
        partsCache[name]?.let { return it }
        if (partsCache.size > PARTS_CACHE_MAX) partsCache.clear()
        return computeParts(name).also { partsCache[name] = it }
    }

    private fun computeParts(name: String): Pair<Set<String>, Set<String>> {
        val tokens = key(name).split(' ').filter { it.isNotEmpty() }
        val marks = tokens.mapNotNull { markers[it] }.toSet()
        val core = tokens.filter { it !in markers }.toSet()
        return core to marks
    }

    /**
     * Aceeași echipă? Cheile sunt egale, sau cuvintele numelui mai scurt se regăsesc toate în cel lung
     * („Arsenal” și „Arsenal London”). Echipa feminină sau de tineret nu se confundă cu cea mare.
     */
    fun same(a: String, b: String): Boolean {
        val (ca, ma) = parts(a)
        val (cb, mb) = parts(b)
        if (ma != mb) return false
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca == cb) return true
        val (small, big) = if (ca.size <= cb.size) ca to cb else cb to ca
        if (small.none { it.length >= 3 }) return false
        if (big.containsAll(small)) return true
        // „Paris Saint-Germain” / „ParisSaintGermain”: aceleași litere, alt despărțit.
        return small.joinToString("") == big.joinToString("")
    }

    /** Aceeași pereche de echipe, în orice ordine (EPG-urile le inversează uneori). */
    fun samePair(home1: String, away1: String, home2: String, away2: String): Boolean =
        (same(home1, home2) && same(away1, away2)) || (same(home1, away2) && same(away1, home2))
}
