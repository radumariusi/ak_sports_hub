package ro.ak.sportshub.core.sports

import java.util.concurrent.ConcurrentHashMap

/**
 * Urmărirea unui nume (jucător, pilot, luptător): orice emisiune din EPG care îl pomenește (titlu, subtitlu sau
 * începutul descrierii) apare în Sports Hub, ca favorit. Numele se compară fără diacritice și majuscule, pe cuvinte
 * întregi („Ion” nu prinde „Ionescu”).
 */
object FollowedNames {

    /** Cel mai scurt nume urmărit (sub atât ar prinde prea multe emisiuni). */
    const val MIN_LENGTH = 3

    /** Câte emisiuni urmărite intră cel mult în listă (cele mai apropiate de acum). */
    const val MAX_MATCHES = 60

    /** Textele împăturite (fără diacritice, litere mici), ținute minte: titlurile se repetă pe canale și zile. */
    private val folded = ConcurrentHashMap<String, String>()
    private const val MAX_CACHE = 20_000

    private fun fold(text: String): String {
        folded[text]?.let { return it }
        if (folded.size > MAX_CACHE) folded.clear()
        return Sport.fold(text).also { folded[text] = it }
    }

    /** Numele curățat pentru salvare; null dacă e prea scurt. */
    fun normalize(name: String): String? = name.trim().replace(Regex("""\s+"""), " ").takeIf { Sport.fold(it).length >= MIN_LENGTH }

    /** [text] pomenește [name] (cuvinte întregi, fără diacritice). */
    fun mentions(name: String, text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val n = fold(name)
        if (n.length < MIN_LENGTH) return false
        val t = fold(text)
        var i = t.indexOf(n)
        while (i >= 0) {
            val before = i == 0 || t[i - 1] == ' '
            val after = i + n.length == t.length || t[i + n.length] == ' '
            if (before && after) return true
            i = t.indexOf(n, i + 1)
        }
        return false
    }

    fun mentionsAny(names: List<String>, vararg texts: String?): Boolean =
        names.any { n -> texts.any { mentions(n, it) } }

    /** Primul nume urmărit pomenit de emisiune (titlu, subtitlu, începutul descrierii); null dacă niciunul. */
    fun nameIn(names: List<String>, b: EpgBroadcast): String? =
        names.firstOrNull { n -> mentions(n, b.title) || mentions(n, b.subtitle) || mentions(n, b.description) }

    /**
     * Emisiunile urmărite devenite „meciuri” (evenimente fără două echipe): aceeași emisiune pe mai multe canale
     * (același titlu, aceeași oră) e un singur meci, cu toate canalele ca surse EPG. Emisiunile terminate nu intră;
     * cel mult [MAX_MATCHES], cele mai apropiate de acum.
     */
    fun matches(broadcasts: List<EpgBroadcast>, names: List<String>, now: Long): List<Match> {
        if (names.isEmpty()) return emptyList()
        return broadcasts.asSequence()
            .filter { it.stop > now }
            .mapNotNull { b -> nameIn(names, b)?.let { b to it } }
            .groupBy { (b, _) -> fold(b.title) + "|" + b.start }
            .values
            .map { group ->
                val first = group.minBy { it.first.programmeId }.first
                val name = group.first().second
                val start = group.minOf { it.first.start }
                val end = group.maxOf { it.first.stop }
                val subtitle = first.subtitle?.takeIf { it.isNotBlank() && !mentions(it, first.title) }
                Match(
                    id = "$PROVIDER:${fold(first.title).replace(' ', '-')}:$start",
                    sport = Sport.detect(first.title, first.subtitle, first.category, first.description),
                    competition = "Urmărit: $name",
                    home = if (subtitle != null) "${first.title}: $subtitle" else first.title,
                    away = "",
                    start = start,
                    end = end,
                    status = when {
                        now < start -> MatchStatus.SCHEDULED
                        now < end -> MatchStatus.LIVE
                        else -> MatchStatus.FINISHED
                    },
                    broadcasts = group.map { it.first }.sortedBy { it.start },
                    providers = setOf(PROVIDER),
                )
            }
            .sortedBy { it.start }
            .take(MAX_MATCHES)
    }

    /** Furnizorul meciurilor din numele urmărite (doar EPG-ul tău). */
    const val PROVIDER = "urmarit"
}
