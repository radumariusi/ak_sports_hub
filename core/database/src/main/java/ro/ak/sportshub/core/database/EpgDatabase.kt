package ro.ak.sportshub.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Programul TV, într-o bază separată de playlisturi: e doar un cache (se reface din fișierele EPG),
 * deci o schimbare de schemă o poate reconstrui fără să atingă playlisturile, iar scrierile mari
 * (sute de mii de emisiuni) nu blochează listele de canale.
 * Setările surselor EPG (adrese externe, decalaj, activ) nu stau aici, ci în preferințe.
 */
@Database(
    entities = [EpgSourceState::class, EpgChannelEntity::class, ProgrammeEntity::class, EpgMapEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class EpgDatabase : RoomDatabase() {
    abstract fun epg(): EpgDao

    companion object {
        fun create(context: Context): EpgDatabase =
            Room.databaseBuilder(context, EpgDatabase::class.java, "ak-epg.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** Starea ultimei actualizări a unei surse EPG (cheia vine din `EpgSources`). */
@Entity(tableName = "epg_source_state")
data class EpgSourceState(
    @PrimaryKey val sourceKey: String,
    val lastUpdated: Long? = null,
    val lastAttempt: Long? = null,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Canalele EPG păstrate (doar cele folosite de playlisturi). */
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    /** Cu ce condiții s-a citit fișierul ultima oară; dacă diferă, se citește din nou (fără descărcare). */
    val parseSignature: String? = null,
)

/** Un canal dintr-un EPG. Numele afișate sunt pe linii separate. */
@Entity(tableName = "epg_channels", indices = [Index("sourceKey")])
data class EpgChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val xmltvId: String,
    val displayNames: String,
)

@Entity(
    tableName = "programmes",
    indices = [Index(value = ["epgChannelId", "start"]), Index("stop")],
    foreignKeys = [
        ForeignKey(
            entity = EpgChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["epgChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgChannelId: Long,
    val start: Long,
    /** 0 până se completează din emisiunea următoare (unele EPG-uri nu dau ora de sfârșit). */
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/**
 * Canal din playlist → canal EPG. Id-urile canalelor se schimbă la fiecare import,
 * deci tabelul se reface pentru playlistul respectiv după fiecare import și după fiecare EPG nou.
 */
@Entity(
    tableName = "epg_map",
    indices = [Index("epgChannelId"), Index("playlistId")],
    foreignKeys = [
        ForeignKey(
            entity = EpgChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["epgChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpgMapEntity(
    @PrimaryKey val channelId: Long,
    val playlistId: Long,
    val epgChannelId: Long,
)

/** O emisiune a unui canal din playlist (pentru acum / urmează). */
data class ChannelProgrammeRow(
    val channelId: Long,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val category: String?,
)

@Dao
interface EpgDao {

    @Query("SELECT * FROM epg_source_state")
    fun observeStates(): Flow<List<EpgSourceState>>

    @Query("SELECT * FROM epg_source_state WHERE sourceKey = :key")
    suspend fun state(key: String): EpgSourceState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: EpgSourceState)

    @Query("DELETE FROM epg_source_state WHERE sourceKey NOT IN (:keys)")
    suspend fun deleteStatesExcept(keys: List<String>)

    @Query("DELETE FROM epg_source_state")
    suspend fun deleteAllStates()

    // Scrierea unui EPG se face sincron, într-o singură tranzacție, din parserul SAX.

    @Query("DELETE FROM epg_channels WHERE sourceKey = :key")
    fun deleteChannelsBlocking(key: String)

    @Insert
    fun insertChannelBlocking(channel: EpgChannelEntity): Long

    @Insert
    fun insertProgrammesBlocking(programmes: List<ProgrammeEntity>)

    /** Orele de sfârșit lipsă: începutul emisiunii următoare de pe același canal, altfel o oră. */
    @Query(
        """
        UPDATE programmes SET stop = COALESCE(
            (SELECT MIN(p2.start) FROM programmes p2
             WHERE p2.epgChannelId = programmes.epgChannelId AND p2.start > programmes.start),
            start + 3600000)
        WHERE stop = 0 AND epgChannelId IN (SELECT id FROM epg_channels WHERE sourceKey = :key)
        """,
    )
    fun fillMissingStopsBlocking(key: String)

    @Query("DELETE FROM epg_channels WHERE sourceKey NOT IN (:keys)")
    suspend fun deleteChannelsExcept(keys: List<String>)

    @Query("DELETE FROM epg_channels")
    suspend fun deleteAllChannels()

    @Query("SELECT * FROM epg_channels")
    suspend fun allChannels(): List<EpgChannelEntity>

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    // Potrivirea canal ↔ EPG

    @Query("DELETE FROM epg_map WHERE playlistId = :playlistId")
    suspend fun deleteMap(playlistId: Long)

    @Query("DELETE FROM epg_map WHERE playlistId NOT IN (:playlistIds)")
    suspend fun deleteMapExcept(playlistIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMap(rows: List<EpgMapEntity>)

    @Query("SELECT COUNT(*) FROM epg_map")
    fun observeMappedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM epg_map WHERE epgChannelId IN (SELECT id FROM epg_channels WHERE sourceKey = :key)")
    suspend fun mappedCount(key: String): Int

    @Query("SELECT epgChannelId FROM epg_map WHERE channelId = :channelId")
    suspend fun epgChannelFor(channelId: Long): Long?

    /** Emisiunile dintre [from] și [until] ale canalelor [channelIds] (cel mult ~500 odată). */
    @Query(
        """
        SELECT m.channelId, p.start, p.stop, p.title, p.subtitle, p.category
        FROM epg_map m JOIN programmes p ON p.epgChannelId = m.epgChannelId
        WHERE m.channelId IN (:channelIds) AND p.stop > :from AND p.start < :until
        ORDER BY m.channelId, p.start
        """,
    )
    suspend fun programmesFor(channelIds: List<Long>, from: Long, until: Long): List<ChannelProgrammeRow>

    /** Programul complet al unui canal EPG, de la [from]. */
    @Query("SELECT * FROM programmes WHERE epgChannelId = :epgChannelId AND stop > :from ORDER BY start")
    suspend fun schedule(epgChannelId: Long, from: Long): List<ProgrammeEntity>
}
