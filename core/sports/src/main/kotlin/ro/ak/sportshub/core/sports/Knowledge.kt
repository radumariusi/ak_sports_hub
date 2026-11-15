package ro.ak.sportshub.core.sports

/**
 * O regulă de drepturi TV: meciurile din competițiile [competitions] se transmit de obicei pe [stations]
 * (în țara [country], dacă e dată). Dă surse „presupuse”, care vin după cele găsite efectiv.
 *
 * O competiție se potrivește dacă numele ei (fără diacritice) conține unul din [competitions] ca șir de
 * cuvinte întregi, în aceeași ordine: „england premier league” prinde „England - Premier League”, dar nu
 * „Egyptian Premier League”. De aceea regulile încorporate au mai multe forme ale aceluiași nume.
 */
data class RightsRule(
    val competitions: List<String>,
    val stations: List<String>,
    val country: String? = null,
    val enabled: Boolean = true,
    /** Cuvinte care scot competiția din regulă („afc champions league” nu e Liga Campionilor UEFA). */
    val excludes: List<String> = emptyList(),
    /** Regulă livrată cu aplicația ([PresetRights]), nu scrisă de tine. */
    val preset: Boolean = false,
    /**
     * Furnizorul programului dă posturile acestei țări meci cu meci (ESPN, NHL, MLB pentru SUA): când meciul vine de la
     * el, regula nu mai presupune nimic, contează doar posturile anunțate.
     */
    val listedPerGame: Boolean = false,
    /** Regula prinde doar meciurile acestor echipe (de exemplu NBA în Canada = Toronto Raptors); gol = toate. */
    val teams: List<String> = emptyList(),
) {
    /** Dacă regula se aplică meciului (echipele și furnizorii lui), după ce [appliesTo] a prins competiția. */
    fun appliesToMatch(match: Match): Boolean {
        if (listedPerGame && match.providers.any { it in PER_GAME_PROVIDERS }) return false
        if (teams.isEmpty()) return true
        val sides = " ${Sport.fold(match.home)} | ${Sport.fold(match.away)} "
        return foldedTeams.any { sides.contains(it) }
    }

    private val foldedTeams by lazy { teams.map { " " + Sport.fold(it) + " " }.map { it.replace("  ", " ") } }

    fun appliesTo(competition: String?): Boolean {
        if (!enabled || competition.isNullOrBlank()) return false
        val c = " ${Sport.fold(competition)} "
        return foldedCompetitions.any { c.contains(it) } && foldedExcludes.none { c.contains(it) }
    }

    // Formele fără diacritice, calculate o dată (regulile se verifică pentru fiecare meci).
    private val foldedCompetitions by lazy { competitions.map { Sport.fold(it) }.filter { it.isNotEmpty() }.map { " $it " } }
    private val foldedExcludes by lazy { excludes.map { Sport.fold(it) }.filter { it.isNotEmpty() }.map { " $it " } }

    /** Cheia unei reguli preumplute (pentru oprirea ei). */
    val id: String get() = (competitions.firstOrNull().orEmpty() + "|" + country.orEmpty()).lowercase()

    /** Pentru afișare: „Premier League → Sky Sports Main Event, TNT Sports 1 (UK)”. */
    val label: String
        get() = competitions.firstOrNull().orEmpty() + " → " + stations.joinToString(", ") + (country?.let { " ($it)" } ?: "")
}

/** Furnizorii care dau posturile din SUA meci cu meci. */
internal val PER_GAME_PROVIDERS = setOf(Espn.PROVIDER, UsLeagues.NHL_API, UsLeagues.MLB_API)

