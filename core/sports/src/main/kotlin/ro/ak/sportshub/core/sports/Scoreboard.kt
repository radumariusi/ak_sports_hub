package ro.ak.sportshub.core.sports

import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Codurile de trei litere ale echipelor, pentru tabela de scor din player („ARS 1–0 CHE”). */
object TeamCodes {

    /** Prefixe și sufixe de club care nu spun nimic despre echipă. */
    private val noise = setOf(
        "FC", "CF", "AC", "AS", "SC", "AFC", "CFC", "SV", "SS", "SSC", "US", "FK", "SK", "NK", "KV", "RC", "RCD", "CD",
        "CA", "UD", "SD", "VFB", "VFL", "TSG", "BV", "BSC", "OSC", "OGC", "THE", "CS", "ACS", "SCM", "AFK",
    )

    /**
     * „Arsenal” → ARS, „Manchester City” → MCI, „Real Madrid” → RMA, „AC Milan” → MIL, „CFR Cluj” → CFR, „FCSB” → FCS.
     * Un cuvânt de trei majuscule (CFR, PSG) rămâne cum e; altfel prima literă a primului cuvânt și primele două ale
     * celui de-al doilea, ori primele trei litere ale unui singur cuvânt.
     */
    fun of(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = plain.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return name.take(3).uppercase()
        words.firstOrNull { it.length == 3 && it.all { c -> c.isUpperCase() } && it.uppercase() !in noise }?.let { return it }
        val meaningful = words.filter { it.uppercase() !in noise && it.any { c -> c.isLetter() } }.ifEmpty { words }
        val code = when {
            meaningful.size >= 2 && meaningful[0].length > 1 && meaningful[1].length >= 2 ->
                meaningful[0].take(1) + meaningful[1].take(2)
            else -> meaningful.joinToString("").take(3)
        }
        return code.uppercase()
    }

    private val shortWords = mapOf(
        "manchester" to "Man", "united" to "Utd", "athletic" to "Ath.", "atletico" to "Atl.", "atlético" to "Atl.",
        "borussia" to "B.", "saint" to "St", "sporting" to "Sp.", "olympique" to "OL", "internazionale" to "Inter",
        "university" to "Univ.", "universitatea" to "U", "state" to "St.", "wanderers" to "W.", "hotspur" to "",
        "albion" to "", "rovers" to "R.", "city" to "City", "county" to "Co.", "national" to "Nat.",
    )

    /**
     * Forme tot mai scurte ale numelui, pentru locurile înguste (panoul din dreapta): numele întreg, fără prefixele
     * de club (FC, AFC…), cu cuvintele lungi prescurtate („Manchester United” → „Man Utd”), apoi primul cuvânt
     * cu inițialele celorlalte, la final codul de 3 litere. Fără duplicate, în ordinea asta.
     */
    fun shorter(name: String): List<String> {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        val noNoise = words.filter { it.uppercase().trimEnd('.') !in noise }.ifEmpty { words }
        val abbreviated = noNoise.mapNotNull { w -> shortWords[w.lowercase()]?.let { it.ifEmpty { null } } ?: w.takeIf { shortWords[w.lowercase()] == null } }
            .ifEmpty { noNoise }
        val initials = if (abbreviated.size > 1) abbreviated.first() + " " + abbreviated.drop(1).joinToString(" ") { it.take(1) + "." } else null
        return listOfNotNull(name.trim(), noNoise.joinToString(" "), abbreviated.joinToString(" "), initials, of(name))
            .filter { it.isNotBlank() }.distinct()
    }
}

/** Ce scrie pe tabela de scor și în panoul meciului din player. */
object Scoreboard {

    private val minute = Regex("(\\d+(?:\\+\\d+)?)'\\s*$")

    /**
     * Ceasul tabelei din eticheta stării: „Repriza 2 · 67'” → 67′, „Reluare · 23'” → 23′, „Pauză” → PAUZĂ,
     * „Final” → FINAL, „Sfertul 3” → Q3. Null când nu e nimic de arătat.
     */
    fun clock(label: String?): String? {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return null
        minute.find(l)?.let { return it.groupValues[1] + "′" }
        val last = l.substringAfterLast(" · ").lowercase()
        return when {
            last.startsWith("pauză") || last.startsWith("între perioade") -> "PAUZĂ"
            last.startsWith("final") -> "FINAL"
            last.startsWith("sfertul ") -> "Q" + last.removePrefix("sfertul ").trim()
            last.startsWith("prelungiri") -> "PREL."
            last.startsWith("lovituri") -> "PEN."
            else -> null
        }
    }

    /** Posesia (gazde, oaspeți) în procente, din statistici („58” sau „58%”); null dacă lipsește. */
    fun possession(stats: List<StatLine>): Pair<Int, Int>? {
        val line = stats.firstOrNull { it.name.startsWith("Posesie") } ?: return null
        val h = line.home.trim().trimEnd('%').trim().toDoubleOrNull() ?: return null
        val a = line.away.trim().trimEnd('%').trim().toDoubleOrNull() ?: return null
        if (h + a <= 0) return null
        return Math.round(h).toInt() to Math.round(a).toInt()
    }

    /** O statistică după numele ei (de exemplu „Șuturi pe poartă”). */
    fun stat(stats: List<StatLine>, name: String): StatLine? = stats.firstOrNull { it.name == name }

    /** Minutul la care sare arhiva pentru un eveniment (cu un minut înainte, ca [MatchClock.jumpStart]). */
    fun jumpMinute(eventMinute: Int): Int = (eventMinute - 1).coerceAtLeast(0)
}

/** „începe în 38 de minute”, „începe în 2 h 10 min”, „a început acum 12 min”, pentru panoul de previzualizare. */
object Countdown {

    fun text(start: Long, now: Long, status: MatchStatus): String {
        val diff = start - now
        return when {
            status == MatchStatus.FINISHED -> "încheiat"
            status == MatchStatus.LIVE || diff <= 0 -> {
                val ago = TimeUnit.MILLISECONDS.toMinutes(-diff).coerceAtLeast(0)
                if (status == MatchStatus.SCHEDULED && ago == 0L) "începe acum" else "a început acum ${duration(ago)}"
            }
            else -> "începe în " + duration(TimeUnit.MILLISECONDS.toMinutes(diff + 59_999))
        }
    }

    private fun duration(minutes: Long): String = when {
        minutes < 1 -> "mai puțin de un minut"
        minutes == 1L -> "un minut"
        minutes < 20 -> "$minutes minute"
        minutes < 60 -> "$minutes de minute"
        minutes < 24 * 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h h" else "$h h $m min"
        }
        else -> {
            val d = minutes / (24 * 60)
            if (d == 1L) "o zi" else "$d zile"
        }
    }
}
