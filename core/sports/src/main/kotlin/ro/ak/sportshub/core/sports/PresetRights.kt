package ro.ak.sportshub.core.sports

/**
 * Drepturile TV preumplute, livrate cu aplicația: cine transmite de obicei o competiție într-o țară
 * (adunate din listele publice de difuzori, Wikipedia „List of … broadcasters”, și din paginile oficiale „unde vezi”).
 *
 * Sunt presupuneri („Presupus (drepturi TV)”), nu program meci cu meci: apar după sursele găsite în EPG sau după
 * postul anunțat, iar o respingere oprește regula pentru competiția respectivă. Se pot opri toate sau câte una
 * (Dicționar → Drepturi TV). Contractele se schimbă de la un sezon la altul: valabile pentru sezonul 2026/27,
 * cât se știa la scrierea lor.
 *
 * Numele competițiilor au mai multe forme (TheSportsDB „English Premier League”, Sofascore „England Premier League”,
 * LiveSoccerTV „Premier League”), iar [RightsRule.excludes] oprește potrivirile greșite („AFC Champions League”).
 */
object PresetRights {

    private val EPL = listOf("english premier league", "england premier league", "premier league england", "epl")
    private val UCL = listOf("uefa champions league", "champions league")
    private val UEL = listOf("uefa europa league", "europa league")
    private val UECL = listOf("uefa conference league", "uefa europa conference league", "conference league")
    private val LALIGA = listOf("spanish la liga", "spain la liga", "laliga", "la liga", "laliga ea sports", "primera division spain")
    private val SERIEA = listOf("italian serie a", "italy serie a", "serie a italy", "serie a")
    private val BUNDESLIGA = listOf("german bundesliga", "germany bundesliga", "bundesliga germany", "bundesliga")
    private val LIGUE1 = listOf("french ligue 1", "france ligue 1", "ligue 1 france", "ligue 1")
    private val CHAMPIONSHIP = listOf("english league championship", "england championship", "efl championship", "championship england")
    private val FACUP = listOf("english fa cup", "fa cup")
    private val SCOTLAND = listOf("scottish premiership", "scotland premiership", "premiership scotland")
    private val LIGA1RO = listOf("romanian liga i", "romanian liga 1", "romania superliga", "romania liga i", "superliga romaniei", "liga 1 romania", "superliga")
    private val PORTUGAL = listOf("portuguese primeira liga", "portugal primeira liga", "liga portugal", "primeira liga")
    private val EREDIVISIE = listOf("dutch eredivisie", "netherlands eredivisie", "eredivisie")
    private val F1 = listOf("formula 1", "formula one", "f1 grand prix", "f1")
    private val MOTOGP = listOf("motogp", "moto gp")
    private val NFL = listOf("nfl")
    private val NBA = listOf("nba")
    private val NHL = listOf("nhl")
    private val MLB = listOf("mlb")
    private val MLS = listOf("american major league soccer", "major league soccer", "mls")
    private val GRAND_SLAM = listOf("australian open", "roland garros", "french open", "us open")
    private val TOURS = listOf("tour de france", "giro d italia", "giro", "vuelta a espana", "la vuelta", "vuelta")
    private val SNOOKER = listOf("snooker", "world snooker", "uk championship", "masters snooker")
    private val DARTS = listOf("pdc", "premier league darts", "world darts championship", "darts")

