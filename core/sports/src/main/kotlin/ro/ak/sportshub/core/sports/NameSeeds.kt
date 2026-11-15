package ro.ak.sportshub.core.sports

import ro.ak.sportshub.core.sports.NameCatalog.Entry
import ro.ak.sportshub.core.sports.NameCatalog.Kind

/**
 * Lista fixă a catalogului de nume, pentru ce nu vine (sau nu vine încă) din programul furnizorilor: competițiile
 * cu prescurtările lor (UCL, EPL, L1), sporturile fără ESPN (snooker, darts, MotoGP) și piloții F1 actuali.
 * Numele învățate de la furnizori au prioritate (aceeași cheie); lista doar completează sugestiile.
 */
internal object NameSeeds {

    /** Prescurtări puse de mână pe competițiile din catalogul ESPN (cheia din [Espn.leagues]). */
    private val competitionExtra = mapOf(
        "EPL" to listOf("Premier League", "PL", "EPL", "Prem"),
        "ENG2" to listOf("Championship", "EFL Championship"),
        "FACUP" to listOf("FA Cup"),
        "EFLCUP" to listOf("Carabao Cup", "League Cup", "EFL Cup"),
        "WSL" to listOf("WSL", "Women's Super League"),
        "LALIGA" to listOf("La Liga", "LaLiga", "Primera Division"),
        "LALIGA2" to listOf("La Liga 2", "Segunda Division"),
        "SERIEA" to listOf("Serie A"),
        "BUNDESLIGA" to listOf("Bundesliga", "BL"),
        "BUNDESLIGA2" to listOf("2. Bundesliga", "2 Bundesliga"),
        "POKAL" to listOf("DFB Pokal", "DFB-Pokal"),
        "LIGUE1" to listOf("Ligue 1", "L1"),
        "LIGUE2" to listOf("Ligue 2", "L2"),
        "ROMANIA" to listOf("Liga 1", "SuperLiga", "Superliga Romaniei", "Liga I"),
        "PORTUGAL" to listOf("Liga Portugal", "Primeira Liga"),
        "TURKEY" to listOf("Super Lig", "Süper Lig"),
        "UCL" to listOf("Champions League", "CL", "UCL", "UEFA CL", "Liga Campionilor"),
        "UEL" to listOf("Europa League", "UEL", "EL"),
        "UECL" to listOf("Conference League", "UECL", "ECL"),
        "NATIONS" to listOf("Nations League", "UNL", "Liga Natiunilor"),
        "EURO" to listOf("Euro", "Campionatul European"),
        "WORLDCUP" to listOf("World Cup", "Campionatul Mondial", "Mondial", "Cupa Mondiala"),
        "CWC" to listOf("Club World Cup", "Mondialul cluburilor"),
        "FRIENDLY" to listOf("Amicale", "Friendlies"),
        "MLS" to listOf("MLS"),
        "LIBERTADORES" to listOf("Libertadores"),
        "F1" to listOf("F1", "Formula One", "Formula 1"),
        "NASCAR" to listOf("NASCAR"),
        "PGA" to listOf("PGA"),
    )

    /** Competiții din afara catalogului ESPN (alți furnizori sau doar EPG-ul). */
    private val otherCompetitions = listOf(
        "EuroLeague" to listOf("Euroleague", "Euroliga"),
        "EuroCup" to listOf("Eurocup"),
        "MotoGP" to listOf("Moto GP"),
        "World Snooker Championship" to listOf("Snooker World Championship", "Crucible"),
        "UK Snooker Championship" to listOf("UK Championship"),
        "The Masters Snooker" to listOf("Masters snooker"),
        "PDC World Darts Championship" to listOf("World Darts Championship", "Darts WC"),
        "Premier League Darts" to listOf("PL Darts"),
        "Tour de France" to listOf("TdF", "Turul Frantei"),
        "Giro d'Italia" to listOf("Giro"),
        "Vuelta a Espana" to listOf("Vuelta"),
        "Six Nations" to listOf("6 Nations", "Six Nations Championship"),
        "Rugby World Cup" to listOf("RWC"),
        "Olympic Games" to listOf("Olympics", "Jocurile Olimpice", "JO"),
        "24 Hours of Le Mans" to listOf("Le Mans", "24h Le Mans"),
        "EHF Champions League" to listOf("Liga Campionilor handbal"),
        "Australian Open" to listOf("AO"),
        "Roland Garros" to listOf("French Open", "RG"),
        "Wimbledon" to emptyList(),
        "US Open" to listOf("USO"),
    )

