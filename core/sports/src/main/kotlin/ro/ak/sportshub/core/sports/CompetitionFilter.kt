package ro.ak.sportshub.core.sports

/**
 * Doar competițiile importante (setare, implicit pornită), aplicat înainte de orice calcul:
 * - fotbal: primele două niveluri din campionatele mari ale Europei, primul nivel din cele mai importante din
 *   America, Asia și restul lumii, competițiile internaționale (cupe europene, echipe naționale) și amicalele
 *   dintre echipe din aceste competiții; Anglia: patru niveluri și mai jos, plus U18, U21 și fotbal feminin;
 * - celelalte sporturi: doar campionatele și turneele puternice;
 * - la orice sport: campionatele mondiale și europene, Jocurile Olimpice și turneele majore.
 *
 * Numele competițiilor vin în mai multe limbi și forme de la furnizori („England - Premier League”,
 * „LaLiga EA Sports”, „Premier League Egipto”), deci se caută șiruri de cuvinte întregi, fără diacritice.
 * O competiție necunoscută la fotbal e eliminată; o competiție lipsă (EPG) se judecă după echipe ([keepMatch]).
 */
object CompetitionFilter {

    enum class Verdict { KEEP, DROP, UNKNOWN }

    /** Turneele majore, la orice sport. */
    private val majors = phrases(
        "world cup", "world championship", "world championships", "mondial", "mondiale", "mundial", "campionatul mondial",
        "coupe du monde", "weltmeisterschaft", "copa do mundo", "copa del mundo", "european championship",
        "european championships", "campionatul european", "euro", "eurobasket", "eurohockey", "olympic", "olympics",
        "olympic games", "jocurile olimpice", "olimpiadas", "nations league", "copa america", "africa cup of nations",
        "afcon", "asian cup", "gold cup", "finalissima", "world series", "super bowl", "stanley cup", "grand slam",
        "world grand prix", "world tour finals", "atp finals", "wta finals", "diamond league", "ryder cup",
        "six nations", "world games", "commonwealth games", "european games", "mondiali", "europei",
        "campeonato mundial", "campeonato europeo", "championnats du monde", "championnat d europe",
    )

    /** Feminin: doar Anglia (și turneele majore). */
    private val women = phrases(
        "women", "womens", "woman", "ladies", "feminin", "feminine", "femenino", "femenina", "femminile", "frauen",
        "vrouwen", "damallsvenskan", "wsl", "femenil", "femminile", "kobiet", "damer",
    )

    /** Tineret și rezerve: doar Anglia. */
    private val youth = phrases(
        "u16", "u17", "u18", "u19", "u20", "u21", "u23", "under 17", "under 18",
        "under 19", "under 20", "under 21", "under 23", "sub 17", "sub 19", "sub 20", "sub 21", "youth", "junior",
        "juniors", "juvenil", "primavera", "reserves", "reserve", "next pro", "academy",
    )

    /** Anglia: tot ce e mai jos de Premier League, plus tineret și feminin. */
    private val england = phrases(
        "england", "english", "inglaterra", "inghilterra", "angleterre", "efl", "fa cup", "fa trophy", "fa vase",
        "fa youth cup", "carabao cup", "league cup", "efl cup", "efl trophy", "community shield", "league one",
        "league two", "national league", "national league north", "national league south", "premier league 2",
        "u18 premier league", "u21 premier league", "professional development league", "pl2", "wsl",
        "women s super league", "womens super league", "women s championship", "womens championship", "fa wsl",
        "barclays women s super league", "fa women s league cup", "fa women s cup",
    )

