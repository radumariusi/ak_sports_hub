package ro.ak.sportshub.data

import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ro.ak.sportshub.BuildConfig
import ro.ak.sportshub.core.common.DiagnosticText

/** Starea trimiterii jurnalului, pentru ecranul de setări. */
sealed interface ReportState {
    data object Idle : ReportState
    data class Sending(val step: String) : ReportState
    data class Sent(val number: Int, val url: String) : ReportState
    data class Failed(val reason: String) : ReportState
}

/**
 * Trimite jurnalul de diagnostic ca issue în repo-ul (privat) al aplicației: un issue cu starea furnizorilor și
 * jurnalul cererilor, plus câte un comentariu cu ultimul răspuns al fiecărui furnizor (scurtat). Folosește cheia
 * GitHub din Setări → Despre, care pentru asta trebuie să aibă și „Issues: Read and write”.
 *
 * În jurnal nu intră nimic din playlisturi sau EPG (adrese, conturi, nume de canale), doar date publice de la
 * furnizorii de meciuri, numărul canalelor de sport și prefixele de țară.
 */
class DiagnosticReporter(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val log: DiagnosticLog,
    private val sports: SportsRepository,
    private val token: () -> String?,
) {
    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    fun send() {
        if (_state.value is ReportState.Sending) return
        scope.launch { _state.value = withContext(Dispatchers.IO) { runCatching { upload() }.getOrElse { ReportState.Failed("eroare: ${it.javaClass.simpleName}") } } }
    }

    private suspend fun upload(): ReportState {
        val key = token() ?: return ReportState.Failed("Lipsește cheia GitHub (Setări → Despre). Cheia trebuie să aibă și „Issues: Read and write”.")
        _state.value = ReportState.Sending("Se pregătește jurnalul…")
        val now = Instant.now()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(now)
        val summary = buildString {
            appendLine("Jurnal de diagnostic trimis din aplicație (Setări → Sports Hub → Trimite jurnalul).")
            appendLine()
            appendLine("- Versiune: v${BuildConfig.VERSION_NAME}")
            appendLine("- Trimis: ${DateTimeFormatter.ISO_INSTANT.format(now)} (fusul TV-ului: ${ZoneId.systemDefault().id}, ora din jurnal e UTC)")
            appendLine("- Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), WebView ${webViewVersion()}")
            appendLine()
            append(sports.diagnosticSummary())
        }
        val journal = log.tail(DiagnosticText.MAX_CHUNK - 200)
        val body = DiagnosticText.cut(summary, DiagnosticText.MAX_CHUNK - 2_000)
        val issue = post(
            ISSUES_URL, key,
            JSONObject().put("title", "Jurnal v${BuildConfig.VERSION_NAME} · $stamp").put("body", body),
        )
        if (issue.first !in 200..299) return failure(issue.first)
        val json = JSONObject(issue.second)
        val number = json.optInt("number")
        val url = json.optString("html_url")
        val comments = ArrayList<String>()
        comments += "### Jurnalul cererilor (UTC)\n```\n${journal.replace("```", "'''")}\n```"
        log.lastCrash()?.let { crash ->
            comments += "### Ultima cădere a aplicației\n```\n${DiagnosticText.cut(crash, DiagnosticText.MAX_CHUNK - 200).replace("```", "'''")}\n```"
        }
        log.samples().entries.sortedBy { it.key }.forEach { (provider, sample) ->
            DiagnosticText.chunks(DiagnosticText.condense(sample.first, DiagnosticText.MAX_CHUNK * 2)).take(2).forEachIndexed { i, part ->
                comments += "### Ultimul răspuns refuzat: $provider${if (i > 0) " (continuare)" else ""}\n```\n${part.replace("```", "'''")}\n```"
            }
        }
        sports.diagnosticFiles().forEach { (title, text) ->
            DiagnosticText.chunks(text).take(2).forEachIndexed { i, part ->
                comments += "### Fișier: $title${if (i > 0) " (continuare)" else ""}\n```\n${part.replace("```", "'''")}\n```"
            }
        }
        var sent = 0
        comments.take(MAX_COMMENTS).forEachIndexed { i, c ->
            _state.value = ReportState.Sending("Se trimite (${i + 1}/${minOf(comments.size, MAX_COMMENTS)})…")
            val r = post("$ISSUES_URL/$number/comments", key, JSONObject().put("body", c))
            if (r.first in 200..299) sent++
            delay(1_000) // GitHub limitează scrierile rapide
        }
        log.log("jurnal", "trimis ca issue #$number ($sent comentarii)")
        log.clearCrashPending()
        return ReportState.Sent(number, url)
    }

    private fun failure(code: Int): ReportState.Failed = ReportState.Failed(
        when (code) {
            401 -> "GitHub a refuzat cheia (401): cheia nu mai e bună."
            403, 404 -> "Cheia nu are voie să scrie issue-uri ($code). Pe GitHub, la cheie: Repository permissions → Issues: Read and write."
            -1 -> "Fără rețea către GitHub."
            else -> "GitHub a răspuns cu $code."
        },
    )

    private fun post(url: String, key: String, json: JSONObject): Pair<Int, String> = try {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AKSportsHub-jurnal")
            .header("Authorization", "Bearer $key")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    } catch (e: Exception) {
        -1 to ""
    }

    private fun webViewVersion(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName else null
    }.getOrNull() ?: "?"

    private companion object {
        const val ISSUES_URL = "https://api.github.com/repos/radumariusi/ak_sports_hub/issues"
        const val MAX_COMMENTS = 24
    }
}
