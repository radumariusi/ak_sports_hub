package ro.ak.sportshub.capturi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.ak.sportshub.ui.components.SportGlyph
import ro.ak.sportshub.ui.components.SportGlyphVariant
import ro.ak.sportshub.ui.components.SportGlyphVariants
import ro.ak.sportshub.ui.screens.HomeScreen
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkTheme

/**
 * Navigarea cu săgețile pe ecranul principal (cum o face telecomanda: `moveFocus`), pas cu pas, cu ce primește
 * focusul după fiecare apăsare. Drumul se scrie în `build/outputs/roborazzi/navigare.txt` și în jurnal.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = CapturiApp::class, sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class NavigareTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: CapturiApp
    private lateinit var focus: FocusManager
    private val log = StringBuilder()

    @Before
    fun seed() {
        app = RuntimeEnvironment.getApplication() as CapturiApp
        runBlocking {
            DemoData.seed(app.container, app.noBackupFilesDir, System.currentTimeMillis())
            app.container.sports.recompute()
        }
    }

    private fun focused(): String {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val node = nodes.firstOrNull() ?: return "(nimic)"
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        return texts?.takeIf { it.isNotBlank() } ?: "(fără text)"
    }

    private fun press(dir: FocusDirection, name: String): String {
        compose.runOnIdle { focus.moveFocus(dir) }
        compose.waitForIdle()
        Thread.sleep(150)
        compose.waitForIdle()
        val f = focused()
        log.append("$name → $f\n")
        println("NAVIGARE $name → $f")
        return f
    }

    @Test
    fun drumulPrinSportsHub() {
        compose.setContent {
            focus = LocalFocusManager.current
            AkTheme {
                HomeScreen(
                    onOpenMatch = {}, onOpenPlaylists = {}, onOpenEpg = {}, onPlayChannel = { _, _ -> },
                    onOpenChannel = {}, onOpenSportsSettings = {},
                )
            }
        }
        compose.waitUntil(15_000) { focused().contains("Sports Hub") }
        log.append("start → ${focused()}\n")
        val problems = mutableListOf<String>()
        fun expect(dir: FocusDirection, name: String, ok: (String) -> Boolean) {
            val f = press(dir, name)
            if (!ok(f)) problems += "$name → $f"
        }
        val down = FocusDirection.Down
        val up = FocusDirection.Up
        val right = FocusDirection.Right
        expect(down, "▼ din Sports Hub") { it.contains("LIVE") }
        expect(right, "▶") { it.contains("Azi") }
        expect(right, "▶") { it.contains("Mâine") }
        expect(right, "▶") { it.contains("Toată") }
        expect(down, "▼ spre zile") { it.contains(".") && it.length <= 6 }
        expect(right, "▶ ziua 2") { it.contains(".") && it.length <= 6 }
        expect(up, "▲ din ziua 2") { it.contains("Toată") }
        expect(down, "▼ înapoi pe zile") { it.contains(".") && it.length <= 6 }
        expect(down, "▼ spre filtre") { it.contains("Sport") }
        expect(down, "▼ spre listă") { !it.contains("Sport") && it != "(nimic)" }
        expect(up, "▲ din listă") { it.contains("Sport") }
        expect(up, "▲ din filtre") { it.contains(".") && it.length <= 6 }
        expect(up, "▲ din zile") { it.contains("Toată") }
        expect(up, "▲ din taburi") { it.contains("Sports Hub") }
        expect(down, "▼ din Sports Hub") { it.contains("Toată") }
        repeat(5) { expect(right, "▶") { true } }
        expect(FocusDirection.Left, "◀") { true }
        expect(right, "▶ Clasamente") { it.contains("Clasamente") }
        expect(up, "▲ din Clasamente") { it.contains("Sports Hub") }
        expect(down, "▼ din Sports Hub") { it.contains("Clasamente") }
        expect(down, "▼ în Clasamente") { it != "(nimic)" && !it.contains("Sports Hub") }
        expect(up, "▲ înapoi") { it.contains("Clasamente") }
        expect(FocusDirection.Left, "◀ Rezumate") { it.contains("Rezumate") }
        expect(FocusDirection.Left, "◀") { true }
        expect(FocusDirection.Left, "◀") { true }
        expect(FocusDirection.Left, "◀ Favorite") { it.contains("Favorite") }
        expect(down, "▼ din Favorite") { it.contains("Sport") }
        expect(up, "▲ din filtre") { it.contains("Favorite") }
        expect(up, "▲") { it.contains("Sports Hub") }
        expect(right, "▶ Live TV") { it.contains("Live TV") }
        expect(down, "▼ în Live TV") { it != "(nimic)" && !it.contains("Live TV") }
        expect(up, "▲ din Live TV") { it.contains("Live TV") }
        expect(right, "▶ Setări") { it.contains("Setări") }
        expect(down, "▼ în Setări") { it != "(nimic)" && it != "Setări" }
        expect(up, "▲ din Setări") { it.contains("Setări") }

        File("build/outputs/roborazzi").mkdirs()
        File("build/outputs/roborazzi/navigare.txt").writeText(log.toString() + "\nProbleme:\n" + problems.joinToString("\n"))
        assertTrue("Navigarea a sărit:\n" + problems.joinToString("\n") + "\n\nDrumul:\n$log", problems.isEmpty())
    }


    /** Variantele noi pentru iconițele de fotbal, handbal, volei și snooker, mari, cu numele lor. */
    @Test
    fun iconiteVariante() {
        compose.setContent {
            AkTheme {
                Column(modifier = Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    SportGlyphVariants.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(46.dp), verticalAlignment = Alignment.CenterVertically) {
                            row.forEach { key ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SportGlyphVariant(key, AkColors.Text, 52.dp)
                                    SportGlyphVariant(key, AkColors.Accent, 20.dp)
                                    Text(text = key, fontSize = 18.sp, color = AkColors.TextDim)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/11-iconite-variante.png")
    }
}