    /** Calificative care arată un campionat exotic chiar dacă numele seamănă („Premier League” din Egipt). */
    private val exotic = phrases(
        "egypt", "egyptian", "egipto", "kenya", "kenyan", "kenia", "nigeria", "nigerian", "ghana", "ghanaian", "uganda",
        "ugandan", "tanzania", "tanzanian", "zambia", "zambian", "zimbabwe", "ethiopia", "ethiopian", "rwanda", "malawi",
        "botswana", "namibia", "mozambique", "angola", "cameroon", "senegal", "ivory coast", "cote d ivoire", "algeria",
        "algerian", "argelia", "tunisia", "tunisian", "tunez", "morocco", "moroccan", "marruecos", "botola", "sudan",
        "libya", "south africa", "south african", "sudafrica", "psl", "india", "indian", "isl", "i league", "bangladesh",
        "pakistan", "nepal", "bhutan", "myanmar", "thai", "thailand", "vietnam", "v league", "indonesia", "indonesian",
        "liga 1 indonesia", "malaysia", "malaysian", "singapore", "philippines", "hong kong", "cambodia", "laos",
        "uzbekistan", "kazakhstan", "kyrgyzstan", "tajikistan", "turkmenistan", "iran", "iranian", "persian gulf",
        "iraq", "iraqi", "jordan", "jordanian", "lebanon", "lebanese", "syria", "syrian", "oman", "bahrain", "kuwait",
        "kuwaiti", "yemen", "palestine", "qatar", "qatari", "uae", "emirates", "jamaica", "jamaican", "trinidad",
        "honduras", "guatemala", "el salvador", "costa rica", "nicaragua", "panama", "haiti", "bolivia", "bolivian",
        "peru", "peruvian", "venezuela", "venezuelan", "paraguay", "paraguayan", "uruguay", "uruguayan", "ecuador",
        "ecuadorian", "chile", "chilean", "colombia", "colombian", "armenia", "armenian", "azerbaijan", "georgia",
        "georgian", "belarus", "moldova", "faroe", "iceland", "icelandic", "malta", "maltese", "gibraltar", "andorra",
        "san marino", "luxembourg", "liechtenstein", "estonia", "estonian", "latvia", "latvian", "lithuania",
        "lithuanian", "albania", "albanian", "kosovo", "montenegro", "north macedonia", "macedonia", "bosnia",
        "cyprus", "cypriot", "finland", "finnish", "veikkausliiga", "wales", "welsh", "cymru", "northern ireland",
        "nifl", "league of ireland", "ireland", "irish", "new zealand", "fiji", "canadian", "canada", "singapore",
        "brunei", "maldives", "sri lanka", "afghanistan", "mongolia", "taiwan", "chinese taipei", "korea dpr",
        "regional", "regionalliga", "oberliga", "tercera", "primera rfef", "segunda rfef", "tercera rfef",
        "serie c", "serie d", "national 2", "national 3", "3 liga", "liga 3", "liga iii", "amateur", "promotion",
        "division 3", "third division", "fourth division", "state league", "npl", "brasileiro serie b",
        "brazil serie b", "serie b brazil", "brazilian serie b", "brasileirao serie b",
    )

    /** Organizatorii continentali și mondiali: competițiile lor rămân și cu nume de țări exotice în titlu. */
    private val confederations = phrases(
        "uefa", "fifa", "caf", "afc", "concacaf", "conmebol", "ofc", "libertadores", "sudamericana",
    )

    private val friendly = phrases(
        "friendly", "friendlies", "club friendlies", "international friendlies", "amical", "amicale", "amicales",
        "amistoso", "amistosos", "amichevole", "testspiel", "freundschaftsspiel", "partido amistoso",
    )

    /** Fotbal: cupele internaționale și competițiile echipelor naționale. */
    private val footballInternational = phrases(
        "champions league", "uefa champions league", "europa league", "uefa europa league", "conference league",
        "uefa conference league", "uefa super cup", "super cup", "club world cup", "intercontinental cup",
        "world cup qualification", "world cup qualifying", "world cup qualifiers", "wc qualification",
        "euro qualification", "euro qualifiers", "european qualifiers", "eliminatorias", "uefa", "fifa",
        "copa libertadores", "libertadores", "copa sudamericana", "sudamericana", "concacaf champions",
        "concacaf champions cup", "leagues cup", "afc champions league", "afc champions league elite",
        "caf champions league", "conmebol",
    )

