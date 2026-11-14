package ro.ak.sportshub.ui

import android.net.Uri
import androidx.compose.runtime.Composable
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
import ro.ak.sportshub.ui.screens.SportsSettingsScreen

private const val HOME = "home"
private const val MATCH = "match/{matchId}"
private const val PLAYLISTS = "playlists"
private const val PLAYLIST_EDIT = "playlist/{id}?type={type}"
private const val PLAYER = "player/{channelId}?zapGroup={zapGroup}"
private const val CHANNEL = "channel/{channelId}"
private const val SCHEDULE = "schedule/{channelId}"
private const val EPG = "epg"
private const val SPORTS_SETTINGS = "sports-settings"

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HOME) {
        composable(HOME) {
            HomeScreen(
                onOpenMatch = { id -> nav.navigate("match/" + Uri.encode(id)) },
                onOpenPlaylists = { nav.navigate(PLAYLISTS) },
                onOpenEpg = { nav.navigate(EPG) },
                onPlayChannel = { id, zapGroup -> nav.navigate("player/$id?zapGroup=$zapGroup") { launchSingleTop = true } },
                onOpenChannel = { id -> nav.navigate("channel/$id") },
                onOpenSportsSettings = { nav.navigate(SPORTS_SETTINGS) },
            )
        }
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
            )
        }
        composable(EPG) { EpgSettingsScreen() }
        composable(SPORTS_SETTINGS) { SportsSettingsScreen() }
        composable(
            PLAYER,
            arguments = listOf(
                navArgument("channelId") { type = NavType.LongType },
                navArgument("zapGroup") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { entry ->
            PlayerScreen(
                channelId = entry.arguments?.getLong("channelId") ?: 0L,
                zapGroup = entry.arguments?.getLong("zapGroup") ?: 0L,
                onExit = { nav.popBackStack() },
            )
        }
        composable(MATCH) { entry ->
            MatchScreen(
                matchId = entry.arguments?.getString("matchId").orEmpty(),
                onPlay = { id -> nav.navigate("player/$id") { launchSingleTop = true } },
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
