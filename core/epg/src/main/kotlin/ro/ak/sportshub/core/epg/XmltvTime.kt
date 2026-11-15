package ro.ak.sportshub.core.epg

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Orele din XMLTV: `20261114200000 +0100`, `20261114200000+0100`, `202611142000`, `20261114200000 UTC`.
 * Fără fus orar se presupune UTC (cum cere formatul); decalajul unei surse se corectează din setări.
 */
object XmltvTime {

    private val pattern = Regex("""^\s*(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?\s*([+-]\d{2}:?\d{2}|[A-Za-z]+)?\s*$""")

    /** Momentul în milisecunde epoch, sau null dacă textul nu e o oră XMLTV. */
    fun parse(text: String?): Long? {
        if (text == null) return null
        fast(text)?.let { return it }
        val m = pattern.find(text) ?: return null
        val g = m.groupValues
        return try {
            val local = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
            )
            local.toInstant(offset(g[7])).toEpochMilli()
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Forma obișnuită (`20261114200000 +0100`, `20261114200000`, `20261114200000 UTC`), fără expresii regulate
     * și fără obiecte de dată: un EPG mare are milioane de ore. Orice altă formă trece pe calea completă.
     */
    private fun fast(t: String): Long? {
        val n = t.length
        if (n < 14) return null
        for (i in 0 until 14) if (t[i] !in '0'..'9') return null
        fun num(from: Int, len: Int): Int {
            var v = 0
            for (i in from until from + len) v = v * 10 + (t[i] - '0')
            return v
        }
        val year = num(0, 4)
        val month = num(4, 2)
        val day = num(6, 2)
        val hour = num(8, 2)
        val minute = num(10, 2)
        val second = num(12, 2)
        if (month !in 1..12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        var i = 14
        while (i < n && t[i] == ' ') i++
        var offsetMinutes = 0
        if (i < n) {
            val sign = t[i]
            if (sign == '+' || sign == '-') {
                val rest = t.substring(i + 1).trimEnd()
                val digits = if (rest.length == 5 && rest[2] == ':') rest.removeRange(2, 3) else rest
                if (digits.length != 4 || digits.any { it !in '0'..'9' }) return null
                val h = (digits[0] - '0') * 10 + (digits[1] - '0')
                val m = (digits[2] - '0') * 10 + (digits[3] - '0')
                if (h > 18 || m > 59) return null
                offsetMinutes = (h * 60 + m) * if (sign == '-') -1 else 1
            } else if (!t.substring(i).trimEnd().all { it.isLetter() }) {
                return null
            }
        }
        val days = epochDay(year, month, day)
        return ((days * 86_400L + hour * 3_600L + minute * 60L + second) - offsetMinutes * 60L) * 1_000L
    }

    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysIn(y: Int, m: Int) = when (m) {
        2 -> if (leap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Zilele de la 1970-01-01 (algoritmul „days from civil”, calendar gregorian). */
    private fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun offset(zone: String): ZoneOffset {
        if (zone.isEmpty() || zone[0].isLetter()) return ZoneOffset.UTC
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        val sign = if (zone[0] == '-') -1 else 1
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }
}
