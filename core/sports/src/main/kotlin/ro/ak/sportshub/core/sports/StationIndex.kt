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
class StationIndex(groupKeys: Collection<String>) {

    private val byKey: Map<String, List<Pair<String?, String>>> = groupKeys.distinct()
        .map { gk ->
            val country = gk.substringBefore(':', "").ifEmpty { null }
            val core = if (country != null) gk.substringAfter(':') else gk
            Triple(compact(core), country, gk)
        }
        .filter { it.first.isNotEmpty() }
        .groupBy({ it.first }, { it.second to it.third })

    /** Cheile de grupare ale canalelor care sunt postul [station]. */
    fun find(station: TvStation): List<String> {
        val parsed = ChannelNameParser.parse(station.name)
        val core = parsed.groupKey.substringAfter(':')
        val candidates = byKey[compact(core)] ?: return emptyList()
        val wanted = Countries.codes(station.country) + listOfNotNull(parsed.country?.lowercase())
        if (wanted.isEmpty()) return candidates.map { it.second }
        // Canalele fără țară în nume se potrivesc oricum; cele cu altă țară, nu.
        return candidates.filter { (c, _) -> c == null || c in wanted }.map { it.second }
    }

    companion object {
        private val numbers = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "unu" to "1", "doi" to "2", "trei" to "3")
        private val dropped = setOf("tv", "channel", "the", "network")

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
        "france" to setOf("fr"), "italy" to setOf("it"), "spain" to setOf("es"), "portugal" to setOf("pt"),
        "netherlands" to setOf("nl"), "belgium" to setOf("be"), "poland" to setOf("pl"), "hungary" to setOf("hu"),
        "turkey" to setOf("tr"), "greece" to setOf("gr"), "bulgaria" to setOf("bg"), "serbia" to setOf("rs"),
        "croatia" to setOf("hr"), "czech republic" to setOf("cz"), "czechia" to setOf("cz"), "slovakia" to setOf("sk"),
        "brazil" to setOf("br"), "sweden" to setOf("se"), "denmark" to setOf("dk"), "norway" to setOf("no"),
        "finland" to setOf("fi"), "albania" to setOf("al"), "russia" to setOf("ru"), "ukraine" to setOf("ua"),
        "slovenia" to setOf("si"), "bosnia and herzegovina" to setOf("ba"), "north macedonia" to setOf("mk"),
        "mexico" to setOf("mx"), "argentina" to setOf("ar"), "india" to setOf("in"),
    )

    /** Prefixele de țară pentru numele [country] (gol dacă nu-l știm: atunci nu se filtrează). */
    fun codes(country: String?): Set<String> {
        if (country.isNullOrBlank()) return emptySet()
        val f = Sport.fold(country)
        map[f]?.let { return it }
        return if (f.length in 2..3) setOf(f) else emptySet()
    }
}
