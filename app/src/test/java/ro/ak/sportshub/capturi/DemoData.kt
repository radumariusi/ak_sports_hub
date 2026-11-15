package ro.ak.sportshub.capturi

import ro.ak.sportshub.core.sports.Espn
import ro.ak.sportshub.core.sports.SportEvent
import ro.ak.sportshub.core.sports.TeamLook
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.core.database.RoomImportSink
import ro.ak.sportshub.core.playlist.ParsedChannel
import ro.ak.sportshub.core.playlist.PlaylistHeader
import ro.ak.sportshub.core.playlist.PlaylistImporter

/** Un playlist și un program de meciuri inventate, ca ecranele să arate ca în folosire. */
object DemoData {

    private val channels = listOf(
        "UK | Sports" to listOf(
            "UK: Sky Sports Main Event UHD", "UK: Sky Sports Main Event FHD", "UK: Sky Sports Main Event HD",
            "UK: Sky Sports Premier League FHD", "UK: Sky Sports Premier League HD",
            "UK: TNT Sports 1 FHD", "UK: TNT Sports 1 HD", "UK: Sky Sports Football FHD",
        ),
        "RO | Sport" to listOf(
            "RO: Digi Sport 1 FHD", "RO: Digi Sport 1 HD", "RO: Digi Sport 2 HD",
            "RO: Prima Sport 1 FHD", "RO: Orange Sport 1 HD",
        ),
        "ES | Deportes" to listOf("ES: DAZN LaLiga FHD", "ES: Movistar LaLiga 4K", "ES: Movistar LaLiga HD"),
        "RO | General" to listOf("RO: Pro TV HD", "RO: Antena 1 HD", "RO: TVR 1 HD"),
        "UK | General" to listOf("UK: BBC One FHD", "UK: ITV 1 HD"),
    )

    private data class Event(
        val id: String, val home: String, val away: String, val league: String, val startOffsetMin: Long,
        val status: String, val score: Pair<Int, Int>? = null, val stations: List<Pair<String, String>> = emptyList(),
    )

    private val events = listOf(
        Event("9001", "Arsenal", "Chelsea", "English Premier League", -35, "1H", 1 to 0,
            listOf("Sky Sports Main Event" to "United Kingdom", "TNT Sports 1" to "United Kingdom", "Digi Sport 1" to "Romania")),
        Event("9002", "FCSB", "CFR Cluj", "Romanian Liga I", 90, "NS",
            stations = listOf("Digi Sport 1" to "Romania", "Prima Sport 1" to "Romania")),
        Event("9003", "Real Madrid", "Barcelona", "Spanish La Liga", 200, "NS",
            stations = listOf("DAZN LaLiga" to "Spain", "Movistar LaLiga" to "Spain")),
        Event("9004", "Liverpool", "Manchester City", "English Premier League", 26 * 60, "NS",
            stations = listOf("Sky Sports Premier League" to "United Kingdom")),
        Event("9005", "Inter", "AC Milan", "Italian Serie A", 50 * 60, "NS"),
        Event("9006", "Bayern Munich", "Borussia Dortmund", "German Bundesliga", 74 * 60, "NS",
            stations = listOf("Sky Sports Football" to "United Kingdom")),
        Event("9007", "Arsenal", "Tottenham Hotspur", "English Premier League", 6 * 24 * 60, "NS"),
    )

    suspend fun seed(container: AppContainer, filesDir: File, now: Long) {
        val db = container.database
        val pid = db.playlists().insert(
            PlaylistEntity(name = "IPTV demo", type = PlaylistType.M3U_URL, url = "http://demo.invalid/playlist.m3u", lastUpdated = now),
        )
        var n = 100
        val parsed = channels.flatMap { (group, names) ->
            names.map { name ->
                n++
                ParsedChannel(
                    name = name,
                    group = group,
                    streamUrl = "http://demo.invalid/live/demo/demo/$n.ts",
                    catchupDays = if (group.endsWith("Sports")) 3 else null,
                    number = n - 100,
                )
            }
        }
        RoomImportSink(db).replaceChannels(pid.toString(), PlaylistHeader(), PlaylistImporter.enrich(parsed.asSequence()))

        val dir = File(filesDir, "sports").apply { mkdirs() }
        val day = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC))
        val ts = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val list = events.joinToString(",") { e ->
            val start = Instant.ofEpochMilli(now + TimeUnit.MINUTES.toMillis(e.startOffsetMin)).atZone(ZoneOffset.UTC).withSecond(0).withNano(0)
            """{"idEvent":"${e.id}","strSport":"Soccer","strLeague":"${e.league}","strHomeTeam":"${e.home}",""" +
                """"strAwayTeam":"${e.away}","strTimestamp":"${ts.format(start.toLocalDateTime())}","strStatus":"${e.status}",""" +
                """"intHomeScore":${e.score?.first?.let { "\"$it\"" } ?: "null"},"intAwayScore":${e.score?.second?.let { "\"$it\"" } ?: "null"}}"""
        }
        File(dir, "events-FOOTBALL-$day.json").writeText("""{"events":[$list]}""")
        val tv = events.flatMap { e -> e.stations.map { (ch, country) -> """{"idEvent":"${e.id}","strChannel":"$ch","strCountry":"$country"}""" } }
        File(dir, "tv-$day.json").writeText("""{"tvevents":[${tv.joinToString(",")}]}""")
        // Culorile, codurile și forma echipelor, ca de la ESPN (siglele nu se descarcă în teste: apare codul).
        val looks = mapOf(
            "Arsenal" to TeamLook(color = "ef0107", code = "ARS", form = "WWDWL"),
            "Chelsea" to TeamLook(color = "034694", code = "CHE", form = "WLWDW"),
            "FCSB" to TeamLook(color = "e30613", altColor = "1d3f8f", code = "FCSB", form = "WWWDL"),
            "CFR Cluj" to TeamLook(color = "7a1e2c", code = "CFR", form = "DLWWD"),
            "Real Madrid" to TeamLook(color = "febe10", altColor = "00529f", code = "RMA", form = "WWWWD"),
            "Barcelona" to TeamLook(color = "a50044", altColor = "004d98", code = "BAR", form = "WDWWW"),
            "Liverpool" to TeamLook(color = "c8102e", code = "LIV", form = "WWLWW"),
            "Manchester City" to TeamLook(color = "6cabdd", code = "MCI", form = "DWLWW"),
            "Inter" to TeamLook(color = "0068a8", code = "INT", form = "WWDWW"),
            "AC Milan" to TeamLook(color = "fb090b", code = "MIL", form = "LWDWL"),
            "Bayern Munich" to TeamLook(color = "dc052d", code = "BAY", form = "WWWWW"),
            "Borussia Dortmund" to TeamLook(color = "fde100", code = "BVB", form = "WDLWD"),
            "Tottenham Hotspur" to TeamLook(color = "132257", altColor = "ffffff", code = "TOT", form = "LLWDW"),
        )
        container.teamLooks.learn(
            events.map { e -> SportEvent(Espn.PROVIDER, "demo-" + e.id, "Soccer", e.league, e.home, e.away, now, homeLook = looks[e.home], awayLook = looks[e.away]) },
        )
    }
}
