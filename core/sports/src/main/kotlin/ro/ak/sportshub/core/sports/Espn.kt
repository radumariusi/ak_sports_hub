package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ESPN (`site.api.espn.com`, JSON public, fără cheie și fără protecție anti-robot), furnizorul principal de program
 * după ce TheSportsDB gratuit dădea doar câteva meciuri pe sport și zi.
 *
 * - Un catalog fix de ligi ([leagues]): fotbal (Europa pe două niveluri, Anglia mai multe, cupele, naționalele,
 *   primele ligi din America și Asia), NBA, NHL, NFL, MLB, tenis (ATP, WTA), F1, IndyCar, NASCAR, golf, UFC și rugby.
 *   Catalogul ține loc și de filtru: ce nu e în el nu se cere.
 * - O cerere pe ligă și zi (zilele ESPN sunt ale SUA, Est), dar doar în zilele cu meciuri: fiecare răspuns aduce
 *   calendarul ligii ([Calendar]), păstrat separat, iar zilele fără meciuri nu se mai cer. Calendarul se reîmprospătează
 *   săptămânal; o ligă care nu există (404) nu se mai cere o lună.
 * - Pentru Pre-meci și Live: pagina meciului (`summary`), cu formații, statistici, faze, clasament și forma ([parseSummary]).
 * Cererile nu merg la furnizorul de IPTV (nu folosesc conexiunea playlisturilor).
 */
object Espn {

    const val PROVIDER = "espn"

    /** Ziua ESPN (`dates=AAAALLZZ`) e în fusul SUA, Est. */
    val ZONE: ZoneId = ZoneId.of("America/New_York")

    /** Cum arată evenimentele unei ligi în răspuns. */
    enum class Kind {
        /** Două echipe pe meci (fotbal, baschet, hochei, rugby…). */
        TEAM,

        /** Turneu cu meciuri pe grupe (`groupings`): simplu masculin / feminin. */
        TENNIS,

        /** Mare Premiu cu sesiuni (antrenamente, calificări, sprint, cursă): calificările, sprintul și cursa. */
        RACING,

        /** Un eveniment fără echipe: o gală (UFC), un turneu de golf. */
        SINGLE,
    }

    /**
     * O ligă: [key] (în numele fișierelor), [path] (în adresă), sportul și numele competiției (cel folosit de
     * filtrul de competiții, regulile de drepturi și unirea cu ceilalți furnizori).
     */
    data class League(
        val key: String,
        val path: String,
        val sport: Sport,
        val competition: String,
        val kind: Kind = Kind.TEAM,
        /**
         * Liga se cere doar dacă ai echipe favorite, iar meciurile ei rămân doar pentru ele (filtrul de competiții
         * o scoate altfel): fotbalul feminin din afara Angliei, pentru echipele feminine ale favoritelor.
         */
        val forFavorites: Boolean = false,
    )

    private fun soccer(key: String, slug: String, name: String, forFavorites: Boolean = false) =
        League(key, "soccer/$slug", Sport.FOOTBALL, name, forFavorites = forFavorites)
    private fun rugby(key: String, id: String, name: String) = League(key, "rugby/$id", Sport.RUGBY, name)

