package ro.ak.sportshub.core.sports

import ro.ak.sportshub.core.common.ChannelNameParser

/**
 * Potrivirea pe nume de post: furnizorul spune „Sky Sports Main Event” (United Kingdom), iar
 * aici găsim canalele tale cu același nume, din aceeași țară (dacă se știe).
 *
 * Se compară cheile de grupare ale canalelor (`ChannelNameParser.groupKey`, de forma `uk:sky sport main event`),
 * fără spații, fără pluralul englezesc și cu numerele scrise în cifre, ca „Sky Sports Main Event”,
 * „Sky Sport Main Event” și „SkySports MainEvent” să fie același post.
 */
class StationIndex(
    groupKeys: Collection<String>,
    /** Grupurile din playlist ale fiecărei chei de grupare („USA | PEACOCK PPV”), pentru rețelele de streaming. */
    groups: Map<String, Set<String>> = emptyMap(),
) {

    private val byKey: Map<String, List<Pair<String?, String>>> = groupKeys.distinct()
        .map { gk ->
            val country = gk.substringBefore(':', "").ifEmpty { null }
            val core = if (country != null) gk.substringAfter(':') else gk
            Triple(compact(core), country, gk)
        }
        .filter { it.first.isNotEmpty() }
        .groupBy({ it.first }, { it.second to it.third })

    /**
     * Cheile de grupare ale canalelor care sunt postul [station]. Se încearcă și numele din dicționarul
     * de posturi ([StationNames]: „BT Sport 1” = „TNT Sports 1”), iar țara scrisă la final
     * („Digi Sport 1 Romania”, „Sky Sports Main Event (UK)”) se folosește ca țară.
     */
    // Același post apare la sute de meciuri; rezultatul se ține minte (indexul se reface când se schimbă canalele).
    private val memo = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, List<String>>()

    fun find(station: TvStation): List<String> = memo.getOrPut(station.name to station.country) { lookup(station) }

    private fun lookup(station: TvStation): List<String> {
        val (name, suffixCountry) = StationNames.splitCountry(station.name)
        val out = LinkedHashSet<String>()
        for (variant in StationNames.variants(name)) {
            val parsed = ChannelNameParser.parse(variant)
            val core = parsed.groupKey.substringAfter(':')
            val candidates = byKey[compact(core)] ?: continue
            val wanted = Countries.codes(station.country ?: suffixCountry) + listOfNotNull(parsed.country?.lowercase())
            if (wanted.isEmpty()) {
                candidates.forEach { out += it.second }
            } else {
                // Canalele fără țară în nume se potrivesc oricum; cele cu altă țară, nu.
                candidates.filter { (c, _) -> c == null || c in wanted }.forEach { out += it.second }
            }
        }
        return out.toList()
    }

    /** Canalele rețelelor de streaming ([StreamingServices]): rețea → (țară, cheia de grupare, numărul), după număr. */
    private val streaming: Map<String, List<Triple<String?, String, Int>>> = groupKeys.distinct()
        .mapNotNull { gk ->
            val country = gk.substringBefore(':', "").ifEmpty { null }
            val core = if (country != null) gk.substringAfter(':') else gk
            StreamingServices.ofChannel(core)?.let { (service, n) -> return@mapNotNull service to Triple(country, gk, n) }
            // „Event 05” din grupul „USA | PEACOCK PPV”: rețeaua vine din grup, numărul din nume.
            val n = StreamingServices.genericNumber(core) ?: return@mapNotNull null
            val service = groups[gk].orEmpty().firstNotNullOfOrNull { StreamingServices.ofGroup(it) } ?: return@mapNotNull null
            service to Triple(country, gk, n)
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedBy { it.third } }

    private val streamingMemo = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, List<String>>()

    /**
     * Canalele rețelei de streaming anunțate ca post [station] („Peacock”, țara „USA”), din țara postului (sau fără
     * țară), cel mult [StreamingServices.MAX_CHANNELS]; gol dacă postul nu e o rețea de streaming.
     */
    fun streamingChannels(station: TvStation): List<String> = streamingMemo.getOrPut(station.name to station.country) {
        val service = StreamingServices.ofStation(station.name) ?: return@getOrPut emptyList()
        val list = streaming[service] ?: return@getOrPut emptyList()
        val wanted = Countries.codes(station.country ?: StationNames.splitCountry(station.name).second)
        list.filter { (c, _, _) -> wanted.isEmpty() || c == null || c in wanted }.map { it.second }.take(StreamingServices.MAX_CHANNELS)
    }

    /** Prefixele de țară ale canalelor tale („uk”, „ro”…), pentru a cere doar posturile din țările tale. */
    fun countries(): Set<String> = byKey.values.flatten().mapNotNull { it.first }.toSet()

    /** Există canale cu numele ăsta (fără să conteze țara)? Pentru „posturi fără canal” din dicționar. */
    fun knows(compactName: String): Boolean = byKey.containsKey(compactName)

    companion object {
        private val numbers = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "unu" to "1", "doi" to "2", "trei" to "3")
        private val dropped = setOf("tv", "channel", "the", "network")

        private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Cheia unui post anunțat: numele fără țara de la final, compactat; ținută minte (se cere la fiecare meci). */
        fun stationKey(name: String): String {
            keys[name]?.let { return it }
            if (keys.size > 20_000) keys.clear()
            return compact(StationNames.splitCountry(name).first).also { keys[name] = it }
        }

        fun compact(name: String): String =
            Sport.fold(name).split(' ')
                .filter { it.isNotEmpty() && it !in dropped }
                .joinToString("") { w ->
                    numbers[w] ?: if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
                }
    }
}