/** Regulile cu care pornește aplicația (exemple editabile, nu adevăr absolut). */
object DefaultRights {
    val rules: List<RightsRule> = listOf(
        RightsRule(
            listOf("english premier league", "england premier league", "premier league england"),
            listOf("Sky Sports Main Event", "Sky Sports Premier League", "TNT Sports 1"),
            "UK",
        ),
        RightsRule(
            listOf("uefa champions league", "champions league"),
            listOf("TNT Sports 1", "TNT Sports 2", "TNT Sports 3", "TNT Sports 4"),
            "UK",
        ),
        RightsRule(
            listOf("uefa europa league", "europa league", "uefa conference league", "conference league"),
            listOf("TNT Sports 1", "TNT Sports 2"),
            "UK",
        ),
        RightsRule(
            listOf("romanian liga i", "romanian liga 1", "romania superliga", "romania liga i", "superliga romaniei", "liga 1 romania"),
            listOf("Digi Sport 1", "Digi Sport 2", "Prima Sport 1"),
            "RO",
        ),
        RightsRule(
            listOf("uefa champions league", "champions league"),
            listOf("Digi Sport 1", "Digi Sport 2", "Prima Sport 1"),
            "RO",
        ),
        RightsRule(listOf("formula 1", "formula one", "f1 grand prix"), listOf("Sky Sports F1"), "UK"),
        RightsRule(listOf("nba"), listOf("Sky Sports Main Event", "Sky Sports Arena"), "UK", enabled = false),
    )
}

/** Ce ai spus despre o sursă la un meci: „da, e meciul” sau „nu e”. */
enum class Verdict { CONFIRMED, REJECTED }

/** Alegerile tale pentru un meci (pe cheia de grupare a canalului), cu momentul ultimei alegeri. */
data class MatchDecisions(val at: Long, val verdicts: Map<String, Verdict>)

/**
 * Ce știe aplicația în plus, de la tine: dicționarul de posturi, regulile de drepturi TV, ce a învățat
 * din confirmări și alias-urile de echipe. Se păstrează în preferințe (nu în baza de date, care e un cache).
 */