    val leagues: List<League> = listOf(
        // Anglia (mai multe niveluri, cupele, feminin)
        soccer("EPL", "eng.1", "English Premier League"),
        soccer("ENG2", "eng.2", "English Championship"),
        soccer("ENG3", "eng.3", "English League One"),
        soccer("ENG4", "eng.4", "English League Two"),
        soccer("ENG5", "eng.5", "English National League"),
        soccer("FACUP", "eng.fa", "English FA Cup"),
        soccer("EFLCUP", "eng.league_cup", "English Carabao Cup"),
        soccer("EFLTROPHY", "eng.trophy", "English EFL Trophy"),
        soccer("WSL", "eng.w.1", "English Women's Super League"),
        soccer("WCHAMP", "eng.w.2", "English Women's Championship"),
        // Fotbal feminin în afara Angliei: doar pentru echipele favorite („Barcelona” cuprinde și echipa feminină).
        soccer("LIGAF", "esp.w.1", "Spanish Liga F Women", forFavorites = true),
        soccer("FRAUENBL", "ger.w.1", "German Frauen-Bundesliga", forFavorites = true),
        soccer("D1F", "fra.w.1", "French Premiere Ligue Feminine", forFavorites = true),
        soccer("SERIEAW", "ita.w.1", "Italian Serie A Femminile", forFavorites = true),
        soccer("UWCL", "uefa.wchampions", "UEFA Women's Champions League", forFavorites = true),
        soccer("NWSL", "usa.nwsl", "American NWSL Women", forFavorites = true),
        soccer("WEURO", "uefa.weuro", "UEFA Women's European Championship"),
        soccer("WWC", "fifa.wwc", "FIFA Women's World Cup"),
        // Spania, Italia, Germania, Franța (două niveluri și cupele)
        soccer("LALIGA", "esp.1", "Spanish LaLiga"),
        soccer("LALIGA2", "esp.2", "Spanish LaLiga 2"),
        soccer("COPADELREY", "esp.copa_del_rey", "Spanish Copa del Rey"),
        soccer("ESPSUPER", "esp.super_cup", "Spanish Supercopa"),
        soccer("SERIEA", "ita.1", "Italian Serie A"),
        soccer("SERIEB", "ita.2", "Italian Serie B"),
        soccer("COPPA", "ita.coppa_italia", "Italian Coppa Italia"),
        soccer("ITASUPER", "ita.super_cup", "Italian Supercoppa"),
        soccer("BUNDESLIGA", "ger.1", "German Bundesliga"),
        soccer("BUNDESLIGA2", "ger.2", "German 2. Bundesliga"),
        soccer("POKAL", "ger.dfb_pokal", "German DFB Pokal"),
        soccer("LIGUE1", "fra.1", "French Ligue 1"),
        soccer("LIGUE2", "fra.2", "French Ligue 2"),
        soccer("CDF", "fra.coupe_de_france", "French Coupe de France"),
        // Restul Europei
        soccer("PORTUGAL", "por.1", "Portuguese Primeira Liga"),
        soccer("PORTUGAL2", "por.2", "Portuguese Liga Portugal 2"),
        soccer("TACA", "por.taca.portugal", "Portuguese Taca de Portugal"),
        soccer("EREDIVISIE", "ned.1", "Dutch Eredivisie"),
        soccer("EERSTE", "ned.2", "Dutch Eerste Divisie"),
        soccer("KNVB", "ned.cup", "Dutch KNVB Beker"),
        soccer("BELGIUM", "bel.1", "Belgian Pro League"),
        soccer("SCOTLAND", "sco.1", "Scottish Premiership"),
        soccer("SCOTLAND2", "sco.2", "Scottish Championship"),
        soccer("SCOCUP", "sco.tennents", "Scottish Cup"),
        soccer("SCOLCUP", "sco.cis", "Scottish League Cup"),
        soccer("TURKEY", "tur.1", "Turkish Super Lig"),
        soccer("GREECE", "gre.1", "Greek Super League"),
        soccer("AUSTRIA", "aut.1", "Austrian Bundesliga"),
        soccer("SWISS", "sui.1", "Swiss Super League"),
        soccer("DENMARK", "den.1", "Danish Superliga"),
        soccer("SWEDEN", "swe.1", "Swedish Allsvenskan"),
        soccer("NORWAY", "nor.1", "Norwegian Eliteserien"),
        soccer("ROMANIA", "rou.1", "Romanian Liga I"),
        soccer("RUSSIA", "rus.1", "Russian Premier League"),
        soccer("POLAND", "pol.1", "Polish Ekstraklasa"),
        soccer("CZECH", "cze.1", "Czech First League"),
        // Cupele europene și naționalele
        soccer("UCL", "uefa.champions", "UEFA Champions League"),
        soccer("UCLQ", "uefa.champions_qual", "UEFA Champions League Qualifying"),
        soccer("UEL", "uefa.europa", "UEFA Europa League"),
        soccer("UECL", "uefa.europa.conf", "UEFA Conference League"),
        soccer("USUPER", "uefa.super_cup", "UEFA Super Cup"),
        soccer("NATIONS", "uefa.nations", "UEFA Nations League"),
        soccer("EURO", "uefa.euro", "UEFA European Championship"),
        soccer("EUROQ", "uefa.euroq", "UEFA European Championship Qualifying"),
        soccer("WORLDCUP", "fifa.world", "FIFA World Cup"),
        soccer("WCQ", "fifa.worldq.uefa", "World Cup Qualification UEFA"),
        soccer("WCQSA", "fifa.worldq.conmebol", "World Cup Qualification CONMEBOL"),
        soccer("WCQNA", "fifa.worldq.concacaf", "World Cup Qualification CONCACAF"),
        soccer("WCQAS", "fifa.worldq.afc", "World Cup Qualification AFC"),
        soccer("WCQAF", "fifa.worldq.caf", "World Cup Qualification CAF"),
        soccer("CWC", "fifa.cwc", "FIFA Club World Cup"),
        soccer("FRIENDLY", "fifa.friendly", "International Friendly"),
        soccer("CLUBFRIENDLY", "club.friendly", "Club Friendly"),
        soccer("CONCACAFNL", "concacaf.nations.league", "CONCACAF Nations League"),
        soccer("GOLDCUP", "concacaf.gold", "CONCACAF Gold Cup"),
        soccer("COPAAMERICA", "conmebol.america", "Copa America"),
        soccer("AFCON", "caf.nations", "Africa Cup of Nations"),
        soccer("ASIANCUP", "afc.asian.cup", "AFC Asian Cup"),
        // America, Asia, Australia (primul nivel) și cupele continentale
        soccer("MLS", "usa.1", "American Major League Soccer"),
        soccer("LIGAMX", "mex.1", "Mexican Liga MX"),
        soccer("BRAZIL", "bra.1", "Brazilian Serie A Brasileirao"),
        soccer("ARGENTINA", "arg.1", "Argentine Liga Profesional"),
        soccer("LIBERTADORES", "conmebol.libertadores", "Copa Libertadores"),
        soccer("SUDAMERICANA", "conmebol.sudamericana", "Copa Sudamericana"),
        soccer("CONCACAFCC", "concacaf.champions", "CONCACAF Champions Cup"),
        soccer("LEAGUESCUP", "concacaf.leagues.cup", "Leagues Cup"),
        soccer("SAUDI", "ksa.1", "Saudi Pro League"),
        soccer("JAPAN", "jpn.1", "Japanese J1 League"),
        soccer("CHINA", "chn.1", "Chinese Super League"),
        soccer("KOREA", "kor.1", "K League 1"),
        soccer("ALEAGUE", "aus.1", "Australian A-League Men"),
        soccer("AFCCL", "afc.champions", "AFC Champions League Elite"),
        // Ligile americane
        League("NBA", "basketball/nba", Sport.BASKETBALL, "NBA"),
        League("NHL", "hockey/nhl", Sport.ICE_HOCKEY, "NHL"),
        League("NFL", "football/nfl", Sport.AMERICAN_FOOTBALL, "NFL"),
        League("MLB", "baseball/mlb", Sport.BASEBALL, "MLB"),
        // Tenis, motorsport, golf, lupte
        League("ATP", "tennis/atp", Sport.TENNIS, "ATP", Kind.TENNIS),
        League("WTA", "tennis/wta", Sport.TENNIS, "WTA", Kind.TENNIS),
        League("F1", "racing/f1", Sport.MOTORSPORT, "Formula 1", Kind.RACING),
        League("INDYCAR", "racing/irl", Sport.MOTORSPORT, "IndyCar", Kind.RACING),
        League("NASCAR", "racing/nascar-premier", Sport.MOTORSPORT, "NASCAR Cup", Kind.RACING),
        League("PGA", "golf/pga", Sport.GOLF, "PGA Tour", Kind.SINGLE),
        League("DPWT", "golf/eur", Sport.GOLF, "DP World Tour", Kind.SINGLE),
        League("UFC", "mma/ufc", Sport.FIGHTING, "UFC", Kind.SINGLE),
        // Rugby (id-uri numerice la ESPN)
        rugby("SIXNATIONS", "180659", "Six Nations"),
        rugby("PREMRUGBY", "267979", "Premiership Rugby"),
        rugby("URC", "270557", "United Rugby Championship"),
        rugby("TOP14", "270559", "Top 14"),
        rugby("CHAMPIONSCUP", "271937", "Champions Cup Rugby"),
        rugby("CHALLENGECUP", "272073", "Challenge Cup Rugby"),
        rugby("RUGBYCHAMP", "244293", "Rugby Championship"),
        rugby("SUPERRUGBY", "242041", "Super Rugby"),
        rugby("RWC", "164205", "Rugby World Cup"),
        rugby("RUGBYTEST", "289234", "Rugby Test Match"),
    )

