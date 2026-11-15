package ro.ak.sportshub.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Citește o pagină printr-un WebView nevăzut, ca un browser adevărat (Chrome din sistem). Folosit doar ca a
 * doua încercare, când un site cu protecție anti-robot refuză cererea simplă (HTTP 403): unele site-uri
 * recunosc clientul HTTP al aplicației după felul conexiunii, nu după antete. Nu are legătură cu playlisturile.
 */
class WebViewFetcher(context: Context) {
    private val app = context.applicationContext

    /** Rezultatul: textul paginii (JSON-ul ca text, sau HTML-ul) ori motivul pentru care nu s-a putut. */
    sealed interface Result {
        data class Ok(val text: String) : Result
        /** [lastText]: ultimul text văzut în pagină (o pagină de verificare), pentru jurnal. */
        data class Failed(val reason: String, val lastText: String? = null) : Result
    }

    /**
     * Deschide [url] și întoarce textul când [accept] îl găsește bun (o pagină de verificare se poate
     * transforma singură în pagina adevărată); cel mult [timeoutMs].
     */
    suspend fun fetch(url: String, html: Boolean, accept: (String) -> Boolean, timeoutMs: Long = 25_000): Result {
        var seen: String? = null
        return withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Main) { load(url, html, accept) { seen = it } } }
            ?: Result.Failed("timp depășit în browser", seen)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, html: Boolean, accept: (String) -> Boolean, onText: (String) -> Unit): Result =
        suspendCancellableCoroutine { cont ->
            val web = runCatching { WebView(app) }.getOrElse {
                cont.resume(Result.Failed("browserul sistemului lipsește (WebView)"))
                return@suspendCancellableCoroutine
            }
            var done = false
            fun finish(r: Result) {
                if (done) return
                done = true
                web.stopLoading()
                web.destroy()
                if (cont.isActive) cont.resume(r)
            }
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.blockNetworkImage = true
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    // Doar pagina principală contează; o pagină de verificare (403) poate trece singură mai departe.
                    if (request.isForMainFrame && response.statusCode !in setOf(403, 503)) {
                        finish(Result.Failed("HTTP ${response.statusCode} în browser"))
                    }
                }

                override fun onPageFinished(view: WebView, loaded: String) {
                    if (done) return
                    val js = if (html) "document.documentElement.outerHTML" else "document.body ? document.body.innerText : ''"
                    view.evaluateJavascript("(function(){return $js;})()") { raw ->
                        val text = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull().orEmpty()
                        onText(text)
                        if (accept(text)) finish(Result.Ok(text))
                        // altfel: poate e o pagină de verificare care se reîncarcă; se așteaptă până la limita de timp.
                    }
                }
            }
            cont.invokeOnCancellation { web.post { if (!done) { done = true; web.stopLoading(); web.destroy() } } }
            web.loadUrl(url)
        }
}
