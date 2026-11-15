package ro.ak.sportshub.core.sports

/**
 * Numele echipelor naționale în limbile furnizorilor (spaniolă, germană, italiană, franceză, portugheză, română,
 * olandeză…), aduse la numele englezesc: „Bielorrusia”, „Weißrussland” și „Belarus” sunt aceeași echipă.
 * Cheile sunt deja trecute prin [Sport.fold] (fără diacritice; „ß” devine spațiu).
 */
internal object NationNames {

    private val table: Map<String, List<String>> = mapOf(
        "albania" to listOf("albanien", "albanie", "albânia", "albania"),
        "andorra" to listOf("andorre"),
        "armenia" to listOf("armenien", "armenie", "armênia"),
        "austria" to listOf("osterreich", "oesterreich", "autriche", "áustria", "oostenrijk"),
        "azerbaijan" to listOf("azerbaiyan", "aserbaidschan", "azerbaigian", "azerbaidjan", "azerbaidjao", "azerbaidjan"),
        "belarus" to listOf("bielorrusia", "weissrussland", "wei russland", "bielorussia", "bielorussie", "bielorrussia", "belarus", "wit rusland"),
        "belgium" to listOf("belgica", "belgien", "belgio", "belgique", "belgia", "belgie"),
        "bosnia and herzegovina" to listOf("bosnia herzegovina", "bosnia y herzegovina", "bosnien herzegowina", "bosnien und herzegowina", "bosnia erzegovina", "bosnie herzegovine", "bosnia si hertegovina", "bosnia hertegovina", "bosnie", "bosnia"),
        "bulgaria" to listOf("bulgarien", "bulgarie", "bulgarije"),
        "croatia" to listOf("croacia", "kroatien", "croazia", "croatie", "croacia", "kroatie"),
        "cyprus" to listOf("chipre", "zypern", "cipro", "chypre", "cipru"),
        "czech republic" to listOf("czechia", "chequia", "republica checa", "tschechien", "repubblica ceca", "cechia", "republique tcheque", "tchequie", "cehia", "tsjechie"),
        "denmark" to listOf("dinamarca", "danemark", "danimarca", "danemarca", "denemarken", "danmark"),
        "england" to listOf("inglaterra", "england", "inghilterra", "angleterre", "anglia", "engeland"),
        "estonia" to listOf("estland", "estonie"),
        "faroe islands" to listOf("islas feroe", "faroer", "faroer inseln", "isole far oer", "iles feroe", "insulele feroe", "feroe"),
        "finland" to listOf("finlandia", "finnland", "finlande", "finlanda"),
        "france" to listOf("francia", "frankreich", "franta", "franca", "frankrijk"),
        "georgia" to listOf("georgien", "georgie", "geórgia"),
        "germany" to listOf("alemania", "deutschland", "germania", "allemagne", "alemanha", "duitsland"),
        "gibraltar" to listOf("gibilterra"),
        "greece" to listOf("grecia", "griechenland", "grece", "grécia", "griekenland"),
        "hungary" to listOf("hungria", "ungarn", "ungheria", "hongrie", "ungaria", "hongarije"),
        "iceland" to listOf("islandia", "island", "islanda", "islande", "ijsland"),
        "ireland" to listOf("republic of ireland", "irlanda", "irland", "irlande", "ierland", "rep of ireland"),
        "israel" to listOf("israele", "israël"),
        "italy" to listOf("italia", "italien", "italie", "itália", "italie"),
        "kazakhstan" to listOf("kazajistan", "kasachstan", "kazakistan", "kazakhstan", "cazaquistao", "kazahstan"),
        "kosovo" to listOf("kosova"),
        "latvia" to listOf("letonia", "lettland", "lettonia", "lettonie", "letonia", "letland"),
        "liechtenstein" to listOf("lichtenstein"),
        "lithuania" to listOf("lituania", "litauen", "lituanie", "lituânia", "litouwen"),
        "luxembourg" to listOf("luxemburgo", "luxemburg", "lussemburgo"),
        "malta" to listOf("malte"),
        "moldova" to listOf("moldavia", "moldawien", "moldavie", "republica moldova", "moldavië"),
        "montenegro" to listOf("montenegro", "monténégro", "muntenegru"),
        "netherlands" to listOf("paises bajos", "holanda", "niederlande", "olanda", "paesi bassi", "pays bas", "tarile de jos", "nederland", "holland"),
        "north macedonia" to listOf("macedonia del norte", "nordmazedonien", "macedonia del nord", "macedoine du nord", "macedonia de nord", "noord macedonie", "macedonia"),
        "northern ireland" to listOf("irlanda del norte", "nordirland", "irlanda del nord", "irlande du nord", "irlanda de nord", "noord ierland"),
        "norway" to listOf("noruega", "norwegen", "norvegia", "norvege", "noorwegen"),
        "poland" to listOf("polonia", "polen", "pologne", "polônia"),
        "portugal" to listOf("portogallo", "portugalia"),
        "romania" to listOf("rumania", "rumanien", "roumanie", "roménia", "roemenie"),
        "russia" to listOf("rusia", "russland", "russie", "rússia", "rusland"),
        "san marino" to listOf("saint marin"),
        "scotland" to listOf("escocia", "schottland", "scozia", "ecosse", "scotia", "schotland"),
        "serbia" to listOf("serbien", "serbie", "sérvia", "servie"),
        "slovakia" to listOf("eslovaquia", "slowakei", "slovacchia", "slovaquie", "slovacia", "slowakije"),
        "slovenia" to listOf("eslovenia", "slowenien", "slovenie", "slovénie", "slovenië"),
        "spain" to listOf("espana", "spanien", "spagna", "espagne", "spania", "espanha", "spanje"),
        "sweden" to listOf("suecia", "schweden", "svezia", "suede", "suedia", "zweden"),
        "switzerland" to listOf("suiza", "schweiz", "svizzera", "suisse", "elvetia", "suica", "zwitserland"),
        "turkey" to listOf("turkiye", "turquia", "turkei", "turchia", "turquie", "turcia", "turkije"),
        "ukraine" to listOf("ucrania", "ucraina", "ucrânia", "oekraine"),
        "wales" to listOf("gales", "pais de gales", "galles", "pays de galles", "tara galilor"),
        "argentina" to listOf("argentinien", "argentine"),
        "brazil" to listOf("brasil", "brasilien", "brasile", "bresil", "brazilia"),
        "mexico" to listOf("mexiko", "messico", "mexique", "mexic"),
        "united states" to listOf("usa", "estados unidos", "eeuu", "vereinigte staaten", "stati uniti", "etats unis", "statele unite", "sua"),
        "japan" to listOf("japon", "giappone", "japao", "japonia"),
        "south korea" to listOf("korea republic", "corea del sur", "sudkorea", "corea del sud", "coree du sud", "coreia do sul", "coreea de sud"),
        "morocco" to listOf("marruecos", "marokko", "marocco", "maroc", "marrocos"),
        "egypt" to listOf("egipto", "agypten", "egitto", "egypte", "egipt"),
    )

    /** Numele străin → numele englezesc (cheie deja normalizată). */
    val aliases: Map<String, String> = buildMap {
        for ((english, others) in table) for (o in others) {
            val k = Sport.fold(o)
            if (k != english) put(k, english)
        }
    }
}
