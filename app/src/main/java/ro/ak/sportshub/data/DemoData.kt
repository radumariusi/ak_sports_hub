package ro.ak.sportshub.data

import ro.ak.sportshub.core.common.ChannelNameParser
import ro.ak.sportshub.core.common.SourceCandidate
import ro.ak.sportshub.core.common.SourceRanking
import ro.ak.sportshub.core.model.Quality

enum class MatchDay { LIVE, TODAY, TOMORROW, WEEK }

data class DemoSource(
    val channelName: String,
    val playlist: String,
    val language: String,
    val quality: Quality,
)

data class DemoMatch(
    val id: String,
    val day: MatchDay,
    val time: String,
    val sport: String,
    val competition: String,
    val home: String,
    val away: String,
    val favorite: Boolean,
    val channels: List<Triple<String, String, String>>, // nume din playlist, playlist, limbă
) {
    val title: String get() = "$home – $away"

    /** Sursele ordonate după preferințele implicite (EN/RO, apoi calitate). */
    val sources: List<DemoSource>
        get() {
            val parsed = channels.mapIndexed { i, (name, playlist, lang) ->
                i to DemoSource(name, playlist, lang, ChannelNameParser.parse(name).quality)
            }.toMap()
            return SourceRanking.rank(
                parsed.map { (i, s) -> SourceCandidate(i.toString(), s.language, s.quality) },
                isFavorite = favorite,
            ).map { parsed.getValue(it.id.toInt()) }
        }
}

/**
 * Date demonstrative, doar ca să poți proba navigarea în etapa 1.
 * Dispar în etapele 2 și 7, când vin playlisturile și meciurile reale.
 */
object DemoData {
    val matches = listOf(
        DemoMatch(
            id = "demo-1", day = MatchDay.LIVE, time = "LIVE 63'", sport = "Fotbal",
            competition = "Premier League", home = "Arsenal", away = "Chelsea", favorite = true,
            channels = listOf(
                Triple("UK: Sky Sport Main Event UHD", "Playlist 1", "en"),
                Triple("UK: Sky Sport Main Event FHD", "Playlist 1", "en"),
                Triple("UK: Sky Sport Main Event HD", "Playlist 2", "en"),
                Triple("RO: Digi Sport 1 FHD", "Playlist 1", "ro"),
                Triple("DE: Sky Sport Premier League UHD", "Playlist 2", "de"),
            ),
        ),
        DemoMatch(
            id = "demo-2", day = MatchDay.TODAY, time = "21:00", sport = "Fotbal",
            competition = "Superliga", home = "FCSB", away = "CFR Cluj", favorite = false,
            channels = listOf(
                Triple("RO: Digi Sport 1 FHD", "Playlist 1", "ro"),
                Triple("RO: Prima Sport 1 HD", "Playlist 2", "ro"),
            ),
        ),
        DemoMatch(
            id = "demo-3", day = MatchDay.TOMORROW, time = "18:30", sport = "Tenis",
            competition = "ATP Finals", home = "Sinner", away = "Alcaraz", favorite = false,
            channels = listOf(
                Triple("RO: Eurosport 1 HD", "Playlist 1", "ro"),
                Triple("IT: Sky Sport Tennis FHD", "Playlist 2", "it"),
            ),
        ),
    )

    fun match(id: String): DemoMatch? = matches.firstOrNull { it.id == id }
}
