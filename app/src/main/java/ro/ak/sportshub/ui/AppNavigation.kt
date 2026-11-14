package ro.ak.sportshub.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.ui.playlists.PlaylistEditScreen
import ro.ak.sportshub.ui.playlists.PlaylistsScreen
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.ui.screens.MatchScreen

private const val HOME = "home"
private const val MATCH = "match/{matchId}"
private const val PLAYLISTS = "playlists"
private const val PLAYLIST_EDIT = "playlist/{id}?type={type}"

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HOME) {
        composable(HOME) {
            HomeScreen(
                onOpenMatch = { id -> nav.navigate("match/$id") },
                onOpenPlaylists = { nav.navigate(PLAYLISTS) },
            )
        }
        composable(MATCH) { entry ->
            MatchScreen(matchId = entry.arguments?.getString("matchId").orEmpty())
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
