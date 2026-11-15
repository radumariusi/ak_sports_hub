package ro.ak.sportshub.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.sports.Confidence
import ro.ak.sportshub.core.sports.EpgBroadcast
import ro.ak.sportshub.core.sports.EventSlot
import ro.ak.sportshub.core.sports.Match
import ro.ak.sportshub.core.sports.MatchSource
import ro.ak.sportshub.core.sports.MatchSources
import ro.ak.sportshub.core.sports.MatchStatus
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.TvStation

/**
 * Lista Sports Hub salvată pe disc după fiecare calcul: la intrarea în aplicație (și după ce Android a închis
 * procesul) lista apare imediat din fișier, iar calculul se reface doar dacă s-a schimbat ceva (EPG, playlisturi,
 * furnizori, setări). Format binar propriu (fără bibliotecă): textul care se repetă (canale, competiții) e scris
 * o singură dată. Nu intră în backup ([android.content.Context.getNoBackupFilesDir]).
 */
class SportsSnapshotFile(private val file: File) {

    /** Lista citită, cu [key] = amprenta intrărilor din care a fost calculată. */
    class Stored(val snapshot: SportsSnapshot, val key: String)

    fun write(snapshot: SportsSnapshot, key: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            file.parentFile?.mkdirs()
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 64 * 1024)).use { out ->
                Writer(out).write(snapshot, key)
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { tmp.delete() }
    }

    fun read(): Stored? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()), 64 * 1024)).use { Reader(it).read() }
        }.getOrNull()
    }

    fun delete() {
        file.delete()
    }

    private class Writer(private val out: DataOutputStream) {
        private val strings = HashMap<String, Int>()
        private val channels = HashMap<Long, Int>()

        /** Un text: o dată întreg, apoi doar numărul lui. */
        private fun str(s: String?) {
            if (s == null) { out.writeInt(-1); return }
            val known = strings[s]
            if (known != null) { out.writeInt(known); return }
            strings[s] = strings.size
            out.writeInt(-2)
            out.writeUTF(s.take(MAX_UTF))
        }

        private fun optInt(v: Int?) {
            out.writeBoolean(v != null)
            if (v != null) out.writeInt(v)
        }

        private fun channel(c: ChannelWithPlaylist) {
            val known = channels[c.id]
            if (known != null) { out.writeInt(known); return }
            channels[c.id] = channels.size
            out.writeInt(-2)
            out.writeLong(c.id)
            out.writeLong(c.playlistId)
            str(c.name)
            str(c.groupName)
            out.writeByte(c.quality.ordinal)
            str(c.language)
            optInt(c.number)
            optInt(c.catchupDays)
            str(c.playlistName)
            str(c.groupKey)
        }

        private fun match(m: Match) {
            str(m.id)
            str(m.sport?.name)
            str(m.competition)
            str(m.home)
            str(m.away)
            out.writeLong(m.start)
            out.writeLong(m.end)
            out.writeByte(m.status.ordinal)
            optInt(m.homeScore)
            optInt(m.awayScore)
            str(m.venue)
            out.writeInt(m.stations.size)
            m.stations.forEach { str(it.name); str(it.country) }
            out.writeInt(m.broadcasts.size)
            m.broadcasts.forEach { b ->
                out.writeLong(b.programmeId)
                out.writeLong(b.epgChannelId)
                out.writeLong(b.start)
                out.writeLong(b.stop)
                str(b.title)
                str(b.subtitle)
                // Descrierea nu se arată în listă: doar începutul, ca fișierul să rămână mic.
                str(b.description?.take(DESCRIPTION_CHARS))
                str(b.category)
            }
            out.writeInt(m.providers.size)
            m.providers.forEach { str(it) }
            out.writeInt(m.eventChannels.size)
            m.eventChannels.forEach { out.writeLong(it) }
        }

        fun write(s: SportsSnapshot, key: String) {
            out.writeInt(VERSION)
            out.writeUTF(key.take(MAX_UTF))
            out.writeLong(s.computedAt)
            out.writeInt(s.unmatchedStations.size)
            s.unmatchedStations.forEach { str(it) }
            out.writeInt(s.emptyEventChannels)
            out.writeInt(s.sportChannelCount)
            out.writeInt(s.noEpgChannels.size)
            s.noEpgChannels.forEach { str(it.name); str(it.group); str(it.playlist); out.writeInt(it.variants) }
            out.writeInt(s.matches.size)
            s.matches.forEach { e ->
                match(e.match)
                out.writeInt(e.sources.size)
                e.sources.forEach { src ->
                    channel(src.channel)
                    str(src.groupKey)
                    str(src.language)
                    out.writeByte(src.quality.ordinal)
                    out.writeByte(src.confidence.ordinal)
                    str(src.station)
                    out.writeBoolean(src.rejected)
                    out.writeByte(src.origin.ordinal)
                    str(src.airing)
                }
            }
            out.writeInt(s.events.size)
            s.events.forEach { e ->
                channel(e.channel)
                val slot = e.slot
                out.writeLong(slot.channelId)
                str(slot.channelName)
                str(slot.group)
                str(slot.title)
                str(slot.home)
                str(slot.away)
                str(slot.sport?.name)
                str(slot.competition)
                out.writeBoolean(slot.start != null)
                slot.start?.let { out.writeLong(it) }
                str(e.matchId)
            }
        }
    }

    private class Reader(private val input: DataInputStream) {
        private val strings = ArrayList<String>()
        private val channels = ArrayList<ChannelWithPlaylist>()

        private fun str(): String? = when (val n = input.readInt()) {
            -1 -> null
            -2 -> input.readUTF().also { strings += it }
            else -> strings[n]
        }

        private fun text(): String = str().orEmpty()

        private fun optInt(): Int? = if (input.readBoolean()) input.readInt() else null

        private fun channel(): ChannelWithPlaylist {
            val n = input.readInt()
            if (n != -2) return channels[n]
            return ChannelWithPlaylist(
                id = input.readLong(),
                playlistId = input.readLong(),
                name = text(),
                groupName = text(),
                quality = QUALITIES[input.readUnsignedByte()],
                language = str(),
                number = optInt(),
                catchupDays = optInt(),
                playlistName = text(),
                groupKey = text(),
            ).also { channels += it }
        }

        private fun sport(): Sport? = str()?.let { n -> Sport.entries.firstOrNull { it.name == n } }

        private fun match(): Match {
            val id = text()
            val sport = sport()
            val competition = str()
            val home = text()
            val away = text()
            val start = input.readLong()
            val end = input.readLong()
            val status = MatchStatus.entries[input.readUnsignedByte()]
            val homeScore = optInt()
            val awayScore = optInt()
            val venue = str()
            val stations = List(input.readInt()) { TvStation(text(), str()) }
            val broadcasts = List(input.readInt()) {
                EpgBroadcast(
                    programmeId = input.readLong(),
                    epgChannelId = input.readLong(),
                    start = input.readLong(),
                    stop = input.readLong(),
                    title = text(),
                    subtitle = str(),
                    description = str(),
                    category = str(),
                )
            }
            val providers = List(input.readInt()) { text() }.toSet()
            val eventChannels = List(input.readInt()) { input.readLong() }
            return Match(id, sport, competition, home, away, start, end, status, homeScore, awayScore, venue, stations, broadcasts, providers, eventChannels)
        }

        fun read(): Stored? {
            if (input.readInt() != VERSION) return null
            val key = input.readUTF()
            val computedAt = input.readLong()
            val unmatched = List(input.readInt()) { text() }
            val emptyEvents = input.readInt()
            val sportChannels = input.readInt()
            val noEpg = List(input.readInt()) { NoEpgChannel(text(), text(), text(), input.readInt()) }
            val matches = List(input.readInt()) {
                val m = match()
                val sources = List(input.readInt()) {
                    MatchSource(
                        channel = channel(),
                        groupKey = text(),
                        language = str(),
                        quality = QUALITIES[input.readUnsignedByte()],
                        confidence = CONFIDENCES[input.readUnsignedByte()],
                        station = str(),
                        rejected = input.readBoolean(),
                        origin = CONFIDENCES[input.readUnsignedByte()],
                        airing = str(),
                    )
                }
                MatchEntry(m, sources, MatchSources.summary(sources))
            }
            val events = List(input.readInt()) {
                val channel = channel()
                val slot = EventSlot(
                    channelId = input.readLong(),
                    channelName = text(),
                    group = str(),
                    title = str(),
                    home = str(),
                    away = str(),
                    sport = sport(),
                    competition = str(),
                    start = if (input.readBoolean()) input.readLong() else null,
                )
                EventEntry(channel, slot, str())
            }
            return Stored(
                SportsSnapshot(
                    matches = matches,
                    computedAt = computedAt,
                    loading = false,
                    unmatchedStations = unmatched,
                    events = events,
                    emptyEventChannels = emptyEvents,
                    noEpgChannels = noEpg,
                    sportChannelCount = sportChannels,
                ),
                key,
            )
        }
    }

    companion object {
        /** Se mărește la orice schimbare de format: fișierul vechi se ignoră și lista se calculează din nou. */
        private const val VERSION = 2
        private const val MAX_UTF = 20_000
        private const val DESCRIPTION_CHARS = 300
        private val QUALITIES = Quality.entries
        private val CONFIDENCES = Confidence.entries
        private const val LIVE_GRACE_MS = 30 * 60_000L

        /**
         * Starea meciurilor la ora [now], fără niciun calcul: o listă salvată (sau calculată acum câteva minute)
         * rămâne corectă la LIVE / terminat. Ce a spus furnizorul (terminat) rămâne.
         */
        fun retime(s: SportsSnapshot, now: Long): SportsSnapshot {
            var changed = false
            val matches = s.matches.map { e ->
                val m = e.match
                // Un meci LIVE la furnizor rămâne LIVE încă 30 de minute după durata obișnuită (prelungiri, pauze),
                // ca la calcul; cele doar din EPG sau din playlist se termină odată cu emisiunea.
                val grace = if (m.status == MatchStatus.LIVE && m.providers.any { it != "epg" && it != "playlist" }) LIVE_GRACE_MS else 0L
                val status = when {
                    m.status == MatchStatus.FINISHED -> MatchStatus.FINISHED
                    now < m.start -> MatchStatus.SCHEDULED
                    now < m.end + grace -> MatchStatus.LIVE
                    else -> MatchStatus.FINISHED
                }
                if (status == m.status) e else { changed = true; e.copy(match = m.copy(status = status)) }
            }
            return if (changed) s.copy(matches = matches) else s
        }
    }
}