    private val byKey: Map<String, League> = leagues.associateBy { it.key }

    fun league(key: String): League? = byKey[key]

    /** Primul cuvânt care poate lipsi din numele competiției la alți furnizori („Italian Serie A” → „Serie A”). */
    private val droppable = setOf(
        "english", "spanish", "italian", "german", "french", "portuguese", "dutch", "belgian", "scottish", "turkish",
        "greek", "austrian", "swiss", "danish", "swedish", "norwegian", "romanian", "russian", "polish", "czech",
        "american", "mexican", "brazilian", "argentine", "saudi", "japanese", "chinese", "australian", "uefa", "fifa",
    )

    /** Alte nume folosite de furnizori și de EPG-uri pentru aceleași ligi. */
    private val extraNames: Map<String, List<String>> = mapOf(
        "LALIGA" to listOf("la liga", "laliga ea sports", "primera division", "liga espanola"),
        "LALIGA2" to listOf("laliga 2", "la liga 2", "laliga2", "laliga hypermotion", "la liga hypermotion", "segunda division", "liga hypermotion"),
        "SERIEA" to listOf("serie a enilive", "serie a tim", "italy serie a"),
        "SERIEB" to listOf("serie bkt", "italy serie b"),
        "BUNDESLIGA2" to listOf("2 bundesliga", "zweite bundesliga"),
        "PORTUGAL" to listOf("liga portugal", "primeira liga", "liga portugal betclic"),
        "PORTUGAL2" to listOf("liga portugal 2", "segunda liga"),
        "BRAZIL" to listOf("brasileirao", "campeonato brasileiro"),
        "ROMANIA" to listOf("superliga romania", "romania superliga", "liga 1 romania"),
        "TURKEY" to listOf("super lig", "trendyol super lig"),
        "BELGIUM" to listOf("jupiler pro league"),
        "SCOTLAND" to listOf("scottish premiership", "spfl premiership"),
        "ENG2" to listOf("efl championship", "sky bet championship"),
        "ENG3" to listOf("efl league one"),
        "ENG4" to listOf("efl league two"),
        "UCL" to listOf("champions league"),
        "UEL" to listOf("europa league"),
        "UECL" to listOf("conference league"),
        "SAUDI" to listOf("roshn saudi league", "saudi professional league"),
        "LIGAMX" to listOf("liga mx"),
        "ARGENTINA" to listOf("liga profesional argentina"),
    )

    private val names: List<Pair<String, League>> by lazy {
        leagues.filter { it.kind == Kind.TEAM }.flatMap { l ->
            val full = Sport.fold(l.competition)
            val words = full.split(' ')
            val short = if (words.size >= 2 && words[0] in droppable) words.drop(1).joinToString(" ") else null
            (listOfNotNull(full, short) + extraNames[l.key].orEmpty().map(Sport::fold)).distinct().map { it to l }
        }
    }

    /**
     * Ligile ESPN probabile pentru o competiție scrisă de alt furnizor („Serie A”, „LaLiga Hypermotion”): cel mai lung
     * nume cuprins în text, cu sportul potrivit; cel mult [limit]. Echipele se verifică apoi în programul ligii.
     */
    fun leaguesFor(sport: Sport?, competition: String?, limit: Int = 2): List<League> {
        val text = competition?.let { " " + Sport.fold(it) + " " } ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return names.asSequence()
            .filter { (n, l) -> (sport == null || l.sport == sport) && text.contains(" $n ") }
            .sortedByDescending { it.first.length }
            .map { it.second }
            .distinct()
            .take(limit)
            .toList()
    }

    /** Ligile de cerut, după sporturile alese în setări. */
    fun leaguesFor(sports: Collection<Sport>, withFavoriteLeagues: Boolean = false): List<League> =
        leagues.filter { it.sport in sports && (withFavoriteLeagues || !it.forFavorites) }

    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"

    fun scoreboardUrl(league: League, day: LocalDate): String =
        "$SITE/${league.path}/scoreboard?dates=" + day.toString().replace("-", "")

    fun summaryUrl(league: League, eventId: String): String = "$SITE/${league.path}/summary?event=$eventId"

    /** Ziua ESPN de acum (SUA, Est): cererile sunt pentru ea și pentru ziua următoare. */
    fun today(now: Long = System.currentTimeMillis()): LocalDate = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()

    /** „EPL-401879243” (id-ul din Sports Hub, fără „espn:”) → liga și id-ul ESPN al meciului. */
    fun splitId(providerId: String): Pair<League, String>? {
        val key = providerId.substringBefore('-', "")
        val id = providerId.substringAfter('-', "")
        val league = byKey[key] ?: return null
        return if (id.isBlank()) null else league to id
    }

    // ---------- calendarul ligii ----------

    /**
     * Zilele cu meciuri ale unei ligi, din răspunsul ESPN (`leagues[0].calendar`): o listă de zile (fotbal, baschet,
     * tenis) sau de perioade (F1, UFC, golf). [unknown] = nu se știe (atunci se cere orice zi).
     */
    data class Calendar(val days: Set<LocalDate> = emptySet(), val ranges: List<ClosedRange<LocalDate>> = emptyList(), val unknown: Boolean = false) {
        fun has(day: LocalDate): Boolean = unknown || day in days || ranges.any { day in it }

