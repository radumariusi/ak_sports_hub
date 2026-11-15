package ro.ak.sportshub.core.sports

/**
 * Evenimentele fără două echipe: o cursă de F1 sau MotoGP, o etapă de ciclism, o zi de turneu de golf,
 * o gală de box sau MMA. Furnizorii le scriu diferit („Abu Dhabi Grand Prix”, „Formula 1: Marele Premiu
 * din Abu Dhabi - Cursa”, „GP Abu Dabi F1”), deci se compară doar cuvintele care le deosebesc (locul,
 * numele galei), fără cuvintele comune ale sportului și cu prefixe („qatar” = „qatarului”).
 */
object EventNames {

    /** Cuvinte care nu deosebesc evenimentele între ele (în engleză, română, spaniolă). */
    private val common = setOf(
        "grand", "prix", "gp", "gran", "premio", "premiu", "marele", "formula", "f1", "1", "2", "3", "one",
        "motogp", "moto", "moto2", "moto3", "indycar", "nascar", "wrc", "rally", "raliul", "rallye", "formula e",
        "race", "cursa", "carrera", "qualifying", "calificari", "calificarile", "clasificacion", "sprint", "practice",
        "antrenamente", "libres", "entrenamientos", "session", "sesiune", "fp1", "fp2", "fp3", "q1", "q2", "q3",
        "live", "direct", "en", "directo", "vivo", "in", "the", "of", "de", "del", "la", "le", "el", "al", "din",
        "si", "and", "y", "a", "di", "du", "des", "les", "at", "on", "vs", "v",
        "stage", "etapa", "tappa", "etape", "round", "runda", "day", "ziua", "dia", "jornada", "final", "finala",
        "semi", "semifinal", "semifinala", "quarter", "tour", "turul", "vuelta", "giro", "championship", "campionat",
        "campionatul", "campeonato", "world", "mondial", "mondiale", "mundial", "cup", "cupa", "copa", "open",
        "masters", "classic", "gala", "fight", "night", "card", "main", "prelims", "preliminarii", "event",
        "golf", "tennis", "tenis", "darts", "snooker", "boxing", "box", "boxeo", "mma", "ufc", "cycling", "ciclism",
        "ciclismo", "motorsport", "motociclism", "motociclismo", "automovilismo", "men", "women", "mens", "womens",
        "masculin", "feminin", "barbati", "femei", "2025", "2026", "2027",
    )

    private val digits = Regex("""\d+""")

    /** Cuvintele și numerele fiecărui nume, ținute minte: la asamblare același nume se compară de mii de ori. */
    private class Words(val significant: List<String>, val numbers: Set<String>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Words>()
    private const val CACHE_MAX = 50_000

    private fun words(name: String): Words {
        cache[name]?.let { return it }
        if (cache.size > CACHE_MAX) cache.clear()
        val folded = Sport.fold(name)
        return Words(
            significant = folded.split(' ').filter { it.length >= 2 && it !in common && !it.all(Char::isDigit) },
            numbers = digits.findAll(folded).map { it.value.trimStart('0') }.filter { it.isNotEmpty() && it.length <= 3 }.toSet(),
        ).also { cache[name] = it }
    }

    /** Cuvintele care deosebesc evenimentul (fără diacritice, litere mici). */
    fun significant(name: String): List<String> = words(name).significant

    /** Numerele din nume („UFC 310”, „Etapa 12”): dacă ambele nume au numere, trebuie să aibă unul comun. */
    private fun numbers(name: String): Set<String> = words(name).numbers

    private fun sameWord(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /**
     * Același eveniment, după nume? Dacă unul din nume nu are niciun cuvânt care să-l deosebească
     * („Formula 1 - Cursa”), răspunsul e null: decide sportul și ora.
     */
    fun same(a: String, b: String): Boolean? {
        val na = numbers(a)
        val nb = numbers(b)
        if (na.isNotEmpty() && nb.isNotEmpty() && na.intersect(nb).isEmpty()) return false
        val wa = significant(a)
        val wb = significant(b)
        if (wa.isEmpty() || wb.isEmpty()) return if (na.isNotEmpty() && nb.isNotEmpty()) true else null
        return wa.any { x -> wb.any { y -> sameWord(x, y) } }
    }

    /**
     * Două evenimente fără echipe sunt același: același sport (dacă amândouă îl au), ora la cel mult [windowMs]
     * și numele compatibile (sau unul din ele fără cuvinte care să-l deosebească).
     */
    fun match(nameA: String, sportA: Sport?, startA: Long, nameB: String, sportB: Sport?, startB: Long, windowMs: Long): Boolean {
        if (sportA != null && sportB != null && sportA != sportB) return false
        if (kotlin.math.abs(startA - startB) > windowMs) return false
        return when (same(nameA, nameB)) {
            true -> true
            false -> false
            // Fără nume care să decidă: doar dacă știm că e același sport.
            null -> sportA != null && sportA == sportB
        }
    }

    /** Cheia stabilă a evenimentului (pentru id-ul meciurilor doar din EPG sau din playlist). */
    fun key(name: String): String = significant(name).sorted().joinToString(" ").ifEmpty { Sport.fold(name) }
}