data class SportsKnowledge(
    /** Postul (forma [StationIndex.compact]) → cheile de grupare ale canalelor tale care îl difuzează. */
    val stationAliases: Map<String, Set<String>> = emptyMap(),
    /** Post → canal respinse („nu e postul ăsta”): perechi `post|cheie`. */
    val stationBlocks: Set<String> = emptySet(),
    val rules: List<RightsRule> = DefaultRights.rules,
    /** Competiția (fără diacritice) → canalele pe care ai confirmat meciuri din ea. */
    val learnedRights: Map<String, Set<String>> = emptyMap(),
    /** Competiție → canal respinse: perechi `competiție|cheie`. */
    val rightsBlocks: Set<String> = emptySet(),
    /** Alegerile pe meci (id-ul meciului → canal → verdict). */
    val decisions: Map<String, MatchDecisions> = emptyMap(),
    /** Alias de echipă → numele complet („Sepsi” → „Sepsi OSK”). */
    val teamAliases: Map<String, String> = emptyMap(),
    /** Drepturile TV preumplute ([PresetRights]) se folosesc. */
    val presetRights: Boolean = true,
    /** Regulile preumplute oprite de tine ([RightsRule.id]). */
    val disabledPresets: Set<String> = emptySet(),
) {
    /** Regulile tale, apoi cele preumplute (dacă sunt pornite), fără cele oprite. */
    val allRules: List<RightsRule>
        get() = if (!presetRights) rules else rules + PresetRights.rules.map { if (it.id in disabledPresets) it.copy(enabled = false) else it }

    fun verdict(matchId: String, groupKey: String): Verdict? = decisions[matchId]?.verdicts?.get(groupKey)

    /**
     * Ai spus [verdict] despre sursa [source] (cheia [groupKey]) la meciul [match].
     * Pe lângă alegerea pentru meci, se învață:
     * - confirmarea unui post anunțat îl pune în dicționar; respingerea îl scoate pentru totdeauna;
     * - confirmarea unei presupuneri (drepturi TV) sau a unui canal adăugat de mână leagă competiția de canal;
     *   respingerea unei presupuneri o oprește pentru competiția respectivă.
     * Cu [verdict] null se anulează doar alegerea pentru meci.
     */
    fun decide(match: Match, groupKey: String, verdict: Verdict?, source: FoundSource?, now: Long): SportsKnowledge {
        val current = decisions[match.id]?.verdicts.orEmpty()
        val nextVerdicts = if (verdict == null) current - groupKey else current + (groupKey to verdict)
        val nextDecisions = if (nextVerdicts.isEmpty()) decisions - match.id else decisions + (match.id to MatchDecisions(now, nextVerdicts))
        var k = copy(decisions = nextDecisions)
        if (verdict == null) return k
        val comp = match.competition?.let { Sport.fold(it) }?.takeIf { it.isNotEmpty() }
        val station = source?.station?.let { StationIndex.compact(StationNames.splitCountry(it).first) }?.takeIf { it.isNotEmpty() }
        when (source?.origin) {
            Confidence.STATION -> if (station != null) {
                val pair = "$station|$groupKey"
                k = if (verdict == Verdict.CONFIRMED) {
                    k.copy(stationAliases = k.stationAliases.plusTo(station, groupKey), stationBlocks = k.stationBlocks - pair)
                } else {
                    k.copy(stationAliases = k.stationAliases.minusFrom(station, groupKey), stationBlocks = k.stationBlocks + pair)
                }
            }
            Confidence.RIGHTS, Confidence.DEDICATED, Confidence.LEARNED, Confidence.CONFIRMED, null -> if (comp != null) {
                val pair = "$comp|$groupKey"
                k = if (verdict == Verdict.CONFIRMED) {
                    k.copy(learnedRights = k.learnedRights.plusTo(comp, groupKey), rightsBlocks = k.rightsBlocks - pair)
                } else {
                    k.copy(learnedRights = k.learnedRights.minusFrom(comp, groupKey), rightsBlocks = k.rightsBlocks + pair)
                }
            }
            Confidence.ELSEWHERE -> Unit // Nu se ajunge aici: [origin] e felul în care fusese găsită.
            Confidence.EPG, Confidence.EVENT -> Unit // EPG-ul și canalele eveniment n-au ce învăța: doar alegerea pentru meci.
        }
        return k
    }

    /** Alegerile pe meciuri mai vechi decât [before] se uită (meciurile s-au terminat demult). */
    fun prune(before: Long): SportsKnowledge {
        val kept = decisions.filterValues { it.at >= before }
        return if (kept.size == decisions.size) this else copy(decisions = kept)
    }

    /** Legi de mână postul [station] de canalul [groupKey] (dicționarul de posturi). */
    fun addStationAlias(station: String, groupKey: String): SportsKnowledge {
        val s = StationIndex.compact(StationNames.splitCountry(station).first)
        if (s.isEmpty()) return this
        return copy(stationAliases = stationAliases.plusTo(s, groupKey), stationBlocks = stationBlocks - "$s|$groupKey")
    }

    fun removeStationAlias(station: String, groupKey: String): SportsKnowledge =
        copy(stationAliases = stationAliases.minusFrom(station, groupKey))

    fun removeLearned(competition: String, groupKey: String): SportsKnowledge =
        copy(learnedRights = learnedRights.minusFrom(competition, groupKey))

    /** Uită tot ce s-a învățat din confirmări (dicționarul, competițiile, respingerile și alegerile pe meci). */
    fun forgetLearning(): SportsKnowledge =
        copy(stationAliases = emptyMap(), stationBlocks = emptySet(), learnedRights = emptyMap(), rightsBlocks = emptySet(), decisions = emptyMap())

    private companion object {
        fun Map<String, Set<String>>.plusTo(key: String, value: String) = this + (key to (this[key].orEmpty() + value))

        fun Map<String, Set<String>>.minusFrom(key: String, value: String): Map<String, Set<String>> {
            val rest = this[key].orEmpty() - value
            return if (rest.isEmpty()) this - key else this + (key to rest)
        }
    }
}

/** Un canal găsit pentru un meci (cheia de grupare: toate variantele lui), cu încrederea și motivul. */
data class FoundSource(
    val groupKey: String,
    val confidence: Confidence,
    /** Postul anunțat sau din regulă, dacă potrivirea e pe nume de post. */
    val station: String? = null,
    /** Ai spus că nu e meciul: rămâne la final, marcată. */
    val rejected: Boolean = false,
    /** Cum fusese găsită, înainte de alegerea ta (pentru ce se învață din confirmare sau respingere). */
    val origin: Confidence = confidence,
    /** Emisiunea din EPG-ul canalului la ora meciului, când arată altceva ([Confidence.ELSEWHERE]). */
    val airing: String? = null,
)

