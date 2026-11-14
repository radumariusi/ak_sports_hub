package ro.ak.sportshub.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ro.ak.sportshub.core.model.Quality

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
               p.name AS playlistName, c.groupKey
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
               p.name AS playlistName, c.groupKey
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

    /** Tot ce îi trebuie playerului pentru un canal. */
    @Query(
        """
        SELECT c.id, c.playlistId, c.groupId, c.name, c.groupName, c.streamUrl, c.quality, c.language,
               c.number, c.catchupDays, COALESCE(c.userAgent, p.userAgent) AS userAgent,
               p.name AS playlistName, p.enabled AS playlistEnabled, c.groupKey
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE c.id = :id
        """,
    )
    suspend fun playable(id: Long): PlayableChannel?

    /** Canalele unui grup, o singură dată (lista din player). */
    @Query(
        """
        SELECT c.id, c.playlistId, c.name, c.groupName, c.quality, c.language, c.number, c.catchupDays,
               p.name AS playlistName, c.groupKey
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE c.groupId = :groupId
        ORDER BY c.position
        """,
    )
    suspend fun channelsInGroup(groupId: Long): List<ChannelWithPlaylist>

    /** Ordinea pentru zapping (CH+/CH−): canalele grupului, ca în playlist. */
    @Query("SELECT id FROM channels WHERE groupId = :groupId ORDER BY position")
    suspend fun channelIdsInGroup(groupId: Long): List<Long>

    /** Canalul cu numărul [number], de preferat din [preferredPlaylistId]. */
    @Query(
        """
        SELECT c.id FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND c.number = :number
        ORDER BY (c.playlistId = :preferredPlaylistId) DESC, p.sortOrder, p.id, c.position
        LIMIT 1
        """,
    )
    suspend fun findByNumber(number: Int, preferredPlaylistId: Long): Long?

    /**
     * Regăsește un canal după actualizarea playlistului (id-urile se schimbă la fiecare import):
     * după nume și grup, apoi doar după nume.
     */
    @Query(
        """
        SELECT id FROM channels
        WHERE playlistId = :playlistId AND name = :name
        ORDER BY (groupName = :groupName) DESC, position
        LIMIT 1
        """,
    )
    suspend fun findByName(playlistId: Long, name: String, groupName: String): Long?

    /**
     * Toate variantele canalelor cu cheile [groupKeys], din playlisturile active, în ordinea
     * playlisturilor și apoi a poziției (ordinea finală o dă `VariantOrder`).
     * Cel mult ~900 de chei pe apel (limita SQLite de parametri).
     */
    @Query(
        """
        SELECT c.id, c.playlistId, c.name, c.groupName, c.quality, c.language, c.number, c.catchupDays,
               p.name AS playlistName, c.groupKey
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND c.groupKey IN (:groupKeys)
        ORDER BY p.sortOrder, p.id, c.position
        """,
    )
    suspend fun variants(groupKeys: List<String>): List<ChannelWithPlaylist>

    /** Canalele cu id-urile [ids], din playlisturile active (cel mult ~900 pe apel). */
    @Query(
        """
        SELECT c.id, c.playlistId, c.name, c.groupName, c.quality, c.language, c.number, c.catchupDays,
               p.name AS playlistName, c.groupKey
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND c.id IN (:ids)
        """,
    )
    suspend fun byIds(ids: List<Long>): List<ChannelWithPlaylist>

    /** Toate cheile de grupare din playlisturile active (pentru potrivirea pe nume de post). */
    @Query("SELECT DISTINCT c.groupKey FROM channels c JOIN playlists p ON p.id = c.playlistId WHERE p.enabled = 1")
    suspend fun activeGroupKeys(): List<String>

    /**
     * Canale care ar putea fi de sport, după nume și grup (filtrul fin e `SportChannels`).
     * LIKE în SQLite nu ține cont de literele mari pentru ASCII.
     */
    @Query(
        """
        SELECT c.id, c.name, c.groupName FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND (
            c.name LIKE '%sport%' OR c.groupName LIKE '%sport%' OR c.name LIKE '%espn%' OR c.name LIKE '%dazn%'
            OR c.name LIKE '%bein%' OR c.name LIKE '%arena%' OR c.name LIKE '%football%' OR c.name LIKE '%fotbal%'
            OR c.name LIKE '%futbol%' OR c.name LIKE '%calcio%' OR c.name LIKE '%soccer%' OR c.name LIKE '%nba%'
            OR c.name LIKE '%nfl%' OR c.name LIKE '%nhl%' OR c.name LIKE '%mlb%' OR c.name LIKE '%f1%'
            OR c.name LIKE '%motogp%' OR c.name LIKE '%tennis%' OR c.name LIKE '%golf%' OR c.name LIKE '%ufc%'
            OR c.name LIKE '%ppv%' OR c.name LIKE '%event%' OR c.name LIKE '%match%' OR c.name LIKE '%laliga%'
            OR c.name LIKE '%movistar%' OR c.name LIKE '%viaplay%' OR c.name LIKE '%setanta%'
            OR c.groupName LIKE '%football%' OR c.groupName LIKE '%ppv%' OR c.groupName LIKE '%event%'
        )
        """,
    )
    suspend fun sportChannelCandidates(): List<ChannelNameRow>

    /** Aplică pe loc o corecție manuală (sau o anulează) tuturor intrărilor cu acest nume din playlist. */
    @Query(
        """
        UPDATE channels SET groupKey = :groupKey, quality = :quality, language = :language
        WHERE playlistId = :playlistId AND name = :name
        """,
    )
    suspend fun relabel(playlistId: Long, name: String, groupKey: String, quality: Quality, language: String?)

    /** Canalele playlisturilor active, pe pagini (pentru potrivirea cu EPG-ul), după [afterId]. */
    @Query(
        """
        SELECT c.id, c.playlistId, c.tvgId, c.tvgName, c.name, c.groupName
        FROM channels c JOIN playlists p ON p.id = c.playlistId
        WHERE p.enabled = 1 AND c.id > :afterId
        ORDER BY c.id
        LIMIT :limit
        """,
    )
    suspend fun channelsForEpg(afterId: Long, limit: Int): List<ChannelEpgRow>

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
