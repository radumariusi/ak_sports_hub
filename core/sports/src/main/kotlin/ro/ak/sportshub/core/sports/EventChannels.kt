package ro.ak.sportshub.core.sports

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Un canal EVENT / PPV din playlist, citit din nume: „EVENT 03: UFC 310 20:00”, „NBA League Pass 03: Lakers @ Celtics 01:30”,
 * „PPV 1 | Arsenal vs Chelsea | 15/11 20:45 UK”. [title] e null când canalul e gol („EVENT 03”, „No event”).
 */
data class EventSlot(
    val channelId: Long,
    val channelName: String,
    val group: String?,
    /** Ce rulează, așa cum scrie în nume (fără eticheta canalului și fără oră). */
    val title: String?,
    /** Echipa gazdă sau numele evenimentului (fără echipe); null dacă nu e niciun eveniment. */
    val home: String?,
    /** Oaspeții; null la evenimentele fără două echipe. */
    val away: String?,
    val sport: Sport?,
    /** Eticheta canalului care spune competiția („NBA League Pass” → NBA), dacă există. */
    val competition: String?,
    /** Ora din nume, în ms UTC; null dacă numele nu are oră. */
    val start: Long?,
)

/**
 * Canalele EVENT / PPV ale furnizorului: au meciul în nume, nu în EPG. Se citesc doar numele și grupul din
 * playlist (zero rețea), ca sursă „sigură” pentru meciul respectiv, mai ales la sporturile fără EPG bun
 * (box, MMA, NFL, NBA).
 */
object EventChannels {

    /** Cuvinte din nume sau din grup care arată un canal de eveniment. */
    private val markers = listOf(
        "event", "events", "evento", "eventos", "eveniment", "evenimente", "ppv", "pay per view", "live event", "live events",
        "league pass", "game pass", "nfl sunday ticket", "sunday ticket", "nfl redzone", "espn+", "espn plus", "fight pass",
        "mls season pass", "mlb tv", "mlb extra innings", "nhl center ice", "nba league pass", "dazn ppv", "flosports", "flo sports",
        "peacock", "paramount+", "tsn+", "sportsnet+", "fanatiz", "f1 tv", "uefa tv", "match center", "matchcenter",
        "backup event", "live sports", "sports events", "fixtures",
    )

    /** Numele unui canal gol („No event”, „Off air”, „TBA”). */
    private val placeholders = listOf(
        "no event", "no events", "no live event", "off air", "offline", "tba", "tbd", "coming soon", "no game", "no match",
        "nothing scheduled", "not live", "fara eveniment", "sin evento", "stay tuned", "standby", "test",
    )

    private val strongSeparator = Regex("""\s+(?i:vs\.?|v\.?|x)\s+|\s+@\s+""")
    private val colonTime = Regex("""(?<![\d:])([01]?\d|2[0-3])[:hH]([0-5]\d)(?:\s*([apAP])\.?[mM]\.?)?(?![\d:])""")
    private val dotTime = Regex("""(?<![\d.:/])([01]?\d|2[0-3])\.([0-5]\d)(?:\s*([apAP])\.?[mM]\.?)?(?![\d.:/])""")
    private val ampmTime = Regex("""(?<![\d:])(1[0-2]|0?[1-9])\s*([ap])\.?m\.?(?![a-z])""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""(?<![\d/.-])(\d{4})-(\d{1,2})-(\d{1,2})(?![\d])|(?<![\d/])(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?![\d/])""")
    private val monthNames = mapOf(
        "jan" to 1, "ian" to 1, "ene" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "abr" to 4, "may" to 5, "mai" to 5,
        "jun" to 6, "iun" to 6, "jul" to 7, "iul" to 7, "aug" to 8, "ago" to 8, "sep" to 9, "oct" to 10, "nov" to 11,
        "noi" to 11, "dec" to 12, "dic" to 12,
    )
    private val textDate = Regex(
        """(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|ian|ene|feb|mar|apr|abr|may|mai|jun|iun|jul|iul|aug|ago|sep|oct|nov|noi|dec|dic)[a-z]*\.?\b|""" +
            """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""",
    )
    private val weekday = Regex("""(?i)\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|lun|mie|joi|vin|sam|dum)[a-z]*\.?\b(?=\s*\d)""")