/**
 * Găsește canalele unui meci, în ordinea încrederii:
 * EPG (sigur) → post anunțat, din dicționar sau după nume (probabil) → competiție confirmată anterior
 * (învățat) → reguli de drepturi TV (presupus). Alegerile tale pe meci au ultimul cuvânt:
 * confirmarea urcă sursa în față, respingerea o coboară la final.
 */
class SourceFinder(private val index: StationIndex, private val knowledge: SportsKnowledge) {

    private val rules = knowledge.allRules

    // Mii de meciuri, dar doar câteva sute de competiții: regulile se aplică o dată pe competiție.
    private val rightsCache = HashMap<String, List<Triple<RightsRule, String, String>>>()

    /** (regula, post, cheia canalului) din regulile de drepturi care prind competiția. */
    private fun rightsFor(competition: String): List<Triple<RightsRule, String, String>> = rightsCache.getOrPut(competition) {
        rules.filter { it.appliesTo(competition) }.flatMap { rule ->
            rule.stations.flatMap { station -> index.find(TvStation(station, rule.country)).map { Triple(rule, station, it) } }
        }
    }

    /**
     * @param epgKeys canalele al căror EPG arată meciul.
     * @param eventKeys canalele EVENT / PPV care au meciul în nume.
     * @param dedicatedKeys canalele dedicate competiției meciului ([DedicatedChannels]).
     */
    fun find(
        match: Match,
        epgKeys: Collection<String>,
        eventKeys: Collection<String> = emptyList(),
        dedicatedKeys: Collection<String> = emptyList(),
    ): List<FoundSource> {
        val found = LinkedHashMap<String, FoundSource>()
        fun add(key: String, confidence: Confidence, station: String? = null) {
            val old = found[key]
            if (old == null || confidence.ordinal < old.confidence.ordinal) found[key] = FoundSource(key, confidence, station)
        }

        epgKeys.forEach { add(it, Confidence.EPG) }
        eventKeys.forEach { add(it, Confidence.EVENT) }

        for (st in match.stations) {
            val compact = StationIndex.stationKey(st.name)
            val keys = knowledge.stationAliases[compact].orEmpty() + index.find(st)
            keys.filter { "$compact|$it" !in knowledge.stationBlocks }.forEach { add(it, Confidence.STATION, st.name) }
        }

        val comp = match.competition?.let { Sport.fold(it) }?.takeIf { it.isNotEmpty() }
        dedicatedKeys.filter { comp == null || "$comp|$it" !in knowledge.rightsBlocks }.forEach { add(it, Confidence.DEDICATED) }
        if (comp != null) {
            knowledge.learnedRights[comp].orEmpty().forEach { add(it, Confidence.LEARNED) }
            for ((rule, station, key) in rightsFor(match.competition.orEmpty())) {
                if (!rule.appliesToMatch(match)) continue
                if ("$comp|$key" !in knowledge.rightsBlocks) add(key, Confidence.RIGHTS, station)
            }
        }

        val verdicts = knowledge.decisions[match.id]?.verdicts.orEmpty()
        for ((key, v) in verdicts) {
            val old = found[key]
            when (v) {
                // Un canal adăugat de mână (negăsit altfel) apare tot ca „confirmat”.
                Verdict.CONFIRMED -> found[key] = FoundSource(key, Confidence.CONFIRMED, old?.station, origin = old?.origin ?: Confidence.CONFIRMED)
                Verdict.REJECTED -> if (old != null) found[key] = old.copy(rejected = true)
            }
        }
        return found.values.toList()
    }
}

/**
 * Postul anunțat, verificat în EPG: dacă programul canalului arată, la ora meciului, alt meci, canalul nu-l
 * transmite pe al nostru. Sursa „probabilă” (post anunțat) coboară printre presupuneri, iar presupunerile
 * (competiție învățată, canal dedicat, drepturi TV) dispar. Sursele sigure și alegerile tale rămân neatinse.
 */
object BusyChannels {

    /** O emisiune recunoscută ca meciul [matchId] pe un canal. */
    data class Slot(val start: Long, val stop: Long, val matchId: String)

