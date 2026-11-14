package ro.ak.sportshub.core.sports

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * FutbolEnLaTV (furnizor opțional, neoficial): pagina „Deportes” cu programul TV din Spania pentru ~18 sporturi
 * (fotbal, baschet, tenis, handbal, F1, MotoGP, ciclism, MMA, box, golf…). Posturile sunt spaniole (M+, DAZN,
 * Teledeporte, Eurosport), dar competiția și ora ajută și la descoperirea meciurilor și a evenimentelor fără echipe.
 *
 * O pagină, la 3 ore, păstrată în fișier. Citirea e tolerantă: rândurile cu o oră (`.hora` sau `HH:mm` la început),
 * echipele din `.local` / `.visitante` (altfel titlul evenimentului), competiția și sportul din `.detalles`
 * și posturile din `.canales` (sau ultima listă din rând). Data vine din titlurile de zi („Partidos de hoy
 * sábado, 14/11/2026”). Orele sunt ale Spaniei. Dacă structura se schimbă, lista rămâne goală, fără erori.
 */
object FutbolEnLaTv {

    const val PROVIDER = "futbolenlatv"
    const val URL = "https://www.futbolenlatv.es/deporte"
    val ZONE: ZoneId = ZoneId.of("Europe/Madrid")
    private const val COUNTRY = "Spain"

