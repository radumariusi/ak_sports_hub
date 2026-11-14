package ro.ak.sportshub.core.playlist

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Adresele API-ului Xtream Codes. Conțin parola: nu se loghează. */
class XtreamEndpoints(server: String, private val username: String, private val password: String) {

    val base: HttpUrl = normalizeServer(server)
        ?: throw PlaylistException("Adresa serverului Xtream nu e validă.")

    fun account(): HttpUrl = api(null)
    fun liveCategories(): HttpUrl = api("get_live_categories")
    fun liveStreams(): HttpUrl = api("get_live_streams")

    /** EPG-ul furnizorului (folosit în etapa 4). */
    fun xmltv(): String = base.newBuilder()
        .addPathSegment("xmltv.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()

    /** Adresa de redare a unui canal live. Formatul (`ts` sau `m3u8`) e o setare a playlistului. */
    fun liveStreamUrl(streamId: String, format: String): String =
        base.newBuilder()
            .addPathSegment("live")
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment("$streamId.$format")
            .build()
            .toString()

    private fun api(action: String?): HttpUrl =
        base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()

    companion object {
        /** „host:8080”, „http://host:8080/”, „http://host:8080/player_api.php” → „http://host:8080/”. */
        fun normalizeServer(input: String): HttpUrl? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
                .filterNot { it.endsWith(".php") || it == "get.php" }
            return url.newBuilder().query(null).fragment(null)
                .encodedPath("/")
                .apply { segments.forEach { addPathSegment(it) } }
                .build()
        }
    }
}

@Serializable
data class XtreamCategory(
    @SerialName("category_id") val id: JsonPrimitive? = null,
    @SerialName("category_name") val name: String? = null,
)

@Serializable
data class XtreamStream(
    val num: JsonPrimitive? = null,
    val name: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("stream_id") val streamId: JsonPrimitive? = null,
    @SerialName("stream_icon") val icon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: JsonPrimitive? = null,
    @SerialName("tv_archive") val tvArchive: JsonPrimitive? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: JsonPrimitive? = null,
)

@Serializable
data class XtreamUserInfo(
    val auth: JsonPrimitive? = null,
    val status: String? = null,
    @SerialName("exp_date") val expDate: JsonPrimitive? = null,
    @SerialName("max_connections") val maxConnections: JsonPrimitive? = null,
    @SerialName("active_cons") val activeConnections: JsonPrimitive? = null,
    val message: String? = null,
)

@Serializable
data class XtreamServerInfo(val timezone: String? = null)

@Serializable
data class XtreamAccountResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

/** Starea contului, afișată la adăugarea playlistului. */
data class XtreamAccount(
    val status: String?,
    /** Data expirării (secunde epoch), dacă serverul o dă. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    /** Fusul orar al serverului (de exemplu `Europe/London`), pentru adresele de catch-up. */
    val timezone: String? = null,
)

object XtreamParser {

    private const val MARK_LIMIT = 1 shl 16

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val JsonPrimitive?.text: String? get() = this?.contentOrNull?.trim()?.ifEmpty { null }
    private val JsonPrimitive?.int: Int? get() = text?.toDoubleOrNull()?.toInt()

    fun account(body: String): XtreamAccount {
        val response = runCatching { json.decodeFromString(XtreamAccountResponse.serializer(), body) }.getOrNull()
        val info = response?.userInfo
            ?: throw PlaylistException("Serverul nu a răspuns ca un server Xtream. Verifică adresa.")
        val auth = info.auth.text
        if (auth != null && auth != "1" && !auth.equals("true", ignoreCase = true)) {
            throw PlaylistException("Utilizator sau parolă greșite.")
        }
        return XtreamAccount(
            status = info.status,
            expiresAt = info.expDate.text?.toLongOrNull(),
            maxConnections = info.maxConnections.int,
            activeConnections = info.activeConnections.int,
            timezone = response.serverInfo?.timezone?.trim()?.ifEmpty { null },
        )
    }

    fun categories(input: InputStream): Map<String, String> = decodeArray(input, XtreamCategory.serializer())
        .mapNotNull { c -> c.id.text?.let { id -> id to (c.name?.trim().orEmpty()) } }
        .toMap()

    /**
     * Canalele live, în flux. `get_live_streams` poate avea zeci de MB, deci nu se citește întreg.
     * Intrările fără id sau fără nume, sau marcate ca VOD de server, sunt sărite.
     */
    fun streams(
        input: InputStream,
        categories: Map<String, String>,
        endpoints: XtreamEndpoints,
        format: String,
    ): Sequence<ParsedChannel> = decodeArray(input, XtreamStream.serializer()).mapNotNull { s ->
        val id = s.streamId.text ?: return@mapNotNull null
        val name = s.name?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        val type = s.streamType?.lowercase()
        if (type != null && type != "live" && type != "created_live" && type != "radio_streams") return@mapNotNull null
        ParsedChannel(
            name = name,
            group = s.categoryId.text?.let { categories[it] }?.ifEmpty { null },
            streamUrl = endpoints.liveStreamUrl(id, format),
            tvgId = s.epgChannelId?.trim()?.ifEmpty { null },
            logo = s.icon?.trim()?.ifEmpty { null },
            catchupDays = if (s.tvArchive.text == "1") s.tvArchiveDuration.int?.takeIf { it > 0 } else null,
            number = s.num.int,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decodeArray(input: InputStream, serializer: kotlinx.serialization.KSerializer<T>): Sequence<T> {
        val buffered = input.buffered()
        buffered.mark(MARK_LIMIT)
        var c = buffered.read()
        while (c != -1 && c.toChar().isWhitespace()) c = buffered.read()
        if (c != '['.code) {
            // Unele servere întorc `{}`, `null` sau o pagină de eroare în loc de listă goală.
            if (c == '{'.code || c == 'n'.code || c == -1) return emptySequence()
            throw PlaylistException("Serverul Xtream a trimis un răspuns neașteptat.")
        }
        buffered.reset()
        return json.decodeToSequence(buffered, serializer, DecodeSequenceMode.ARRAY_WRAPPED)
    }
}
