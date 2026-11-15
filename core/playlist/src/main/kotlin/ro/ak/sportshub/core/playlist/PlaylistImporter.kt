package ro.ak.sportshub.core.playlist

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.ak.sportshub.core.common.ChannelNameParser
import ro.ak.sportshub.core.common.ConnectionGate
import ro.ak.sportshub.core.common.Languages
import ro.ak.sportshub.core.common.LoadReporter
import ro.ak.sportshub.core.common.SearchText
import ro.ak.sportshub.core.model.Quality

/** De unde se încarcă un playlist. Câmpurile conțin date de acces: nu se loghează. */
sealed interface ImportSource {
    val playlistId: String

    data class M3uUrl(override val playlistId: String, val url: String, val userAgent: String?) : ImportSource

    /** Fișier ales de pe dispozitiv (de exemplu de pe un stick USB). Nu folosește rețeaua. */
    data class M3uLocal(override val playlistId: String, val open: () -> InputStream) : ImportSource

    data class Xtream(
        override val playlistId: String,
        val server: String,
        val username: String,
        val password: String,
        /** `ts` sau `m3u8`. */
        val format: String = "ts",
        val userAgent: String? = null,
    ) : ImportSource
}

/** O intrare live gata de salvat: datele din playlist plus ce deducem din ele (fără să citim stream-ul). */
data class ImportedChannel(
    val source: ParsedChannel,
    /** Ordinea din playlist. */
    val position: Int,
    /** Doar pentru gruparea variantelor; nu se afișează. */
    val groupKey: String,
    val quality: Quality,
    val country: String?,
    val language: String?,
    /** Textul indexat pentru căutare. */
    val searchText: String,
)

/** Unde ajung canalele. Implementarea (Room) înlocuiește atomic canalele vechi ale playlistului. */
fun interface ImportSink {
    /** @return câte canale au fost salvate */
    suspend fun replaceChannels(playlistId: String, header: PlaylistHeader, channels: Sequence<ImportedChannel>): Int
}

sealed interface ImportOutcome {
    /** Playlistul e folosit acum (de exemplu de player); actualizarea s-a amânat. */
    data object Busy : ImportOutcome

    /** Serverul spune că fișierul nu s-a schimbat de la ultima descărcare. */
    data object NotModified : ImportOutcome

    data class Imported(
        val channels: Int,
        val skipped: Int,
        val tag: CacheTag?,
        val epgUrls: List<String>,
        val account: XtreamAccount? = null,
    ) : ImportOutcome
}

/**
 * Importul unui playlist în doi pași:
 * 1. **descărcare** (singurul pas care folosește rețeaua): ține permisul playlistului din [ConnectionGate],
 *    cererile sunt secvențiale, iar fișierele merg în [workDir]; conexiunea se eliberează imediat după;
 * 2. **parsare** din fișiere, în flux, cu salvare în loturi prin [ImportSink].
 */
