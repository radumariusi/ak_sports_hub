package ro.ak.sportshub.core.common

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Cum a mers redarea unei variante la tine (C1), din ce face playerul oricum: cât a durat pornirea, de câte ori s-a
 * oprit să încarce sau a căzut (pe oră de vizionare) și câte porniri n-au reușit. Plus limbile declarate de pistele
 * audio ale stream-ului (C2). Nicio cerere în plus și nicio verificare separată a stream-ului.
 */
data class PlaybackHealth(
    /** Pornirile reușite (s-a văzut imagine). */
    val plays: Int = 0,
    /** Pornirile care n-au ajuns la imagine (eroare, playlist ocupat nu se numără). */
    val fails: Int = 0,
    /** Timpul de pornire (media ultimelor porniri, ms); 0 = necunoscut. */
    val startMs: Int = 0,
    /** Opririle pentru încărcare și căderile, pe oră de vizionare (media ultimelor sesiuni). */
    val stallsPerHour: Float = 0f,
    /** Limbile pistelor audio văzute la ultima redare („en”, „es”), în ordinea din stream. */
    val languages: List<String> = emptyList(),
    val at: Long = 0,
) {
    enum class Verdict { STABLE, UNKNOWN, UNSTABLE }

    val verdict: Verdict
        get() = when {
            fails >= 2 && fails * 2 >= plays + fails -> Verdict.UNSTABLE
            plays >= 2 && stallsPerHour > SourceHealth.UNSTABLE_STALLS -> Verdict.UNSTABLE
            plays >= SourceHealth.MIN_PLAYS && fails * 4 <= plays && startMs in 1..SourceHealth.STABLE_START_MS &&
                stallsPerHour <= SourceHealth.STABLE_STALLS -> Verdict.STABLE
            else -> Verdict.UNKNOWN
        }

    /** Pentru ordine: 0 = stabilă, 1 = nu se știe, 2 = instabilă. */
    val rank: Int
        get() = when (verdict) {
            Verdict.STABLE -> 0
            Verdict.UNKNOWN -> 1
            Verdict.UNSTABLE -> 2
        }

    /** „Stabilă: 5 porniri, pornește în 2,1 s, fără opriri” (pentru „de ce e aici sursa”). */
    val summary: String?
        get() {
            if (plays + fails == 0) return null
            val head = when (verdict) {
                Verdict.STABLE -> "Stabilă la tine"
                Verdict.UNSTABLE -> "Instabilă la tine"
                Verdict.UNKNOWN -> "Redată la tine"
            }
            val parts = ArrayList<String>()
            parts += if (plays == 1) "o pornire" else "$plays porniri"
            if (fails > 0) parts += if (fails == 1) "una n-a pornit" else "$fails n-au pornit"
            if (plays > 0 && startMs > 0) parts += "pornește în " + String.format(Locale.forLanguageTag("ro"), "%.1f s", startMs / 1000f)
            if (plays > 0) {
                val r = stallsPerHour.roundToInt()
                parts += when {
                    stallsPerHour < 0.5f -> "fără opriri"
                    r <= 1 -> "~1 oprire pe oră"
                    else -> "~$r opriri pe oră"
                }
            }
            return head + ": " + parts.joinToString(", ")
        }
}

/** O sesiune de redare a unei variante, de la pornire până la oprire (sau trecerea la altă variantă). */
data class PlaybackSession(
    /** S-a văzut imagine. */
    val started: Boolean,
    /** De la cererea stream-ului până la prima imagine (ms), dacă a pornit. */
    val startMs: Long? = null,
    /** Cât s-a văzut (ms). */
    val watchedMs: Long = 0,
    /** Opririle pentru încărcare după pornire, plus reconectările (căderile). */
    val stalls: Int = 0,
)

/**
 * Sănătatea surselor învățată din redare (C1) și limba reală a sunetului (C2).
 *
 * - O variantă e „stabilă” după cel puțin [MIN_PLAYS] porniri reușite, aproape fără eșecuri, pornire sub
 *   [STABLE_START_MS] și cel mult [STABLE_STALLS] opriri pe oră; primește o bulină și urcă la calitate egală.
 * - E „instabilă” când pornește rar (jumătate din încercări eșuate) sau se oprește des: coboară la calitate egală.
 * - Media se face pe ultimele sesiuni (cele vechi contează tot mai puțin), ca o variantă reparată de furnizor să-și revină.
 */
object SourceHealth {

    const val MIN_PLAYS = 2
    const val STABLE_START_MS = 8_000
    const val STABLE_STALLS = 4f
    const val UNSTABLE_STALLS = 20f

    /** Peste atâtea sesiuni, numărătorile se înjumătățesc (contează sesiunile recente). */
    private const val MAX_COUNT = 12

    /** Ponderea sesiunii noi în medii. */
    private const val ALPHA = 0.3f

    /** Sesiunile mai scurte nu schimbă media opririlor (zapping), dacă n-au avut nicio oprire. */
    private const val MIN_WATCH_MS = 60_000L

    /** Rata se socotește pe cel puțin atât (o oprire într-un minut nu înseamnă 60 pe oră). */
    private const val MIN_RATE_WINDOW_MS = 5 * 60_000L

    fun after(prev: PlaybackHealth?, s: PlaybackSession, now: Long): PlaybackHealth {
        val p = prev ?: PlaybackHealth()
        var next = if (!s.started) {
            p.copy(fails = p.fails + 1, at = now)
        } else {
            val start = s.startMs?.takeIf { it > 0 }?.let { ms ->
                if (p.startMs <= 0) ms.toInt() else (p.startMs + ALPHA * (ms - p.startMs)).roundToInt()
            } ?: p.startMs
            val stalls = if (s.watchedMs >= MIN_WATCH_MS || s.stalls > 0) {
                val rate = s.stalls * 3_600_000f / maxOf(s.watchedMs, MIN_RATE_WINDOW_MS)
                if (p.plays == 0) rate else p.stallsPerHour + ALPHA * (rate - p.stallsPerHour)
            } else {
                p.stallsPerHour
            }
            p.copy(plays = p.plays + 1, startMs = start, stallsPerHour = stalls, at = now)
        }
        if (next.plays + next.fails > MAX_COUNT) {
            next = next.copy(plays = (next.plays + 1) / 2, fails = next.fails / 2)
        }
        return next
    }

    /** Limbile declarate de pistele audio („eng”, „spa”, „und”) → coduri ISO 639-1, fără „und”, fără dubluri. */
    fun audioLanguages(raw: List<String?>): List<String> = raw.mapNotNull { Languages.code(it) }.distinct()

    /**
     * Limba sursei (C2): corecția ta are prioritate (se aplică înainte, în playlist); apoi, dacă stream-ul declară
     * limbi la piste: limba din playlist dacă e printre ele, altfel prima pistă (cea implicită). Fără piste
     * declarate rămâne limba din playlist.
     */
    fun language(playlist: String?, observed: List<String>): String? {
        if (observed.isEmpty()) return playlist
        val p = playlist?.lowercase()
        return if (p != null && p in observed) playlist else observed.first()
    }
}
