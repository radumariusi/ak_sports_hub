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
        "czech republic" to listOf("czechia", "chequia", "republica checa", "tschechien", "repubblica ceca", "cechia", "republique tcheque", "tchequie", "cehia", "tsjechie", "tschechische republik", "tschechische", "tschech republik", "republica cehă"),
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

    /**
     * Aceleași naționale în turcă, poloneză, maghiară, cehă, croată/sârbă, rusă, ucraineană, greacă și arabă
     * (titlurile EPG ale canalelor din acele țări). Alfabetele nelatine trec prin [Translit] la normalizare.
     */
    private val more: Map<String, List<String>> = mapOf(
        "portugal" to listOf("portekiz", "portugália", "portugalsko", "португалия", "португалія", "πορτογαλία", "البرتغال"),
        "spain" to listOf("ispanya", "hiszpania", "spanyolország", "španělsko", "španjolska", "španija", "испания", "іспанія", "ισπανία", "إسبانيا", "اسبانيا"),
        "germany" to listOf("almanya", "niemcy", "németország", "německo", "njemačka", "nemačka", "германия", "німеччина", "γερμανία", "ألمانيا", "المانيا"),
        "france" to listOf("fransa", "francja", "franciaország", "francie", "francuska", "франция", "франція", "γαλλία", "فرنسا"),
        "italy" to listOf("italya", "włochy", "olaszország", "itálie", "italija", "италия", "італія", "ιταλία", "إيطاليا", "ايطاليا"),
        "england" to listOf("ingiltere", "anglie", "engleska", "англия", "англія", "αγγλία", "إنجلترا", "انجلترا", "إنكلترا"),
        "netherlands" to listOf("hollanda", "holandia", "hollandia", "nizozemsko", "nizozemska", "holandija", "нидерланды", "голландия", "нідерланди", "ολλανδία", "هولندا"),
        "belgium" to listOf("belçika", "belgie", "belgija", "бельгия", "бельгія", "βέλγιο", "بلجيكا"),
        "croatia" to listOf("hırvatistan", "chorwacja", "horvátország", "chorvatsko", "hrvatska", "хорватия", "хорватія", "κροατία", "كرواتيا"),
        "czech republic" to listOf("çekya", "czechy", "csehország", "česko", "česká republika", "češka", "чехия", "чехія", "τσεχία", "التشيك"),
        "poland" to listOf("polonya", "polska", "lengyelország", "polsko", "poljska", "польша", "польща", "πολωνία", "بولندا"),
        "romania" to listOf("romanya", "rumunia", "románia", "rumunsko", "rumunjska", "rumunija", "румыния", "румунія", "ρουμανία", "رومانيا"),
        "turkey" to listOf("turcja", "törökország", "turecko", "turska", "турция", "туреччина", "τουρκία", "تركيا"),
        "greece" to listOf("yunanistan", "grecja", "görögország", "řecko", "grčka", "греция", "греція", "ελλάδα", "اليونان"),
        "serbia" to listOf("sırbistan", "szerbia", "srbsko", "srbija", "сербия", "сербія", "србија", "σερβία", "صربيا"),
        "switzerland" to listOf("isviçre", "szwajcaria", "svájc", "švýcarsko", "švicarska", "švajcarska", "швейцария", "швейцарія", "ελβετία", "سويسرا"),
        "austria" to listOf("avusturya", "ausztria", "rakousko", "austrija", "австрия", "австрія", "αυστρία", "النمسا"),
        "denmark" to listOf("danimarka", "dania", "dánia", "dánsko", "danska", "дания", "данія", "δανία", "الدنمارك"),
        "sweden" to listOf("isveç", "szwecja", "svédország", "švédsko", "švedska", "швеция", "швеція", "σουηδία", "السويد"),
        "norway" to listOf("norveç", "norwegia", "norvégia", "norsko", "norveška", "норвегия", "норвегія", "νορβηγία", "النرويج"),
        "scotland" to listOf("iskoçya", "szkocja", "skócia", "skotsko", "škotska", "шотландия", "шотландія", "σκωτία", "اسكتلندا"),
        "wales" to listOf("galler", "walia", "уэльс", "уельс", "ουαλία", "ويلز"),
        "ukraine" to listOf("ukrayna", "ukraina", "ukrajna", "ukrajina", "украина", "україна", "ουκρανία", "أوكرانيا", "اوكرانيا"),
        "hungary" to listOf("macaristan", "węgry", "magyarország", "maďarsko", "mađarska", "венгрия", "угорщина", "ουγγαρία", "المجر"),
        "albania" to listOf("arnavutluk", "albánie", "albanija", "албания", "албанія", "αλβανία", "ألبانيا"),
        "armenia" to listOf("ermenistan", "örményország", "arménie", "armenija", "армения", "вірменія", "αρμενία", "أرمينيا", "ارمينيا"),
        "georgia" to listOf("gürcistan", "gruzja", "grúzia", "gruzie", "gruzija", "грузия", "грузія", "γεωργία", "جورجيا"),
        "azerbaijan" to listOf("azerbejdżan", "azerbajdzsán", "ázerbájdžán", "азербайджан", "αζερμπαϊτζάν", "أذربيجان"),
        "kazakhstan" to listOf("kazakistan", "kazachstan", "kazahsztán", "kazachstán", "казахстан", "καζακστάν", "كازاخستان"),
        "belarus" to listOf("belarus", "białoruś", "fehéroroszország", "bělorusko", "bjelorusija", "беларусь", "белоруссия", "білорусь", "λευκορωσία", "بيلاروسيا"),
        "finland" to listOf("finlandiya", "finlandia", "finnország", "finsko", "finska", "финляндия", "фінляндія", "φινλανδία", "فنلندا"),
        "iceland" to listOf("izlanda", "islandia", "izland", "island", "исландия", "ісландія", "ισλανδία", "أيسلندا", "آيسلندا"),
        "ireland" to listOf("irlanda", "irlandia", "írország", "irsko", "irska", "ирландия", "ірландія", "ιρλανδία", "أيرلندا", "ايرلندا"),
        "northern ireland" to listOf("kuzey irlanda", "irlandia północna", "észak írország", "severní irsko", "sjeverna irska", "северная ирландия", "північна ірландія", "βόρεια ιρλανδία", "أيرلندا الشمالية"),
        "bulgaria" to listOf("bulgaristan", "bułgaria", "bulgária", "bulharsko", "bugarska", "болгария", "болгарія", "βουλγαρία", "بلغاريا"),
        "north macedonia" to listOf("kuzey makedonya", "macedonia północna", "észak macedónia", "severní makedonie", "sjeverna makedonija", "северная македония", "північна македонія", "βόρεια μακεδονία", "مقدونيا الشمالية"),
        "montenegro" to listOf("karadağ", "czarnogóra", "montenegró", "černá hora", "crna gora", "черногория", "чорногорія", "μαυροβούνιο", "الجبل الأسود"),
        "slovakia" to listOf("slovakya", "słowacja", "szlovákia", "slovensko", "slovačka", "словакия", "словаччина", "σλοβακία", "سلوفاكيا"),
        "slovenia" to listOf("slovenya", "słowenia", "szlovénia", "slovinsko", "slovenija", "словения", "словенія", "σλοβενία", "سلوفينيا"),
        "bosnia and herzegovina" to listOf("bosna hersek", "bośnia i hercegowina", "bosznia hercegovina", "bosna a hercegovina", "bosna i hercegovina", "босния и герцеговина", "боснія і герцеговина", "βοσνία", "البوسنة والهرسك"),
        "russia" to listOf("rusya", "rosja", "oroszország", "rusko", "rusija", "россия", "росія", "ρωσία", "روسيا"),
        "israel" to listOf("izrael", "израиль", "ізраїль", "ισραήλ"),
        "cyprus" to listOf("kıbrıs", "cypr", "ciprus", "kypr", "cipar", "кипр", "кіпр", "κύπρος", "قبرص"),
        "latvia" to listOf("letonya", "łotwa", "lettország", "lotyšsko", "latvija", "латвия", "латвія", "λετονία", "لاتفيا"),
        "lithuania" to listOf("litvanya", "litwa", "litvánia", "litva", "литва", "λιθουανία", "ليتوانيا"),
        "estonia" to listOf("estonya", "észtország", "estonsko", "estonija", "эстония", "естонія", "εσθονία", "إستونيا"),
        "moldova" to listOf("mołdawia", "moldávia", "moldavsko", "moldavija", "молдова", "молдавия", "μολδαβία", "مولدوفا"),
        "kosovo" to listOf("kosowo", "koszovó", "косово", "κόσοβο", "كوسوفو"),
        "luxembourg" to listOf("lüksemburg", "luksemburg", "lucemburk", "luksemburg", "люксембург", "λουξεμβούργο", "لوكسمبورغ"),
        "brazil" to listOf("brezilya", "brazylia", "brazília", "brazílie", "brazil", "бразилия", "бразилія", "βραζιλία", "البرازيل"),
        "argentina" to listOf("arjantin", "argentyna", "argentína", "argentina", "аргентина", "αργεντινή", "الأرجنتين", "الارجنتين"),
        "united states" to listOf("abd", "stany zjednoczone", "сша", "ηπα", "الولايات المتحدة", "أمريكا"),
        "mexico" to listOf("meksika", "meksyk", "mexikó", "meksiko", "мексика", "μεξικό", "المكسيك"),
        "japan" to listOf("japonya", "japonia", "japán", "japonsko", "japan", "япония", "японія", "ιαπωνία", "اليابان"),
        "morocco" to listOf("maroko", "marokkó", "марокко", "μαρόκο", "المغرب"),
        "egypt" to listOf("mısır", "egipt", "egyiptom", "egypt", "египет", "єгипет", "αίγυπτος", "مصر"),
        "saudi arabia" to listOf("suudi arabistan", "arabia saudyjska", "szaúd arábia", "саудовская аравия", "السعودية", "العربية السعودية"),
        "qatar" to listOf("katar", "катар", "κατάρ", "قطر"),
        "algeria" to listOf("cezayir", "algieria", "algéria", "alžírsko", "alžir", "алжир", "αλγερία", "الجزائر"),
        "tunisia" to listOf("tunus", "tunezja", "tunézia", "tunisko", "tunis", "тунис", "туніс", "τυνησία", "تونس"),
    )

    /** Numele străin → numele englezesc (cheie deja normalizată). */
    val aliases: Map<String, String> = buildMap {
        for ((english, others) in table + more.mapValues { (k, v) -> table[k].orEmpty() + v }) for (o in others) {
            val k = Sport.fold(o)
            if (k.isNotEmpty() && k != english) put(k, english)
        }
    }
}