/** Numele de țări folosite de furnizori → prefixele de țară din playlisturi. */
object Countries {
    private val map = mapOf(
        "united kingdom" to setOf("uk", "gb", "en"), "uk" to setOf("uk", "gb", "en"), "england" to setOf("uk", "gb", "en"),
        "scotland" to setOf("uk", "gb", "en"), "wales" to setOf("uk", "gb", "en"), "great britain" to setOf("uk", "gb", "en"),
        "ireland" to setOf("ie", "uk", "en"), "usa" to setOf("us", "usa"), "united states" to setOf("us", "usa"),
        "canada" to setOf("ca"), "australia" to setOf("au"), "romania" to setOf("ro"), "moldova" to setOf("md", "ro"),
        "germany" to setOf("de", "ger"), "austria" to setOf("at", "de"), "switzerland" to setOf("ch"),
        "france" to setOf("fr"), "italy" to setOf("it"), "portugal" to setOf("pt"),
        "netherlands" to setOf("nl"), "belgium" to setOf("be"), "poland" to setOf("pl"), "hungary" to setOf("hu"),
        "turkey" to setOf("tr"), "greece" to setOf("gr"), "bulgaria" to setOf("bg"), "serbia" to setOf("rs"),
        "croatia" to setOf("hr"), "czech republic" to setOf("cz"), "czechia" to setOf("cz"), "slovakia" to setOf("sk"),
        "brazil" to setOf("br"), "sweden" to setOf("se"), "denmark" to setOf("dk"), "norway" to setOf("no"),
        "finland" to setOf("fi"), "albania" to setOf("al"), "russia" to setOf("ru"), "ukraine" to setOf("ua"),
        "slovenia" to setOf("si"), "bosnia and herzegovina" to setOf("ba"), "north macedonia" to setOf("mk"),
        "gb" to setOf("uk", "gb", "en"), "us" to setOf("us", "usa"),
        "spain" to setOf("es"), "espana" to setOf("es"),
        "mexico" to setOf("mx"), "argentina" to setOf("ar"), "india" to setOf("in"),
        // Țările din listele pe țări ale LiveSoccerTV / Sofascore (pagina meciului are 100+ țări).
        "andorra" to setOf("ad", "es"), "luxembourg" to setOf("lu"), "malta" to setOf("mt"), "cyprus" to setOf("cy"),
        "iceland" to setOf("is"), "estonia" to setOf("ee"), "latvia" to setOf("lv"), "lithuania" to setOf("lt"),
        "armenia" to setOf("am"), "georgia" to setOf("ge"), "azerbaijan" to setOf("az"), "kazakhstan" to setOf("kz"),
        "belarus" to setOf("by"), "montenegro" to setOf("me"), "kosovo" to setOf("xk"), "israel" to setOf("il"),
        "bosnia" to setOf("ba"), "bosnia herzegovina" to setOf("ba"), "macedonia" to setOf("mk"),
        "new zealand" to setOf("nz"), "south africa" to setOf("za"), "nigeria" to setOf("ng"), "kenya" to setOf("ke"),
        "ghana" to setOf("gh"), "egypt" to setOf("eg"), "morocco" to setOf("ma"), "algeria" to setOf("dz"),
        "tunisia" to setOf("tn"), "saudi arabia" to setOf("sa"), "united arab emirates" to setOf("ae"), "uae" to setOf("ae"),
        "qatar" to setOf("qa"), "bahrain" to setOf("bh"), "kuwait" to setOf("kw"), "oman" to setOf("om"),
        "jordan" to setOf("jo"), "lebanon" to setOf("lb"), "iraq" to setOf("iq"), "iran" to setOf("ir"),
        "pakistan" to setOf("pk"), "bangladesh" to setOf("bd"), "sri lanka" to setOf("lk"), "nepal" to setOf("np"),
        "china" to setOf("cn"), "hong kong" to setOf("hk"), "taiwan" to setOf("tw"), "japan" to setOf("jp"),
        "south korea" to setOf("kr"), "korea" to setOf("kr"), "thailand" to setOf("th"), "vietnam" to setOf("vn"),
        "malaysia" to setOf("my"), "singapore" to setOf("sg"), "indonesia" to setOf("id"), "philippines" to setOf("ph"),
        "chile" to setOf("cl"), "colombia" to setOf("co"), "peru" to setOf("pe"), "uruguay" to setOf("uy"),
        "paraguay" to setOf("py"), "ecuador" to setOf("ec"), "venezuela" to setOf("ve"), "bolivia" to setOf("bo"),
        "costa rica" to setOf("cr"), "jamaica" to setOf("jm"), "caribbean" to setOf("jm"),
        "latin america" to setOf("latam"), "middle east" to setOf("mena"), "mena" to setOf("mena"),
        "angola" to setOf("ao"), "cameroon" to setOf("cm"), "senegal" to setOf("sn"), "ivory coast" to setOf("ci"),
        "uganda" to setOf("ug"), "tanzania" to setOf("tz"), "zambia" to setOf("zm"), "zimbabwe" to setOf("zw"),
        "netherlands antilles" to setOf("an"), "holland" to setOf("nl"), "the netherlands" to setOf("nl"),
        "korea republic" to setOf("kr"), "republic of ireland" to setOf("ie", "uk", "en"), "northern ireland" to setOf("uk", "gb", "en"),
        "czech" to setOf("cz"), "türkiye" to setOf("tr"), "turkiye" to setOf("tr"),
    )

