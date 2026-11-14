package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.core.sports.MatchFilter
import ro.ak.sportshub.core.sports.MatchReminders
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.SportsFavorites
import ro.ak.sportshub.core.sports.TeamNames
import ro.ak.sportshub.core.sports.TheSportsDb

/** Setările Sports Hub. Nu conțin date de acces la playlisturi. */
data class SportsSettings(
    /** Limbile preferate și pragul de calitate pentru ordinea surselor. */
    val sourcePreferences: SourcePreferences = SourcePreferences(),
    val sportsDbEnabled: Boolean = true,
    /** Cheia TheSportsDB; `123` e cheia gratuită publică. */
    val sportsDbKey: String = TheSportsDb.DEFAULT_KEY,
    /** Cere și posturile TV de la TheSportsDB (pentru potrivirea pe nume de post). */
    val sportsDbTv: Boolean = true,
    /** Sporturile cerute de la TheSportsDB. */
    val sports: List<Sport> = Sport.DEFAULT_FETCH,
    /** LiveSoccerTV (neoficial, opțional): meciurile de fotbal de azi și mâine, cu posturile TV. */
    val liveSoccerTvEnabled: Boolean = true,
    /** Sofascore (neoficial, opțional): programul și scorurile, fără posturi TV. */
    val sofascoreEnabled: Boolean = false,
    /** Câte zile (de azi încolo) se cer de la TheSportsDB. */
    val daysAhead: Int = 3,
    val favorites: SportsFavorites = SportsFavorites(),
    /** „Fără spoilere”: scorul, evenimentele și statisticile meciurilor începute rămân ascunse până le ceri. */
    val noSpoilers: Boolean = false,
    /** Ultimele filtre folosite (rămân după repornire). */
    val filter: MatchFilter = MatchFilter(),
    /** Notificare înainte de meciurile favorite. */
    val reminders: Boolean = true,
    /** Cu câte minute înainte de start vine notificarea. */
    val reminderMinutes: Int = MatchReminders.DEFAULT_LEAD_MINUTES,
    /** Rândul „Meciurile tale de azi” pe ecranul principal Android TV. */
    val homeRow: Boolean = true,
)

class SportsSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SportsSettings> = _settings.asStateFlow()
    val current: SportsSettings get() = _settings.value

    @Synchronized
    fun update(transform: (SportsSettings) -> SportsSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        prefs.edit().putString(KEY, write(next)).apply()
        _settings.value = next
    }

    fun toggleFavoriteTeam(name: String): Unit = update { s ->
        val fav = s.favorites
        val teams = if (fav.isTeam(name)) fav.teams.filterNot { TeamNames.same(it, name) } else fav.teams + name
        s.copy(favorites = fav.copy(teams = teams))
    }

    fun toggleFavoriteCompetition(name: String): Unit = update { s ->
        val fav = s.favorites
        val list = if (fav.isCompetition(name)) fav.competitions.filterNot { it.equals(name, ignoreCase = true) } else fav.competitions + name
        s.copy(favorites = fav.copy(competitions = list))
    }

    private fun read(): SportsSettings {
        val json = prefs.getString(KEY, null) ?: return SportsSettings()
        return runCatching {
            val o = JSONObject(json)
            val d = SportsSettings()
            val f = o.optJSONObject("filter")
            val fav = o.optJSONObject("favorites")
            SportsSettings(
                sourcePreferences = SourcePreferences(
                    preferredLanguages = o.optJSONArray("languages").strings().ifEmpty { d.sourcePreferences.preferredLanguages },
                    goodQuality = quality(o.optString("goodQuality")) ?: d.sourcePreferences.goodQuality,
                ),
                sportsDbEnabled = o.optBoolean("sportsDbEnabled", d.sportsDbEnabled),
                sportsDbKey = o.optString("sportsDbKey", d.sportsDbKey).ifBlank { d.sportsDbKey },
                sportsDbTv = o.optBoolean("sportsDbTv", d.sportsDbTv),
                liveSoccerTvEnabled = o.optBoolean("liveSoccerTv", d.liveSoccerTvEnabled),
                sofascoreEnabled = o.optBoolean("sofascore", d.sofascoreEnabled),
                sports = if (o.has("sports")) o.optJSONArray("sports").strings().mapNotNull(Sport::fromName) else d.sports,
                daysAhead = o.optInt("daysAhead", d.daysAhead).coerceIn(1, 7),
                noSpoilers = o.optBoolean("noSpoilers", d.noSpoilers),
                reminders = o.optBoolean("reminders", d.reminders),
                reminderMinutes = o.optInt("reminderMinutes", d.reminderMinutes).coerceIn(1, 120),
                homeRow = o.optBoolean("homeRow", d.homeRow),
                favorites = SportsFavorites(
                    teams = fav?.optJSONArray("teams").strings(),
                    competitions = fav?.optJSONArray("competitions").strings(),
                    sports = fav?.optJSONArray("sports").strings().mapNotNull(Sport::fromName).toSet(),
                ),
                filter = if (f == null) MatchFilter() else MatchFilter(
                    sport = Sport.fromName(f.optString("sport")),
                    competition = f.optString("competition").ifBlank { null },
                    team = f.optString("team").ifBlank { null },
                    language = f.optString("language").ifBlank { null },
                    minQuality = quality(f.optString("minQuality")),
                    onlyWithSources = f.optBoolean("onlyWithSources", false),
                ),
            )
        }.getOrElse { SportsSettings() }
    }

    private fun write(s: SportsSettings): String = JSONObject().apply {
        put("languages", JSONArray(s.sourcePreferences.preferredLanguages))
        put("goodQuality", s.sourcePreferences.goodQuality.name)
        put("sportsDbEnabled", s.sportsDbEnabled)
        put("sportsDbKey", s.sportsDbKey)
        put("sportsDbTv", s.sportsDbTv)
        put("liveSoccerTv", s.liveSoccerTvEnabled)
        put("sofascore", s.sofascoreEnabled)
        put("sports", JSONArray(s.sports.map { it.name }))
        put("daysAhead", s.daysAhead)
        put("noSpoilers", s.noSpoilers)
        put("reminders", s.reminders)
        put("reminderMinutes", s.reminderMinutes)
        put("homeRow", s.homeRow)
        put(
            "favorites",
            JSONObject().apply {
                put("teams", JSONArray(s.favorites.teams))
                put("competitions", JSONArray(s.favorites.competitions))
                put("sports", JSONArray(s.favorites.sports.map { it.name }))
            },
        )
        put(
            "filter",
            JSONObject().apply {
                s.filter.sport?.let { put("sport", it.name) }
                s.filter.competition?.let { put("competition", it) }
                s.filter.team?.let { put("team", it) }
                s.filter.language?.let { put("language", it) }
                s.filter.minQuality?.let { put("minQuality", it.name) }
                put("onlyWithSources", s.filter.onlyWithSources)
            },
        )
    }.toString()

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().ifEmpty { null } }

    private fun quality(name: String?): Quality? = Quality.entries.firstOrNull { it.name == name }

    companion object {
        /** Numele fișierului de preferințe (pentru backup). */
        const val PREFS = "sports"
        private const val KEY = "settings"
    }
}
