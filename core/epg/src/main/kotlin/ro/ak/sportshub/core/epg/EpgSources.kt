package ro.ak.sportshub.core.epg

import java.net.URI
import java.security.MessageDigest

/** Un EPG extern adăugat de tine (adresă XMLTV sau .xml.gz). */
data class ExternalEpg(
    val id: String,
    val url: String,
    val name: String,
    val enabled: Boolean = true,
    val shiftMinutes: Int = 0,
)

/** Ce poți schimba la un EPG venit din playlist: dacă e folosit și decalajul orar. */
data class EpgOverride(val enabled: Boolean = true, val shiftMinutes: Int = 0)

/** Setările EPG, păstrate pe televizor (nu în baza de date, ca să nu se piardă la reconstruirea ei). */
data class EpgSettings(
    val policy: EpgUpdatePolicy = EpgUpdatePolicy(),
    val external: List<ExternalEpg> = emptyList(),
    val overrides: Map<String, EpgOverride> = emptyMap(),
)

/** Datele unui playlist de care are nevoie EPG-ul. */
data class PlaylistEpgInfo(
    val playlistId: Long,
    val name: String,
    val enabled: Boolean,
    /** Adresa playlistului sau serverul Xtream; doar gazda contează. */
    val url: String,
    val xtream: Boolean,
    /** `url-tvg` din M3U sau `xmltv.php` la Xtream. */
    val epgUrls: List<String>,
)

/** O sursă EPG gata de folosit. [url] poate conține date de acces: nu se loghează și nu se afișează. */
data class EpgSource(
    val key: String,
    val url: String,
    val label: String,
    /** Playlistul din care vine sursa; null pentru cele externe. */
    val playlistId: Long?,
    /**
     * Sursa e pe serverul furnizorului: descărcarea ține permisul playlistului din `ConnectionGate`
     * și se amână cât rulează playerul pe acel playlist.
     */
    val gated: Boolean,
    val enabled: Boolean,
    val shiftMinutes: Int,
    /** Ordinea între sursele de același fel. */
    val order: Int,
)

object EpgSources {

    fun resolve(playlists: List<PlaylistEpgInfo>, settings: EpgSettings): List<EpgSource> {
        val out = ArrayList<EpgSource>()
        for (p in playlists) {
            if (!p.enabled) continue
            val playlistHost = host(p.url)
            p.epgUrls.distinct().forEachIndexed { i, url ->
                val key = playlistKey(p.playlistId, url)
                val o = settings.overrides[key] ?: EpgOverride()
                val host = host(url)
                out += EpgSource(
                    key = key,
                    url = url,
                    label = if (p.xtream) "EPG furnizor · ${p.name}" else "${p.name} · ${host ?: "EPG"}",
                    playlistId = p.playlistId,
                    gated = p.xtream || (host != null && host == playlistHost),
                    enabled = o.enabled,
                    shiftMinutes = o.shiftMinutes,
                    order = i,
                )
            }
        }
        settings.external.forEachIndexed { i, e ->
            out += EpgSource(
                key = externalKey(e.id),
                url = e.url,
                label = e.name.ifBlank { host(e.url) ?: "EPG extern" },
                playlistId = null,
                gated = false,
                enabled = e.enabled,
                shiftMinutes = e.shiftMinutes,
                order = i,
            )
        }
        return out
    }

    /**
     * Prioritatea surselor pentru canalele playlistului [playlistId] (mai mic = mai bun):
     * EPG-urile proprii ale playlistului, apoi cele externe, apoi ale altor playlisturi.
     */
    fun priorityFor(playlistId: Long, sources: List<EpgSource>): (String) -> Int {
        val map = sources.associate { s ->
            s.key to when (s.playlistId) {
                playlistId -> s.order
                null -> 1_000 + s.order
                else -> 2_000 + s.order
            }
        }
        return { key -> map[key] ?: Int.MAX_VALUE }
    }

    fun playlistKey(playlistId: Long, url: String) = "p$playlistId-" + sha1(url).take(12)

    fun externalKey(id: String) = "x$id"

    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
