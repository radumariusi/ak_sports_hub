package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * LiveSoccerTV (furnizor opțional, neoficial): pagina cu programul unei zile, cu meciurile de fotbal și
 * posturile TV care le transmit (pentru țara din care se deschide pagina).
 *
 * Site-ul nu are API; pagina se cere rar (o pagină pe zi, la câteva ore), cu user-agent de browser, și se
 * păstrează într-un fișier. Citirea e tolerantă: se caută legăturile spre meciuri (`/match/…`), ora (atributul
 * `dv` cu ms UTC sau textul `HH:mm`) și legăturile spre posturi (`/channels/…`) din același rând; competiția
 * e ultimul titlu de competiție (`/competitions/…`) dinaintea rândului. Dacă structura se schimbă, se strică
 * doar furnizorul ăsta: lista goală, fără erori.
 */
object LiveSoccerTv {

    const val PROVIDER = "livesoccertv"
    private const val BASE = "https://www.livesoccertv.com"

    fun scheduleUrl(day: LocalDate): String = "$BASE/schedules/$day/"

    /** Pagina pare un program adevărat (nu o pagină de verificare sau de eroare)? */
    fun looksValid(html: String): Boolean = html.contains("/match/") && html.contains("/channels/")

    private val matchId = Regex("""/match/(\d+)""")
    private val timeText = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?:\s*([ap])\.?m\b\.?)?""", RegexOption.IGNORE_CASE)
    private val matchSlug = Regex("""/match/\d+/([^/?#]+)""")
    private val minute = Regex("""\b\d{1,3}'""")
    private val scoreInTitle = Regex("""^(.+?)\s+(\d{1,2})\s*[-–:]\s*(\d{1,2})\s+(.+)$""")
    private val teamsSplit = Regex("""\s+(?:vs\.?|v|-|–)\s+""", RegexOption.IGNORE_CASE)

    /**
     * Meciurile din pagina zilei [day]. Orele scrise doar ca text (`HH:mm`) se citesc în fusul [zone]
     * (pagina le arată în fusul celui care o deschide).
     */
    fun parse(html: String, day: LocalDate, zone: ZoneId): List<SportEvent> {
        val doc = runCatching { Jsoup.parse(html, BASE) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SportEvent>()
        val seenRows = HashSet<Element>()
        val seenIds = HashSet<String>()
        var competition: String? = null
        var lastStart: Long? = null

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.contains("/competitions/")) {
                // Un titlu de competiție (nu legătura din interiorul unui rând de meci).
                if (a.closest("tr")?.selectFirst("a[href*=/match/]") == null) {
                    competition = a.text().trim().ifEmpty { a.attr("title").trim() }.ifEmpty { competition }
                }
                continue
            }
            val id = matchId.find(href)?.groupValues?.get(1) ?: continue
            val row = a.closest("tr") ?: a.closest("li") ?: a.parent() ?: continue
            if (!seenRows.add(row) || !seenIds.add(id)) continue
            parseRow(row, a, id, competition, day, zone, lastStart)?.let {
                out += it
                lastStart = it.start
            }
        }
        return out
    }

    private fun parseRow(
        row: Element,
        link: Element,
        id: String,
        competition: String?,
        day: LocalDate,
        zone: ZoneId,
        previousStart: Long?,
    ): SportEvent? {
        var title = link.text().trim().ifEmpty { link.attr("title").trim() }
        var homeScore: Int? = null
        var awayScore: Int? = null
        scoreInTitle.find(title)?.let { m ->
            homeScore = m.groupValues[2].toInt()
            awayScore = m.groupValues[3].toInt()
            title = m.groupValues[1] + " vs " + m.groupValues[4]
        }
        var parts = title.split(teamsSplit, limit = 2).map { it.trim() }
        if (parts.size != 2 || parts.any { it.isEmpty() }) {
            // Textul legăturii nu are „vs”: echipele din adresă („/match/123/latvia-vs-armenia/”).
            parts = teamsFromSlug(link.attr("href")) ?: return null
        }

        val rowText = spaced(row)
        val classes = (row.className() + " " + row.select("[class]").joinToString(" ") { it.className() }).lowercase()
        val status = when {
            Regex("""\b(FT|AET|Pen\.?)\b""").containsMatchIn(rowText) -> MatchStatus.FINISHED
            classes.contains("live") || minute.containsMatchIn(rowText) || Regex("""\bHT\b""").containsMatchIn(rowText) -> MatchStatus.LIVE
            else -> null
        }
        if (Regex("""\b(Postponed|Cancelled|Canceled)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rowText)) return null
        // Un meci în desfășurare arată uneori doar minutul: ora aproximativă e cea a rândului de dinainte.
        val start = startOf(row, day, zone) ?: previousStart?.takeIf { status == MatchStatus.LIVE } ?: return null

        val stations = row.select("a[href*=/channels/]")
            .map { it.attr("title").trim().ifEmpty { it.text().trim() } }
            .filter { it.length >= 2 && !it.startsWith("+") && !it.equals("more", ignoreCase = true) }
            .distinctBy { StationIndex.compact(it) }
            .map { TvStation(it) }

        return SportEvent(
            provider = PROVIDER,
            providerId = id,
            sport = Sport.FOOTBALL.sportsDbName,
            competition = competition,
            home = parts[0],
            away = parts[1],
            start = start,
            status = status,
            homeScore = homeScore.takeIf { status != null },
            awayScore = awayScore.takeIf { status != null },
            stations = stations,
        )
    }

    /** „latvia-vs-armenia” → [„Latvia”, „Armenia”]. */
    internal fun teamsFromSlug(href: String): List<String>? {
        val slug = matchSlug.find(href)?.groupValues?.get(1) ?: return null
        val halves = slug.split("-vs-", limit = 2)
        if (halves.size != 2) return null
        val names = halves.map { h -> h.split('-').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar(Char::uppercaseChar) } }
        return names.takeIf { it.all(String::isNotEmpty) }
    }

    /**
     * Textul elementului cu spații între elementele copil: `Element.text()` lipește elementele inline
     * („<span>21:30</span><span>FT</span>” → „21:30FT”), iar atunci nici ora, nici „FT” nu se mai recunosc.
     */
    private fun spaced(e: Element): String = e.select("*").joinToString(" ") { it.ownText() }.trim()

    /** Ora de start: atributul cu ms UTC (`dv`, `data-ts`…), altfel primul `HH:mm` din rând, în fusul [zone]. */
    private fun startOf(row: Element, day: LocalDate, zone: ZoneId): Long? {
        for (attr in listOf("dv", "data-ts", "data-time", "data-timestamp", "data-utc")) {
            val v = row.selectFirst("[$attr]")?.attr(attr)?.trim()?.toLongOrNull() ?: continue
            return if (v < 100_000_000_000L) v * 1000 else v
        }
        val cell = row.selectFirst(".timecell, .time, td") ?: row
        val m = timeText.find(spaced(cell)) ?: timeText.find(spaced(row)) ?: return null
        var hour = m.groupValues[1].toInt()
        // Pagina poate arăta ora cu am/pm („9:00am”, „12:30pm”).
        when (m.groupValues[3].lowercase()) {
            "a" -> if (hour == 12) hour = 0
            "p" -> if (hour < 12) hour += 12
        }
        val t = LocalTime.of(hour, m.groupValues[2].toInt())
        return day.atTime(t).atZone(zone).toInstant().toEpochMilli()
    }
}
