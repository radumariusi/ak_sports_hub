package ro.ak.sportshub.core.common

/**
 * Textele din jurnalul de diagnostic (trimis pe GitHub la cererea ta): adrese fără chei, răspunsuri scurtate și
 * împărțite în bucăți care încap într-un comentariu GitHub. Fără nimic din playlisturi.
 */
object DiagnosticText {

    private val spaces = Regex("\\s+")

    /** Un comentariu GitHub are cel mult 65.536 de caractere; rămâne loc pentru titlu și blocul de cod. */
    const val MAX_CHUNK = 60_000

    /** Cheile gratuite, publice, care pot rămâne în adresă. */
    private val publicKeys = setOf("123", "3")

    private val secretParams = setOf("key", "apikey", "api_key", "token", "access_token", "password", "pass", "username", "user", "auth", "sig", "signature")

    /**
     * Adresa fără chei: cheia TheSportsDB din cale (`/json/<cheie>/`, dacă nu e cea gratuită) și parametrii cu
     * nume de cheie sau parolă (`?token=…`) devin „***”.
     */
    fun sanitizeUrl(url: String): String {
        var u = url
        val marker = "/json/"
        val at = u.indexOf(marker)
        if (at >= 0) {
            val from = at + marker.length
            val end = u.indexOf('/', from)
            if (end > from) {
                val key = u.substring(from, end)
                if (key !in publicKeys) u = u.substring(0, from) + "***" + u.substring(end)
            }
        }
        val q = u.indexOf('?')
        if (q < 0) return u
        val params = u.substring(q + 1).split('&').joinToString("&") { p ->
            val name = p.substringBefore('=')
            if (name.lowercase() in secretParams && p.contains('=')) "$name=***" else p
        }
        return u.substring(0, q + 1) + params
    }

    /**
     * Un răspuns scurtat pentru jurnal. HTML: fără conținutul `<script>` și `<style>` (în afară de JSON-ul din
     * `__NEXT_DATA__` sau `application/json`, care poartă datele), fără comentarii, spațiile strânse. JSON: așa cum e.
     * Cel mult [max] caractere, cu nota „… (tăiat, N caractere în total)”.
     */
    fun condense(body: String, max: Int = MAX_CHUNK * 2): String {
        val trimmed = body.trimStart()
        val isHtml = trimmed.startsWith("<")
        val text = if (isHtml) condenseHtml(body) else body
        return cut(text, max)
    }

    fun cut(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "\n… (tăiat, ${text.length} caractere în total)"

    private fun condenseHtml(html: String): String {
        val out = StringBuilder(minOf(html.length, 400_000))
        var i = 0
        val lower = html.lowercase()
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                appendSquashed(out, html, i, html.length)
                break
            }
            appendSquashed(out, html, i, lt)
            when {
                lower.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) html.length else end + 3
                }
                lower.startsWith("<script", lt) || lower.startsWith("<style", lt) -> {
                    val tagEnd = html.indexOf('>', lt)
                    if (tagEnd < 0) { i = html.length; continue }
                    val tag = html.substring(lt, tagEnd + 1)
                    val name = if (lower.startsWith("<script", lt)) "script" else "style"
                    val close = lower.indexOf("</$name", tagEnd)
                    val inner = if (close < 0) "" else html.substring(tagEnd + 1, close)
                    val keep = name == "script" && (tag.contains("__NEXT_DATA__") || tag.contains("application/json") || tag.contains("application/ld+json"))
                    out.append(tag)
                    if (keep) out.append(inner.trim()) else if (inner.isNotBlank()) out.append("…")
                    out.append("</").append(name).append('>')
                    i = if (close < 0) html.length else (html.indexOf('>', close).takeIf { it >= 0 }?.plus(1) ?: html.length)
                }
                else -> {
                    val end = html.indexOf('>', lt)
                    if (end < 0) {
                        out.append(html, lt, html.length)
                        i = html.length
                    } else {
                        out.append(html, lt, end + 1)
                        i = end + 1
                    }
                }
            }
        }
        return out.toString()
    }

    /** Textul dintre etichete, cu orice șir de spații (și rânduri) strâns într-un singur spațiu sau rând. */
    private fun appendSquashed(out: StringBuilder, s: String, from: Int, to: Int) {
        var space = false
        var newline = false
        for (k in from until to) {
            val c = s[k]
            if (c.isWhitespace()) {
                space = true
                if (c == '\n') newline = true
            } else {
                if (space) out.append(if (newline) '\n' else ' ')
                space = false
                newline = false
                out.append(c)
            }
        }
        if (space) out.append(if (newline) '\n' else ' ')
    }

    /** Textul împărțit în bucăți de cel mult [max] caractere, de preferat la un capăt de rând. */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end)
                if (nl > start + max / 2) end = nl + 1
            }
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    /**
     * Un răspuns JSON care e de fapt un refuz (`{"error": {"code": 403, "reason": "challenge"}}`, ca la Sofascore):
     * nu înlocuiește fișierul bun. Întoarce motivul scurt sau null dacă nu e o eroare.
     */
    fun jsonError(body: String): String? {
        val t = body.trimStart()
        if (!t.startsWith("{")) return null
        // Doar cheia de la începutul obiectului (un răspuns bun poate avea „error” undeva în interior).
        val head = t.drop(1).trimStart()
        if (!head.startsWith("\"error\"") && !head.startsWith("\"message\"") && !head.startsWith("\"errors\"")) return null
        if (head.startsWith("\"message\"") && t.length > 2_000) return null
        return cut(t.replace(spaces, " "), 200)
    }
}
