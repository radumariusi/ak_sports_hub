package ro.ak.sportshub.setup

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.core.common.PhoneSetup
import ro.ak.sportshub.core.sports.DetailsSchedule
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.SecondScreen
import ro.ak.sportshub.core.sports.Spoilers
import ro.ak.sportshub.data.MatchDetailsState
import ro.ak.sportshub.data.MatchEntry
import ro.ak.sportshub.data.bestSource

/**
 * F4, telefonul ca al doilea ecran. Setarea (oprită implicit), codul aleator al adresei și portul stau în preferințele
 * `telefon` (nu intră în backup: țin de televizorul acesta). Serverul ascultă doar cât e pornită setarea **și**
 * aplicația e pe ecran ([setVisible] din `MainActivity.onStart/onStop`); adresa rămâne aceeași de la o pornire la alta,
 * ca pagina salvată pe telefon să meargă din nou. „Cod nou” schimbă adresa (cea veche nu mai răspunde).
 */
class SecondScreenHost(context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class State(val enabled: Boolean, val url: String?, val noNetwork: Boolean = false)

    private val _state = MutableStateFlow(State(enabled = prefs.getBoolean(KEY_ON, false), url = null))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var visible = false
    private var server: SecondScreenServer? = null

    @Synchronized
    fun setVisible(v: Boolean) {
        visible = v
        sync()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _state.value = _state.value.copy(enabled = on)
        sync()
    }

    /** Cod nou: adresa veche nu mai răspunde. */
    @Synchronized
    fun newToken() {
        prefs.edit().putString(KEY_TOKEN, PhoneSetup.newToken()).apply()
        stopServer()
        sync()
    }

    private fun token(): String = prefs.getString(KEY_TOKEN, null) ?: PhoneSetup.newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    private fun sync() {
        val want = visible && _state.value.enabled
        if (!want) {
            stopServer()
            return
        }
        if (server != null) return
        val s = SecondScreenServer(container, token())
        val url = s.start(prefs.getInt(KEY_PORT, 0))
        if (url == null) {
            _state.value = _state.value.copy(url = null, noNetwork = true)
            return
        }
        prefs.edit().putInt(KEY_PORT, s.port).apply()
        server = s
        _state.value = _state.value.copy(url = url, noNetwork = false)
    }

    private fun stopServer() {
        server?.stop()
        server = null
        _state.value = _state.value.copy(url = null)
    }

    companion object {
        const val PREFS = "telefon"
        private const val KEY_ON = "alDoileaEcran"
        private const val KEY_TOKEN = "cod"
        private const val KEY_PORT = "port"
    }
}

/**
 * Serverul HTTP mic al celui de-al doilea ecran (aceeași construcție ca [PhoneSetupServer]): răspunde doar la adresa cu
 * codul aleator, fără nimic în loguri, cel mult [MAX_CLIENTS] cereri odată. Nu dă nicio adresă de stream și nu atinge
 * conexiunea playlistului; „Pornește pe televizor” trece prin `AppContainer.phonePlay` (calea schimbării canalului).
 * Detaliile meciului deschis pe telefon se cer ca la fereastra meciului de pe televizor: un singur meci odată
 * (ultimul deschis), Pre-meci la deschidere, Live cel mult o dată pe minut cât e live.
 */
