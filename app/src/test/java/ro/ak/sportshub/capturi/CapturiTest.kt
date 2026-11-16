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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ro.ak.sportshub.core.sports.Formation
import ro.ak.sportshub.core.sports.LineupPlayer
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.PanelView
import ro.ak.sportshub.core.sports.StatLine
import ro.ak.sportshub.core.sports.TimelineEntry
import ro.ak.sportshub.core.sports.TimelineKind
import ro.ak.sportshub.player.MatchPanelActions
import ro.ak.sportshub.player.MatchPanelContent
import ro.ak.sportshub.player.MatchScoreboard
import androidx.compose.foundation.layout.width
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.player.QuickMenuContent
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.screens.ChannelScreen
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.core.common.ObservedVideo
import ro.ak.sportshub.ui.screens.PitchView
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
        click("Azi")
        waitFor("Arsenal")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1-sports-hub.png")
    }

    @Test
    fun saptamana() {
        home()
        click("Toată săptămâna")
        waitFor("Inter")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1b-toata-saptamana.png")
    }

    /** Rândul de filtre (varianta D: iconiță + text), fără filtre și cu filtre puse. */
    @Test
    fun filtre() {
        home()
        click("Azi")
        waitFor("Arsenal")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/9-filtre-fara.png")
        app.container.sportsSettings.update {
            it.copy(
                filter = ro.ak.sportshub.core.sports.MatchFilter(
                    sport = ro.ak.sportshub.core.sports.Sport.FOOTBALL,
                    competition = "UEFA Champions League",
                    team = "Manchester United",
                    language = "en",
                ),
            )
        }
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/9-filtre-puse.png")
    }

    /** Toate iconițele de sport ale filtrului (planșă pentru Radu). */
    @Test
    fun iconiteSporturi() {
        val sports = listOf<ro.ak.sportshub.core.sports.Sport?>(null) + ro.ak.sportshub.core.sports.Sport.entries
        compose.setContent {
            AkTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.padding(40.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                ) {
                    sports.chunked(5).forEach { row ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                            row.forEach { sp ->
                                ro.ak.sportshub.ui.components.FilterPill(
                                    icon = ro.ak.sportshub.ui.components.FilterIcon.SPORT,
                                    label = "Sport",
                                    value = sp?.label,
                                    onClick = {},
                                    sport = sp,
                                )
                            }
                        }
                    }
                    // Aceleași iconițe mari, ca să se vadă desenul.
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)) {
                        sports.forEach { sp -> ro.ak.sportshub.ui.components.SportGlyph(sp, ro.ak.sportshub.ui.theme.AkColors.Text, 44.dp) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/10-iconite-sporturi.png")
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

    private fun arsenalLive() = app.container.sports.snapshot.value.matches.first { it.match.home == "Arsenal" && it.match.isLive }

    /** O echipă inventată, 4-3-3 sau 4-2-3-1, cu nume inventate. */
    private fun team(home: Boolean, lines: List<Pair<String, Int>>): List<LineupPlayer> {
        var n = 1
        val prefix = if (home) "A." else "C."
        return lines.flatMap { (pos, count) ->
            List(count) { LineupPlayer("$prefix Jucător${n}", pos, (n++).toString(), home = home, substitute = false) }
        }
    }

    private val homeTeam = team(true, listOf("Goalkeeper" to 1, "Defender" to 4, "Midfielder" to 3, "Forward" to 3))
    private val awayTeam = team(false, listOf("Goalkeeper" to 1, "Defender" to 4, "Defensive Midfield" to 2, "Attacking Midfield" to 3, "Forward" to 1))

    @Test
    fun terenFormatii() {
        compose.setContent {
            AkTheme {
                Column(modifier = Modifier.fillMaxSize().background(AkColors.Background).padding(40.dp)) {
                    SectionLabel("Formații: Arsenal 4-3-3 · Chelsea 4-2-3-1 · Emirates Stadium, London")
                    PitchView(Formation.of(homeTeam)!!, Formation.of(awayTeam)!!)
                }
            }
        }
        waitFor("Jucător1")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/4-teren-formatii.png")
    }

    @Test
    fun panouMeciInPlayer() {
        val m = arsenalLive().match
        val view = PanelView(
            homeScore = 1, awayScore = 0, label = "Repriza 1 · 35'", status = MatchStatus.LIVE,
            timeline = listOf(
                TimelineEntry(23, TimelineKind.GOAL, true, "A. Jucător10", "A. Jucător8"),
                TimelineEntry(31, TimelineKind.YELLOW, false, "C. Jucător6", null),
                TimelineEntry(34, TimelineKind.SUBSTITUTION, false, "C. Jucător12", "C. Jucător9"),
            ),
            stats = listOf(StatLine("Posesie (%)", "58", "42"), StatLine("Șuturi pe poartă", "4", "1"), StatLine("Cornere", "5", "2")),
            pending = 1,
        )
        compose.setContent {
            AkTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    MatchScoreboard(
                        match = m, view = view, hidden = false, replay = false,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 32.dp),
                    )
                    MatchPanelContent(
                        match = m, view = view, hidden = false, replay = false, lineup = homeTeam + awayTeam,
                        loading = false, status = null, delaySeconds = 30, offsetSeconds = 10,
                        actions = MatchPanelActions(onJump = {}, onFromStart = {}, onDelay = {}, onOffset = {}),
                        modifier = Modifier.align(Alignment.CenterEnd).width(600.dp),
                    )
                }
            }
        }
        waitFor("A. Jucător10")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/5-player-panou-meci.png")
    }

    @Test
    fun meniuRapid() {
        val e = arsenalLive()
        compose.setContent {
            AkTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    QuickMenuContent(
                        previousName = "Sky Sports Premier League FHD", current = null, announced = e,
                        favorites = listOf(e), others = emptyList(), onPrevious = {}, onMatchPanel = null, onPlayMatch = {},
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
        waitFor("A început")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/6-player-meniu-rapid.png")
    }

    /** Rezoluția văzută la redare: o variantă „4K” care e de fapt FHD și una FHD verificată. */
    private fun seedObserved(): Long = runBlocking {
        val pid = app.container.database.playlists().all().first().id
        app.container.observedVideo.record(pid, "UK: Sky Sports Main Event UHD", ObservedVideo(1920, 1080, 50f, System.currentTimeMillis()))
        app.container.observedVideo.record(pid, "UK: Sky Sports Main Event FHD", ObservedVideo(1920, 1080, 50f, System.currentTimeMillis()))
        app.container.database.channels().findByName(pid, "UK: Sky Sports Main Event FHD", "UK | Sports")!!
    }

    @Test
    fun variantaVerificata() {
        val id = seedObserved()
        compose.setContent { AkTheme { ChannelScreen(channelId = id, onPlay = {}, onOpenSchedule = {}) } }
        waitFor("Sky Sports Main Event UHD")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/7-variante-rezolutie-reala.png")
    }

    @Test
    fun acumPeCanale() {
        seedObserved()
        home()
        click("Acum pe canale")
        waitFor("canale cu meci acum")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/8-acum-pe-canale.png")
    }
}
