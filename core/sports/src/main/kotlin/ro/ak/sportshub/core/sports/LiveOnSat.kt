package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import org.jsoup.Jsoup

/**
 * LiveOnSat (furnizor opțional, neoficial): pagina zilei cu meciurile de fotbal și zeci de posturi pe meci, din
 * toată lumea (numele postului are de obicei țara la final: „Sport TV6 Portugal HD”, „Fox Soccer Plus USA”).
 * Al doilea strat global pentru fotbal, lângă LiveSoccerTV.
 *
 * O pagină la 3 ore, păstrată în fișier. Pagina se citește ca un șir de rânduri, în ordine: titlul zilei
 * („Saturday, 14th November”), competiția, echipele („Latvia v Armenia”), ora („ST: 18:00”) și posturile (câte
 * o legătură pe rând, în tabele). Ora e în fusul ales de site, scris în subsolul paginii („Last updated on …
 * (GMT -06:00)”); fără el, ora Marii Britanii. Dacă structura se schimbă, lista rămâne goală, fără erori.
 */
object LiveOnSat {

    const val PROVIDER = "liveonsat"
    const val URL = "https://liveonsat.com/2day.php"
    val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/London")

    private val blockTags = "div, td, th, p, li, h1, h2, h3, h4, h5, span, b, strong, a"
    private val dayHeading = Regex(
        """\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday),?\s+(\d{1,2})(?:st|nd|rd|th)?\s+(january|february|march|april|may|june|july|august|september|october|november|december)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val startTime = Regex("""\bST:\s*([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
    private val fixture = Regex("""^(.{2,60}?)\s+v\s+(.{2,60}?)(?:\s*[\[(].*)?$""")
    private val gmtOffset = Regex("""\(GMT\s*([+-])\s*(\d{1,2}):(\d{2})\)""")
    private val updated = Regex("""last updated[^()]{0,80}(\(GMT\s*[+-]\s*\d{1,2}:\d{2}\))""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""\((?=[^)]*(?:geo|\$|/))[^)]{1,12}\)|\[[^\]]{0,12}]""", RegexOption.IGNORE_CASE)
    private val symbols = Regex("""[\p{So}\p{Cs}️]""")

    /** Pagina pare un program adevărat (nu o pagină de verificare sau de eroare)? */
    fun looksValid(html: String): Boolean = startTime.containsMatchIn(html) && Regex("""\sv\s""").containsMatchIn(html)

    /** Fusul orelor din pagină: cel din subsol („(GMT +01:00)”), altfel ora Marii Britanii. */
    fun zoneOf(html: String): ZoneId {
        val text = updated.find(html)?.groupValues?.get(1) ?: return DEFAULT_ZONE
        val m = gmtOffset.find(text) ?: return DEFAULT_ZONE
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return runCatching { ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt()) }.getOrDefault(DEFAULT_ZONE)
    }

    private data class Line(val text: String, val link: Boolean, val inTable: Boolean)

    /**
     * Rândurile de text ale paginii, în ordine: elementele care nu mai conțin alte elemente de text și cele cu
     * text propriu (atunci rândul e tot textul lor: „ST: <span>18:00</span>” rămâne „ST: 18:00”).
     */
    private fun lines(doc: org.jsoup.nodes.Document): List<Line> =
        doc.body().select(blockTags).asSequence()
            .filter { el -> el.ownText().isNotBlank() || el.children().none { it.`is`(blockTags) } }
            .mapNotNull { el ->
                val text = el.text().replace(' ', ' ').trim()
                if (text.isEmpty()) null else Line(text, el.closest("a") != null || el.selectFirst("a") != null, el.closest("table") != null)
            }
            .toList()

    /** Meciurile din pagină. [today] e ziua în care s-a descărcat pagina (pentru anul titlurilor de zi). */
    fun parse(html: String, today: LocalDate): List<SportEvent> {
        val doc = runCatching { Jsoup.parse(html, URL) }.getOrNull() ?: return emptyList()
        val zone = zoneOf(html)
        val out = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        var day = today
        var competition: String? = null
        var candidate: String? = null

        var home: String? = null
        var away: String? = null
        var comp: String? = null
        var time: LocalTime? = null
        var fixtureDay = day
        val stations = ArrayList<TvStation>()

        fun flush() {
            val h = home
            val a = away
            val t = time
            if (h != null && a != null && t != null) {
                val start = fixtureDay.atTime(t).atZone(zone).toInstant().toEpochMilli()
                val id = "$fixtureDay ${Sport.fold(h)}|${Sport.fold(a)}"
                if (seen.add(id)) {
                    out += SportEvent(
                        provider = PROVIDER,
                        providerId = id,
                        sport = Sport.FOOTBALL.sportsDbName,
                        competition = comp,
                        home = h,
                        away = a,
                        start = start,
                        stations = stations.distinctBy { StationIndex.compact(it.name) },
                    )
                }
            }
            home = null
            away = null
            time = null
            stations.clear()
        }

        for (line in lines(doc)) {
            val text = line.text
            val dm = dayHeading.find(text)
            if (dm != null && text.length < 60) {
                flush()
                day = dateOf(dm.groupValues[1].toInt(), dm.groupValues[2], today) ?: day
                candidate = null
                continue
            }
            val tm = startTime.find(text)
            if (tm != null) {
                if (home != null && time == null) time = LocalTime.of(tm.groupValues[1].toInt(), tm.groupValues[2].toInt())
                continue
            }
            if (line.link && line.inTable && home != null && time != null && fixture.find(text) == null) {
                // Un post al meciului curent (după ora de start).
                clean(text)?.let { stations += TvStation(it) }
                continue
            }
            val f = fixture.find(text)
            if (f != null && !text.contains(':')) {
                flush()
                home = f.groupValues[1].trim()
                away = f.groupValues[2].trim()
                competition = candidate ?: competition
                comp = competition
                fixtureDay = day
                candidate = null
                continue
            }
            // Un rând simplu: titlul competiției următoare (sau o notă a meciului, ignorată).
            if (home == null || time != null) {
                if (text.length in 3..100 && !text.startsWith("(")) candidate = text
            }
        }
        flush()
        return out
    }

    /** „Fubo Sports USA ($/geo/R) 📺” → „Fubo Sports USA”. */
    internal fun clean(name: String): String? =
        name.replace(tags, " ").replace(symbols, " ").replace(Regex("""\s+"""), " ").trim().takeIf { it.length >= 2 }

    /** Ziua „14 november”, cu anul cel mai apropiat de [today] (pagina nu scrie anul). */
    internal fun dateOf(dayOfMonth: Int, month: String, today: LocalDate): LocalDate? {
        val m = runCatching { Month.valueOf(month.uppercase()) }.getOrNull() ?: return null
        return listOf(today.year - 1, today.year, today.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, m, dayOfMonth) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
    }
}