        /** Pentru fișier: `day|2026-11-21,…` sau `list|2026-11-20/2026-11-22,…` sau `unknown`. */
        fun encode(): String = when {
            unknown -> "unknown"
            ranges.isNotEmpty() -> "list|" + ranges.joinToString(",") { "${it.start}/${it.endInclusive}" }
            else -> "day|" + days.sorted().joinToString(",")
        }

        companion object {
            val UNKNOWN = Calendar(unknown = true)

            fun decode(text: String?): Calendar? {
                val t = text?.trim() ?: return null
                if (t == "unknown") return UNKNOWN
                val kind = t.substringBefore('|')
                val items = t.substringAfter('|', "").split(',').filter { it.isNotBlank() }
                return when (kind) {
                    "day" -> Calendar(days = items.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet())
                    "list" -> Calendar(
                        ranges = items.mapNotNull { r ->
                            val a = runCatching { LocalDate.parse(r.substringBefore('/')) }.getOrNull()
                            val b = runCatching { LocalDate.parse(r.substringAfter('/')) }.getOrNull()
                            if (a != null && b != null && !b.isBefore(a)) a..b else null
                        },
                    )
                    else -> null
                }
            }
        }
    }

    /** Calendarul din răspuns; null dacă răspunsul nu e un program ESPN. */
    fun calendar(body: String): Calendar? {
        val root = parse(body) ?: return null
        val league = root.arr("leagues").firstOrNull() as? JsonObject ?: return null
        if (league.text("calendarIsWhitelist") == "false") return Calendar.UNKNOWN
        val raw = league.arr("calendar")
        if (raw.isEmpty()) return Calendar.UNKNOWN
        val days = HashSet<LocalDate>()
        val ranges = ArrayList<ClosedRange<LocalDate>>()
        for (c in raw) {
            when (c) {
                is JsonPrimitive -> c.contentOrNull?.let(::espnDay)?.let(days::add)
                is JsonObject -> {
                    // Perioadele (F1, UFC, golf) sau săptămânile (NFL), uneori grupate în `entries`.
                    val entries = c.arr("entries").filterIsInstance<JsonObject>().ifEmpty { listOf(c) }
                    for (e in entries) {
                        val a = e.text("startDate")?.let(::isoMillis) ?: continue
                        val b = e.text("endDate")?.let(::isoMillis) ?: a
                        val from = Instant.ofEpochMilli(a).atZone(ZONE).toLocalDate()
                        val to = Instant.ofEpochMilli(b).atZone(ZONE).toLocalDate()
                        if (!to.isBefore(from)) ranges += from..to
                    }
                }
                else -> Unit
            }
        }
        return when {
            ranges.isNotEmpty() -> Calendar(ranges = ranges)
            days.isNotEmpty() -> Calendar(days = days)
            else -> Calendar.UNKNOWN
        }
    }

    /** „2026-08-15T07:00Z” → 2026-08-15 (ziua ESPN e data din șir: ora e doar începutul zilei în SUA). */
    private fun espnDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

    // ---------- programul ----------

    /** Câte zile în jurul zilei cerute păstrăm din turneele lungi (tenis): restul turneului e în alte fișiere. */
    private val TOURNAMENT_SLACK_MS = TimeUnit.DAYS.toMillis(2)

    /**
     * Meciurile dintr-un răspuns `scoreboard`. [day] e ziua cerută (pentru turnee de mai multe zile: tenisul păstrează
     * doar meciurile din jurul ei, golful apare în fiecare zi a turneului).
     */
    fun parse(body: String, league: League, day: LocalDate? = null): List<SportEvent> {
        val root = parse(body) ?: return emptyList()
        val events = root.arr("events").filterIsInstance<JsonObject>()
        val parsed = when (league.kind) {
            Kind.TEAM -> events.mapNotNull { teamEvent(it, league) }
            Kind.TENNIS -> events.flatMap { tennis(it, league, day) }
            Kind.RACING -> events.flatMap { racing(it, league) }
            Kind.SINGLE -> events.mapNotNull { single(it, league, day) }
        }
        val logo = leagueLogo(root) ?: return parsed
        return parsed.map { it.copy(competitionLogo = logo) }
    }

    /** Sigla ligii (`leagues[0].logos[0].href`). */
    private fun leagueLogo(root: JsonObject): String? {
        val league = root.arr("leagues").firstOrNull() as? JsonObject ?: return null
        val logos = league.arr("logos").filterIsInstance<JsonObject>()
        val pick = logos.firstOrNull { l -> (l["rel"] as? JsonArray)?.any { it.str() == "dark" } == true } ?: logos.firstOrNull()
        return pick?.text("href")
    }

    /** Sigla, culorile, codul și forma unei echipe (`competitors[].team`, `competitors[].form`). */
    private fun look(c: JsonObject): TeamLook? {
        val t = c.obj("team") ?: return null
        val logo = t.text("logo") ?: t.arr("logos").filterIsInstance<JsonObject>().firstOrNull()?.text("href")
        val look = TeamLook(
            logo = logo,
            color = t.text("color")?.takeIf { TeamLooks.argb(it) != null },
            altColor = t.text("alternateColor")?.takeIf { TeamLooks.argb(it) != null },
            code = t.text("abbreviation"),
            form = c.text("form")?.takeIf { f -> f.length <= 10 && f.all { it in "WDLwdl" } },
        )
        return look.takeUnless { it.isEmpty }
    }

