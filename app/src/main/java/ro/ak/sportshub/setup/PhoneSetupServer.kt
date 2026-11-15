package ro.ak.sportshub.setup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.ak.sportshub.core.common.PhoneSetup

/**
 * Serverul HTTP mic pentru configurarea din telefon: ascultă în rețeaua locală doar cât e deschis ecranul cu codul QR.
 * Răspunde doar la adresa cu codul aleator ([token]); o cerere pe rând, fără nimic scris în loguri.
 * La trimiterea formularului, [onSubmit] primește playlistul (pe firul serverului).
 */
class PhoneSetupServer(
    private val token: String,
    private val onSubmit: (PhoneSetup.Submission) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    /** Adresa de deschis pe telefon (null dacă televizorul nu e într-o rețea locală). */
    var url: String? = null
        private set

    /** Pornește pe primul port liber din [PORTS]; întoarce adresa sau null. */
    fun start(scope: CoroutineScope): String? {
        val ip = localIp() ?: return null
        val server = PORTS.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return null
        socket = server
        url = "http://$ip:${server.localPort}/$token"
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                runCatching { client.use { handle(it) } }
            }
        }
        return url
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
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trimEnd('/')
        if (path != "/$token") return respond(client, 404, "<h1>404</h1>")
        when (method) {
            "GET" -> respond(client, 200, PhoneSetup.formPage())
            "POST" -> {
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val form = PhoneSetup.parseForm(String(body, 0, read, Charsets.UTF_8))
                PhoneSetup.read(form).fold(
                    onSuccess = { s ->
                        respond(client, 200, PhoneSetup.donePage(s.name.ifBlank { "Playlist" }))
                        onSubmit(s)
                    },
                    onFailure = { e -> respond(client, 200, PhoneSetup.formPage(e.message, form - "password")) },
                )
            }
            else -> respond(client, 405, "<h1>405</h1>")
        }
    }

    private fun respond(client: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
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
        val PORTS = 8642..8652
        private const val MAX_BODY = 16_384
        private const val MAX_LINE = 8_192

        /** Adresa IPv4 locală a televizorului (Wi-Fi sau cablu). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