    /** Ce nu e fotbal european de club, deși are „champions league” / „europa league” în nume. */
    private val NOT_UEFA = listOf("afc", "caf", "concacaf", "ofc", "asian", "african", "ehf", "women", "womens", "w", "youth", "u19", "basketball", "volleyball", "hockey", "chl", "cev", "fiba")
    private val NOT_EPL = listOf("egyptian", "egypt", "kenyan", "kenya", "ghana", "ghanaian", "nigeria", "zambia", "russian", "russia", "ukrainian", "ukraine", "kazakhstan", "bangladesh", "women", "womens", "w", "u21", "u18", "2", "darts", "international", "pl2", "malta", "maltese", "hong kong", "lebanese", "syrian", "jamaica", "jamaican", "tanzania", "uganda", "bahrain", "kuwait", "qatar", "iraq", "jordan", "palestine", "belarus", "armenia", "armenian", "welsh", "wales", "scotland", "scottish", "northern ireland", "nigerian", "gibraltar", "cambodia", "myanmar", "liberia", "ethiopia", "zimbabwe", "botswana", "malawi", "rwanda", "burundi", "south sudan", "sudan", "eswatini", "lesotho", "namibia", "mauritius", "seychelles", "sierra leone", "gambia")
    private val NOT_BUNDESLIGA = listOf("austrian", "austria", "2", "3", "women", "womens", "frauen", "w", "handball", "basketball", "hockey", "volleyball")
    private val NOT_SERIEA = listOf("brazil", "brazilian", "brasileirao", "ecuador", "ecuadorian", "women", "womens", "w", "femminile", "volleyball", "basketball")
    private val NOT_LIGUE1 = listOf("algerian", "algeria", "tunisian", "tunisia", "ivory coast", "cote d ivoire", "benin", "cameroon", "senegal", "guinea", "mali", "burkina faso", "togo", "gabon", "congo", "niger", "women", "womens", "w")
    private val NOT_LALIGA = listOf("women", "womens", "f", "femenina", "hypermotion", "2", "salvador", "el salvador", "guatemala", "honduras", "panama", "costa rica")
    private val NOT_FACUP = listOf("women", "womens", "youth", "scottish", "welsh", "irish")
    private val NOT_SUPERLIGA = listOf("danish", "denmark", "serbian", "serbia", "albanian", "albania", "turkish", "turkey", "greek", "greece", "swiss", "switzerland", "slovak", "slovakia", "argentine", "argentina", "kosovo", "women", "womens", "w", "handball", "basketball", "volleyball")
    private val NOT_US_OPEN = listOf("golf", "pga", "cup", "squash", "badminton")
    private val NOT_MLS = listOf("next pro", "nextpro")

    private fun rule(
        competitions: List<String>,
        country: String?,
        vararg stations: String,
        excludes: List<String> = emptyList(),
        listedPerGame: Boolean = false,
        teams: List<String> = emptyList(),
    ) = RightsRule(competitions, stations.toList(), country, enabled = true, excludes = excludes, preset = true, listedPerGame = listedPerGame, teams = teams)

    /** Echipele canadiene: TSN și Sportsnet dau NBA și MLB doar pentru ele (plus câteva meciuri naționale). */
    private val RAPTORS = listOf("toronto raptors", "raptors")
    private val BLUE_JAYS = listOf("toronto blue jays", "blue jays")