    /** Fusurile scrise după oră („20:00 UK”, „7:30 PM ET”). */
    private val zones = mapOf(
        "et" to "America/New_York", "est" to "America/New_York", "edt" to "America/New_York",
        "ct" to "America/Chicago", "cst" to "America/Chicago", "cdt" to "America/Chicago",
        "mt" to "America/Denver", "mst" to "America/Denver", "mdt" to "America/Denver",
        "pt" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
        "uk" to "Europe/London", "gmt" to "Europe/London", "bst" to "Europe/London",
        "cet" to "Europe/Paris", "cest" to "Europe/Paris", "eet" to "Europe/Bucharest", "eest" to "Europe/Bucharest",
        "ro" to "Europe/Bucharest", "utc" to "UTC", "aest" to "Australia/Sydney", "aedt" to "Australia/Sydney",
    )
    private val zoneAfter = Regex("""^\s*\(?\s*(${zones.keys.joinToString("|")})\b\s*\)?""", RegexOption.IGNORE_CASE)
    private val countryPrefix = Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,3}\s*[|\]):]\s*(?=\S)""")
    private val slotLabel = Regex("""^(.*?(?:\b\d{1,3}\b|\b[a-z]+\+))\s*[:|\-–—]\s*(.*\p{L}.*)$""", RegexOption.IGNORE_CASE)
    private val contextSplit = Regex("""\s*[:|]\s+""")
    private val leadingNumber = Regex("""^\s*(?:#|no\.?\s*)?\d{1,3}\s*[:|\-–—.]\s*""")

    /** Canalul pare unul de eveniment, după nume sau după grup. */
    fun isEventChannel(name: String, group: String?): Boolean {
        // „Sky Sports Main Event” e un post, nu un canal de eveniment.
        val n = (" " + Sport.fold(name) + " ").replace(" main event ", " ")
        val g = " " + Sport.fold(group.orEmpty()) + " "
        val plusName = name.lowercase()
        if (markers.any { m -> markerIn(m, n, plusName) || markerIn(m, g, group.orEmpty().lowercase()) }) return true
        // Fără marcaj: un nume cu „A vs B” și o oră e tot un canal de eveniment.
        return strongSeparator.containsMatchIn(name) && (colonTime.containsMatchIn(name) || ampmTime.containsMatchIn(name))
    }

    private fun markerIn(marker: String, folded: String, lower: String): Boolean =
        if (marker.endsWith("+")) lower.contains(marker) else folded.contains(" ${Sport.fold(marker)} ")

    /**
     * Citește numele canalului [name] (grupul [group]). Orele fără fus se citesc în [zone]; fără dată, ziua e
     * cea mai apropiată în care ora nu e trecută de mai mult de 6 ore față de [now].
     */
    fun parse(channelId: Long, name: String, group: String?, now: Long, zone: ZoneId): EventSlot {
        var text = name.trim()
        countryPrefix.find(text)?.let { text = text.substring(it.range.last + 1) }

        // Ora (și fusul de după ea): „20:45”, „20h45”, „7:30 PM”, „8PM”, apoi „20.45” dacă nu e alta.
        var time: LocalTime? = null
        var timeZone = zone
        val colon = colonTime.find(text) ?: dotTime.find(text)
        val ampm = if (colon == null) ampmTime.find(text) else null
        val t = colon ?: ampm
        if (t != null) {
            var hour = t.groupValues[1].toInt()
            val minute = if (colon != null) t.groupValues[2].toInt() else 0
            val half = (if (colon != null) t.groupValues[3] else t.groupValues[2]).lowercase()
            if (half == "p" && hour < 12) hour += 12
            if (half == "a" && hour == 12) hour = 0
            time = LocalTime.of(hour, minute)
            var end = t.range.last + 1
            zoneAfter.find(text.substring(end))?.let { z ->
                timeZone = ZoneId.of(zones.getValue(z.groupValues[1].lowercase()))
                end += z.range.last + 1
            }
            text = text.removeRange(t.range.first, end)
        }
        weekday.find(text)?.let { text = text.removeRange(it.range) }

        // Data, dacă e scrisă.
        var date: LocalDate? = null
        val today = Instant.ofEpochMilli(now).atZone(timeZone).toLocalDate()
        numericDate.find(text)?.let { d ->
            date = runCatching {
                if (d.groupValues[1].isNotEmpty()) {
                    LocalDate.of(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt())
                } else {
                    val year = d.groupValues[6].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                    nearest(d.groupValues[4].toInt(), d.groupValues[5].toInt(), year, today)
                }
            }.getOrNull()
            if (date != null) text = text.removeRange(d.range)
        }
        if (date == null) {
            textDate.find(text)?.let { d ->
                val (dayStr, monStr) = if (d.groupValues[1].isNotEmpty()) d.groupValues[1] to d.groupValues[2] else d.groupValues[4] to d.groupValues[3]
                val month = monthNames[monStr.lowercase().take(3)]
                date = month?.let { runCatching { nearest(dayStr.toInt(), it, null, today) }.getOrNull() }
                if (date != null) text = text.removeRange(d.range)
            }
        }
        // Eticheta canalului („EVENT 03”, „NBA League Pass 3”, „ESPN+”) stă înaintea primului „:” sau „|”.
        var label: String? = null
        slotLabel.find(text)?.let { m ->
            val left = m.groupValues[1].trim()
            if (left.length <= 40 && (isLabel(left) || !strongSeparator.containsMatchIn(left))) {
                label = left
                text = m.groupValues[2]
            }
        }
        if (label == null) {
            // „EVENT 03 Arsenal vs Chelsea” (fără separator): eticheta e cuvântul-marcaj + numărul.
            val m = Regex("""^\s*((?:\S+\s+){0,3}?(?:event|events|ppv|pass|ticket)\s*\d{0,3})\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
            if (m != null && isLabel(m.groupValues[1])) {
                label = m.groupValues[1].trim()
                text = m.groupValues[2]
            }
        }
        text = text.replace(leadingNumber, "")
        text = cleanup(text)

        val start = time?.let { tm ->
            val d = date ?: pickDay(tm, timeZone, now)
            d.atTime(tm).atZone(timeZone).toInstant().toEpochMilli()
        }

        val placeholder = text.isEmpty() || text.none { it.isLetter() } || isPlaceholder(text) || isLabel(text)
        if (placeholder) {
            return EventSlot(channelId, name, group, null, null, null, Sport.detect(label, group), competition(label), start)
        }

        val sep = strongSeparator.find(text)
        var home: String = text
        var away: String? = null
        var context: String? = null
        if (sep != null) {
            // „UFC 310: Pantoja vs Asakura”: ce e înainte de „:” e competiția, nu echipa.
            val left = text.substring(0, sep.range.first).split(contextSplit)
            context = left.dropLast(1).joinToString(" ").trim().ifEmpty { null }
            val a = cleanup(left.last())
            val b = cleanup(text.substring(sep.range.last + 1).split(contextSplit).first())
            if (a.any { it.isLetter() } && b.any { it.isLetter() }) {
                val reversed = sep.value.trim() == "@"
                home = if (reversed) b else a
                away = if (reversed) a else b
            }
        }
        val sport = Sport.detect(label, text) ?: Sport.detect(group)
        if (away == null) home = text.split(contextSplit).joinToString(" - ")
        return EventSlot(
            channelId = channelId,
            channelName = name,
            group = group,
            title = text,
            home = home,
            away = away,
            sport = sport,
            competition = context ?: competition(label),
            start = start,
        )
    }

    /** Eticheta canalului nu e un eveniment: doar marcajul, numărul și numele serviciului. */
    private fun isLabel(text: String): Boolean {
        val f = " " + Sport.fold(text).replace(Regex("""\b\d{1,3}\b"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        if (f.isBlank()) return true
        val lower = text.lowercase()
        return markers.any { m -> markerIn(m, f, lower) } &&
            f.trim().split(' ').size <= 4 && !strongSeparator.containsMatchIn(text)
    }

    private fun isPlaceholder(text: String): Boolean {
        val f = " " + Sport.fold(text) + " "
        return placeholders.any { f.contains(" $it ") } && f.trim().split(' ').size <= 5
    }

    /** Eticheta dă competiția doar când are mai mult decât marcajul („NBA League Pass” → „NBA”). */
    private fun competition(label: String?): String? {
        if (label == null) return null
        val words = Sport.fold(label).split(' ').filter { w ->
            w.isNotEmpty() && !w.all(Char::isDigit) && w !in setOf("event", "events", "ppv", "live", "league", "pass", "game", "ticket", "sunday", "backup", "hd", "fhd", "uhd", "4k", "sd")
        }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.uppercase().takeIf { u -> u.length <= 4 } ?: it.replaceFirstChar(Char::uppercase) }
    }

    private fun cleanup(text: String): String =
        text.replace(Regex("""[()\[\]{}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '–', '—', ':', '|', '@', ',', '.', '/', ' ')
            .trim()

    /** Ziua pentru ora [time]: azi, ieri sau mâine, prima care nu e trecută cu mai mult de 6 ore. */
    private fun pickDay(time: LocalTime, zone: ZoneId, now: Long): LocalDate {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val limit = now - TimeUnit.HOURS.toMillis(6)
        for (d in listOf(today.minusDays(1), today, today.plusDays(1))) {
            if (d.atTime(time).atZone(zone).toInstant().toEpochMilli() >= limit) return d
        }
        return today.plusDays(1)
    }

    /** Data cu ziua și luna date, în anul cel mai apropiat de [today] (sau în [year]). */
    private fun nearest(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate {
        if (year != null) return LocalDate.of(year, month, day)
        return listOf(today.year - 1, today.year, today.year + 1)
            .mapNotNull { y -> try { LocalDate.of(y, Month.of(month), day) } catch (e: DateTimeException) { null } }
            .minBy { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
    }
}

/**
 * Canalele dedicate unei competiții („DAZN F1”, „F1 TV”, „MotoGP TV”, „UFC Fight Pass”): transmit de obicei
 * toate sesiunile ei, deci sunt surse presupuse pentru evenimentele competiției, după cele găsite efectiv.
 */
object DedicatedChannels {

    private data class Series(val key: String, val words: List<String>, val sport: Sport)

    private val series = listOf(
        Series("f1", listOf("f1", "formula 1", "formula1", "formula one"), Sport.MOTORSPORT),
        Series("motogp", listOf("motogp", "moto gp"), Sport.MOTORSPORT),
        Series("indycar", listOf("indycar"), Sport.MOTORSPORT),
        Series("nascar", listOf("nascar"), Sport.MOTORSPORT),
        Series("formula e", listOf("formula e"), Sport.MOTORSPORT),
        Series("wrc", listOf("wrc"), Sport.MOTORSPORT),
        Series("ufc", listOf("ufc"), Sport.FIGHTING),
        Series("pdc", listOf("pdc"), Sport.DARTS),
    )

    private fun has(text: String, word: String): Boolean = " ${Sport.fold(text)} ".contains(" ${Sport.fold(word)} ")

    /** Seria căreia îi e dedicat canalul (după nume), sau null. „Formula E” nu e „Formula 1”. */
    fun seriesOfChannel(name: String): String? =
        series.firstOrNull { s -> s.words.any { has(name, it) } && !(s.key == "f1" && has(name, "formula e")) }?.key

    /** Seria meciului (după competiție și nume), sau null. */
    fun seriesOfMatch(m: Match): String? {
        val text = listOfNotNull(m.competition, m.home, m.away.takeIf { it.isNotBlank() }).joinToString(" ")
        return series.firstOrNull { s ->
            (m.sport == null || m.sport == s.sport) && s.words.any { has(text, it) } && !(s.key == "f1" && has(text, "formula e"))
        }?.key
    }
}