class SecondScreenServer(private val container: AppContainer, private val token: String) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val clients = AtomicInteger(0)

    /** Ultima cerere de detalii pe meci (pentru ritmul cererilor la furnizori). */
    private val lastRefresh = ConcurrentHashMap<String, Long>()
    @Volatile private var preDoneFor: String? = null

    var port: Int = 0
        private set

    /** Pornește pe [preferred] (dacă e liber) sau pe primul port liber din [PORTS]; întoarce adresa sau null. */
    fun start(preferred: Int): String? {
        val ip = PhoneSetupServer.localIp() ?: return null
        val candidates = (listOf(preferred).filter { it in PORTS } + PORTS).distinct()
        val server = candidates.firstNotNullOfOrNull { p ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } }.getOrNull()
        } ?: return null
        socket = server
        port = server.localPort
        job = container.scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                if (clients.incrementAndGet() > MAX_CLIENTS) {
                    clients.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                launch {
                    try {
                        runCatching { client.use { handle(it) } }
                    } finally {
                        clients.decrementAndGet()
                    }
                }
            }
        }
        return "http://$ip:$port/$token"
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        when (val r = SecondScreen.route(method, path, token)) {
            SecondScreen.Route.Page -> respond(client, 200, "text/html", SecondScreen.page())
            SecondScreen.Route.Now -> respond(client, 200, JSON, now())
            SecondScreen.Route.Matches -> respond(client, 200, JSON, matches())
            is SecondScreen.Route.Details -> respond(client, 200, JSON, details(r.matchId))
            is SecondScreen.Route.Play -> respond(client, 200, JSON, play(r.matchId))
            is SecondScreen.Route.Reveal -> {
                container.matchDetails.reveal(r.matchId)
                respond(client, 200, JSON, SecondScreen.ok("Scorul se vede acum și pe televizor."))
            }
            SecondScreen.Route.WrongMethod -> respond(client, 405, "text/plain", "405")
            SecondScreen.Route.NotFound -> respond(client, 404, "text/plain", "404")
        }
    }

    private fun row(e: MatchEntry): SecondScreen.Row {
        val s = container.sportsSettings.current
        val revealed = container.matchDetails.revealed.value
        val best = e.bestSource(s)
        return SecondScreen.Row(
            match = e.match,
            favorite = s.favorites.isFavorite(e.match),
            hideScore = Spoilers.hide(s.noSpoilers, e.match.id in revealed, e.match.status),
            sources = e.summary.count,
            sureSources = e.summary.sure,
            best = best?.let { "${it.name} · ${it.quality.label}" },
        )
    }

    private fun entry(id: String): MatchEntry? = container.sports.snapshot.value.matches.firstOrNull { it.match.id == id }

    private fun now(): String {
        val tv = container.onTv.value
        return SecondScreen.nowJson(tv?.channel, tv?.matchId?.let(::entry)?.let(::row))
    }

    private fun matches(): String {
        val t = System.currentTimeMillis()
        val rows = container.sports.snapshot.value.matches.filter { SecondScreen.shown(it.match, t) }.map(::row)
        return SecondScreen.matchesJson(SecondScreen.order(rows))
    }

    private fun details(id: String): String {
        val e = entry(id) ?: return SecondScreen.error("Meciul nu mai e în listă.")
        val details = container.matchDetails
        refreshFor(e.match)
        val s = container.sportsSettings.current
        val raw: MatchDetailsState = details.states.value[id] ?: MatchDetailsState(details = runCatching { details.read(e.match) }.getOrDefault(ro.ak.sportshub.core.sports.MatchDetails()))
        val shown = details.delayedDetails(e.match, System.currentTimeMillis(), s.streamDelaySeconds * 1_000L, raw.details)
        val r = row(e)
        val status = listOfNotNull(shown.event?.status, e.match.status).maxBy { it.ordinal }
        val hide = Spoilers.hide(s.noSpoilers, id in details.revealed.value, status)
        return SecondScreen.detailsJson(r.copy(hideScore = hide), shown, raw.loading)
    }

    /**
     * Cererile la furnizori pentru meciul deschis pe telefon, în fundal (răspunsul nu le așteaptă): Pre-meci o dată la
     * deschidere, Live cel mult o dată pe minut cât e live (sau a trecut ora de start). Repository-ul are mai departe
     * propriile pauze (fișierele salvate, `DetailsSchedule`).
     */
    private fun refreshFor(m: Match) {
        val t = System.currentTimeMillis()
        val last = lastRefresh[m.id] ?: 0L
        if (t - last < DetailsSchedule.LIVE_REFRESH_MS) return
        lastRefresh.keys.retainAll(setOf(m.id))
        lastRefresh[m.id] = t
        val first = preDoneFor != m.id
        preDoneFor = m.id
        container.scope.launch {
            runCatching {
                if (first) container.matchDetails.refreshPreMatch(m)
                if (m.status == MatchStatus.LIVE || (m.status == MatchStatus.SCHEDULED && t >= m.start)) {
                    container.matchDetails.refreshLive(m)
                }
            }
        }
    }

    private fun play(id: String): String {
        val e = entry(id) ?: return SecondScreen.error("Meciul nu mai e în listă.")
        if (e.bestSource(container.sportsSettings.current) == null) return SecondScreen.error("Meciul nu are surse în playlisturile tale.")
        // Navigarea (pe firul principal) o preia; răspunsul nu așteaptă pornirea stream-ului.
        val sent = container.phonePlay.tryEmit(id)
        return if (sent) SecondScreen.ok("Pornește pe televizor: ${e.match.title}.") else SecondScreen.error("Televizorul e ocupat, încearcă din nou.")
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (out.size() > MAX_LINE) return null
            out.write(b)
        }
    }

    companion object {
        /** Alte porturi decât configurarea prin QR ([PhoneSetupServer.PORTS]), ca amândouă să poată rula odată. */
        val PORTS = 8660..8670
        private const val MAX_CLIENTS = 4
        private const val MAX_LINE = 8_192
        private const val JSON = "application/json"
    }
}
