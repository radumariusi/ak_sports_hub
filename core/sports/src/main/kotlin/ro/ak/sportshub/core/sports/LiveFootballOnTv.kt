package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.jsoup.Jsoup

/**
 * live-footballontv.com: meciurile de fotbal transmise în Marea Britanie (Sky, TNT, Amazon, BBC, ITV, Premier
 * Sports, LaLiga TV…), de la Premier League până la ligile inferioare, tineret și feminin, plus ligile mari străine.
 * Doar adaugă posturi („United Kingdom”) meciurilor furnizorilor, ca FutbolEnLaTV; se cere doar cu canale din UK.
 *
 * Pagina principală are câteva zile, citite ca șir de rânduri: titlul zilei („Monday 16th November 2026”), ora
 * („19:45”, ora Londrei), echipele („Germany v Netherlands”), competiția, apoi posturile, fiecare în elementul lui.
 * Structura a fost văzută doar ca text (16.11.2026); dacă se schimbă, lista rămâne goală, fără erori.
 */
object LiveFootballOnTv {

    const val PROVIDER = "livefootballontv"
    const val URL = "https://www.live-footballontv.com/"
    val ZONE: ZoneId = ZoneId.of("Europe/London")
    const val COUNTRY = "United Kingdom"

    private val blockTags = "div, td, th, p, li, h1, h2, h3, h4, h5, span, b, strong, a"
    private val dayHeading = Regex(
        """^(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(\d{1,2})(?:st|nd|rd|th)?\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{4})$""",
        RegexOption.IGNORE_CASE,
    )
    private val time = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
    private val fixture = Regex("""^(.{2,60}?)\s+v\s+(.{2,60}?)$""")
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    fun looksValid(html: String): Boolean = Regex("""\s v \s?""").containsMatchIn(html) && dayHeading.pattern.isNotEmpty() &&
        Regex("""(?i)(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+\d{1,2}(st|nd|rd|th)""").containsMatchIn(html)

    private fun lines(html: String): List<String> {
        val doc = runCatching { Jsoup.parse(html, URL) }.getOrNull() ?: return emptyList()
        return doc.body().select(blockTags).asSequence()
            .filter { el -> el.ownText().isNotBlank() || el.children().none { it.`is`(blockTags) } }
            .map { it.text().replace(' ', ' ').trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun parse(html: String): List<SportEvent> {
        val out = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        var day: LocalDate? = null
        var start: LocalTime? = null
        var home: String? = null
        var away: String? = null
        var competition: String? = null
        val stations = ArrayList<TvStation>()

        fun flush() {
            val d = day
            val t = start
            val h = home
            val a = away
            if (d != null && t != null && h != null && a != null) {
                val id = "$d ${Sport.fold(h)}|${Sport.fold(a)}"
                if (seen.add(id)) {
                    out += SportEvent(
                        provider = PROVIDER,
                        providerId = id,
                        sport = Sport.FOOTBALL.sportsDbName,
                        competition = competition,
                        home = h,
                        away = a,
                        start = d.atTime(t).atZone(ZONE).toInstant().toEpochMilli(),
                        stations = stations.distinctBy { StationIndex.compact(it.name) },
                    )
                }
            }
            home = null
            away = null
            competition = null
            stations.clear()
        }

        for (text in lines(html)) {
            val dm = dayHeading.find(text)
            if (dm != null) {
                flush()
                val month = months.indexOf(dm.groupValues[2].lowercase()) + 1
                day = runCatching { LocalDate.of(dm.groupValues[3].toInt(), month, dm.groupValues[1].toInt()) }.getOrNull()
                start = null
                continue
            }
            if (day == null) continue
            val tm = time.find(text)
            if (tm != null) {
                flush()
                start = LocalTime.of(tm.groupValues[1].toInt(), tm.groupValues[2].toInt())
                continue
            }
            val f = fixture.find(text)
            if (f != null && start != null && home == null) {
                home = f.groupValues[1].trim()
                away = f.groupValues[2].trim()
                continue
            }
            if (home == null) continue
            if (competition == null) {
                competition = text
                continue
            }
            // Posturile: rânduri scurte după competiție.
            if (text.length in 2..40) stations += TvStation(text, COUNTRY)
        }
        flush()
        return out
    }
}