    private val time = Regex("""^\s*([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val dateText = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")
    private val teamsSplit = Regex("""\s+(?:vs\.?|v|-|–)\s+""", RegexOption.IGNORE_CASE)
    private val dial = Regex("""\s*\((?:\d[^)]*|ver[^)]*|dial[^)]*)\)\s*""", RegexOption.IGNORE_CASE)

    /** Sporturile, după numele lor în spaniolă (cele mai lungi întâi, ca „fútbol americano” să nu fie fotbal). */
    private val sports: List<Pair<String, Sport?>> = listOf(
        "futbol americano" to Sport.AMERICAN_FOOTBALL, "futbol sala" to null, "futbol playa" to null,
        "baloncesto" to Sport.BASKETBALL, "basket" to Sport.BASKETBALL, "balonmano" to Sport.HANDBALL,
        "voleibol" to Sport.VOLLEYBALL, "voley" to Sport.VOLLEYBALL, "hockey" to Sport.ICE_HOCKEY,
        "formula 1" to Sport.MOTORSPORT, "motogp" to Sport.MOTORSPORT, "motociclismo" to Sport.MOTORSPORT,
        "automovilismo" to Sport.MOTORSPORT, "motor" to Sport.MOTORSPORT, "rally" to Sport.MOTORSPORT,
        "ciclismo" to Sport.CYCLING, "rugby" to Sport.RUGBY, "mma" to Sport.FIGHTING, "ufc" to Sport.FIGHTING,
        "boxeo" to Sport.FIGHTING, "artes marciales" to Sport.FIGHTING, "golf" to Sport.GOLF, "tenis" to Sport.TENNIS,
        "beisbol" to Sport.BASEBALL, "dardos" to Sport.DARTS, "snooker" to Sport.SNOOKER, "cricket" to Sport.CRICKET,
        "padel" to null, "atletismo" to null, "natacion" to null, "futbol" to Sport.FOOTBALL,
    )

    /** Pagina pare un program adevărat (nu o pagină de verificare sau de eroare)? */
    fun looksValid(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("futbolenlatv") && Regex(""">\s*\d{1,2}:\d{2}\s*<""").containsMatchIn(html) &&
            !lower.contains("cf-challenge") && !lower.contains("captcha")
    }

    /** Sportul după numele spaniol din text (fără diacritice). */
    fun sportOf(text: String): Sport? {
        val f = " " + Sport.fold(text) + " "
        return sports.firstOrNull { (k, _) -> f.contains(" $k ") }?.second ?: Sport.detect(text)
    }

    private fun knowsSport(text: String): Boolean {
        val f = " " + Sport.fold(text) + " "
        return sports.any { (k, _) -> f.contains(" $k ") }
    }

    /** Evenimentele din pagină. [today] e ziua (în Spania) în care s-a descărcat pagina, pentru rândurile fără titlu de zi. */
    fun parse(html: String, today: LocalDate): List<SportEvent> {
        val doc = runCatching { Jsoup.parse(html, URL) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        var day = today
        for (el in doc.select("tr, [class*=cabecera], h2, h3, caption")) {
            val own = el.text()
            // Un titlu de zi (fără oră în față).
            if (time.find(cellText(el)) == null) {
                dateText.find(own)?.let { m ->
                    if (own.length < 120) {
                        runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
                            .getOrNull()?.let { day = it }
                    }
                }
                continue
            }
            if (el.tagName() != "tr") continue
            val event = parseRow(el, day) ?: continue
            if (seen.add(event.providerId)) out += event
        }
        return out
    }

    /** Textul celulei cu ora (sau al primei celule). */
    private fun cellText(row: Element): String =
        (row.selectFirst(".hora, [class*=hora], .time") ?: row.children().firstOrNull() ?: row).text()

    private fun parseRow(row: Element, day: LocalDate): SportEvent? {
        val tm = time.find(cellText(row)) ?: return null
        val start = day.atTime(LocalTime.of(tm.groupValues[1].toInt(), tm.groupValues[2].toInt()))
            .atZone(ZONE).toInstant().toEpochMilli()

        // Detaliile: sportul (din text, titluri sau clase de iconițe) și competiția.
        val details = row.selectFirst(".detalles, [class*=detalle], [class*=competicion]")
        val hints = buildString {
            append(details?.text().orEmpty())
            row.select("[title], img[alt], [class*=ico]").forEach { e ->
                append(' ').append(e.attr("title")).append(' ').append(e.attr("alt")).append(' ')
                append(e.className().replace('_', ' ').replace('-', ' '))
            }
        }
        val sport = sportOf(hints)
        val competition = details?.let { d ->
            val parts = d.select("li, label, span, a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            (parts.ifEmpty { listOf(d.text().trim()) })
                .filterNot { knowsSport(it) && Sport.fold(it).split(' ').size <= 2 }
                .firstOrNull { it.length in 2..80 }
        }

        val local = row.selectFirst(".local, [class*=local]")?.text()?.trim().orEmpty()
        val visitor = row.selectFirst(".visitante, [class*=visitante]")?.text()?.trim().orEmpty()
        var home = local
        var away = visitor
        if (home.isEmpty() || away.isEmpty()) {
            val title = (row.selectFirst("[class*=evento], [class*=partido], [class*=titulo]")?.text()
                ?: row.children().filterNot { c ->
                    (details != null && c.allElements.contains(details)) || c.hasClass("hora") || c.className().contains("canal") || time.containsMatchIn(c.text())
                }.joinToString(" ") { it.text() }).trim()
            if (title.isEmpty()) return null
            val parts = title.split(teamsSplit, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.isNotEmpty() } && sport != Sport.MOTORSPORT && sport != Sport.CYCLING && sport != Sport.GOLF) {
                home = parts[0]
                away = parts[1]
            } else {
                home = title
                away = ""
            }
        }
        if (home.isBlank()) return null

        val channelBox = row.selectFirst(".canales, [class*=canales], [class*=channels], ul.listaCanales")
            ?: row.select("ul").lastOrNull()
        val stations = channelBox?.select("li")?.ifEmpty { channelBox.select("a, span") }.orEmpty()
            .map { e -> e.attr("title").trim().ifEmpty { e.ownText().trim().ifEmpty { e.text().trim() } } }
            .map { it.replace(dial, " ").trim() }
            .filter { it.length >= 2 && !it.lowercase().startsWith("ver ") && it.lowercase() != "ver en directo" }
            .distinctBy { StationIndex.compact(it) }
            .map { TvStation(it, COUNTRY) }

        val id = "$day ${tm.value.trim()} ${Sport.fold(home)}|${Sport.fold(away)}"
        return SportEvent(
            provider = PROVIDER,
            providerId = id,
            sport = sport?.sportsDbName,
            competition = competition,
            home = home,
            away = away,
            start = start,
            stations = stations,
        )
    }
}
