package ro.ak.sportshub.home

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.tv.TvContract
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.DeepLinks
import ro.ak.sportshub.R
import ro.ak.sportshub.core.sports.HomeRow
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchStatus

/**
 * Rândul „Meciurile tale de azi” de pe ecranul principal Android TV (Android 8+): meciurile favorite,
 * apoi celelalte meciuri de azi cu surse găsite. Se actualizează din lista Sports Hub (fără rețea);
 * OK pe un meci deschide fereastra lui în aplicație.
 */
class HomeChannel(private val context: Context, private val container: AppContainer) {

    private val prefs = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    /** Un meci din rând, cu ce se vede pe card. */
    private data class Card(val id: String, val title: String, val line: String, val live: Boolean, val start: Long, val end: Long, val competition: String?)

    @OptIn(FlowPreview::class)
    fun start() {
        if (Build.VERSION.SDK_INT < 26) return
        container.scope.launch {
            combine(container.sports.snapshot, container.sportsSettings.settings) { snap, s ->
                if (snap.loading) {
                    null
                } else if (!s.homeRow) {
                    emptyList<Card>()
                } else {
                    val zone = ZoneId.systemDefault()
                    HomeRow.pick(
                        items = snap.matches,
                        match = { it.match },
                        sources = { it.summary.count },
                        isFavorite = s.favorites::isFavorite,
                        now = System.currentTimeMillis(),
                        zone = zone,
                    ).map { e -> card(e.match, e.summary.count, zone) }
                }
            }.debounce(5_000).distinctUntilChanged().collect { cards ->
                if (cards != null) withContext(Dispatchers.IO) { runCatching { sync(cards) } }
            }
        }
    }

    private fun card(m: Match, sources: Int, zone: ZoneId): Card {
        val time = Instant.ofEpochMilli(m.start).atZone(zone).format(TimeFormat)
        val line = listOfNotNull(
            if (m.status == MatchStatus.LIVE) "LIVE" else time,
            m.competition,
            when (sources) {
                0 -> null
                1 -> "1 sursă"
                else -> "$sources surse"
            },
        ).joinToString(" · ")
        return Card(m.id, m.title, line, m.isLive, m.start, m.end, m.competition)
    }

    @RequiresApi(26)
    private fun sync(cards: List<Card>) {
        val resolver = context.contentResolver
        val channelId = ensureChannel() ?: return
        resolver.delete(TvContract.buildPreviewProgramsUriForChannel(channelId), null, null)
        val posters = File(context.filesDir, PosterProvider.DIR).apply { mkdirs() }
        posters.listFiles()?.forEach { it.delete() }
        cards.forEachIndexed { i, c ->
            val name = "p$i-${c.id.hashCode().toUInt()}.png"
            runCatching { File(posters, name).outputStream().use { poster(c).compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val values = ContentValues().apply {
                put("channel_id", channelId)
                put("type", TYPE_EVENT)
                put("title", c.title)
                put("short_description", c.line)
                put("poster_art_uri", PosterProvider.uri(name).toString())
                put("poster_art_aspect_ratio", ASPECT_16_9)
                put("intent_uri", DeepLinks.intent(context, DeepLinks.match(c.id)).toUri(Intent.URI_INTENT_SCHEME))
                put("live", if (c.live) 1 else 0)
                put("start_time_utc_millis", c.start)
                put("end_time_utc_millis", c.end)
                put("internal_provider_id", c.id)
                put("weight", cards.size - i)
            }
            runCatching { resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values) }
        }
    }

    /** Canalul aplicației din launcher; se creează o dată și se cere afișarea lui. */
    @RequiresApi(26)
    private fun ensureChannel(): Long? {
        val resolver = context.contentResolver
        val saved = prefs.getLong(CHANNEL_ID, -1L)
        if (saved > 0) {
            val exists = runCatching {
                resolver.query(TvContract.buildChannelUri(saved), arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
            if (exists) return saved
        }
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val values = ContentValues().apply {
            put("type", TvContract.Channels.TYPE_PREVIEW)
            put("display_name", "Meciurile tale de azi")
            launch?.let { put("app_link_intent_uri", it.toUri(Intent.URI_INTENT_SCHEME)) }
        }
        val uri = runCatching { resolver.insert(TvContract.Channels.CONTENT_URI, values) }.getOrNull() ?: return null
        val id = ContentUris.parseId(uri)
        prefs.edit().putLong(CHANNEL_ID, id).apply()
        runCatching {
            resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { logo().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Primul canal al aplicației devine vizibil fără întrebări; pe unele televizoare apare o confirmare.
        runCatching { TvContract.requestChannelBrowsable(context, id) }
        return id
    }

    private fun logo(): Bitmap {
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val drawable = context.getDrawable(R.drawable.ic_app) ?: return bmp
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /** Cardul unui meci: competiția, echipele, ora (fără logo-uri, doar text). */
    private fun poster(c: Card): Bitmap {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(0x15, 0x1B, 0x24), Color.rgb(0x0B, 0x0F, 0x14), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        canvas.drawRect(0f, h - 14f, w.toFloat(), h.toFloat(), Paint().apply { color = if (c.live) LIVE else ACCENT })
        val condensed = font(R.font.barlowcondensed_bold)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9A, 0xA4, 0xB1); textSize = 30f; typeface = font(R.font.barlow_medium) }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xEE, 0xF1, 0xF4); textSize = 54f; typeface = condensed }
        canvas.drawText(ellipsize(c.competition ?: "", small, w - 60f), 30f, 60f, small)
        val parts = c.title.split(" – ", limit = 2)
        canvas.drawText(ellipsize(parts[0], big, w - 60f), 30f, 160f, big)
        if (parts.size == 2) canvas.drawText(ellipsize(parts[1], big, w - 60f), 30f, 230f, big)
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (c.live) LIVE else ACCENT; textSize = 38f; typeface = condensed }
        canvas.drawText(c.line.substringBefore(" · "), 30f, 310f, badge)
        return bmp
    }

    /** Literele temei (Barlow); pe Android < 8 rămâne fontul sistemului. */
    private fun font(id: Int): Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { context.resources.getFont(id) }.getOrDefault(Typeface.DEFAULT_BOLD) else Typeface.DEFAULT_BOLD

    private fun ellipsize(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private companion object {
        const val STATE = "home_channel"
        const val CHANNEL_ID = "channelId"
        /** `TvContract.PreviewPrograms.TYPE_EVENT` și `ASPECT_RATIO_16_9`. */
        const val TYPE_EVENT = 5
        const val ASPECT_16_9 = 0
        val LIVE = Color.rgb(0xFF, 0x4D, 0x4F)
        val ACCENT = Color.rgb(0xFF, 0xB5, 0x47)
        val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ro"))
    }
}