class PlaylistImporter(
    private val downloader: PlaylistDownloader,
    private val gate: ConnectionGate,
    private val workDir: File,
) {

    /**
     * @param waitForConnection true la actualizarea pornită de tine (așteaptă să se elibereze playlistul);
     *        false la actualizarea programată (renunță dacă playlistul e ocupat).
     * @param previous antetele de la ultima descărcare; null forțează descărcarea completă.
     */
    suspend fun import(
        source: ImportSource,
        sink: ImportSink,
        previous: CacheTag? = null,
        waitForConnection: Boolean = true,
        /** Progresul pentru ecran: pasul, octeții descărcați și canalele citite. */
        report: LoadReporter = LoadReporter.NONE,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val files = mutableListOf<File>()
        fun temp(name: String) = File(workDir, "pl-${source.playlistId.filter(Char::isLetterOrDigit)}-$name.tmp")
            .also { files += it }
        try {
            when (source) {
                is ImportSource.M3uLocal -> importM3u(source.playlistId, source.open(), sink, null, report)

                is ImportSource.M3uUrl -> {
                    val file = temp("m3u")
                    report.phase("așteaptă conexiunea")
                    val result = network(source, waitForConnection) {
                        report.phase("se descarcă playlistul")
                        downloader.download(source.url, file, source.userAgent, previous, onBytes = report::bytes)
                    } ?: return@withContext ImportOutcome.Busy
                    when (result) {
                        PlaylistDownloader.Result.NotModified -> ImportOutcome.NotModified
                        is PlaylistDownloader.Result.Saved ->
                            importM3u(source.playlistId, file.inputStream(), sink, result.tag, report)
                    }
                }

                is ImportSource.Xtream -> {
                    val endpoints = XtreamEndpoints(source.server, source.username, source.password)
                    val categoriesFile = temp("cat")
                    val streamsFile = temp("live")
                    report.phase("așteaptă conexiunea")
                    val account = network(source, waitForConnection) {
                        report.phase("se verifică contul")
                        val account = XtreamParser.account(downloader.fetchText(endpoints.account(), source.userAgent))
                        report.phase("se descarcă grupurile")
                        downloader.download(
                            endpoints.liveCategories().toString(), categoriesFile, source.userAgent, onBytes = report::bytes,
                        )
                        report.phase("se descarcă lista de canale")
                        downloader.download(
                            endpoints.liveStreams().toString(), streamsFile, source.userAgent, onBytes = report::bytes,
                        )
                        account
                    } ?: return@withContext ImportOutcome.Busy
                    report.phase("se citesc canalele")

                    val categories = categoriesFile.inputStream().use { XtreamParser.categories(it) }
                    var count = 0
                    streamsFile.inputStream().use { input ->
                        val channels = XtreamParser.streams(input, categories, endpoints, source.format).iterator()
                        if (!channels.hasNext()) {
                            // Nu ștergem canalele vechi pe baza unui răspuns gol.
                            throw PlaylistException("Serverul Xtream nu a trimis niciun canal live.")
                        }
                        report.count("grupuri", categories.size.toLong())
                        count = sink.replaceChannels(source.playlistId, PlaylistHeader(), counted(enrich(channels.asSequence()), report))
                    }
                    ImportOutcome.Imported(count, skipped = 0, tag = null, epgUrls = listOf(endpoints.xmltv()), account = account)
                }
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    /** Verifică un cont Xtream (o singură cerere, prin [ConnectionGate]), fără să importe nimic. */
    suspend fun checkXtream(source: ImportSource.Xtream): XtreamAccount {
        val endpoints = XtreamEndpoints(source.server, source.username, source.password)
        return gate.withConnection(source.playlistId) {
            XtreamParser.account(downloader.fetchText(endpoints.account(), source.userAgent))
        }
    }

    private suspend fun <T> network(source: ImportSource, wait: Boolean, block: suspend () -> T): T? =
        if (wait) gate.withConnection(source.playlistId, block) else gate.tryWithConnection(source.playlistId, block)

    private suspend fun importM3u(
        playlistId: String,
        raw: InputStream,
        sink: ImportSink,
        tag: CacheTag?,
        report: LoadReporter,
    ): ImportOutcome {
        report.phase("se citesc canalele")
        val parser = M3uParser()
        val count = maybeGunzip(raw).bufferedReader(Charsets.UTF_8).use { reader ->
            val channels = parser.parse(reader).iterator()
            // Antetul (#EXTM3U) e citit odată cu prima intrare. Dacă nu există nicio intrare live,
            // ne oprim înainte de salvare, ca o pagină de eroare să nu-ți șteargă canalele vechi.
            if (!channels.hasNext()) {
                throw PlaylistException(
                    if (parser.skipped > 0 && parser.sawEntries) "Playlistul nu are niciun canal live (doar filme sau seriale)."
                    else "Fișierul nu pare un playlist M3U sau nu are niciun canal.",
                )
            }
            val live = counted(enrich(channels.asSequence()), report).onEach {
                if (parser.skipped > 0) report.count("filme și seriale sărite", parser.skipped.toLong())
            }
            sink.replaceChannels(playlistId, parser.header, live)
        }
        return ImportOutcome.Imported(count, parser.skipped, tag, parser.header.epgUrls)
    }

    private fun maybeGunzip(raw: InputStream): InputStream {
        val input = BufferedInputStream(raw)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input) else input
    }

    /** Numără canalele pe măsură ce sunt salvate (pentru ecranul de progres). */
    private fun counted(channels: Sequence<ImportedChannel>, report: LoadReporter): Sequence<ImportedChannel> {
        var n = 0L
        return channels.onEach { report.count("canale", ++n) }
    }

    companion object {
        fun enrich(channels: Sequence<ParsedChannel>): Sequence<ImportedChannel> =
            channels.mapIndexed { index, c -> enrich(c, index) }

        fun enrich(c: ParsedChannel, position: Int): ImportedChannel {
            val parsed = ChannelNameParser.parse(c.name, c.group)
            val quality = parsed.quality.takeIf { it != Quality.UNKNOWN }
                ?: c.group?.let { ChannelNameParser.parse(it).quality }
                ?: Quality.UNKNOWN
            val country = parsed.country ?: c.tvgCountry?.trim()?.uppercase()?.takeIf { it.length in 2..3 }
            return ImportedChannel(
                source = c,
                position = position,
                groupKey = parsed.groupKey,
                quality = quality,
                country = country,
                language = Languages.guess(c.tvgLanguage, country),
                searchText = SearchText.of(c.name, c.tvgName?.takeIf { it != c.name }),
            )
        }
    }
}
