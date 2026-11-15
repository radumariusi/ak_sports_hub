package ro.ak.sportshub.core.sports

/**
 * Catalogul de nume pentru favorite: echipele, sportivii și competițiile din programul descărcat deja de la furnizori
 * (fără cereri în plus), fiecare cu formele lui („Manchester United”, „Man United”, „MUN”). Când scrii un favorit,
 * [search] îți propune numele întreg; [teamAliases] învață `TeamNames` că formele scurte sunt aceeași echipă.
 */
class NameCatalog private constructor(private val entries: Map<String, Entry>) {

    enum class Kind { TEAM, ATHLETE, COMPETITION }

    /** [name] = forma întreagă; [alt] = celelalte forme; [seen] = de câte ori a adus ceva nou (cele mai cunoscute primele). */
    data class Entry(val name: String, val kind: Kind, val alt: Set<String>, val seen: Int)

    val size: Int get() = entries.size

    /** O carte nouă cu ce aduc [events], sau aceeași, dacă nu s-a schimbat nimic de folos. */
    fun learn(events: Collection<SportEvent>): NameCatalog {
        var next: HashMap<String, Entry>? = null
        fun add(name: String?, kind: Kind, alt: List<String>) {
            val n = name?.trim().orEmpty()
            if (n.length < 2 || n.length > MAX_NAME) return
            val key = kind.name + ":" + Sport.fold(n)
            if (key.length <= kind.name.length + 1) return
            val cur = (next ?: entries)[key]
            val alts = alt.filter { it.length in 2..MAX_NAME && Sport.fold(it) != Sport.fold(n) }
            // Deja știut, fără forme noi: nimic de schimbat (fișierul nu se rescrie la fiecare calcul).
            if (cur != null && cur.alt.containsAll(alts)) return
            val merged = Entry(cur?.name ?: n, kind, (cur?.alt.orEmpty() + alts).take(MAX_ALT).toSet(), ((cur?.seen ?: 0) + 1).coerceAtMost(MAX_SEEN))
            if (merged != cur) {
                if (next == null) next = HashMap(entries)
                next!![key] = merged
            }
        }
        for (e in events) {
            add(e.home, Kind.TEAM, e.homeAlt)
            if (e.away.isNotBlank()) add(e.away, Kind.TEAM, e.awayAlt)
            add(e.competition, Kind.COMPETITION, e.competition?.substringBefore(" · ")?.let { listOf(it) }.orEmpty())
            for ((name, short) in e.athletes) add(name, Kind.ATHLETE, listOfNotNull(short))
            // Tenisul: cei doi jucători sunt și sportivi (pentru „Nume urmărite”).
            if (e.sport == Sport.TENNIS.sportsDbName && e.away.isNotBlank() && '/' !in e.home && '/' !in e.away) {
                add(e.home, Kind.ATHLETE, e.homeAlt)
                add(e.away, Kind.ATHLETE, e.awayAlt)
            }
        }
        val n = next ?: return this
        // Prea mare: rămân cele mai văzute.
        val kept = if (n.size <= MAX_ENTRIES) n else n.entries.sortedByDescending { it.value.seen }.take(MAX_ENTRIES).associate { it.key to it.value }
        return NameCatalog(kept)
    }

