package ro.ak.sportshub.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import ro.ak.sportshub.core.model.Quality

/** Tipul unui playlist. */
enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

/**
 * Un playlist configurat. Datele de acces (adresă, utilizator, parolă) rămân doar pe televizor:
 * nu se loghează și nu ies din aplicație (backup-ul din etapa 9 le va trata separat).
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** Adresa M3U, URI-ul fișierului ales sau serverul Xtream. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val userAgent: String? = null,
    /** Doar Xtream: `ts` sau `m3u8`. */
    val streamFormat: String = "ts",
    val enabled: Boolean = true,
    val maxConnections: Int = 1,
    /** La câte ore se actualizează automat; 0 = doar manual. */
    val updateIntervalHours: Int = 24,
    val lastUpdated: Long? = null,
    val lastAttempt: Long? = null,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val channelCount: Int = 0,
    val skippedCount: Int = 0,
    /** EPG-urile declarate de playlist, câte unul pe linie (folosite în etapa 4). */
    val epgUrls: String? = null,
    /** Doar Xtream: data expirării contului (secunde epoch). */
    val expiresAt: Long? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "channel_groups",
    indices = [Index("playlistId")],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val position: Int,
    val channelCount: Int = 0,
    val searchText: String,
)

@Entity(
    tableName = "channels",
    indices = [Index("playlistId"), Index("groupId"), Index("groupKey"), Index("tvgId")],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val groupId: Long,
    /** Numele original din playlist; așa se afișează. */
    val name: String,
    val groupName: String,
    val streamUrl: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val quality: Quality,
    val language: String?,
    val country: String?,
    /** Doar pentru gruparea variantelor (etapa 5); nu se afișează. */
    val groupKey: String,
    val catchupDays: Int?,
    val userAgent: String?,
    val number: Int?,
    val position: Int,
    @ColumnInfo(name = "searchText") val searchText: String,
)

/** Indexul de căutare (FTS4) peste numele canalelor; Room îl ține sincron cu `channels`. */
@Fts4(contentEntity = ChannelEntity::class)
@Entity(tableName = "channels_fts")
data class ChannelFts(
    @ColumnInfo(name = "searchText") val searchText: String,
)

/** Un grup, cu numele playlistului din care vine. */
data class GroupWithPlaylist(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val position: Int,
    val channelCount: Int,
    val searchText: String,
    val playlistName: String,
)

/** Un canal, cu numele playlistului din care vine. */
data class ChannelWithPlaylist(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val groupName: String,
    val quality: Quality,
    val language: String?,
    val number: Int?,
    val catchupDays: Int?,
    val playlistName: String,
)

/** Un canal pregătit pentru player (cu user-agentul efectiv și starea playlistului). */
data class PlayableChannel(
    val id: Long,
    val playlistId: Long,
    val groupId: Long,
    val name: String,
    val groupName: String,
    val streamUrl: String,
    val quality: Quality,
    val language: String?,
    val number: Int?,
    val catchupDays: Int?,
    val userAgent: String?,
    val playlistName: String,
    val playlistEnabled: Boolean,
)
