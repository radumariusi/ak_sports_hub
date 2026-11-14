package ro.ak.sportshub.core.model

/** Un playlist configurat de utilizator. Datele de acces nu se loghează și nu intră în repo. */
sealed interface PlaylistSource {
    val id: String
    val name: String
    val enabled: Boolean

    /** Câte conexiuni simultane permite furnizorul. Playlisturile lui Radu: 1. */
    val maxConnections: Int

    data class M3u(
        override val id: String,
        override val name: String,
        val url: String,
        val epgUrl: String? = null,
        val userAgent: String? = null,
        override val enabled: Boolean = true,
        override val maxConnections: Int = 1,
    ) : PlaylistSource

    data class Xtream(
        override val id: String,
        override val name: String,
        val serverUrl: String,
        val username: String,
        val password: String,
        override val enabled: Boolean = true,
        override val maxConnections: Int = 1,
    ) : PlaylistSource
}
