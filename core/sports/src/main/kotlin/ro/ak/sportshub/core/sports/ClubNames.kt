package ro.ak.sportshub.core.sports

/**
 * Formele sub care apar cluburile din primele ligi europene și din Liga 1 în EPG-uri, pe canalele EVENT și la
 * furnizori: prescurtări, porecle, numele local („U Craiova”, „UCV”, „CSU Craiova” → Universitatea Craiova).
 * Primul nume e cel folosit de ESPN (furnizorul principal), ca cheile să coincidă. Doar formele care duc la un
 * singur club: cele comune mai multor echipe („United”, „City”, „Real”) nu sunt aici.
 */
internal object ClubNames {

    private val clubs = listOf(
        // Anglia
        "Arsenal|The Arsenal",
        "Aston Villa|Villa|AVFC",
        "AFC Bournemouth|Bournemouth",
        "Brentford",
        "Brighton & Hove Albion|Brighton|Brighton Hove|BHA",
        "Burnley",
        "Chelsea",
        "Crystal Palace|Palace|CPFC|C Palace",
        "Everton",
        "Fulham",
        "Leeds United|Leeds|Leeds Utd|LUFC",
        "Liverpool",
        "Manchester City|Man City|Man. City|MCFC",
        "Manchester United|Man Utd|Man United|Man U|Man. Utd|Man. United|MUFC",
        "Newcastle United|Newcastle|Newcastle Utd|NUFC",
        "Nottingham Forest|Nottm Forest|Notts Forest|Forest|NFFC",
        "Sunderland|SAFC",
        "Tottenham Hotspur|Tottenham|Spurs|THFC",
        "West Ham United|West Ham|West Ham Utd|WHUFC",
        "Wolverhampton Wanderers|Wolves|Wolverhampton",
        "Leicester City|Leicester|LCFC",
        "Southampton",
        "Ipswich Town|Ipswich",
        "Sheffield United|Sheff Utd|Sheffield Utd",
        "Sheffield Wednesday|Sheff Wed|Sheffield Wed",
        "West Bromwich Albion|West Brom|WBA",
        "Queens Park Rangers|QPR",
        "Middlesbrough",
        // Spania
        "Real Madrid|Real Madrid CF",
        "Barcelona|Barca|Barça|FC Barcelona",
        "Atlético Madrid|Atletico|Atletico de Madrid|Atl Madrid|Atleti",
        "Athletic Club|Athletic Bilbao|Bilbao|Ath Bilbao",
        "Real Sociedad|La Real|R Sociedad",
        "Real Betis|Betis",
        "Sevilla|Sevilla FC|Seville",
        "Villarreal",
        "Valencia|Valencia CF",
        "Celta Vigo|Celta|RC Celta",
        "Rayo Vallecano|Rayo",
        "Osasuna|CA Osasuna",
        "Mallorca|RCD Mallorca|Real Mallorca",
        "Espanyol|RCD Espanyol",
        "Getafe|Getafe CF",
        "Alavés|Alaves|Deportivo Alaves",
        "Girona|Girona FC",
        "Levante|Levante UD",
        "Elche|Elche CF",
        "Real Oviedo|Oviedo",
        // Italia
        "Internazionale|Inter|Inter Milan|Inter Milano|FC Internazionale",
        "AC Milan|Milan",
        "Juventus|Juve|Bianconeri",
        "Napoli|SSC Napoli",
        "AS Roma|Roma",
        "Lazio|SS Lazio",
        "Atalanta|Atalanta BC|Atalanta Bergamo",
        "Fiorentina|ACF Fiorentina",
        "Bologna|Bologna FC",
        "Torino|Torino FC",
        "Genoa|Genoa CFC",
        "Udinese|Udinese Calcio",
        "Hellas Verona|Verona",
        "Cagliari|Cagliari Calcio",
        "Lecce|US Lecce",
        "Parma|Parma Calcio",
        "Como|Como 1907",
        "Sassuolo|US Sassuolo",
        "Cremonese|US Cremonese",
        "Pisa|Pisa SC",
        // Germania
        "Bayern Munich|Bayern|Bayern München|Bayern Munchen|FC Bayern|FCB Munich",
        "Borussia Dortmund|Dortmund|BVB|B Dortmund",
        "Bayer Leverkusen|Leverkusen|Bayer 04|B Leverkusen",
        "RB Leipzig|Leipzig",
        "Eintracht Frankfurt|Frankfurt|E Frankfurt",
        "VfB Stuttgart|Stuttgart",
        "SC Freiburg|Freiburg",
        "VfL Wolfsburg|Wolfsburg",
        "Borussia Mönchengladbach|Gladbach|Monchengladbach|M'gladbach|B Monchengladbach",
        "Werder Bremen|Bremen|SV Werder",
        "TSG Hoffenheim|Hoffenheim|1899 Hoffenheim",
        "1. FC Union Berlin|Union Berlin",
        "FSV Mainz 05|Mainz|Mainz 05",
        "FC Augsburg|Augsburg",
        "1. FC Heidenheim 1846|Heidenheim",
        "FC St. Pauli|St Pauli|St. Pauli",
        "Hamburg SV|Hamburg|HSV|Hamburger SV",
        "1. FC Köln|Koln|Köln|Cologne|FC Koln",
        "Schalke 04|Schalke",
        // Franța
        "Paris Saint-Germain|PSG|Paris SG|Paris St Germain|Paris St-Germain",
        "Marseille|Olympique de Marseille|Olympique Marseille",
        "Lyon|Olympique Lyonnais|Olympique Lyon",
        "AS Monaco|Monaco",
        "Lille|LOSC|LOSC Lille",
        "Lens|RC Lens",
        "Nice|OGC Nice",
        "Rennes|Stade Rennais",
        "Strasbourg|RC Strasbourg",
        "Nantes|FC Nantes",
        "Toulouse|Toulouse FC",
        "Brest|Stade Brestois",
        "Auxerre|AJ Auxerre",
        "Angers|Angers SCO",
        "Le Havre|Le Havre AC",
        "Lorient|FC Lorient",
        "Metz|FC Metz",
        "Paris FC",
        // Portugalia
        "Benfica|SL Benfica|Benfica Lisbon",
        "Sporting CP|Sporting Lisbon|Sporting Clube de Portugal",
        "FC Porto|Porto",
        "SC Braga|Braga|Sporting Braga",
        "Vitória de Guimarães|Vitoria Guimaraes|Guimaraes",
        // Olanda
        "Ajax Amsterdam|Ajax",
        "PSV Eindhoven|PSV",
        "Feyenoord Rotterdam|Feyenoord",
        "AZ Alkmaar|AZ",
        "FC Twente|Twente",
        "FC Utrecht|Utrecht",
        // Belgia
        "Club Brugge|Club Bruges|Brugge|Bruges",
        "Anderlecht|RSC Anderlecht",
        "Union St.-Gilloise|Union Saint-Gilloise|Union SG|USG",
        "Genk|KRC Genk|Racing Genk",
        "Gent|KAA Gent|Ghent",
        "Standard Liege|Standard|Standard de Liege",
        "Royal Antwerp|Antwerp",
        // Turcia
        "Galatasaray",
        "Fenerbahce|Fenerbahçe",
        "Besiktas|Beşiktaş|BJK",
        "Trabzonspor|Trabzon",
        "Istanbul Basaksehir|Basaksehir|Başakşehir",
        // Scoția
        "Celtic|Celtic Glasgow",
        "Rangers|Glasgow Rangers",
        "Aberdeen",
        "Heart of Midlothian|Hearts",
        "Hibernian|Hibs",
        // România
        "FCSB|Steaua|Steaua Bucuresti|Steaua București|FC FCSB|SC FCSB",
        "Universitatea Craiova|U Craiova|UCV|Univ Craiova|Univ. Craiova|Univ Cr|CSU Craiova|CS Universitatea Craiova|Stiinta Craiova|Craiova",
        "CFR Cluj|CFR|CFR 1907 Cluj|CFR 1907",
        "Rapid Bucuresti|Rapid|Rapid București|FC Rapid|FC Rapid 1923|Rapid 1923",
        "Dinamo Bucuresti|Dinamo|Dinamo București|Dinamo Bucharest|FC Dinamo",
        "Universitatea Cluj|U Cluj|Univ Cluj|Univ. Cluj|FC Universitatea Cluj",
        "Farul Constanta|Farul|Farul Constanța|FCV Farul",
        "Petrolul Ploiesti|Petrolul|Petrolul Ploiești|Petrolul 52",
        "UTA Arad|UTA",
        "Otelul Galati|Otelul|Oțelul Galați|Oțelul",
        "FC Hermannstadt|Hermannstadt|Sibiu",
        "FC Botosani|Botosani|Botoșani|FC Botoșani",
        "Unirea Slobozia|Slobozia",
        "Csikszereda|Csíkszereda|FK Csikszereda|Miercurea Ciuc|Csikszereda Miercurea Ciuc",
        "Metaloglobus Bucuresti|Metaloglobus",
        "FC Arges|Arges|Argeș|Arges Pitesti|FC Argeș Pitești",
        "Gloria Buzau|Gloria Buzău",
        "Politehnica Iasi|Poli Iasi|Politehnica Iași|CSM Iasi",
        "Sepsi OSK|Sepsi|Sepsi Sfantu Gheorghe",
        "Corvinul Hunedoara|Corvinul",
    )

    /** Fiecare formă (normalizată de [norm]) → cheia clubului ([target]); o formă care ar duce la două cluburi nu se ia. */
    fun aliases(norm: (String) -> String, target: (String) -> String): Map<String, String> {
        val canonical = clubs.map { norm(it.substringBefore('|')) }.toSet()
        val owners = HashMap<String, MutableSet<String>>()
        for (line in clubs) {
            val names = line.split('|')
            val target = target(names[0])
            for (a in names.drop(1)) {
                val k = norm(a)
                if (k.isEmpty() || k == target || k in canonical) continue
                owners.getOrPut(k) { HashSet() } += target
            }
        }
        return owners.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    /** Clubul și formele lui, pentru catalogul de nume: numele întreg → celelalte forme. */
    val forms: List<Pair<String, List<String>>> by lazy {
        clubs.map { line -> line.split('|').let { it[0] to it.drop(1) } }
    }
}
