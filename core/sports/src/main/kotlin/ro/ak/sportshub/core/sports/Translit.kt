package ro.ak.sportshub.core.sports

/**
 * Literele din alte alfabete aduse la litere latine, ca un titlu EPG în rusă, ucraineană, sârbă, bulgară, greacă sau
 * arabă să poată fi comparat cu numele echipelor („Реал Мадрид” → „real madrid”). Plus câteva litere latine care nu
 * se descompun (ı, ł, ø, æ, đ). Se aplică după ce diacriticele au fost scoase și textul e cu litere mici; textul
 * fără astfel de litere (aproape tot) trece neatins, fără nicio copie.
 */
internal object Translit {

    private val table: Map<Char, String> = buildMap {
        // Chirilică (rusă, ucraineană, bulgară, sârbă, macedoneană). „й” ajunge „и” după scoaterea diacriticelor.
        val cyr = "абвгдежзиклмнопрстуфхцчшщъыьэюяёіїєґђјљњћџѓќѕ"
        val cyrLatin = listOf(
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts",
            "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "e", "i", "yi", "ye", "g", "dj", "j", "lj", "nj", "c", "dz",
            "g", "k", "dz",
        )
        cyr.forEachIndexed { i, c -> put(c, cyrLatin[i]) }
        // Greacă (fără accente, scoase înainte).
        val greek = "αβγδεζηθικλμνξοπρσςτυφχψω"
        val greekLatin = listOf("a", "v", "g", "d", "e", "z", "i", "th", "i", "k", "l", "m", "n", "x", "o", "p", "r", "s", "s", "t", "y", "f", "ch", "ps", "o")
        greek.forEachIndexed { i, c -> put(c, greekLatin[i]) }
        // Arabă: o transcriere simplă, aceeași pentru titluri și pentru dicționarul de nume.
        val arabic = mapOf(
            'ا' to "a", 'أ' to "a", 'إ' to "a", 'آ' to "a", 'ٱ' to "a", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j",
            'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "dh", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s",
            'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ك' to "k", 'ل' to "l",
            'م' to "m", 'ن' to "n", 'ه' to "h", 'و' to "w", 'ي' to "y", 'ى' to "a", 'ة' to "a", 'ء' to "", 'ئ' to "y",
            'ؤ' to "w", 'پ' to "p", 'گ' to "g", 'ڤ' to "v", 'ی' to "y", 'ک' to "k",
        )
        putAll(arabic)
        // Litere latine care nu se descompun în literă + diacritic.
        putAll(mapOf('ı' to "i", 'ł' to "l", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ŀ' to "l"))
    }

    /** [text] (deja fără diacritice, cu litere mici) cu literele din [table] înlocuite. */
    fun latin(text: String): String {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code >= 0x0100 && table.containsKey(c)) break
            i++
        }
        if (i == text.length) return text
        val sb = StringBuilder(text.length + 8)
        sb.append(text, 0, i)
        while (i < text.length) {
            val c = text[i]
            val r = if (c.code >= 0x0100) table[c] else null
            if (r != null) sb.append(r) else sb.append(c)
            i++
        }
        return sb.toString()
    }
}
