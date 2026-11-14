package ro.ak.sportshub.core.common.player

/**
 * Reconectarea după o întrerupere: pauze tot mai lungi, apoi renunță (și eliberează conexiunea).
 * Încercările se fac una câte una; stream-ul vechi e oprit înainte de fiecare încercare.
 */
class ReconnectBackoff(
    private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
) {
    var attempt: Int = 0
        private set

    val maxAttempts: Int get() = delaysMs.size

    /** Pauza dinaintea următoarei încercări, sau null dacă s-au terminat încercările. */
    fun next(): Long? {
        val delay = delaysMs.getOrNull(attempt) ?: return null
        attempt++
        return delay
    }

    /** După ce stream-ul a mers stabil o vreme sau la schimbarea canalului. */
    fun reset() {
        attempt = 0
    }
}

/** Textele de eroare pentru cod HTTP, fără adrese sau date de acces. */
object PlaybackErrors {

    fun forHttpStatus(code: Int): String = when (code) {
        401, 403 -> "Serverul a refuzat accesul (cont expirat sau altă conexiune activă pe cont)."
        404, 410 -> "Canalul nu mai există pe server."
        429, 458, 503, 509 -> "Serverul spune că limita de conexiuni e atinsă."
        in 500..599 -> "Serverul are o problemă (eroare $code)."
        else -> "Serverul a răspuns cu eroarea $code."
    }

    /** Erori după care nu are rost să insistăm mult: contul sau canalul nu mai sunt valabile. */
    fun isPermanent(code: Int): Boolean = code == 401 || code == 404 || code == 410
}