    private val athletes = listOf(
        // Formula 1
        "Max Verstappen" to listOf("Verstappen", "M. Verstappen"), "Lewis Hamilton" to listOf("Hamilton"),
        "Charles Leclerc" to listOf("Leclerc"), "Lando Norris" to listOf("Norris"), "Oscar Piastri" to listOf("Piastri"),
        "George Russell" to listOf("Russell"), "Andrea Kimi Antonelli" to listOf("Kimi Antonelli", "Antonelli"),
        "Fernando Alonso" to listOf("Alonso"), "Lance Stroll" to listOf("Stroll"), "Pierre Gasly" to listOf("Gasly"),
        "Franco Colapinto" to listOf("Colapinto"), "Alexander Albon" to listOf("Alex Albon"), "Carlos Sainz" to listOf("Sainz"),
        "Nico Hulkenberg" to listOf("Hülkenberg"), "Gabriel Bortoleto" to listOf("Bortoleto"), "Esteban Ocon" to listOf("Ocon"),
        "Oliver Bearman" to listOf("Ollie Bearman"), "Liam Lawson" to listOf("Lawson"), "Isack Hadjar" to listOf("Hadjar"),
        "Arvid Lindblad" to listOf("Lindblad"), "Sergio Perez" to listOf("Checo Perez"), "Valtteri Bottas" to listOf("Bottas"),
        // MotoGP
        "Marc Marquez" to listOf("Márquez"), "Alex Marquez" to emptyList(), "Francesco Bagnaia" to listOf("Pecco Bagnaia"),
        "Jorge Martin" to emptyList(), "Pedro Acosta" to emptyList(), "Marco Bezzecchi" to emptyList(),
        "Fabio Quartararo" to emptyList(), "Fabio Di Giannantonio" to listOf("Diggia"), "Franco Morbidelli" to emptyList(),
        "Enea Bastianini" to emptyList(), "Brad Binder" to emptyList(), "Joan Mir" to emptyList(),
        // Snooker
        "Judd Trump" to emptyList(), "Ronnie O'Sullivan" to listOf("Ronnie OSullivan", "The Rocket"),
        "Kyren Wilson" to emptyList(), "Mark Selby" to emptyList(), "Neil Robertson" to emptyList(), "Mark Allen" to emptyList(),
        "John Higgins" to emptyList(), "Mark Williams" to emptyList(), "Zhao Xintong" to emptyList(), "Shaun Murphy" to emptyList(),
        "Luca Brecel" to emptyList(), "Ding Junhui" to emptyList(), "Si Jiahui" to emptyList(), "Barry Hawkins" to emptyList(),
        "Xiao Guodong" to emptyList(), "Ali Carter" to emptyList(), "Wu Yize" to emptyList(), "Chris Wakelin" to emptyList(),
        "Jak Jones" to emptyList(), "Stuart Bingham" to emptyList(), "Gary Wilson" to emptyList(), "Zhang Anda" to emptyList(),
        "Jack Lisowski" to emptyList(), "Tom Ford" to emptyList(), "Hossein Vafaei" to emptyList(), "Pang Junxu" to emptyList(),
        // Darts
        "Luke Littler" to listOf("The Nuke"), "Luke Humphries" to listOf("Cool Hand Luke"),
        "Michael van Gerwen" to listOf("MVG"), "Gerwyn Price" to emptyList(), "Stephen Bunting" to emptyList(),
        "Rob Cross" to emptyList(), "Michael Smith" to emptyList(), "Peter Wright" to listOf("Snakebite"),
        "Nathan Aspinall" to emptyList(), "Gary Anderson" to emptyList(), "Danny Noppert" to emptyList(),
        "Jonny Clayton" to emptyList(), "Damon Heta" to emptyList(), "Josh Rock" to emptyList(), "Gian van Veen" to emptyList(),
        "Dimitri Van den Bergh" to emptyList(), "Chris Dobey" to emptyList(), "James Wade" to emptyList(),
        "Dave Chisnall" to emptyList(), "Mike De Decker" to emptyList(), "Martin Schindler" to emptyList(), "Ross Smith" to emptyList(),
        "Ryan Searle" to emptyList(),
    )

