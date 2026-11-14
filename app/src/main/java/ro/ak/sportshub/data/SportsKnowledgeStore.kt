package ro.ak.sportshub.data

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.sports.DefaultRights
import ro.ak.sportshub.core.sports.MatchDecisions
import ro.ak.sportshub.core.sports.RightsRule
import ro.ak.sportshub.core.sports.SportsKnowledge
import ro.ak.sportshub.core.sports.TeamNames
import ro.ak.sportshub.core.sports.Verdict

/**
 * Dicționarul de posturi, regulile de drepturi TV, ce s-a învățat din confirmări și alias-urile de echipe.
 * Stau în preferințe (nu în baza de date, care se poate reface), pe chei de grupare și nume, niciodată pe adrese de stream.
 */
class SportsKnowledgeStore(context: Context) {

    private val prefs = context.getSharedPreferences("sports_knowledge", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read().prune(System.currentTimeMillis() - KEEP_DECISIONS_MS))
    val state: StateFlow<SportsKnowledge> = _state.asStateFlow()
    val current: SportsKnowledge get() = _state.value

    init {
        TeamNames.setUserAliases(_state.value.teamAliases)
    }

    @Synchronized
    fun update(transform: (SportsKnowledge) -> SportsKnowledge) {
        val next = transform(_state.value)
        if (next == _state.value) return
        if (next.teamAliases != _state.value.teamAliases) TeamNames.setUserAliases(next.teamAliases)
        prefs.edit().putString(KEY, write(next)).apply()
        _state.value = next
    }

    private fun read(): SportsKnowledge {
        val json = prefs.getString(KEY, null) ?: return SportsKnowledge()
        return runCatching {
            val o = JSONObject(json)
            SportsKnowledge(
                stationAliases = o.optJSONObject("stationAliases").setMap(),
                stationBlocks = o.optJSONArray("stationBlocks").strings().toSet(),
                rules = o.optJSONArray("rules")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val r = arr.optJSONObject(i) ?: return@mapNotNull null
                        RightsRule(
                            competitions = r.optJSONArray("competitions").strings(),
                            stations = r.optJSONArray("stations").strings(),
                            country = r.optString("country").ifBlank { null },
                            enabled = r.optBoolean("enabled", true),
                        ).takeIf { it.competitions.isNotEmpty() && it.stations.isNotEmpty() }
                    }
                } ?: DefaultRights.rules,
                learnedRights = o.optJSONObject("learnedRights").setMap(),
                rightsBlocks = o.optJSONArray("rightsBlocks").strings().toSet(),
                decisions = o.optJSONObject("decisions")?.let { d ->
                    d.keys().asSequence().mapNotNull { id ->
                        val m = d.optJSONObject(id) ?: return@mapNotNull null
                        val v = m.optJSONObject("verdicts") ?: return@mapNotNull null
                        val verdicts = v.keys().asSequence().mapNotNull { k ->
                            runCatching { Verdict.valueOf(v.getString(k)) }.getOrNull()?.let { k to it }
                        }.toMap()
                        id to MatchDecisions(m.optLong("at"), verdicts)
                    }.toMap()
                }.orEmpty(),
                teamAliases = o.optJSONObject("teamAliases")?.let { t ->
                    t.keys().asSequence().associateWith { t.optString(it) }.filterValues { it.isNotBlank() }
                }.orEmpty(),
            )
        }.getOrElse { SportsKnowledge() }
    }

    private fun write(k: SportsKnowledge): String = JSONObject().apply {
        put("stationAliases", JSONObject().apply { k.stationAliases.forEach { (s, keys) -> put(s, JSONArray(keys.toList())) } })
        put("stationBlocks", JSONArray(k.stationBlocks.toList()))
        put(
            "rules",
            JSONArray().apply {
                k.rules.forEach { r ->
                    put(
                        JSONObject().apply {
                            put("competitions", JSONArray(r.competitions))
                            put("stations", JSONArray(r.stations))
                            r.country?.let { put("country", it) }
                            put("enabled", r.enabled)
                        },
                    )
                }
            },
        )
        put("learnedRights", JSONObject().apply { k.learnedRights.forEach { (c, keys) -> put(c, JSONArray(keys.toList())) } })
        put("rightsBlocks", JSONArray(k.rightsBlocks.toList()))
        put(
            "decisions",
            JSONObject().apply {
                k.decisions.forEach { (id, d) ->
                    put(
                        id,
                        JSONObject().apply {
                            put("at", d.at)
                            put("verdicts", JSONObject().apply { d.verdicts.forEach { (gk, v) -> put(gk, v.name) } })
                        },
                    )
                }
            },
        )
        put("teamAliases", JSONObject().apply { k.teamAliases.forEach { (a, n) -> put(a, n) } })
    }.toString()

    private fun JSONObject?.setMap(): Map<String, Set<String>> =
        if (this == null) emptyMap() else keys().asSequence().associateWith { optJSONArray(it).strings().toSet() }.filterValues { it.isNotEmpty() }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().ifEmpty { null } }

    private companion object {
        const val KEY = "knowledge"
        /** Alegerile pe meci se păstrează o săptămână (meciurile se termină demult până atunci). */
        val KEEP_DECISIONS_MS = TimeUnit.DAYS.toMillis(7)
    }
}