/** Cele mai mari cluburi scrise în arabă și în rusă (canalele arabe și cele din est), aduse la numele englezesc. */
internal object NativeClubNames {
    private val table: Map<String, List<String>> = mapOf(
        "real madrid" to listOf("ريال مدريد"),
        "barcelona" to listOf("برشلونة", "барселона"),
        "manchester united" to listOf("مانشستر يونايتد", "манчестер юнайтед", "ман юнайтед"),
        "manchester city" to listOf("مانشستر سيتي", "манчестер сити", "ман сити"),
        "liverpool" to listOf("ليفربول", "ливерпуль"),
        "chelsea" to listOf("تشيلسي", "челси"),
        "arsenal" to listOf("آرسنال", "أرسنال"),
        "tottenham hotspur" to listOf("توتنهام", "тоттенхэм", "тоттенхем"),
        "bayern munich" to listOf("بايرن ميونخ", "بايرن ميونيخ", "бавария"),
        "borussia dortmund" to listOf("بوروسيا دورتموند", "боруссия дортмунд", "боруссия д"),
        "paris saint germain" to listOf("باريس سان جيرمان", "пари сен жермен", "псж"),
        "juventus" to listOf("يوفنتوس", "ювентус"),
        "inter milan" to listOf("إنتر ميلان", "انتر ميلان", "интер"),
        "ac milan" to listOf("ميلان", "эй си милан"),
        "atletico madrid" to listOf("أتلتيكو مدريد", "اتلتيكو مدريد", "атлетико", "атлетико мадрид"),
        "napoli" to listOf("نابولي"),
        "benfica" to listOf("بنفيكا", "бенфика"),
        "fc porto" to listOf("بورتو", "порту"),
        "sporting cp" to listOf("سبورتينغ لشبونة", "سبورتنغ لشبونة", "спортинг"),
        "ajax" to listOf("أياكس", "اياكس", "аякс"),
        "galatasaray" to listOf("غلطة سراي", "جالطة سراي"),
        "fenerbahce" to listOf("فنربخشة", "فنربهتشة", "фенербахче"),
        "al hilal" to listOf("الهلال"),
        "al nassr" to listOf("النصر"),
        "al ittihad" to listOf("الاتحاد"),
        "al ahli" to listOf("الأهلي"),
        "newcastle united" to listOf("نيوكاسل", "نيوكاسل يونايتد", "ньюкасл"),
        "aston villa" to listOf("أستون فيلا", "استون فيلا", "астон вилла"),
        "bayer leverkusen" to listOf("باير ليفركوزن", "байер", "байер леверкузен"),
    )

    val aliases: Map<String, String> = buildMap {
        for ((english, others) in table) for (o in others) {
            val k = Sport.fold(o)
            if (k.isNotEmpty() && k != english) put(k, english)
        }
    }
}