    private fun teamEvent(o: JsonObject, league: League): SportEvent? {
        val id = o.text("id") ?: return null
        val comp = o.arr("competitions").firstOrNull() as? JsonObject ?: return null
        if (cancelled(o) || cancelled(comp)) return null
        val teams = comp.arr("competitors").filterIsInstance<JsonObject>()
        val home = teams.firstOrNull { it.text("homeAway") == "home" } ?: teams.firstOrNull { it.text("order") == "0" } ?: teams.getOrNull(0) ?: return null
        val away = teams.firstOrNull { it.text("homeAway") == "away" } ?: teams.firstOrNull { it !== home } ?: return null
        val start = (o.text("date") ?: comp.text("date"))?.let(::isoMillis) ?: return null
        val status = status(o) ?: status(comp)
        return SportEvent(
            provider = PROVIDER,
            providerId = "${league.key}-$id",
            sport = league.sport.sportsDbName,
            competition = league.competition,
            home = teamName(home) ?: return null,
            away = teamName(away) ?: return null,
            start = start,
            status = status,
            homeScore = home.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
            awayScore = away.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
            venue = comp.obj("venue")?.text("fullName"),
            stations = stations(comp),
            homeLook = look(home),
            awayLook = look(away),
            homeAlt = altNames(home),
            awayAlt = altNames(away),
        )
    }

    /** Celelalte forme ale numelui echipei: scurt („Man United”), simplu („Colts”), codul („MUN”). */
    private fun altNames(c: JsonObject): List<String> {
        val t = c.obj("team") ?: return listOfNotNull(c.obj("athlete")?.text("shortName"))
        val main = t.text("displayName") ?: t.text("name")
        return listOfNotNull(t.text("shortDisplayName"), t.text("name"), t.text("nickname"), t.text("abbreviation"))
            .map { it.trim() }.filter { it.isNotEmpty() && it != main }.distinct()
    }

    private fun teamName(c: JsonObject): String? =
        c.obj("team")?.let { it.text("displayName") ?: it.text("name") } ?: athlete(c)

    private fun athlete(c: JsonObject): String? =
        c.obj("athlete")?.let { it.text("displayName") ?: it.text("fullName") }
            ?: c.obj("roster")?.text("displayName")
            ?: c.arr("athletes").filterIsInstance<JsonObject>().mapNotNull { it.text("displayName") }.takeIf { it.isNotEmpty() }?.joinToString(" / ")

    /** Tenis: meciurile de simplu din grupele turneului, din jurul zilei cerute. */
    private fun tennis(o: JsonObject, league: League, day: LocalDate?): List<SportEvent> {
        val tournament = o.text("name") ?: o.text("shortName") ?: return emptyList()
        val groups = o.arr("groupings").filterIsInstance<JsonObject>()
        val comps = if (groups.isEmpty()) {
            o.arr("competitions").filterIsInstance<JsonObject>().map { it to null }
        } else {
            groups.flatMap { g ->
                val slug = g.obj("grouping")?.text("slug") ?: g.text("slug") ?: ""
                if ("doubles" in slug || "mixed" in slug) emptyList() else g.arr("competitions").filterIsInstance<JsonObject>().map { it to slug }
            }
        }
        val center = day?.atTime(12, 0)?.atZone(ZONE)?.toInstant()?.toEpochMilli()
        return comps.mapNotNull { (c, _) ->
            val id = c.text("id") ?: return@mapNotNull null
            if (cancelled(c)) return@mapNotNull null
            val start = (c.text("date") ?: c.text("startDate"))?.let(::isoMillis) ?: return@mapNotNull null
            if (center != null && kotlin.math.abs(start - center) > TOURNAMENT_SLACK_MS) return@mapNotNull null
            val players = c.arr("competitors").filterIsInstance<JsonObject>()
                .sortedBy { it.text("order")?.toIntOrNull() ?: 0 }
            if (players.size < 2) return@mapNotNull null
            val a = athlete(players[0]) ?: return@mapNotNull null
            val b = athlete(players[1]) ?: return@mapNotNull null
            val status = status(c)
            SportEvent(
                provider = PROVIDER,
                providerId = "${league.key}-$id",
                sport = league.sport.sportsDbName,
                competition = "${league.competition} · $tournament",
                home = a,
                away = b,
                start = start,
                status = status,
                venue = c.obj("venue")?.text("fullName"),
                stations = stations(c),
            )
        }
    }

    /** Motorsport: calificările, sprintul și cursa fiecărui Mare Premiu (fără antrenamente). */
    private fun racing(o: JsonObject, league: League): List<SportEvent> {
        val name = o.text("name") ?: o.text("shortName") ?: return emptyList()
        val comps = o.arr("competitions").filterIsInstance<JsonObject>()
        if (comps.isEmpty()) return listOfNotNull(single(o, league, null))
        return comps.mapNotNull { c ->
            val id = c.text("id") ?: return@mapNotNull null
            if (cancelled(c)) return@mapNotNull null
            val type = (c.obj("type")?.let { it.text("abbreviation") ?: it.text("text") ?: it.text("name") } ?: "Race").trim()
            val t = type.lowercase()
            val session = when {
                t.startsWith("fp") || "practice" in t -> return@mapNotNull null
                "sprint" in t && ("qual" in t || "shoot" in t) || t == "sq" || t == "ss" -> "Sprint Qualifying"
                "sprint" in t -> "Sprint"
                "qual" in t -> "Qualifying"
                else -> null // cursa
            }
            val start = (c.text("date") ?: c.text("startDate"))?.let(::isoMillis) ?: return@mapNotNull null
            SportEvent(
                provider = PROVIDER,
                providerId = "${league.key}-$id",
                sport = league.sport.sportsDbName,
                competition = league.competition,
                home = if (session == null) name else "$name $session",
                away = "",
                start = start,
                status = status(c),
                venue = c.obj("venue")?.text("fullName"),
                stations = stations(c),
                athletes = athletes(listOf(c)),
            )
        }
    }

    /** Sportivii din `competitors[].athlete` (piloți, golfiști, luptători), cel mult [MAX_ATHLETES]. */
    private fun athletes(comps: List<JsonObject>): List<Pair<String, String?>> =
        comps.asSequence().flatMap { it.arr("competitors").filterIsInstance<JsonObject>().asSequence() }
            .mapNotNull { c ->
                val a = c.obj("athlete") ?: return@mapNotNull null
                val name = (a.text("displayName") ?: a.text("fullName"))?.trim()?.takeIf { it.length >= 3 } ?: return@mapNotNull null
                name to a.text("shortName")?.trim()?.takeIf { it.isNotEmpty() && it != name }
            }
            .distinctBy { it.first }.take(MAX_ATHLETES).toList()

