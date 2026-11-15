package ro.ak.sportshub.core.playlist

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ro.ak.sportshub.core.common.ConnectionGate

/** Un playlist descărcat identic nu se mai reimportă; EPG-ul se salvează comprimat. */
class ImportFingerprintTest {

    private lateinit var server: MockWebServer
    private val workDir = Files.createTempDirectory("ak-import").toFile()
    private val printDir = Files.createTempDirectory("ak-prints").toFile()
    private val downloader = PlaylistDownloader(OkHttpClient())
    private val importer = PlaylistImporter(downloader, ConnectionGate(), workDir, printDir)

    private class CountingSink : ImportSink {
        var writes = 0
        override suspend fun replaceChannels(playlistId: String, header: PlaylistHeader, channels: Sequence<ImportedChannel>): Int {
            writes++
            return channels.count()
        }
    }

    private val m3u = """
        #EXTM3U
        #EXTINF:-1 group-title="UK | SPORTS",UK: Sky Sport Main Event FHD
        http://h.example/live/u/p/1.ts
        #EXTINF:-1 group-title="RO | SPORT",RO: Digi Sport 1 HD
        http://h.example/live/u/p/2.ts
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        workDir.deleteRecursively()
        printDir.deleteRecursively()
    }

    @Test
    fun `acelasi fisier nu rescrie canalele, unul schimbat da`() = runTest {
        val src = ImportSource.M3uUrl("7", server.url("/pl.m3u").toString(), null)
        val sink = CountingSink()
        server.enqueue(MockResponse().setBody(m3u))
        assertTrue(importer.import(src, sink) is ImportOutcome.Imported)

        // Același conținut, playlist deja încărcat (previous != null): canalele rămân, fără rescriere.
        server.enqueue(MockResponse().setBody(m3u))
        assertTrue(importer.import(src, sink, previous = CacheTag()) is ImportOutcome.Unchanged)
        assertEquals(1, sink.writes)

        // Forțat (previous = null): se reimportă oricum.
        server.enqueue(MockResponse().setBody(m3u))
        assertTrue(importer.import(src, sink, previous = null) is ImportOutcome.Imported)
        assertEquals(2, sink.writes)

        // Conținut schimbat: se reimportă.
        server.enqueue(MockResponse().setBody(m3u + "\n#EXTINF:-1,Alt canal\nhttp://h.example/live/u/p/3.ts"))
        val out = importer.import(src, sink, previous = CacheTag())
        assertEquals(3, (out as ImportOutcome.Imported).channels)
        assertEquals(3, sink.writes)
    }

    @Test
    fun `descarcarea comprimata salveaza gzip, iar un gz primit ramane neschimbat`() = runTest {
        val xml = "<tv>" + "<channel id=\"a\"><display-name>A</display-name></channel>".repeat(200) + "</tv>"
        server.enqueue(MockResponse().setBody(xml))
        val target = java.io.File(workDir, "epg.xml")
        downloader.download(server.url("/e.xml").toString(), target, null, compress = true)
        val bytes = target.readBytes()
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
        assertTrue(bytes.size < xml.length / 5)
        assertEquals(xml, GZIPInputStream(target.inputStream()).bufferedReader().readText())

        val gz = okio.Buffer().write(bytes)
        server.enqueue(MockResponse().setBody(gz))
        val again = java.io.File(workDir, "epg2.xml")
        downloader.download(server.url("/e.xml.gz").toString(), again, null, compress = true)
        assertTrue(bytes.contentEquals(again.readBytes()))
    }
}
