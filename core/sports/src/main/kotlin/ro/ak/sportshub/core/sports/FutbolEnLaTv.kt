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

    /**
     * Un site al rețelei (același șablon, altă țară): pagina citită, fusul orelor, țara posturilor și ordinea din dată
     * (SUA scrie luna întâi). [code] apare în numele fișierului și în id-urile meciurilor.
     */
    data class Site(val code: String, val url: String, val zone: ZoneId, val country: String, val monthFirst: Boolean = false)

    val SPAIN = Site("es", "https://www.futbolenlatv.es/deporte", ZoneId.of("Europe/Madrid"), "Spain")

    /**
     * Site-urile surori (aceeași firmă, același șablon), cu posturile țării lor: toate sporturile acolo unde există
     * pagina „sport”, altfel prima pagină (fotbal). Se cer doar pentru țările canalelor tale ([sitesFor]).
     */
    val SISTERS: List<Site> = listOf(
        Site("gb", "https://www.ukfootballontv.co.uk/sport", ZoneId.of("Europe/London"), "United Kingdom"),
        Site("us", "https://www.sportsontvusa.com/sport", ZoneId.of("America/New_York"), "USA", monthFirst = true),
        Site("de", "https://www.fussball-im-tv.com/sport", ZoneId.of("Europe/Berlin"), "Germany"),
        Site("it", "https://www.calcio-tv.com/", ZoneId.of("Europe/Rome"), "Italy"),
        Site("fr", "https://www.foottv.fr/", ZoneId.of("Europe/Paris"), "France"),
        Site("pt", "https://www.futebolnatv.pt/", ZoneId.of("Europe/Lisbon"), "Portugal"),
        Site("mx", "https://www.futbolenvivomexico.com/", ZoneId.of("America/Mexico_City"), "Mexico"),
        Site("ar", "https://www.futbolenvivoargentina.com/", ZoneId.of("America/Argentina/Buenos_Aires"), "Argentina"),
    )

    /** Câte site-uri surori se cer cel mult (fiecare pagină are ~1 MB). */
    const val MAX_SISTERS = 3

    // Compatibilitate: Spania rămâne site-ul de bază.
    val URL: String get() = SPAIN.url
    val ZONE: ZoneId get() = SPAIN.zone

    fun site(code: String): Site? = if (code == SPAIN.code) SPAIN else SISTERS.firstOrNull { it.code == code }

    /** Site-urile surori pentru prefixele de țară ale canalelor tale, în ordinea din [SISTERS], cel mult [MAX_SISTERS]. */
    fun sitesFor(prefixes: Set<String>): List<Site> {
        val alias = mapOf("uk" to "gb", "en" to "gb", "usa" to "us", "ger" to "de", "arg" to "ar")
        val codes = prefixes.map { alias[it] ?: it }.toSet()
        return SISTERS.filter { it.code in codes }.take(MAX_SISTERS)
    }

    /** Adresa e a unui site din rețea? */
    fun isSite(url: String): Boolean {
        val u = url.lowercase()
        return "futbolenlatv" in u || SISTERS.any { s -> s.url.substringAfter("//").substringBefore('/').removePrefix("www.") in u }
    }

    private val time = Regex("""^\s*([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val dateText = Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](\d{4})\b""")
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
        "padel" to null, "atletismo" to null, "natacion" to null,
        // Site-urile surori (engleză, germană, franceză, italiană, portugheză): înainte de „football”.
        "american football" to Sport.AMERICAN_FOOTBALL, "football americain" to Sport.AMERICAN_FOOTBALL,
        "futebol americano" to Sport.AMERICAN_FOOTBALL, "futsal" to null, "motor racing" to Sport.MOTORSPORT,
        "motorcycle racing" to Sport.MOTORSPORT, "autorennen" to Sport.MOTORSPORT, "motorradrennen" to Sport.MOTORSPORT,
        "rugby union" to Sport.RUGBY, "ice hockey" to Sport.ICE_HOCKEY, "eishockey" to Sport.ICE_HOCKEY,
        "basketball" to Sport.BASKETBALL, "basquete" to Sport.BASKETBALL, "pallacanestro" to Sport.BASKETBALL,
        "handball" to Sport.HANDBALL, "pallamano" to Sport.HANDBALL, "andebol" to Sport.HANDBALL,
        "volleyball" to Sport.VOLLEYBALL, "pallavolo" to Sport.VOLLEYBALL, "voleibol" to Sport.VOLLEYBALL,
        "boxing" to Sport.FIGHTING, "boxen" to Sport.FIGHTING, "boxe" to Sport.FIGHTING, "cycling" to Sport.CYCLING,
        "radsport" to Sport.CYCLING, "ciclismo" to Sport.CYCLING, "cyclisme" to Sport.CYCLING, "tennis" to Sport.TENNIS,
        "baseball" to Sport.BASEBALL, "darts" to Sport.DARTS,
        "futbol" to Sport.FOOTBALL, "football" to Sport.FOOTBALL, "fussball" to Sport.FOOTBALL, "calcio" to Sport.FOOTBALL,
        "futebol" to Sport.FOOTBALL, "soccer" to Sport.FOOTBALL,
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
    fun parse(html: String, today: LocalDate, site: Site = SPAIN): List<SportEvent> {
        val doc = runCatching { Jsoup.parse(html, site.url) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        var day = today
        for (el in doc.select("tr, [class*=cabecera], h2, h3, caption")) {
            val own = el.text()
            // Un titlu de zi (fără oră în față).
            if (time.find(cellText(el)) == null) {
                dateText.find(own)?.let { m ->
                    if (own.length < 120) dateOf(m, site)?.let { day = it }
                }
                continue
            }
            if (el.tagName() != "tr") continue
            val event = parseRow(el, day, site) ?: continue
            if (seen.add(event.providerId)) out += event
        }
        return out.ifEmpty { parseLoose(doc, today, site) }
    }

    private fun dateOf(m: MatchResult, site: Site): LocalDate? {
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val (d, mo) = if (site.monthFirst) b to a else a to b
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), mo, d) }.getOrNull()
    }

    private val exactTime = Regex("""^\s*([01]?\d|2[0-3]):([0-5]\d)\s*h?\s*$""")

    /**
     * Citirea de rezervă, fără tabel: fiecare element al cărui text e doar o oră e începutul unui rând;
     * rândul e cel mai mare strămoș care nu mai conține și altă oră. Titlurile de zi se urmăresc în ordinea paginii.
     */
    internal fun parseLoose(doc: org.jsoup.nodes.Document, today: LocalDate, site: Site = SPAIN): List<SportEvent> {
        val all = doc.allElements
        val times = all.filter { exactTime.matches(it.ownText()) }
        if (times.isEmpty()) return emptyList()
        val counts = HashMap<Element, Int>()
        times.forEach { t -> t.parents().forEach { p -> counts.merge(p, 1, Int::plus) } }
        val timeSet = times.toHashSet()
        val out = ArrayList<SportEvent>()
        val seen = HashSet<String>()
        var day = today
        for (el in all) {
            val own = el.ownText()
            if (el !in timeSet) {
                if (own.length in 6..120) dateText.find(own)?.let { m -> dateOf(m, site)?.let { day = it } }
                continue
            }
            var row: Element = el
            while (true) {
                val parent = row.parent() ?: break
                if (parent.tagName() == "body" || (counts[parent] ?: 0) > 1) break
                row = parent
            }
            val event = parseRow(row, day, site, own.trim()) ?: continue
            if (seen.add(event.providerId)) out += event
        }
        return out
    }

    /** „Bolívar Bolívar Atlético Mineiro Atlético Mineiro” (nume + text alternativ al siglei) → cele două echipe. */
    internal fun doubledTeams(text: String): Pair<String, String>? {
        val all = text.trim().split(Regex("""\s+"""))
        // Înaintea echipelor poate fi competiția („LaLiga Real Madrid Real Madrid Getafe Getafe”).
        for (k in 0 until all.size) doubledFrom(all.drop(k))?.let { return it }
        return null
    }

    private fun doubledFrom(w: List<String>): Pair<String, String>? {
        for (i in 1..w.size / 2) {
            if (w.subList(0, i) != w.subList(i, minOf(2 * i, w.size))) continue
            val rest = w.drop(2 * i)
            if (rest.isEmpty() || rest.size % 2 != 0) continue
            val half = rest.size / 2
            if (rest.subList(0, half) == rest.subList(half, rest.size)) {
                return w.subList(0, i).joinToString(" ") to rest.subList(0, half).joinToString(" ")
            }
        }
        return null
    }

    /** Textul celulei cu ora (sau al primei celule). */
    private fun cellText(row: Element): String =
        (row.selectFirst(".hora, [class*=hora], .time") ?: row.children().firstOrNull() ?: row).text()

    private fun parseRow(row: Element, day: LocalDate, site: Site, timeText: String? = null): SportEvent? {
        val tm = time.find(timeText ?: cellText(row)) ?: return null
        val start = day.atTime(LocalTime.of(tm.groupValues[1].toInt(), tm.groupValues[2].toInt()))
            .atZone(site.zone).toInstant().toEpochMilli()

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
                    (details != null && c.allElements.contains(details)) || c.hasClass("hora") || c.className().contains("canal") || time.containsMatchIn(c.text()) ||
                        c.tagName() in setOf("ul", "ol") || c.selectFirst("ul, ol") != null
                }.joinToString(" ") { it.text() }).trim()
            if (title.isEmpty()) return null
            val parts = doubledTeams(title)?.toList() ?: title.split(teamsSplit, limit = 2).map { it.trim() }
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
            .map { TvStation(it, site.country) }

        val id = (if (site == SPAIN) "" else "${site.code} ") + "$day ${tm.value.trim()} ${Sport.fold(home)}|${Sport.fold(away)}"
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