    private const val MAX_ATHLETES = 200

    /** O gală sau un turneu de golf: un singur eveniment, fără echipe. Turneele de mai multe zile apar în fiecare zi. */
    private fun single(o: JsonObject, league: League, day: LocalDate?): SportEvent? {
        val id = o.text("id") ?: return null
        val name = o.text("name") ?: o.text("shortName") ?: return null
        if (cancelled(o)) return null
        val first = o.text("date")?.let(::isoMillis) ?: return null
        val end = o.text("endDate")?.let(::isoMillis)
        var start = first
        // Golful (joi–duminică): ziua cerută, la ora primei zile.
        if (day != null && end != null && end - first > TimeUnit.HOURS.toMillis(30)) {
            val firstDay = Instant.ofEpochMilli(first).atZone(ZONE)
            val lastDay = Instant.ofEpochMilli(end).atZone(ZONE).toLocalDate()
            if (day.isAfter(firstDay.toLocalDate()) && !day.isAfter(lastDay)) {
                start = day.atTime(firstDay.toLocalTime()).atZone(ZONE).toInstant().toEpochMilli()
            }
        }
        val comp = o.arr("competitions").filterIsInstance<JsonObject>()
        val stations = comp.flatMap { stations(it) }.distinct()
        return SportEvent(
            provider = PROVIDER,
            providerId = "${league.key}-$id" + if (start != first) "-$day" else "",
            sport = league.sport.sportsDbName,
            competition = league.competition,
            home = name,
            away = "",
            start = start,
            status = status(o) ?: comp.firstNotNullOfOrNull { status(it) },
            venue = comp.firstOrNull()?.obj("venue")?.text("fullName"),
            stations = stations,
            athletes = athletes(comp),
        )
    }

    private fun stations(comp: JsonObject): List<TvStation> {
        val names = LinkedHashSet<String>()
        comp.arr("broadcasts").filterIsInstance<JsonObject>().forEach { b -> b.arr("names").forEach { n -> n.str()?.let(names::add) } }
        comp.arr("geoBroadcasts").filterIsInstance<JsonObject>().forEach { g ->
            val type = g.obj("type")?.text("shortName")
            if (type == null || type.equals("TV", ignoreCase = true)) g.obj("media")?.let { m -> (m.text("shortName") ?: m.text("name"))?.let(names::add) }
        }
        return names.map { TvStation(it, "USA") }
    }

    private fun status(o: JsonObject): MatchStatus? = when (o.obj("status")?.obj("type")?.text("state")) {
        "pre" -> MatchStatus.SCHEDULED
        "in" -> MatchStatus.LIVE
        "post" -> MatchStatus.FINISHED
        else -> null
    }

    private fun cancelled(o: JsonObject): Boolean {
        val name = o.obj("status")?.obj("type")?.text("name").orEmpty()
        return "POSTPONED" in name || "CANCELED" in name || "CANCELLED" in name || "ABANDONED" in name
    }

    // ---------- pagina meciului (Pre-meci și Live) ----------

