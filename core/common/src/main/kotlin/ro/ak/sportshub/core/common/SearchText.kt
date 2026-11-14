package ro.ak.sportshub.core.common

/**
 * Textul după care se caută: fără diacritice, litere mici, doar litere și cifre separate de spații.
 * Același tratament se aplică la import (coloana indexată FTS) și la ce tastezi tu.
 */
object SearchText {

    private val nonWord = Regex("""[^a-z0-9]+""")

    fun of(vararg parts: String?): String =
        parts.filterNotNull()
            .joinToString(" ") { ChannelNameParser.fold(it) }
            .replace(nonWord, " ")
            .trim()

    fun tokens(query: String): List<String> = of(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Interogare FTS4: fiecare cuvânt tastat trebuie să apară ca început de cuvânt.
     * „sky main” → `sky* main*`. Null dacă nu rămâne nimic de căutat.
     */
    fun ftsQuery(query: String): String? =
        tokens(query).takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

    /** Aceeași regulă, pentru liste mici ținute în memorie (de exemplu grupurile). */
    fun matches(text: String, query: String): Boolean {
        val wanted = tokens(query)
        if (wanted.isEmpty()) return true
        val words = of(text).split(' ')
        return wanted.all { w -> words.any { it.startsWith(w) } }
    }
}
