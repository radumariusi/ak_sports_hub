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
        "women" to "w", "womens" to "w", "woman" to "w", "w" to "w", "feminin" to "w", "fem" to "w", "ladies" to "w", "frauen" to "w",
        "femenino" to "w", "femenina" to "w", "femminile" to "w", "feminino" to "w", "wfc" to "w",
        "u17" to "u17", "u18" to "u18", "u19" to "u19", "u20" to "u20", "u21" to "u21", "u23" to "u23",
        "ii" to "ii", "b" to "ii", "reserves" to "ii", "youth" to "u19",
    )

    /** Alias-uri cunoscute (după normalizare) → numele complet normalizat. */
    private val clubAliases = mapOf(
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
    private val aliases: Map<String, String> = run {
        val known = clubAliases + NationNames.aliases + NativeClubNames.aliases
        // Cheia clubului după alias-urile de mai sus, ca formele noi să ducă la aceeași cheie („Inter” = „Inter Milan”).
        fun keyOf(name: String): String {
            val folded = Sport.fold(name)
            known[folded]?.let { return it }
            val b = base(name)
            return known[b] ?: b
        }
        known + ClubNames.aliases(::base, ::keyOf).filterKeys { it !in known }
    }

    /** Prescurtările încorporate ale cluburilor (formă scurtă → nume complet normalizat), pentru catalogul de nume. */
    internal val builtInClubAliases: Map<String, String> get() = clubAliases

    /**
     * Alias-urile tale (din dicționar), deja normalizate: alias → nume. Au prioritate față de cele încorporate.
     * Se schimbă rar (din setări), deci o simplă referință volatilă e destul.
     */
    @Volatile
    private var userAliases: Map<String, String> = emptyMap()

    /** Formele scurte învățate din catalogul de nume ([NameCatalog.teamAliases]): după cele încorporate. */
    @Volatile
    private var learnedAliases: Map<String, String> = emptyMap()

    /** Pune formele învățate (formă scurtă → cheia echipei); golește cache-urile doar dacă s-a schimbat ceva. */
    fun setLearnedAliases(map: Map<String, String>) {
        val next = map.entries.mapNotNull { (alias, key) ->
            val a = base(alias)
            if (a.isEmpty() || a == key || aliases.containsKey(a) || userAliases.containsKey(a)) null else a to key
        }.toMap()
        if (next == learnedAliases) return
        learnedAliases = next
        partsCache.clear()
        partsOrder.clear()
        favoriteCache.clear()
    }

    /** Pune alias-urile tale (alias → numele complet, așa cum le-ai scris). */
    fun setUserAliases(map: Map<String, String>) {
        userAliases = map.entries.mapNotNull { (alias, full) ->
            val a = base(alias)
            val f = base(full)
            if (a.isEmpty() || f.isEmpty() || a == f) null else a to (aliases[f] ?: f)
        }.toMap()
        partsCache.clear()
        partsOrder.clear()
        favoriteCache.clear()
    }

    /**
     * Cuvintele deja normalizate ale unui nume: [core] (fără mărci), [marks] (feminin, tineret, rezerve) și
     * [joined] (cuvintele lipite, pentru „ParisSaintGermain”). La asamblare se calculează o dată pe nume.
     */
    class Parts internal constructor(
        /** [key] al numelui. */
        val key: String,
        val core: Set<String>,
        val marks: Set<String>,
    ) {
        val joined: String = core.joinToString("")

        /** Cheia mulțimii de cuvinte (ordonate), pentru indexul pe nume întregi. */
        val setKey: String = core.sorted().joinToString(" ")

        /** Cuvintele de cel puțin 3 litere: o echipă care le conține pe toate e aceeași sau mai lungă. */
        val longTokens: List<String> = core.filter { it.length >= 3 }

        /**
         * Cheile submulțimilor de cuvinte care pot fi o echipă egală cu asta: o echipă cu mai puține cuvinte e aceeași
         * doar dacă toate cuvintele ei sunt aici și are măcar unul de 3 litere. Null dacă sunt prea multe cuvinte.
         */
        val subsetKeys: List<String>? by lazy {
            val words = core.sorted()
            if (words.size > MAX_SUBSET_WORDS) return@lazy null
            val out = ArrayList<String>()
            for (mask in 1 until (1 shl words.size)) {
                val pick = words.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                if (pick.size == words.size || pick.any { it.length >= 3 }) out += pick.joinToString(" ")
            }
            out
        }
    }

    private const val MAX_SUBSET_WORDS = 6

    /**
     * Cuvintele fiecărui nume, ținute minte: la asamblarea meciurilor același nume se compară de mii de ori,
     * iar normalizarea (diacritice, alias-uri) costă mult mai mult decât compararea. Un EPG mare are zeci de mii
     * de nume: cache-ul e mare, iar când se umple se golește doar jumătatea cea mai veche (nu tot, ca înainte,
     * când se golea întruna și totul se normaliza din nou).
     */
    private val partsCache = java.util.concurrent.ConcurrentHashMap<String, Parts>()
    private val partsOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val PARTS_CACHE_MAX = 200_000

    /** [key], din cache (pentru asamblare, unde același nume revine de mii de ori). */
    fun cachedKey(name: String): String = parts(name).key

    /** Cheia unei echipe (doar pentru comparare; în UI apare numele original). */
    fun key(name: String): String {
        val folded = Sport.fold(name)
        userAliases[folded]?.let { return it }
        aliases[folded]?.let { return it }
        val joined = base(name)
        return userAliases[joined] ?: aliases[joined] ?: learnedAliases[joined] ?: joined
    }

    private fun base(name: String): String {
        val folded = Sport.fold(name)
        val tokens = folded.split(' ').filter { it.isNotEmpty() && it !in clubTokens }
        return tokens.joinToString(" ").ifEmpty { folded }
    }

    /** Cuvintele normalizate ale numelui (din cache). */
    fun parts(name: String): Parts {
        partsCache[name]?.let { return it }
        if (partsCache.size > PARTS_CACHE_MAX) {
            repeat(PARTS_CACHE_MAX / 2) { partsOrder.poll()?.let(partsCache::remove) }
        }
        val p = computeParts(name)
        if (partsCache.putIfAbsent(name, p) == null) partsOrder.add(name)
        return p
    }

    private fun computeParts(name: String): Parts {
        val k = key(name)
        val tokens = youth(k.split(' ').filter { it.isNotEmpty() })
        val marks = tokens.mapNotNull { markers[it] }.toSet()
        var core = tokens.filter { it !in markers }.toSet()
        // „Man Utd U21”: alias-ul se aplică numelui fără semn („Man Utd” → „Manchester United”).
        if (marks.isNotEmpty() && core.isNotEmpty()) {
            val stripped = youth(Sport.fold(name).split(' ').filter { it.isNotEmpty() }).filter { it !in markers }.joinToString(" ")
            val ck = key(stripped).split(' ').filter { it.isNotEmpty() && it !in markers }.toSet()
            if (ck.isNotEmpty()) core = ck
        }
        return Parts(k, core, marks)
    }

    /** „U-21”, „U 21”, „Under 21” → „u21”. */
    private fun youth(tokens: List<String>): List<String> {
        if (tokens.none { it == "u" || it == "under" || it == "sub" }) return tokens
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            if ((t == "u" || t == "under" || t == "sub") && next != null && next.length == 2 && next.all { it.isDigit() } && "u$next" in markers) {
                out += "u$next"
                i += 2
            } else {
                out += t
                i++
            }
        }
        return out
    }

    /**
     * Aceeași echipă? Cheile sunt egale, sau cuvintele numelui mai scurt se regăsesc toate în cel lung
     * („Arsenal” și „Arsenal London”). Echipa feminină sau de tineret nu se confundă cu cea mare.
     */
    fun same(a: String, b: String): Boolean = same(parts(a), parts(b))

    fun same(a: Parts, b: Parts): Boolean {
        if (a === b) return a.core.isNotEmpty()
        if (a.marks != b.marks) return false
        return sameCore(a, b)
    }

    /** Aceeași echipă, fără să conteze semnul de tineret, rezerve sau feminin. */
    private fun sameCore(a: Parts, b: Parts): Boolean {
        if (a === b) return a.core.isNotEmpty()
        val ca = a.core
        val cb = b.core
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca == cb) return true
        val (small, big) = if (ca.size <= cb.size) a to b else b to a
        if (small.core.none { it.length >= 3 }) return false
        if (big.core.containsAll(small.core)) return true
        // „Paris Saint-Germain” / „ParisSaintGermain”: aceleași litere, alt despărțit.
        return small.joined == big.joined
    }

    /** Cuvintele care, puse după o echipă favorită, o țin doar la echipa mare („Manchester United seniori”). */
    private val seniorWords = setOf("senior", "seniori", "seniors", "seniorii", "seniorilor")

    private class Favorite(val parts: Parts, val strict: Boolean)

    private val favoriteCache = java.util.concurrent.ConcurrentHashMap<String, Favorite>()

    private fun favorite(name: String): Favorite = favoriteCache[name] ?: run {
        if (favoriteCache.size > 2_000) favoriteCache.clear()
        val tokens = Sport.fold(name).split(' ').filter { it.isNotEmpty() }
        val senior = tokens.any { it in seniorWords }
        val p = if (senior) parts(tokens.filter { it !in seniorWords }.joinToString(" ")) else parts(name)
        Favorite(p, strict = senior || p.marks.isNotEmpty()).also { favoriteCache[name] = it }
    }

    /** Numele are semn de tineret, rezerve sau feminin („Chelsea U21”, „Arsenal Women”)? */
    fun hasMarker(name: String): Boolean = parts(name).marks.isNotEmpty()

    /** Favorita e scrisă cu „seniori” (doar echipa mare)? */
    fun isSeniorOnly(favorite: String): Boolean = Sport.fold(favorite).split(' ').any { it in seniorWords }

    /** Numele favoritei fără „seniori”: „Manchester United seniori” → „Manchester United”. */
    fun withoutSenior(favorite: String): String =
        favorite.split(' ').filter { it.isNotEmpty() && Sport.fold(it) !in seniorWords }.joinToString(" ")

    /**
     * Echipa favorită [favorite] cuprinde echipa [name]? Cererea lui Radu: „Manchester United” cuprinde și
     * echipele U18, U21, a doua echipă și echipa feminină. Favorita scrisă cu semn („Arsenal Women”, „Chelsea U21”)
     * ține doar echipa aceea, iar cea scrisă cu „seniori” („Manchester United seniori”) doar echipa mare.
     */
    fun covers(favorite: String, name: String): Boolean {
        if (name.isBlank()) return false
        val f = favorite(favorite)
        val other = parts(name)
        return if (f.strict) same(f.parts, other) else sameCore(f.parts, other)
    }

    /** Aceeași pereche de echipe (cuvintele deja calculate), în orice ordine. */
    fun samePair(home1: Parts, away1: Parts, home2: Parts, away2: Parts): Boolean =
        (same(home1, home2) && same(away1, away2)) || (same(home1, away2) && same(away1, home2))

    /** Aceeași pereche de echipe, în orice ordine (EPG-urile le inversează uneori). */
    private val womenWords = setOf("women", "womens", "woman", "w", "feminin", "femenino", "femminile", "fem", "ladies", "frauen", "lionesses", "wfc")
    private val womenCompetition = Regex("""\b(women|womens|feminin\w*|femenin\w*|femminile|frauen\w*|ladies|wsl|nwsl|liga f)\b""")

    /** Competiția e feminină („Women's Super League”, „Bundesliga Frauen”, „Liga F”)? */
    fun isWomenCompetition(competition: String?): Boolean =
        competition != null && womenCompetition.containsMatchIn(Sport.fold(competition))

    /** Numele are semnul echipei feminine („Arsenal Women”, „Bayern Frauen”)? */
    fun isWomenTeam(name: String): Boolean = Sport.fold(name).split(' ').any { it in womenWords }

    /** Numele fără cuvintele care arată echipa feminină: „Arsenal Frauen” → „Arsenal”. */
    fun withoutWomen(name: String): String = Sport.fold(name).split(' ').filter { it.isNotEmpty() && it !in womenWords }.joinToString(" ")

    fun samePair(home1: String, away1: String, home2: String, away2: String): Boolean =
        (same(home1, home2) && same(away1, away2)) || (same(home1, away2) && same(away1, home2))
}
