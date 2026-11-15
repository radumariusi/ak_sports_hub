package ro.ak.sportshub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences
import ro.ak.sportshub.core.sports.FollowedNames
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
    /** Sporturile cerute de la furnizori (ESPN, EuroLeague, TheSportsDB pentru restul). */
    val sports: List<Sport> = Sport.DEFAULT_FETCH,
    /** LiveSoccerTV (neoficial, opțional): meciurile de fotbal de azi și mâine, cu posturile TV. */
    val liveSoccerTvEnabled: Boolean = true,
    /** Sofascore (neoficial, opțional): ne refuză (403) chiar și prin WebView, deci e oprit implicit (din v3). */
    val sofascoreEnabled: Boolean = false,
    /** Posturile TV pe țară de la Sofascore (câteva meciuri pe rundă), când Sofascore e pornit. */
    val sofascoreTv: Boolean = true,
    /** FutbolEnLaTV (neoficial, opțional): ~18 sporturi pe zi, cu posturile din Spania. */
    val futbolEnLaTvEnabled: Boolean = true,
    /** LiveOnSat (neoficial, opțional): fotbal, zeci de posturi pe meci din toată lumea. */
    val liveOnSatEnabled: Boolean = true,
    /** FotMob TV guide (neoficial, opțional): fotbal, posturile din țările canalelor tale (inclusiv România). */
    val fotMobEnabled: Boolean = true,
    /**
     * ESPN (JSON public): programul a ~100 de ligi la toate sporturile, plus Pre-meci și Live; NHL și MLB doar pentru
     * posturile din SUA și Canada, dacă ai canale de acolo.
     */
    val usLeaguesEnabled: Boolean = true,
    /** API-ul oficial EuroLeague: programul EuroLeague și EuroCup (baschet). */
    val euroLeagueEnabled: Boolean = true,
    /** Canalele EVENT / PPV din playlisturi: meciul citit din numele canalului. */
    val eventChannels: Boolean = true,
    /** Doar competițiile importante (`CompetitionFilter`): restul meciurilor nu intră în listă. */
    val topCompetitionsOnly: Boolean = true,
    /** Fusul orelor scrise fără fus în numele canalelor EVENT („20:00”); gol = fusul televizorului. */
    val eventZone: String = "",
    /** Câte zile (de azi încolo) se cer de la TheSportsDB. */
    val daysAhead: Int = 3,
    val favorites: SportsFavorites = SportsFavorites(),
    /** „Fără spoilere”: scorul, evenimentele și statisticile meciurilor începute rămân ascunse până le ceri. */
    val noSpoilers: Boolean = false,
    /**
     * Cu cât e în urmă stream-ul live față de realitate (secunde): în panoul meciului din player,
     * scorul și evenimentele noi apar abia după atât, ca să nu le afli înainte să le vezi.
     */
    val streamDelaySeconds: Int = DEFAULT_STREAM_DELAY,
    /** Ultimele filtre folosite (rămân după repornire). */
    val filter: MatchFilter = MatchFilter(),
    /** Notificare înainte de meciurile favorite. */
    val reminders: Boolean = true,
    /** Cu câte minute înainte de start vine notificarea. */
    val reminderMinutes: Int = MatchReminders.DEFAULT_LEAD_MINUTES,
    /** Rândul „Meciurile tale de azi” pe ecranul principal Android TV. */
    val homeRow: Boolean = true,
    /** Comutare automată pe meciul favorit cu un minut înainte de start (cu 10 secunde ca s-o anulezi). */
    val autoSwitch: Boolean = true,
    /** Alertele de gol și „Final” pentru meciurile favorite (niciodată cu „Fără spoilere”). */
    val goalAlerts: Boolean = true,
)

const val DEFAULT_STREAM_DELAY = 30

/** Valorile din care alegi întârzierea stream-ului (secunde). */
/** Întârzierea stream-ului, din 10 în 10 secunde, până la 5 minute. */
val StreamDelays: List<Int> = (0..300 step 10).toList()

/** Fusurile din care poți alege pentru orele din numele canalelor EVENT (gol = fusul televizorului). */
val EventZones: List<Pair<String, String>> = listOf(
    "" to "Fusul televizorului",
    "Europe/London" to "Marea Britanie (UK)",
    "Europe/Paris" to "Europa Centrală (CET)",
    "Europe/Bucharest" to "România (EET)",
    "America/New_York" to "SUA, Est (ET)",
    "UTC" to "UTC",
)