    val rules: List<RightsRule> = listOf(
        // ---------- Marea Britanie ----------
        rule(EPL, "UK", "Sky Sports Main Event", "Sky Sports Premier League", "TNT Sports 1", "Sky Sports Ultra HDR", excludes = NOT_EPL),
        rule(UCL, "UK", "TNT Sports 1", "TNT Sports 2", "TNT Sports 3", "TNT Sports 4", "TNT Sports Ultimate", "Amazon Prime Video", excludes = NOT_UEFA),
        rule(UEL, "UK", "TNT Sports 1", "TNT Sports 2", "TNT Sports 3", excludes = NOT_UEFA),
        rule(UECL, "UK", "TNT Sports 1", "TNT Sports 2", "TNT Sports 3", excludes = NOT_UEFA),
        rule(CHAMPIONSHIP, "UK", "Sky Sports Football", "Sky Sports Main Event", "Sky Sports+", excludes = listOf("women", "womens")),
        rule(FACUP, "UK", "TNT Sports 1", "TNT Sports 2", "BBC One", excludes = NOT_FACUP),
        rule(SCOTLAND, "UK", "Sky Sports Football", "Sky Sports Main Event", "Sky Sports Scotland", excludes = listOf("women", "womens", "championship")),
        rule(LALIGA, "UK", "Premier Sports 1", "Premier Sports 2", excludes = NOT_LALIGA),
        rule(SERIEA, "UK", "TNT Sports 1", "TNT Sports 2", "TNT Sports 3", excludes = NOT_SERIEA),
        rule(BUNDESLIGA, "UK", "Sky Sports Football", "Sky Sports Main Event", "Sky Sports Mix", excludes = NOT_BUNDESLIGA),
        rule(F1, "UK", "Sky Sports F1", "Sky Sports Main Event", excludes = listOf("academy")),
        rule(MOTOGP, "UK", "TNT Sports 1", "TNT Sports 2"),
        rule(NFL, "UK", "Sky Sports NFL", "Sky Sports Main Event", "Sky Sports Arena"),
        rule(DARTS, "UK", "Sky Sports Arena", "Sky Sports Action", "Sky Sports Main Event", excludes = listOf("bdo", "wdf")),
        rule(SNOOKER, "UK", "Eurosport 1", "Eurosport 2", "BBC Two"),

        // ---------- România ----------
        rule(LIGA1RO, "RO", "Digi Sport 1", "Digi Sport 2", "Prima Sport 1", "Prima Sport 2", excludes = NOT_SUPERLIGA),
        rule(UCL, "RO", "Digi Sport 1", "Digi Sport 2", "Digi Sport 3", "Prima Sport 1", "Prima Sport 2", excludes = NOT_UEFA),
        rule(UEL, "RO", "Digi Sport 1", "Digi Sport 2", "Prima Sport 1", "Prima Sport 2", excludes = NOT_UEFA),
        rule(UECL, "RO", "Digi Sport 1", "Digi Sport 2", "Prima Sport 1", "Prima Sport 2", excludes = NOT_UEFA),
        rule(EPL, "RO", "Digi Sport 1", "Digi Sport 2", "Digi Sport 3", "Digi Sport 4", excludes = NOT_EPL),
        rule(LALIGA, "RO", "Digi Sport 1", "Digi Sport 2", "Digi Sport 3", "Digi Sport 4", excludes = NOT_LALIGA),
        rule(SERIEA, "RO", "Digi Sport 1", "Digi Sport 2", "Digi Sport 3", "Digi Sport 4", "Prima Sport 1", excludes = NOT_SERIEA),
        rule(BUNDESLIGA, "RO", "Digi Sport 1", "Digi Sport 2", "Digi Sport 3", "Prima Sport 1", excludes = NOT_BUNDESLIGA),
        rule(LIGUE1, "RO", "Prima Sport 1", "Prima Sport 2", "Digi Sport 3", excludes = NOT_LIGUE1),

        // ---------- toată Europa (Eurosport) ----------
        rule(GRAND_SLAM, null, "Eurosport 1", "Eurosport 2", excludes = NOT_US_OPEN),
        rule(TOURS, null, "Eurosport 1", "Eurosport 2", excludes = listOf("femmes", "women", "womens")),
        rule(SNOOKER, null, "Eurosport 1", "Eurosport 2"),

        // ---------- SUA ----------
        rule(NFL, "USA", "CBS", "FOX", "NBC", "ESPN", "ABC", "NFL Network", "Amazon Prime Video", "Peacock", "Netflix", listedPerGame = true),
        rule(NBA, "USA", "ESPN", "ABC", "NBC", "Peacock", "Amazon Prime Video", "NBA TV", listedPerGame = true),
        rule(NHL, "USA", "ESPN", "ESPN2", "ABC", "TNT", "TBS", "truTV", "NHL Network", listedPerGame = true),
        rule(MLB, "USA", "FOX", "FS1", "NBC", "Netflix", "TBS", "MLB Network", listedPerGame = true),
        rule(MLS, "USA", "Apple TV", "FOX", "FS1", excludes = NOT_MLS, listedPerGame = true),
        rule(EPL, "USA", "NBC", "USA Network", "Peacock", "CNBC", excludes = NOT_EPL),
        rule(UCL, "USA", "CBS", "CBS Sports Network", "Paramount+", "TUDN", "UniMas", excludes = NOT_UEFA),
        rule(LALIGA, "USA", "ESPN", "ESPN2", "ESPN Deportes", "ESPN+", excludes = NOT_LALIGA),
        rule(BUNDESLIGA, "USA", "ESPN", "ESPN2", "ESPN+", excludes = NOT_BUNDESLIGA),
        rule(SERIEA, "USA", "CBS Sports Network", "Paramount+", "CBS Sports Golazo", excludes = NOT_SERIEA),
        rule(F1, "USA", "Apple TV", excludes = listOf("academy")),

        // ---------- Canada ----------
        rule(NHL, "Canada", "Sportsnet", "Sportsnet ONE", "Sportsnet 360", "CBC", "TVA Sports"),
        rule(NBA, "Canada", "TSN", "TSN 1", "TSN 2", "Sportsnet", "Sportsnet ONE", teams = RAPTORS),
        rule(NFL, "Canada", "TSN", "TSN 1", "CTV", "DAZN"),
        rule(MLB, "Canada", "Sportsnet", "Sportsnet ONE", teams = BLUE_JAYS),

        // ---------- Germania ----------
        rule(BUNDESLIGA, "Germany", "Sky Sport Bundesliga", "Sky Sport Top Event", "DAZN 1", "DAZN 2", excludes = NOT_BUNDESLIGA),
        rule(UCL, "Germany", "DAZN 1", "DAZN 2", "Amazon Prime Video", excludes = NOT_UEFA),
        rule(EPL, "Germany", "Sky Sport Premier League", "Sky Sport Top Event", excludes = NOT_EPL),
        rule(F1, "Germany", "Sky Sport F1", excludes = listOf("academy")),

        // ---------- Spania ----------
        rule(LALIGA, "Spain", "M+ LaLiga", "Movistar LaLiga", "DAZN LaLiga", "DAZN 1", excludes = NOT_LALIGA),
        rule(UCL, "Spain", "M+ Liga de Campeones", "Movistar Liga de Campeones", excludes = NOT_UEFA),
        rule(F1, "Spain", "DAZN F1", excludes = listOf("academy")),
        rule(MOTOGP, "Spain", "DAZN MotoGP", "DAZN 1"),

        // ---------- Italia ----------
        rule(SERIEA, "Italy", "DAZN 1", "Sky Sport Calcio", "Sky Sport Uno", "Zona DAZN", excludes = NOT_SERIEA),
        rule(UCL, "Italy", "Sky Sport Uno", "Sky Sport Calcio", "Amazon Prime Video", excludes = NOT_UEFA),
        rule(F1, "Italy", "Sky Sport F1", "TV8", excludes = listOf("academy")),
        rule(MOTOGP, "Italy", "Sky Sport MotoGP", "TV8"),

        // ---------- Franța ----------
        rule(LIGUE1, "France", "Ligue 1+", "beIN Sports 1", excludes = NOT_LIGUE1),
        rule(UCL, "France", "Canal+", "Canal+ Foot", "Canal+ Sport", excludes = NOT_UEFA),
        rule(F1, "France", "Canal+", "Canal+ Sport", excludes = listOf("academy")),

        // ---------- Portugalia și Olanda ----------
        rule(PORTUGAL, "Portugal", "Sport TV 1", "Sport TV 2", "Sport TV 3", "BTV", "DAZN 1", excludes = listOf("women", "womens", "2")),
        rule(UCL, "Portugal", "Sport TV 1", "Sport TV 2", "Sport TV 3", excludes = NOT_UEFA),
        rule(EREDIVISIE, "Netherlands", "ESPN 1", "ESPN 2", "ESPN 3", excludes = listOf("women", "womens", "vrouwen")),
        rule(UCL, "Netherlands", "Ziggo Sport", "Ziggo Sport 2", excludes = NOT_UEFA),
    )
}