    /** Primul cuvânt al numelor ESPN e țara („English Premier League”): fără el e numele obișnuit. */
    private val nationality = Regex("^(English|Spanish|Italian|German|French|Portuguese|Dutch|Belgian|Scottish|Turkish|Greek|Austrian|Swiss|Danish|Swedish|Norwegian|Romanian|Russian|Polish|Czech|American|Mexican|Brazilian|Argentine|Saudi|Japanese|Chinese|Australian) ")

    val entries: Map<String, Entry> by lazy {
        val map = LinkedHashMap<String, Entry>()
        fun put(name: String, kind: Kind, alt: Collection<String>) {
            val key = kind.name + ":" + Sport.fold(name)
            val cur = map[key]
            val alts = alt.map { it.trim() }.filter { it.isNotEmpty() && Sport.fold(it) != Sport.fold(name) }
            map[key] = Entry(cur?.name ?: name, kind, (cur?.alt.orEmpty() + alts).toSet(), 0)
        }
        for (l in Espn.leagues) {
            val alt = ArrayList<String>()
            nationality.replace(l.competition, "").takeIf { it != l.competition }?.let(alt::add)
            competitionExtra[l.key]?.let(alt::addAll)
            put(l.competition, Kind.COMPETITION, alt)
        }
        for ((name, alt) in otherCompetitions) put(name, Kind.COMPETITION, alt)
        for ((name, alt) in athletes) put(name, Kind.ATHLETE, alt)
        // Cluburile din primele ligi și din Liga 1, cu formele lor („U Craiova”, „UCV”).
        for ((name, alt) in ClubNames.forms) put(name, Kind.TEAM, alt)
        // Cluburile cu prescurtări încorporate („man utd” → Manchester United).
        TeamNames.builtInClubAliases.entries.groupBy({ it.value }, { it.key }).forEach { (full, shorts) ->
            put(titleCase(full), Kind.TEAM, shorts.map(::titleCase))
        }
        map
    }

    /** Forma scurtă a unei competiții (fără diacritice) → numele întreg, tot fără diacritice; doar formele unice. */
    private val competitionAliases: Map<String, String> by lazy {
        val owners = HashMap<String, MutableSet<String>>()
        for (e in entries.values) {
            if (e.kind != Kind.COMPETITION) continue
            for (a in e.alt) owners.getOrPut(Sport.fold(a)) { HashSet() } += Sport.fold(e.name)
        }
        owners.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    /** Cheia unei competiții: „UCL”, „Champions League” și „UEFA Champions League” dau aceeași. */
    fun competitionKey(name: String): String {
        val f = Sport.fold(name)
        return competitionAliases[f] ?: f
    }

    /**
     * Competiția favorită [favorite] se potrivește cu cea a meciului [competition]: aceeași (și prescurtată),
     * sau turneul e din ea („ATP” pentru „ATP · Paris Masters”).
     */
    fun sameCompetition(favorite: String, competition: String): Boolean {
        val fav = competitionKey(favorite)
        if (fav.isEmpty()) return false
        return fav == competitionKey(competition) || fav == competitionKey(competition.substringBefore(" · ")) ||
            competition.contains(" · ") && fav == competitionKey(competition.substringAfter(" · "))
    }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.length <= 4 && w in setOf("psg", "psv", "fcsb", "cfr", "bvb", "ac", "fc", "cp")) w.uppercase()
        else w.replaceFirstChar { it.uppercase() }
    }
}