/** Fusul pentru orele din numele canalelor EVENT. */
fun SportsSettings.eventZoneId(): java.time.ZoneId =
    eventZone.takeIf { it.isNotBlank() }?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()

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
        val teams = if (fav.isTeam(name)) fav.teams.filterNot { TeamNames.covers(it, name) } else fav.teams + name
        s.copy(favorites = fav.copy(teams = teams))
    }

    fun toggleFavoriteCompetition(name: String): Unit = update { s ->
        val fav = s.favorites
        val list = if (fav.isCompetition(name)) fav.competitions.filterNot { it.equals(name, ignoreCase = true) } else fav.competitions + name
        s.copy(favorites = fav.copy(competitions = list))
    }

    /** Urmărește un nume (jucător, pilot, luptător) sau oprește urmărirea lui. */
    fun toggleFollowedName(name: String): Unit = update { s ->
        val fav = s.favorites
        val clean = FollowedNames.normalize(name) ?: return@update s
        val names = if (fav.isName(clean)) fav.names.filterNot { Sport.fold(it) == Sport.fold(clean) } else fav.names + clean
        s.copy(favorites = fav.copy(names = names))
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
                // Versiunea 3: Sofascore oprit pentru toți (refuză orice cerere: 403); se poate porni din nou din setări.
                sofascoreEnabled = if (o.optInt("version", 1) < VERSION) false else o.optBoolean("sofascore", d.sofascoreEnabled),
                sofascoreTv = o.optBoolean("sofascoreTv", d.sofascoreTv),
                futbolEnLaTvEnabled = o.optBoolean("futbolEnLaTv", d.futbolEnLaTvEnabled),
                liveOnSatEnabled = o.optBoolean("liveOnSat", d.liveOnSatEnabled),
                fotMobEnabled = o.optBoolean("fotMob", d.fotMobEnabled),
                usLeaguesEnabled = o.optBoolean("usLeagues", d.usLeaguesEnabled),
                euroLeagueEnabled = o.optBoolean("euroLeague", d.euroLeagueEnabled),
                eventChannels = o.optBoolean("eventChannels", d.eventChannels),
                topCompetitionsOnly = o.optBoolean("topCompetitionsOnly", d.topCompetitionsOnly),
                eventZone = o.optString("eventZone", d.eventZone),
                sports = if (!o.has("sports")) d.sports else o.optJSONArray("sports").strings().mapNotNull(Sport::fromName).let { saved ->
                    // Versiunea 4 (ESPN extins): sporturile noi acoperite de ESPN se adaugă o dată la setările vechi.
                    if (o.optInt("version", 1) < 4) (saved + Sport.ADDED_WITH_ESPN).distinct() else saved
                },
                daysAhead = o.optInt("daysAhead", d.daysAhead).coerceIn(1, 7),
                noSpoilers = o.optBoolean("noSpoilers", d.noSpoilers),
                streamDelaySeconds = ((o.optInt("streamDelay", d.streamDelaySeconds).coerceIn(0, 300) + 5) / 10 * 10).coerceAtMost(300),
                reminders = o.optBoolean("reminders", d.reminders),
                reminderMinutes = o.optInt("reminderMinutes", d.reminderMinutes).coerceIn(1, 120),
                homeRow = o.optBoolean("homeRow", d.homeRow),
                autoSwitch = o.optBoolean("autoSwitch", d.autoSwitch),
                goalAlerts = o.optBoolean("goalAlerts", d.goalAlerts),
                favorites = SportsFavorites(
                    teams = fav?.optJSONArray("teams").strings(),
                    competitions = fav?.optJSONArray("competitions").strings(),
                    sports = fav?.optJSONArray("sports").strings().mapNotNull(Sport::fromName).toSet(),
                    names = fav?.optJSONArray("names").strings(),
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
        put("version", VERSION)
        put("languages", JSONArray(s.sourcePreferences.preferredLanguages))
        put("goodQuality", s.sourcePreferences.goodQuality.name)
        put("sportsDbEnabled", s.sportsDbEnabled)
        put("sportsDbKey", s.sportsDbKey)
        put("sportsDbTv", s.sportsDbTv)
        put("liveSoccerTv", s.liveSoccerTvEnabled)
        put("sofascore", s.sofascoreEnabled)
        put("sofascoreTv", s.sofascoreTv)
        put("futbolEnLaTv", s.futbolEnLaTvEnabled)
        put("liveOnSat", s.liveOnSatEnabled)
        put("fotMob", s.fotMobEnabled)
        put("usLeagues", s.usLeaguesEnabled)
        put("euroLeague", s.euroLeagueEnabled)
        put("eventChannels", s.eventChannels)
        put("topCompetitionsOnly", s.topCompetitionsOnly)
        put("eventZone", s.eventZone)
        put("sports", JSONArray(s.sports.map { it.name }))
        put("daysAhead", s.daysAhead)
        put("noSpoilers", s.noSpoilers)
        put("streamDelay", s.streamDelaySeconds)
        put("reminders", s.reminders)
        put("reminderMinutes", s.reminderMinutes)
        put("homeRow", s.homeRow)
        put("autoSwitch", s.autoSwitch)
        put("goalAlerts", s.goalAlerts)
        put(
            "favorites",
            JSONObject().apply {
                put("teams", JSONArray(s.favorites.teams))
                put("competitions", JSONArray(s.favorites.competitions))
                put("sports", JSONArray(s.favorites.sports.map { it.name }))
                put("names", JSONArray(s.favorites.names))
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

        /** Versiunea formatului; la o versiune mai veche se aplică schimbările de valori implicite. */
        private const val VERSION = 4
    }
}