    /** Momentul verificat: puțin după start (emisiunea de dinainte se poate termina cu câteva minute întârziere). */
    private val CHECK_AFTER_START_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)

    private val guesses = setOf(Confidence.STATION, Confidence.LEARNED, Confidence.DEDICATED, Confidence.RIGHTS)

    /** [busy]: cheia de grupare a canalului → meciurile din EPG-ul lui. */
    fun adjust(match: Match, found: List<FoundSource>, busy: Map<String, List<Slot>>): List<FoundSource> {
        if (busy.isEmpty()) return found
        val at = match.start + CHECK_AFTER_START_MS
        return found.mapNotNull { f ->
            if (f.rejected || f.confidence !in guesses) return@mapNotNull f
            val slots = busy[f.groupKey] ?: return@mapNotNull f
            if (slots.any { it.matchId == match.id }) return@mapNotNull f
            val other = slots.any { at >= it.start && at < it.stop }
            when {
                !other -> f
                f.confidence == Confidence.STATION -> f.copy(confidence = Confidence.RIGHTS)
                else -> null
            }
        }
    }
}

/**
 * Verificarea în EPG a surselor nesigure: un post anunțat sau o presupunere pe un canal care are EPG la ora meciului,
 * dar emisiunea de atunci nu pomenește nicio echipă și nici competiția („Știri”, „Snooker”, „NFL: Chiefs v Bills”),
 * transmite altceva. Sursa coboară la final ([Confidence.ELSEWHERE]), cu titlul emisiunii; nu dispare.
 * Un canal fără EPG la ora aceea, sau cu un titlu generic („Live: Football”), rămâne cum era.
 */
object AiringCheck {

    /** O emisiune a canalului: [text] = titlul, subtitlul și începutul descrierii. */
    data class Airing(val start: Long, val stop: Long, val title: String, val text: String)

    private val CHECK_AFTER_START_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)

    private val checked = setOf(Confidence.STATION, Confidence.LEARNED, Confidence.DEDICATED, Confidence.RIGHTS)

    /** Cuvinte care nu spun ce meci e: un titlu doar din ele e generic. */
    private val generic = setOf(
        "live", "direct", "en", "vivo", "in", "diretta", "directo", "ao", "vivo", "football", "fotbal", "soccer", "futbol",
        "fussball", "calcio", "voetbal", "sport", "sports", "match", "meci", "game", "tbc", "tba", "tbd", "programme",
        "program", "event", "eveniment", "coverage", "transmisiune", "the", "of", "and", "si", "v", "vs",
    )

    /** Cuvintele din competiție care nu o deosebesc de altele. */
    private val genericCompetition = setOf(
        "league", "liga", "cup", "cupa", "copa", "coppa", "coupe", "pokal", "division", "serie", "premier", "first", "second",
        "super", "superliga", "championship", "national", "nationala", "uefa", "fifa", "football", "fotbal", "women", "men",
        "round", "group", "stage", "the", "of", "de", "and",
    )

    private val word = Regex("[a-z0-9]+")

    fun words(text: String): Set<String> = word.findAll(Sport.fold(text)).map { it.value }.toSet()

    fun adjust(match: Match, found: List<FoundSource>, airing: (String) -> List<Airing>?): List<FoundSource> {
        if (found.none { !it.rejected && it.confidence in checked }) return found
        val at = match.start + CHECK_AFTER_START_MS
        val teamTokens = (TeamNames.parts(match.home).longTokens + if (match.away.isBlank()) emptyList() else TeamNames.parts(match.away).longTokens)
            .filter { it !in generic }.toSet()
        val compTokens = match.competition?.let { words(it) }.orEmpty().filter { it.length >= 4 && it !in genericCompetition }.toSet()
        return found.map { f ->
            if (f.rejected || f.confidence !in checked) return@map f
            val list = airing(f.groupKey) ?: return@map f
            val now = list.filter { at >= it.start && at < it.stop }
            if (now.isEmpty()) return@map f
            // Toate emisiunile de atunci (canalul poate avea mai multe EPG-uri) trebuie să arate altceva.
            val fits = now.any { a ->
                val w = words(a.text)
                teamTokens.any { it in w } || compTokens.any { it in w } || (w - generic).isEmpty()
            }
            if (fits) return@map f
            f.copy(confidence = Confidence.ELSEWHERE, airing = now.first().title)
        }
    }
}
