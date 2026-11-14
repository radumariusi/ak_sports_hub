package ro.ak.sportshub.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.model.ChannelCorrection
import ro.ak.sportshub.core.model.ChannelLabels
import ro.ak.sportshub.core.playlist.ImportSink
import ro.ak.sportshub.core.playlist.ImportedChannel
import ro.ak.sportshub.core.playlist.PlaylistHeader

@Database(
    entities = [PlaylistEntity::class, GroupEntity::class, ChannelEntity::class, ChannelFts::class],
    version = 1,
    exportSchema = false,
)
abstract class AkDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao

    companion object {
        fun create(context: Context): AkDatabase =
            Room.databaseBuilder(context, AkDatabase::class.java, "ak-sports-hub.db")
                // Până la prima versiune stabilă, o schimbare de schemă reface baza
                // (playlisturile se reîncarcă singure de pe server).
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/**
 * Salvează canalele unui playlist în Room, înlocuindu-le atomic pe cele vechi.
 * [corrections] dă corecțiile manuale ale playlistului (după numele original al canalului),
 * aplicate din nou la fiecare import.
 */
class RoomImportSink(
    private val db: AkDatabase,
    private val corrections: (playlistId: Long) -> Map<String, ChannelCorrection> = { emptyMap() },
) : ImportSink {

    override suspend fun replaceChannels(
        playlistId: String,
        header: PlaylistHeader,
        channels: Sequence<ImportedChannel>,
    ): Int {
        val pid = playlistId.toLong()
        val dao = db.channels()
        val fixes = corrections(pid)
        return db.withTransaction {
            dao.deleteChannels(pid)
            dao.deleteGroups(pid)

            val groupIds = HashMap<String, Long>()
            val batch = ArrayList<ChannelEntity>(BATCH)
            var count = 0
            for (c in channels) {
                val groupName = c.source.group ?: NO_GROUP
                val groupId = groupIds[groupName] ?: dao.insertGroup(
                    GroupEntity(
                        playlistId = pid,
                        name = groupName,
                        position = groupIds.size,
                        searchText = SearchText.of(groupName),
                    ),
                ).also { groupIds[groupName] = it }

                val labels = ChannelLabels(c.groupKey, c.quality, c.language).let { l ->
                    fixes[c.source.name]?.applyTo(l) ?: l
                }
                batch += ChannelEntity(
                    playlistId = pid,
                    groupId = groupId,
                    name = c.source.name,
                    groupName = groupName,
                    streamUrl = c.source.streamUrl,
                    tvgId = c.source.tvgId,
                    tvgName = c.source.tvgName,
                    logo = c.source.logo,
                    quality = labels.quality,
                    language = labels.language,
                    country = c.country,
                    groupKey = labels.groupKey,
                    catchupDays = c.source.catchupDays,
                    userAgent = c.source.userAgent,
                    number = c.source.number,
                    position = c.position,
                    searchText = c.searchText,
                )
                count++
                if (batch.size >= BATCH) {
                    dao.insertChannels(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.insertChannels(batch)
            dao.updateGroupCounts(pid)
            count
        }
    }

    companion object {
        const val NO_GROUP = "Fără grup"
        private const val BATCH = 500
    }
}