    /**
     * Numele care se potrivesc cu [query] (ce scrii), cele mai bune primele: forma exactă, apoi toate cuvintele scrise
     * la începutul cuvintelor numelui („man u” → Manchester United), apoi codul („mun”). Fără diacritice, puncte,
     * majuscule; prescurtările știute („Man Utd”) trec prin `TeamNames`.
     */
    fun search(query: String, kinds: Set<Kind> = Kind.entries.toSet(), limit: Int = 8): List<Entry> {
        val q = Sport.fold(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val qKey = TeamNames.key(query)
        val scored = ArrayList<Pair<Int, Entry>>()
        // Lista fixă din aplicație (competițiile cu prescurtări, snooker, darts, F1) completează ce s-a învățat.
        val seeds = NameSeeds.entries
        val all = entries.asSequence().map { (k, e) -> seeds[k]?.let { e.copy(alt = e.alt + it.alt) } ?: e } +
            seeds.asSequence().filter { (k, _) -> k !in entries }.map { it.value }
        for (e in all) {
            if (e.kind !in kinds) continue
            var best = Int.MAX_VALUE
            for (form in listOf(e.name) + e.alt) {
                val f = Sport.fold(form)
                val score = when {
                    f == q -> 0
                    e.kind == Kind.TEAM && form === e.name && TeamNames.key(form) == qKey -> 1
                    f.startsWith(q) -> 2
                    qTokens.all { t -> f.split(' ').any { it.startsWith(t) } } -> 3
                    q.length >= 3 && f.replace(" ", "").contains(q.replace(" ", "")) -> 5
                    else -> Int.MAX_VALUE
                }
                if (score < best) best = score
            }
            if (best != Int.MAX_VALUE) scored += best to e
        }
        return scored.sortedWith(compareBy<Pair<Int, Entry>> { it.first }.thenByDescending { it.second.seen }.thenBy { it.second.name.length })
            .asSequence().map { it.second }
            // Aceeași echipă scrisă altfel (din lista fixă și de la ESPN) apare o dată.
            .distinctBy { e -> e.kind.name + ":" + if (e.kind == Kind.TEAM) TeamNames.key(e.name) else Sport.fold(e.name) }
            .take(limit).toList()
    }

    /**
     * Formele scurte de echipe care duc la o singură echipă (nu codurile de 2–4 litere, care se repetă între sporturi):
     * „man united” → cheia lui „Manchester United”. Pentru `TeamNames.setLearnedAliases`.
     */
    fun teamAliases(): Map<String, String> {
        val owners = HashMap<String, MutableSet<String>>()
        for (e in entries.values) {
            if (e.kind != Kind.TEAM) continue
            val target = TeamNames.key(e.name)
            for (a in e.alt) {
                val f = Sport.fold(a)
                if (f.length < 5 || !f.contains(' ') && f.length < 6) continue
                owners.getOrPut(f) { HashSet() } += target
            }
        }
        // O formă scurtă care e și numele întreg al altei echipe nu se ia („Inter” față de „Inter Miami”).
        val fullNames = entries.values.filter { it.kind == Kind.TEAM }.map { Sport.fold(it.name) }.toSet()
        return owners.filter { (alias, t) -> t.size == 1 && alias !in fullNames && t.first() != alias }.mapValues { it.value.first() }
    }

    /** Un rând pe nume: „TEAM\tnumele\tvăzut\tformă1|formă2”. */
    fun encode(): String = buildString {
        append(VERSION).append('\n')
        entries.values.forEach { e ->
            append(e.kind.name).append('\t').append(e.name.replace('\t', ' ')).append('\t').append(e.seen).append('\t')
                .append(e.alt.joinToString("|") { it.replace('|', ' ').replace('\t', ' ') }).append('\n')
        }
    }

    companion object {
        private const val VERSION = "nume-1"
        private const val MAX_NAME = 60
        private const val MAX_ALT = 6
        private const val MAX_SEEN = 1_000
        private const val MAX_ENTRIES = 20_000

        val EMPTY = NameCatalog(emptyMap())

        fun decode(text: String?): NameCatalog {
            val lines = text?.lineSequence()?.iterator() ?: return EMPTY
            if (!lines.hasNext() || lines.next().trim() != VERSION) return EMPTY
            val map = HashMap<String, Entry>()
            while (lines.hasNext()) {
                val p = lines.next().split('\t')
                if (p.size < 4) continue
                val kind = runCatching { Kind.valueOf(p[0]) }.getOrNull() ?: continue
                val name = p[1]
                map[kind.name + ":" + Sport.fold(name)] = Entry(name, kind, p[3].split('|').filter { it.isNotBlank() }.toSet(), p[2].toIntOrNull() ?: 1)
            }
            return NameCatalog(map)
        }
    }
}
