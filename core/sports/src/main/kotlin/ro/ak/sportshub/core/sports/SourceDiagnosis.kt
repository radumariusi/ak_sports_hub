package ro.ak.sportshub.core.sports

/** Ce s-a întâmplat cu un post anunțat pentru meci. */
enum class StationOutcome(val label: String) {
    /** Găsit după nume (sau după dicționarul încorporat) în playlisturile tale. */
    FOUND("găsit în playlisturi"),

    /** Găsit prin dicționarul tău (l-ai legat tu sau l-ai confirmat). */
    FROM_DICTIONARY("găsit prin dicționarul tău"),

    /** Există canale cu numele ăsta, dar din altă țară. */
    OTHER_COUNTRY("există doar în altă țară"),

    /** L-ai respins la un meci anterior pentru toate canalele găsite. */
    BLOCKED("respins de tine"),

    /** Niciun canal cu numele ăsta. */
    MISSING("nu există în playlisturi"),
}

/** Un post anunțat de un furnizor și ce s-a găsit pentru el. */
data class StationCheck(val station: TvStation, val outcome: StationOutcome, val groupKeys: List<String>)

/** O regulă de drepturi TV care se aplică meciului și canalele găsite pentru ea. */
data class RuleCheck(val rule: RightsRule, val groupKeys: List<String>, val missing: List<String>)

/**
 * „De ce nu are sursă?” pentru un meci: furnizorii care l-au dat, posturile anunțate (găsite sau nu),
 * emisiunile EPG, canalele EVENT / PPV, regulile de drepturi și ce ai spus tu.
 */
data class SourceDiagnosis(
    val providers: Set<String>,
    val stations: List<StationCheck>,
    val epgBroadcasts: Int,
    val eventChannels: Int,
    val rules: List<RuleCheck>,
    val learned: Int,
    val rejected: Int,
) {
    /** Posturile pe care le poți lega de un canal (lipsă sau doar în altă țară). */
    val linkable: List<StationCheck>
        get() = stations.filter { it.outcome == StationOutcome.MISSING || it.outcome == StationOutcome.OTHER_COUNTRY }
}

object SourceDiagnostics {

    fun diagnose(match: Match, index: StationIndex, knowledge: SportsKnowledge): SourceDiagnosis {
        val stations = match.stations
            .distinctBy { StationIndex.compact(it.name) + "|" + Countries.codes(it.country).sorted().joinToString(",") }
            .map { st -> check(st, index, knowledge) }
            .sortedBy { it.outcome.ordinal }
        val comp = match.competition?.let { Sport.fold(it) }?.takeIf { it.isNotEmpty() }
        val rules = knowledge.allRules.filter { it.appliesTo(match.competition) }.map { rule ->
            val found = rule.stations.associateWith { s -> index.find(TvStation(s, rule.country)) }
            RuleCheck(
                rule = rule,
                groupKeys = found.values.flatten().distinct().filter { comp == null || "$comp|$it" !in knowledge.rightsBlocks },
                missing = found.filterValues { it.isEmpty() }.keys.toList(),
            )
        }
        return SourceDiagnosis(
            providers = match.providers,
            stations = stations,
            epgBroadcasts = match.broadcasts.size,
            eventChannels = match.eventChannels.size,
            rules = rules,
            learned = comp?.let { knowledge.learnedRights[it]?.size } ?: 0,
            rejected = knowledge.decisions[match.id]?.verdicts?.count { it.value == Verdict.REJECTED } ?: 0,
        )
    }

    private fun check(st: TvStation, index: StationIndex, knowledge: SportsKnowledge): StationCheck {
        val compact = StationIndex.compact(StationNames.splitCountry(st.name).first)
        val fromDictionary = knowledge.stationAliases[compact].orEmpty().filter { "$compact|$it" !in knowledge.stationBlocks }
        if (fromDictionary.isNotEmpty()) return StationCheck(st, StationOutcome.FROM_DICTIONARY, fromDictionary.sorted())
        val byName = index.find(st)
        val allowed = byName.filter { "$compact|$it" !in knowledge.stationBlocks }
        return when {
            allowed.isNotEmpty() -> StationCheck(st, StationOutcome.FOUND, allowed)
            byName.isNotEmpty() -> StationCheck(st, StationOutcome.BLOCKED, byName)
            else -> {
                val anyCountry = index.find(TvStation(StationNames.splitCountry(st.name).first, null))
                if (anyCountry.isNotEmpty()) {
                    StationCheck(st, StationOutcome.OTHER_COUNTRY, anyCountry)
                } else {
                    StationCheck(st, StationOutcome.MISSING, emptyList())
                }
            }
        }
    }
}
