package ro.ak.sportshub.home

import android.graphics.Color
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.ak.sportshub.AkApp
import ro.ak.sportshub.R
import ro.ak.sportshub.core.sports.ScreensaverBoard
import ro.ak.sportshub.core.sports.Spoilers

/**
 * F3: economizorul de ecran „AK Sports Hub: scoruri” (ales din setările televizorului): ora, scorurile meciurilor
 * favorite live și următoarele meciuri favorite. Fără favorite de arătat, meciurile live și de azi cu surse.
 * Datele vin din lista Sports Hub deja calculată; scorurile live se reîmprospătează pe calea obișnuită (ESPN, cel mult
 * o dată pe minut), deci nicio conexiune la playlist. „Fără spoilere” ascunde scorurile. Textul se mută din când în
 * când, ca să nu rămână întipărit pe ecran.
 */
class ScoresDream : DreamService() {

    private var scope: CoroutineScope? = null
    private lateinit var column: LinearLayout
    private lateinit var root: FrameLayout

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(column, FrameLayout.LayoutParams(dp(560), FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val container = (application as AkApp).container
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            var tick = 0
            while (isActive) {
                render()
                if (tick % MOVE_EVERY == 0) move()
                tick++
                delay(REFRESH_MS)
            }
        }
        // Scorurile live: aceeași reîmprospătare ca în Sports Hub (o cerere pe ligă, cel mult o dată pe minut).
        s.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { container.sports.refreshLiveScores() }
                delay(REFRESH_MS)
            }
        }
    }

    override fun onDreamingStopped() {
        scope?.cancel()
        scope = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun render() {
        val container = (application as AkApp).container
        val settings = container.sportsSettings.current
        val revealed = container.matchDetails.revealed.value
        val snapshot = container.sports.snapshot.value
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val sources = snapshot.matches.associate { it.match.id to it.summary.count }
        val board = ScreensaverBoard.build(
            matches = snapshot.matches.map { it.match },
            isFavorite = settings.favorites::isFavorite,
            hasSources = { (sources[it.id] ?: 0) > 0 },
            hideScore = { Spoilers.hide(settings.noSpoilers, it.id in revealed, it.status) },
            now = now,
            zone = zone,
        )
        column.removeAllViews()
        column.addView(text(Instant.ofEpochMilli(now).atZone(zone).format(CLOCK), 64f, TEXT, bold = true))
        column.addView(text(Instant.ofEpochMilli(now).atZone(zone).format(DATE), 20f, DIM))
        if (board.isEmpty) {
            column.addView(space())
            column.addView(text(if (snapshot.loading) "Se încarcă meciurile…" else "Niciun meci favorit acum.", 22f, DIM))
            return
        }
        if (board.live.isNotEmpty()) {
            column.addView(space())
            column.addView(text(if (board.favorites) "LIVE · FAVORITE" else "LIVE", 16f, LIVE, bold = true))
            board.live.forEach { column.addView(row(it)) }
        }
        if (board.next.isNotEmpty()) {
            column.addView(space())
            column.addView(text(if (board.favorites) "URMEAZĂ · FAVORITE" else "URMEAZĂ AZI", 16f, ACCENT, bold = true))
            board.next.forEach { column.addView(row(it)) }
        }
    }

    private fun row(l: ScreensaverBoard.Line): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(2))
        val top = LinearLayout(this@ScoresDream).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(l.title, 26f, TEXT, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val score = l.score ?: if (l.live) "LIVE" else null
            if (score != null) addView(text(score, 28f, if (l.score != null) ACCENT else LIVE, bold = true).apply { setPadding(dp(12), 0, 0, 0) })
        }
        addView(top)
        if (l.detail.isNotBlank()) addView(text(l.detail, 17f, DIM))
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        typeface = font(if (bold) R.font.barlowcondensed_semibold else R.font.barlow_regular, bold)
    }

    private fun space(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(18)) }

    /** Coloana se mută într-un loc ales la întâmplare (fără să iasă din ecran). */
    private fun move() {
        root.post {
            val maxX = (root.width - column.width).coerceAtLeast(0)
            val maxY = (root.height - column.height).coerceAtLeast(0)
            column.translationX = if (maxX > 0) Random.nextInt(maxX).toFloat() else 0f
            column.translationY = if (maxY > 0) Random.nextInt(maxY).toFloat() else 0f
        }
    }

    private fun font(id: Int, bold: Boolean): Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { resources.getFont(id) }.getOrDefault(Typeface.DEFAULT)
        else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 30_000L
        /** La câte reîmprospătări se mută textul (aici: la 2 minute). */
        const val MOVE_EVERY = 4
        val BG = Color.rgb(0x0B, 0x0F, 0x14)
        val TEXT = Color.rgb(0xEE, 0xF1, 0xF4)
        val DIM = Color.rgb(0x9A, 0xA4, 0xB1)
        val ACCENT = Color.rgb(0xFF, 0xB5, 0x47)
        val LIVE = Color.rgb(0xFF, 0x4D, 0x4F)
        val RO: Locale = Locale.forLanguageTag("ro")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RO)
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RO)
    }
}