    /** Numele [folded] (fără diacritice, litere mici) e o țară cunoscută? */
    fun isCountry(folded: String): Boolean = folded in map

    /**
     * Prefixele de țară pentru numele [country]: gol doar dacă țara lipsește (atunci nu se filtrează). O țară
     * pe care n-o știm („Bangladesh” fără prefix în tabel) dă un prefix care nu e al niciunui canal: postul se
     * potrivește doar cu canalele fără țară în nume, nu cu „beIN Sports 2” din toate țările.
     */
    fun codes(country: String?): Set<String> {
        if (country.isNullOrBlank()) return emptySet()
        val f = Sport.fold(country).replace('-', ' ').trim()
        map[f]?.let { return it }
        return if (f.length in 2..3 && ' ' !in f) setOf(f) else setOf("?$f")
    }

    /** Țara scrisă la finalul unei adrese de post („/channels/digi-sport-4-romania/” → „romania”), dacă e cunoscută. */
    fun fromSlug(slug: String): String? {
        val words = slug.trim('/').substringAfterLast('/').split('-').filter { it.isNotEmpty() }
        for (n in minOf(3, words.size - 1) downTo 1) {
            val tail = words.takeLast(n).joinToString(" ")
            if (isCountry(tail)) return tail
        }
        return null
    }
}