    /** Fotbal: campionatele (Europa: două niveluri; restul: primul nivel al celor mai importante) și cupele lor. */
    private val footballLeagues = phrases(
        // Anglia (restul niveluri în [england])
        "premier league", "english premier league", "championship", "efl championship",
        // Spania
        "laliga", "la liga", "laliga ea sports", "primera division", "laliga2", "laliga 2", "la liga 2",
        "laliga hypermotion", "hypermotion", "segunda division", "copa del rey", "supercopa", "supercopa de espana",
        // Italia
        "serie a", "serie b", "coppa italia", "supercoppa", "supercoppa italiana",
        // Germania (și Austria)
        "bundesliga", "2 bundesliga", "dfb pokal", "supercup", "ofb cup",
        // Franța
        "ligue 1", "ligue 2", "coupe de france", "trophee des champions",
        // Portugalia
        "primeira liga", "liga portugal", "liga portugal 2", "liga portugal betclic", "segunda liga", "taca de portugal",
        "taca da liga",
        // Olanda, Belgia
        "eredivisie", "eerste divisie", "keuken kampioen divisie", "knvb beker", "pro league", "jupiler pro league",
        "first division a", "challenger pro league", "belgian cup",
        // Scoția
        "premiership", "scottish premiership", "scottish championship", "scottish cup", "scottish league cup",
        // Turcia
        "super lig", "1 lig", "turkish cup",
        // România
        "superliga", "superliga romaniei", "liga 1", "liga i", "liga 2", "liga ii", "cupa romaniei",
        "supercupa romaniei",
        // Grecia, Elveția, Danemarca, Suedia, Norvegia, Polonia, Cehia, Croația, Serbia, Ucraina, Rusia, Ungaria
        "super league greece", "super league 1", "greek cup", "super league", "challenge league", "danish superliga",
        "allsvenskan", "eliteserien", "ekstraklasa", "czech first league", "chance liga", "fortuna liga", "hnl",
        "supersport hnl", "serbian superliga", "mozzart bet superliga", "premier liga", "ukrainian premier league",
        "russian premier league", "nb i", "otp bank liga",
        // America
        "mls", "major league soccer", "liga mx", "brasileirao", "campeonato brasileiro", "brasileiro serie a",
        "liga profesional", "liga profesional argentina", "primera division argentina", "copa de la liga",
        // Asia, Australia
        "saudi pro league", "roshn saudi league", "saudi professional league", "j1 league", "j league", "k league 1",
        "k league", "a league", "a league men", "chinese super league",
    )

    private val other: Map<Sport, List<String>> = mapOf(
        Sport.BASKETBALL to phrases(
            "nba", "nba cup", "euroleague", "eurocup", "liga endesa", "acb", "basketball champions league", "lega basket",
            "lba", "serie a basket", "bbl", "easycredit bbl", "betclic elite", "lnb", "pro a", "fiba", "liga nationala",
            "eurobasket", "aba league", "greek basket league", "turkish basketball super league", "bsl",
        ),
        Sport.TENNIS to phrases(
            "atp", "wta", "australian open", "roland garros", "french open", "wimbledon", "us open", "davis cup",
            "billie jean king", "united cup", "laver cup", "masters", "next gen",
        ),
        Sport.ICE_HOCKEY to phrases(
            "nhl", "khl", "shl", "liiga", "del", "national league", "champions hockey league", "iihf", "extraliga",
            "ice hockey league", "ich",
        ),
        Sport.HANDBALL to phrases(
            "ehf", "champions league", "european league", "ehf european league", "bundesliga", "handball bundesliga",
            "liga asobal", "lnh", "starligue", "liqui moly hbl", "hbl", "liga nationala", "handbollsligan",
        ),
        Sport.VOLLEYBALL to phrases(
            "cev", "champions league", "superlega", "plusliga", "vnl", "fivb", "volleyball nations league",
            "efeler ligi", "sultanlar ligi",
        ),
        Sport.RUGBY to phrases(
            "six nations", "premiership", "premiership rugby", "gallagher premiership", "top 14", "champions cup",
            "investec champions cup", "challenge cup", "urc", "united rugby championship", "rugby championship",
            "super rugby", "autumn nations", "autumn internationals", "test", "test match", "rugby world cup",
        ),
        Sport.AMERICAN_FOOTBALL to phrases("nfl", "super bowl", "nfl playoffs"),
        Sport.BASEBALL to phrases("mlb", "world series", "world baseball classic"),
        Sport.MOTORSPORT to phrases(
            "formula 1", "formula1", "f1", "motogp", "moto gp", "wec", "le mans", "indycar", "nascar cup", "nascar",
            "formula e", "wrc", "dakar", "grand prix",
        ),
        Sport.FIGHTING to phrases("ufc", "boxing", "box", "glory", "one championship", "pfl"),
        Sport.DARTS to phrases(
            "pdc", "premier league", "world matchplay", "grand slam", "world grand prix", "players championship finals",
            "uk open", "european championship", "world masters",
        ),
        Sport.SNOOKER to phrases("snooker", "world snooker", "wst", "uk championship", "masters", "champion of champions"),
        Sport.CYCLING to phrases(
            "tour de france", "giro", "giro d italia", "vuelta", "la vuelta", "paris roubaix", "milano sanremo",
            "milan san remo", "tour of flanders", "ronde van vlaanderen", "liege bastogne liege", "il lombardia",
            "uci world tour",
        ),
        Sport.GOLF to phrases(
            "masters", "the masters", "us open", "open championship", "the open", "pga championship", "ryder cup",
            "pga tour", "dp world tour", "solheim cup", "presidents cup",
        ),
        Sport.CRICKET to phrases("ipl", "test", "test match", "ashes", "t20 world cup", "champions trophy", "world cup"),
    )

