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

    private fun offset(zone: String): ZoneOffset {
        if (zone.isEmpty() || zone[0].isLetter()) return ZoneOffset.UTC
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        val sign = if (zone[0] == '-') -1 else 1
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }
}