    /** Pre-meci și Live din `summary`: starea, scorul, formațiile, fazele, statisticile, clasamentul, forma, directele. */
    fun parseSummary(body: String, eventId: String): MatchDetails? {
        val root = parse(body) ?: return null
        val box = root.obj("boxscore")
        // Înainte de meci, ESPN dă uneori un `summary` fără `header` și cu unele părți mutate în `boxscore`.
        fun part(key: String): JsonElement? = root[key] ?: box?.get(key)
        fun partObj(key: String): JsonObject? = part(key) as? JsonObject
        fun partArr(key: String): List<JsonObject> = (part(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val header = root.obj("header")
        val comp = header?.arr("competitions")?.firstOrNull() as? JsonObject
        val boxTeams = box?.arr("teams")?.filterIsInstance<JsonObject>().orEmpty()
        val competitors = comp?.arr("competitors")?.filterIsInstance<JsonObject>()?.takeIf { it.isNotEmpty() } ?: boxTeams
        val home = competitors.firstOrNull { it.text("homeAway") == "home" } ?: competitors.getOrNull(0)
        val away = competitors.firstOrNull { it.text("homeAway") == "away" } ?: competitors.getOrNull(1)
        val homeId = home?.obj("team")?.text("id") ?: home?.text("id")
        val awayId = away?.obj("team")?.text("id") ?: away?.text("id")
        val homeName = home?.let(::teamName).orEmpty()
        val awayName = away?.let(::teamName).orEmpty()
        val st = comp?.obj("status")
        val status = comp?.let(::status)
        val info = partObj("gameInfo")
        val event = if (homeName.isEmpty() || awayName.isEmpty()) null else EventDetails(
            eventId = eventId,
            home = homeName,
            away = awayName,
            homeTeamId = homeId,
            awayTeamId = awayId,
            league = header?.obj("league")?.let { it.text("name") ?: it.text("abbreviation") },
            season = header?.obj("season")?.text("year"),
            start = comp?.text("date")?.let(::isoMillis),
            status = status,
            statusCode = statusCode(st),
            progress = st?.text("displayClock")?.takeIf { status == MatchStatus.LIVE }?.trimEnd('\'')?.takeIf { it != "0" && it != "0:00" },
            homeScore = home?.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
            awayScore = away?.text("score")?.toIntOrNull().takeIf { status != MatchStatus.SCHEDULED },
            venue = info?.obj("venue")?.text("fullName"),
            city = info?.obj("venue")?.obj("address")?.text("city"),
            referee = info?.arr("officials")?.filterIsInstance<JsonObject>()?.firstOrNull()?.let { it.text("displayName") ?: it.text("fullName") },
            spectators = info?.text("attendance")?.toIntOrNull()?.takeIf { it > 0 },
        )
        fun isHome(teamId: String?, homeAway: String?): Boolean =
            if (homeAway != null) homeAway == "home" else teamId != null && teamId == homeId

        val lineup = partArr("rosters").flatMap { r ->
            val h = isHome(r.obj("team")?.text("id"), r.text("homeAway"))
            r.arr("roster").filterIsInstance<JsonObject>().mapNotNull { p ->
                val name = p.obj("athlete")?.let { it.text("displayName") ?: it.text("fullName") } ?: return@mapNotNull null
                LineupPlayer(
                    name = name,
                    position = p.obj("position")?.let { it.text("name") ?: it.text("displayName") ?: it.text("abbreviation") },
                    number = p.text("jersey"),
                    home = h,
                    substitute = p.text("starter") != "true",
                )
            }
        }

        val timeline = partArr("keyEvents").mapNotNull { e ->
            val type = (e.obj("type")?.let { it.text("text") ?: it.text("type") } ?: "").lowercase()
            val kind = when {
                "own goal" in type -> TimelineKind.OWN_GOAL
                "penalty" in type && ("miss" in type || "saved" in type) -> TimelineKind.MISSED_PENALTY
                "penalty" in type && ("scored" in type || "goal" in type) -> TimelineKind.PENALTY
                "goal" in type -> TimelineKind.GOAL
                "red card" in type || "second yellow" in type -> TimelineKind.RED
                "yellow card" in type -> TimelineKind.YELLOW
                "substitution" in type -> TimelineKind.SUBSTITUTION
                "var" in type.split(' ', '-') -> TimelineKind.VAR
                else -> return@mapNotNull null // început, pauză, final…
            }
            val people = e.arr("participants").filterIsInstance<JsonObject>()
                .mapNotNull { p -> p.obj("athlete")?.let { it.text("displayName") ?: it.text("fullName") } }
            TimelineEntry(
                minute = minute(e.obj("clock")?.text("displayValue")),
                kind = kind,
                home = isHome(e.obj("team")?.text("id"), null),
                player = people.getOrNull(0),
                other = people.getOrNull(1),
            )
        }.sortedBy { it.minute ?: Int.MAX_VALUE }

        val stats = run {
            val h = boxTeams.firstOrNull { isHome(it.obj("team")?.text("id"), it.text("homeAway")) }
            val a = boxTeams.firstOrNull { it !== h }
            if (h == null || a == null) emptyList() else {
                val away = a.arr("statistics").filterIsInstance<JsonObject>().associateBy { it.text("name") ?: it.text("label") }
                h.arr("statistics").filterIsInstance<JsonObject>().mapNotNull { s ->
                    val key = s.text("name") ?: s.text("label") ?: return@mapNotNull null
                    val other = away[key] ?: return@mapNotNull null
                    StatLine(statLabel(key, s.text("label")), s.text("displayValue") ?: return@mapNotNull null, other.text("displayValue") ?: "0")
                }.distinctBy { it.name }
            }
        }

        val table = partObj("standings")?.arr("groups")?.filterIsInstance<JsonObject>().orEmpty()
            // Grupa cu una din echipe (la cupe sunt mai multe grupe), altfel prima.
            .let { groups ->
                groups.firstOrNull { g -> entries(g).any { r -> r.teamId != null && (r.teamId == homeId || r.teamId == awayId) } }
                    ?: groups.firstOrNull()
            }?.let(::entries).orEmpty()

        val forms = box?.arr("form")?.filterIsInstance<JsonObject>().orEmpty()
            .ifEmpty { partArr("lastFiveGames") }
        val homeForm = forms.firstOrNull { it.obj("team")?.text("id") == homeId } ?: forms.getOrNull(0)
        val awayForm = forms.firstOrNull { it.obj("team")?.text("id") == awayId } ?: forms.getOrNull(1)
        val h2h = partArr("headToHeadGames").flatMap { g -> pastGames(g) }

        // Fără meci început, `boxscore` are statisticile sezonului (goluri marcate, primite…), nu ale meciului.
        val season = status == null || status == MatchStatus.SCHEDULED
        val leaders = partArr("leaders").let { groups ->
            val h = groups.firstOrNull { isHome(it.obj("team")?.text("id"), it.text("homeAway")) }
            val a = groups.firstOrNull { it !== h }
            if (h == null || a == null) emptyList() else leaderLines(h, a)
        }

        return MatchDetails(
            event = event,
            seasonStats = if (season) stats + leaders else leaders,
            homeRecent = homeForm?.let(::pastGames).orEmpty(),
            awayRecent = awayForm?.let(::pastGames).orEmpty(),
            headToHead = h2h.distinctBy { it.providerId },
            table = table,
            lineup = lineup,
            timeline = timeline,
            stats = if (season) emptyList() else stats,
        )
    }

    /** Cei mai buni jucători ai sezonului (`leaders`): „Goluri: Panichelli 9 – Ely 4”. */
    private fun leaderLines(home: JsonObject, away: JsonObject): List<StatLine> {
        fun best(group: JsonObject): Map<String, Pair<String, String>> =
            group.arr("leaders").filterIsInstance<JsonObject>().mapNotNull { cat ->
                val key = cat.text("name") ?: cat.text("displayName") ?: return@mapNotNull null
                val top = cat.arr("leaders").firstOrNull() as? JsonObject ?: return@mapNotNull null
                val who = top.obj("athlete")?.let { it.text("shortName") ?: it.text("displayName") } ?: return@mapNotNull null
                val value = top.text("displayValue")?.substringBefore(',')?.trim() ?: top.text("value") ?: return@mapNotNull null
                key to ((cat.text("displayName") ?: key) to "$who ($value)")
            }.toMap()
        val h = best(home)
        val a = best(away)
        return h.keys.filter { it in a }.map { k -> StatLine(leaderLabel(k, h.getValue(k).first), h.getValue(k).second, a.getValue(k).second) }
    }

    private fun leaderLabel(name: String, label: String): String = when (name.lowercase()) {
        "goals", "goalsleaders" -> "Golgheter"
        "assists", "assistsleaders" -> "Pase decisive"
        "points", "pointsleaders" -> "Cele mai multe puncte"
        "rebounds", "reboundsleaders" -> "Cele mai multe recuperări"
        else -> label
    }

    /** Codul stării, în forma înțeleasă de [MatchInsights.liveLabel]. */
    private fun statusCode(st: JsonObject?): String? {
        val name = st?.obj("type")?.text("name")?.uppercase() ?: return null
        val period = st.text("period")?.toIntOrNull()
        return when {
            "HALFTIME" in name -> "HT"
            "FIRST_HALF" in name -> "1H"
            "SECOND_HALF" in name -> "2H"
            "SHOOTOUT" in name -> "P"
            "FINAL_PEN" in name -> "PEN"
            "FINAL_AET" in name || "FINAL_ET" in name -> "AET"
            "OVERTIME" in name || "EXTRA_TIME" in name -> "ET"
            "FULL_TIME" in name || name == "STATUS_FINAL" -> "FT"
            "SCHEDULED" in name -> "NS"
            "IN_PROGRESS" in name && period != null && period in 1..4 -> "Q$period"
            else -> null
        }
    }

    private val minuteText = Regex("""(\d{1,3})(?:'?\s*\+\s*(\d{1,2}))?""")

    /** „45'+2'” → 47, „67'” → 67. */
    internal fun minute(text: String?): Int? {
        val m = text?.let { minuteText.find(it) } ?: return null
        return m.groupValues[1].toInt() + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun entries(group: JsonObject): List<StandingRow> {
        val list = group.obj("standings")?.arr("entries")?.filterIsInstance<JsonObject>()
            ?: group.arr("entries").filterIsInstance<JsonObject>()
        return list.mapIndexedNotNull { i, e ->
            val team = (e["team"] as? JsonPrimitive)?.contentOrNull ?: e.obj("team")?.let { it.text("displayName") ?: it.text("name") } ?: return@mapIndexedNotNull null
            val stats = e.arr("stats").filterIsInstance<JsonObject>().associate { s ->
                (s.text("name") ?: s.text("type") ?: "") to (s.text("value") ?: s.text("displayValue"))
            }
            fun int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { k -> stats[k]?.toDoubleOrNull()?.toInt() }
            StandingRow(
                rank = int("rank") ?: (i + 1),
                team = team,
                teamId = e.text("id") ?: e.obj("team")?.text("id"),
                played = int("gamesPlayed"),
                won = int("wins"),
                drawn = int("ties", "draws"),
                lost = int("losses"),
                goalsFor = int("pointsFor"),
                goalsAgainst = int("pointsAgainst"),
                points = int("points"),
            )
        }.sortedBy { it.rank }
    }

    /** Meciurile trecute din `form`, `lastFiveGames` sau `headToHeadGames` (echipa + adversarul, cu scor). */
    private fun pastGames(group: JsonObject): List<SportEvent> {
        val team = group.obj("team")?.let { it.text("displayName") ?: it.text("name") }
        val teamId = group.obj("team")?.text("id")
        return group.arr("events").filterIsInstance<JsonObject>().mapNotNull { e ->
            val id = e.text("id") ?: return@mapNotNull null
            val start = e.text("gameDate")?.let(::isoMillis) ?: e.text("date")?.let(::isoMillis) ?: return@mapNotNull null
            val opponent = e.obj("opponent")?.let { it.text("displayName") ?: it.text("name") } ?: return@mapNotNull null
            val me = team ?: return@mapNotNull null
            val atHome = when {
                e.text("homeTeamId") != null && teamId != null -> e.text("homeTeamId") == teamId
                else -> e.text("atVs") != "@"
            }
            var hs = e.text("homeTeamScore")?.toIntOrNull()
            var aw = e.text("awayTeamScore")?.toIntOrNull()
            if (hs == null || aw == null) {
                // „2-1” din perspectiva echipei, cu rezultatul „W/D/L”.
                val parts = e.text("score")?.split('-')?.mapNotNull { it.trim().toIntOrNull() }
                if (parts?.size == 2) {
                    val (mine, theirs) = when (e.text("gameResult")) {
                        "L" -> minOf(parts[0], parts[1]) to maxOf(parts[0], parts[1])
                        else -> maxOf(parts[0], parts[1]) to minOf(parts[0], parts[1])
                    }
                    if (atHome) { hs = mine; aw = theirs } else { hs = theirs; aw = mine }
                }
            }
            SportEvent(
                provider = PROVIDER,
                providerId = id,
                sport = null,
                competition = e.text("leagueName") ?: e.text("leagueAbbreviation"),
                home = if (atHome) me else opponent,
                away = if (atHome) opponent else me,
                start = start,
                status = MatchStatus.FINISHED,
                homeScore = hs,
                awayScore = aw,
            )
        }
    }

    private val statNames = mapOf(
        "possessionpct" to "Posesie (%)",
        "totalshots" to "Total șuturi",
        "shotsontarget" to "Șuturi pe poartă",
        "wonCorners".lowercase() to "Cornere",
        "foulscommitted" to "Faulturi",
        "yellowcards" to "Cartonașe galbene",
        "redcards" to "Cartonașe roșii",
        "offsides" to "Ofsaiduri",
        "saves" to "Parade",
        "totalpasses" to "Pase",
        "accuratepasses" to "Pase reușite",
        "passpct" to "Precizia paselor (%)",
        "blockedshots" to "Șuturi blocate",
        "totaltackles" to "Deposedări",
        "interceptions" to "Intercepții",
        "fieldgoalspct" to "Aruncări reușite (%)",
        "threepointfieldgoalspct" to "Aruncări de 3 reușite (%)",
        "totalrebounds" to "Recuperări",
        "assists" to "Pase decisive",
        "turnovers" to "Mingi pierdute",
        "shotstotal" to "Șuturi",
        "powerplaygoals" to "Goluri în powerplay",
        "faceoffpercent" to "Face-off-uri câștigate (%)",
        "totalgoals" to "Goluri marcate",
        "goalsconceded" to "Goluri primite",
        "goaldifference" to "Golaveraj",
        "goalassists" to "Pase de gol",
        "cleansheet" to "Meciuri fără gol primit",
    )

    private fun statLabel(name: String, label: String?): String =
        statNames[name.lowercase()] ?: TheSportsDbDetails.statLabel(label ?: name)

    // ---------- JSON ----------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
