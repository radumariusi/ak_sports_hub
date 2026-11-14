package ro.ak.sportshub.capturi

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import org.robolectric.RuntimeEnvironment
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.ui.screens.MatchScreen
import ro.ak.sportshub.ui.theme.AkTheme

/**
 * Capturile de ecran ale aplicației, cu date inventate, la 1920×1080 (ecran de televizor).
 * Rulează doar în workflow-ul „Capturi”; imaginile nu intră în repo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = CapturiApp::class, sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class CapturiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: CapturiApp

    @Before
    fun seed() {
        app = RuntimeEnvironment.getApplication() as CapturiApp
        runBlocking {
            DemoData.seed(app.container, app.noBackupFilesDir, System.currentTimeMillis())
            app.container.sports.recompute()
        }
    }

    private fun clickable(text: String): SemanticsMatcher =
        hasClickAction() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun click(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(clickable(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(clickable(text), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(300)
        compose.waitForIdle()
    }

    private fun home() = compose.setContent {
        AkTheme {
            HomeScreen(
                onOpenMatch = {},
                onOpenPlaylists = {},
                onOpenEpg = {},
                onPlayChannel = { _, _ -> },
                onOpenChannel = {},
                onOpenSportsSettings = {},
            )
        }
    }

    @Test
    fun sportsHub() {
        home()
        click("Săptămâna asta")
        waitFor("Arsenal")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1-sports-hub.png")
    }

    @Test
    fun meci() {
        val id = app.container.sports.snapshot.value.matches.first { it.match.home == "Arsenal" && it.match.isLive }.match.id
        compose.setContent { AkTheme { MatchScreen(matchId = id, onPlay = {}) } }
        waitFor("Sky Sports Main Event")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/2-meci-surse.png")
    }

    @Test
    fun liveTv() {
        home()
        click("Live TV")
        click("UK | Sports")
        waitFor("Sky Sports Premier League")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/3-live-tv.png")
    }
}
