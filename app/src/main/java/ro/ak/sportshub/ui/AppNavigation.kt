package ro.ak.sportshub.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.ui.screens.MatchScreen

private const val HOME = "home"
private const val MATCH = "match/{matchId}"

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HOME) {
        composable(HOME) {
            HomeScreen(onOpenMatch = { id -> nav.navigate("match/$id") })
        }
        composable(MATCH) { entry ->
            MatchScreen(matchId = entry.arguments?.getString("matchId").orEmpty())
        }
    }
}
