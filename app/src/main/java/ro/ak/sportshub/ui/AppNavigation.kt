package ro.ak.sportshub.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ro.ak.sportshub.DeepLinks
import ro.ak.sportshub.data.bestSource
import ro.ak.sportshub.player.Archive
import ro.ak.sportshub.ui.screens.BackupScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.player.PlayerScreen
import ro.ak.sportshub.ui.epg.EpgSettingsScreen
import ro.ak.sportshub.ui.epg.ScheduleScreen
import ro.ak.sportshub.ui.playlists.PlaylistEditScreen
import ro.ak.sportshub.ui.playlists.PlaylistsScreen
import ro.ak.sportshub.ui.screens.ChannelScreen
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.ui.screens.MatchScreen
import ro.ak.sportshub.ui.screens.SportsKnowledgeScreen
import ro.ak.sportshub.ui.screens.SportsSettingsScreen

private const val HOME = "home"
private const val MATCH = "match/{matchId}"
private const val PLAYLISTS = "playlists"
private const val PLAYLIST_EDIT = "playlist/{id}?type={type}"
private const val PLAYER = "player/{channelId}?zapGroup={zapGroup}&archiveStart={archiveStart}&archiveEnd={archiveEnd}"
private const val CHANNEL = "channel/{channelId}"
private const val SCHEDULE = "schedule/{channelId}"
private const val EPG = "epg"
private const val SPORTS_SETTINGS = "sports-settings"
private const val SPORTS_KNOWLEDGE = "sports-knowledge"
private const val BACKUP = "backup"

/** Playerul pe arhiva canalului (catch-up), de la [start] la [end] (ms UTC). */
private fun archiveRoute(channelId: Long, start: Long, end: Long) = "player/$channelId?archiveStart=$start&archiveEnd=$end"

@Composable
fun AppNavigation(deepLink: MutableStateFlow<DeepLinks.Target?> = MutableStateFlow(null)) {
    val nav = rememberNavController()
    val container = appContainer()
    val link by deepLink.collectAsStateWithLifecycle()

    // Din notificare sau din rândul de pe ecranul principal: meciul, sau direct sursa recomandată.
    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        when (target) {
            is DeepLinks.Target.Match -> nav.navigate("match/" + Uri.encode(target.id)) { launchSingleTop = true }
            is DeepLinks.Target.Play -> {
                val snapshot = withTimeoutOrNull(15_000) { container.sports.snapshot.first { !it.loading } }
                val best = snapshot?.matches?.firstOrNull { it.match.id == target.matchId }
                    ?.bestSource(container.sportsSettings.current)
                if (best != null) {
                    nav.navigate("player/${best.id}") { launchSingleTop = true }
                } else {
                    nav.navigate("match/" + Uri.encode(target.matchId)) { launchSingleTop = true }
                }
            }
        }
        // La sfârșit (schimbarea cheii oprește efectul): legătura s-a deschis.
        deepLink.value = null
    }

    NavHost(navController = nav, startDestination = HOME) {
        composable(HOME) {
            HomeScreen(
                onOpenMatch = { id -> nav.navigate("match/" + Uri.encode(id)) },
                onOpenPlaylists = { nav.navigate(PLAYLISTS) },
                onOpenEpg = { nav.navigate(EPG) },
                onPlayChannel = { id, zapGroup -> nav.navigate("player/$id?zapGroup=$zapGroup") { launchSingleTop = true } },
                onOpenChannel = { id -> nav.navigate("channel/$id") },
                onOpenSportsSettings = { nav.navigate(SPORTS_SETTINGS) },
                onOpenBackup = { nav.navigate(BACKUP) },
            )
        }
        composable(BACKUP) { BackupScreen() }
        composable(CHANNEL, arguments = listOf(navArgument("channelId") { type = NavType.LongType })) { entry ->
            ChannelScreen(
                channelId = entry.arguments?.getLong("channelId") ?: 0L,
                onPlay = { id -> nav.navigate("player/$id") { launchSingleTop = true } },
                onOpenSchedule = { id -> nav.navigate("schedule/$id") },
            )
        }
        composable(SCHEDULE, arguments = listOf(navArgument("channelId") { type = NavType.LongType })) { entry ->
            ScheduleScreen(
                channelId = entry.arguments?.getLong("channelId") ?: 0L,
                onPlay = { id ->
                    nav.navigate("player/$id") {
                        popUpTo(SCHEDULE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onPlayArchive = { id, start, end -> nav.navigate(archiveRoute(id, start, end)) { launchSingleTop = true } },
            )
        }
        composable(EPG) { EpgSettingsScreen() }
        composable(SPORTS_SETTINGS) { SportsSettingsScreen(onOpenKnowledge = { nav.navigate(SPORTS_KNOWLEDGE) }) }
        composable(SPORTS_KNOWLEDGE) { SportsKnowledgeScreen() }
        composable(
            PLAYER,
            arguments = listOf(
                navArgument("channelId") { type = NavType.LongType },
                navArgument("zapGroup") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("archiveStart") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
                navArgument("archiveEnd") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { entry ->
            val archiveStart = entry.arguments?.getLong("archiveStart") ?: 0L
            val archiveEnd = entry.arguments?.getLong("archiveEnd") ?: 0L
            PlayerScreen(
                channelId = entry.arguments?.getLong("channelId") ?: 0L,
                zapGroup = entry.arguments?.getLong("zapGroup") ?: 0L,
                archive = if (archiveStart > 0 && archiveEnd > archiveStart) Archive(archiveStart, archiveEnd) else null,
                onExit = { nav.popBackStack() },
            )
        }
        composable(MATCH) { entry ->
            MatchScreen(
                matchId = entry.arguments?.getString("matchId").orEmpty(),
                onPlay = { id -> nav.navigate("player/$id") { launchSingleTop = true } },
                onPlayArchive = { id, start, end -> nav.navigate(archiveRoute(id, start, end)) { launchSingleTop = true } },
            )
        }
        composable(PLAYLISTS) {
            PlaylistsScreen(
                onEdit = { id -> nav.navigate("playlist/$id") },
                onAdd = { type -> nav.navigate("playlist/0?type=${type.name}") },
            )
        }
        composable(
            PLAYLIST_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("type") {
                    type = NavType.StringType
                    defaultValue = PlaylistType.M3U_URL.name
                },
            ),
        ) { entry ->
            val type = entry.arguments?.getString("type")
                ?.let { runCatching { PlaylistType.valueOf(it) }.getOrNull() }
                ?: PlaylistType.M3U_URL
            PlaylistEditScreen(
                playlistId = entry.arguments?.getLong("id") ?: 0L,
                newType = type,
                onClose = { nav.popBackStack() },
            )
        }
    }
}
