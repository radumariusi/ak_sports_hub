package ro.ak.sportshub.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET lastAttempt = :at, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, at: Long, error: String?)
}

@Dao
interface ChannelDao {

    @Query(
        """
        SELECT g.id, g.playlistId, g.name, g.position, g.channelCount, g.searchText, p.name AS playlistName
        FROM channel_groups g JOIN playlists p ON p.id = g.playlistId
        WHERE p.enabled = 1
        ORDER BY p.sortOrder, p.id, g.position
        """,
    )
    fun observeGroups(): Flow<List<GroupWithPlaylist>>

    @Query(
        """
        SELECT c.id, c.playlistId, c.name, c.groupName, c.quality, c.language, c.number, c.catchupDays,
               p.name AS playlistName
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE c.groupId = :groupId
        ORDER BY c.position
        """,
    )
    fun observeChannelsInGroup(groupId: Long): Flow<List<ChannelWithPlaylist>>

    /** Căutare FTS: [ftsQuery] vine din `SearchText.ftsQuery` (de exemplu `sky* main*`). */
    @Query(
        """
        SELECT c.id, c.playlistId, c.name, c.groupName, c.quality, c.language, c.number, c.catchupDays,
               p.name AS playlistName
        FROM channels c
        JOIN channels_fts ON c.id = channels_fts.rowid
        JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND channels_fts MATCH :ftsQuery
        ORDER BY c.searchText, p.sortOrder, c.position
        LIMIT :limit
        """,
    )
    fun search(ftsQuery: String, limit: Int): Flow<List<ChannelWithPlaylist>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Insert
    suspend fun insertGroup(group: GroupEntity): Long

    @Insert
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannels(playlistId: Long)

    @Query("DELETE FROM channel_groups WHERE playlistId = :playlistId")
    suspend fun deleteGroups(playlistId: Long)

    @Query(
        """
        UPDATE channel_groups SET channelCount =
            (SELECT COUNT(*) FROM channels WHERE channels.groupId = channel_groups.id)
        WHERE playlistId = :playlistId
        """,
    )
    suspend fun updateGroupCounts(playlistId: Long)
}