    private val notTopTennis = phrases("challenger", "itf", "utr", "futures", "125", "wta 125", "atp challenger")

    private val championshipWords = phrases("championship", "championships", "cup", "games", "campionatul", "campeonato", "championnat", "championnats", "meisterschaft")
    private val scopeWords = phrases("world", "european", "europe", "mondial", "mondiale", "mundial", "europeo", "europei", "olympic")

    /** „World Aquatics Championships”, „European Gymnastics Championships”: cuvintele pot fi despărțite. */
    private fun isMajor(text: String): Boolean = majors.hit(text) || scopeWords.hit(text) && championshipWords.hit(text)

    private fun phrases(vararg p: String): List<String> = p.map { " ${Sport.fold(it)} " }.distinct()

    private fun List<String>.hit(text: String): Boolean = any { text.contains(it) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Verdict>()

    /** Verdictul pentru competiția [competition] a sportului [sport] (ținut minte: sunt câteva sute de nume). */
    fun verdict(sport: Sport?, competition: String?): Verdict {
        if (competition.isNullOrBlank()) return Verdict.UNKNOWN
        val key = "${sport?.name}|$competition"
        cache[key]?.let { return it }
        if (cache.size > 20_000) cache.clear()
        return compute(sport, competition).also { cache[key] = it }
    }

    private fun compute(sport: Sport?, competition: String): Verdict {
        val text = " ${Sport.fold(competition)} "
        if (text.isBlank()) return Verdict.UNKNOWN
        val s = sport ?: Sport.detect(competition)
        if (s == Sport.FOOTBALL || s == null && (footballLeagues.hit(text) || footballInternational.hit(text))) {
            return football(text)
        }
        if (isMajor(text)) return Verdict.KEEP
        if (s == null) return Verdict.UNKNOWN
        if (s == Sport.TENNIS && notTopTennis.hit(text)) return Verdict.DROP
        if (youth.hit(text) || women.hit(text) && s != Sport.TENNIS) return Verdict.DROP
        return if (other[s]?.hit(text) == true) Verdict.KEEP else Verdict.DROP
    }

    private fun football(text: String): Verdict {
        val isExotic = exotic.hit(text)
        val englandHit = !isExotic && (england.hit(text) || text.contains(" premier league "))
        val isWomen = women.hit(text)
        if (youth.hit(text)) return if (englandHit) Verdict.KEEP else Verdict.DROP
        if (isWomen) return if (englandHit || isMajor(text)) Verdict.KEEP else Verdict.DROP
        if (friendly.hit(text)) return Verdict.UNKNOWN
        if (isExotic) return if (confederations.hit(text) || isMajor(text)) Verdict.KEEP else Verdict.DROP
        if (footballInternational.hit(text) || isMajor(text)) return Verdict.KEEP
        return if (englandHit || footballLeagues.hit(text)) Verdict.KEEP else Verdict.DROP
    }

    /**
     * Rămâne meciul [match]? [verdict] e cel al competiției; [knownTeams] = cheile echipelor ([TeamNames.key]) din
     * competițiile păstrate. Amicalele și meciurile fără competiție cunoscută rămân doar între echipe cunoscute
     * (amicalele: amândouă; meciurile din EPG fără competiție: cel puțin una). Evenimentele fără echipe și
     * sporturile fără echipe cunoscute rămân.
     */
    fun keepMatch(sport: Sport?, competition: String?, home: String, away: String, knownTeams: Set<String>, fromProvider: Boolean): Boolean {
        val v = verdict(sport, competition)
        if (v == Verdict.KEEP) return true
        if (v == Verdict.DROP) return false
        if (away.isBlank()) return true
        val a = TeamNames.key(home) in knownTeams
        val b = TeamNames.key(away) in knownTeams
        if (a && b) return true
        val isFootball = (sport ?: Sport.detect(competition)) == Sport.FOOTBALL
        if (!isFootball && !competition.isNullOrBlank()) return false
        if (!isFootball) return true
        return !fromProvider && (a || b)
    }
}
