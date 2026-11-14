package ro.ak.sportshub.core.playlist

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ro.ak.sportshub.core.common.ConnectionGate
import ro.ak.sportshub.core.model.Quality

class PlaylistImporterTest {

    private lateinit var server: MockWebServer
    private val workDir = Files.createTempDirectory("ak-import").toFile()
    private val gate = ConnectionGate()
    private val importer = PlaylistImporter(PlaylistDownloader(OkHttpClient()), gate, workDir)

    /** Sink de test: ține minte ce primește. */
    private class MemorySink : ImportSink {
        var header: PlaylistHeader? = null
        val channels = mutableListOf<ImportedChannel>()
        override suspend fun replaceChannels(playlistId: String, header: PlaylistHeader, channels: Sequence<ImportedChannel>): Int {
            this.header = header
            this.channels.clear()
            this.channels += channels
            return this.channels.size
        }
    }

    private val m3u = """
        #EXTM3U url-tvg="http://epg.example/e.xml"
        #EXTINF:-1 group-title="UK | SPORTS FHD",UK: Sky Sport Main Event
        http://h.example/live/u/p/1.ts
        #EXTINF:-1 tvg-language="English" group-title="RO | SPORT",RO: Digi Sport 1 HD
        http://h.example/live/u/p/2.ts
        #EXTINF:-1 group-title="VOD",Film
        http://h.example/movie/u/p/3.mp4
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
    }

    @Test
    fun `importa m3u, deduce calitatea din grup si limba din prefix, sterge fisierele temporare`() = runTest {
        server.enqueue(MockResponse().setBody(m3u).setHeader("ETag", "\"v1\""))
        val sink = MemorySink()
        val out = importer.import(ImportSource.M3uUrl("1", server.url("/pl.m3u").toString(), "AK"), sink)

        out as ImportOutcome.Imported
        assertEquals(2, out.channels)
        assertEquals(1, out.skipped)
        assertEquals("\"v1\"", out.tag?.etag)
        assertEquals(listOf("http://epg.example/e.xml"), sink.header?.epgUrls)

        val (sky, digi) = sink.channels
        assertEquals(Quality.FHD, sky.quality) // din grupul „SPORTS FHD”
        assertEquals("en", sky.language) // din prefixul UK
        assertEquals("uk:sky sport main event", sky.groupKey)
        assertEquals("UK: Sky Sport Main Event", sky.source.name) // numele original rămâne
        assertEquals(Quality.HD, digi.quality)
        assertEquals("en", digi.language) // tvg-language are prioritate față de prefixul RO
        assertEquals("ro digi sport 1 hd", digi.searchText)

        assertEquals("AK", server.takeRequest().getHeader("User-Agent"))
        assertTrue(workDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `actualizarea incrementala trimite etag si nu reimporta daca nu s-a schimbat`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))
        val sink = MemorySink()
        val out = importer.import(
            ImportSource.M3uUrl("1", server.url("/pl.m3u").toString(), null),
            sink,
            previous = CacheTag(etag = "\"v1\"", lastModified = "Sat, 14 Nov 2026 10:00:00 GMT"),
        )
        assertEquals(ImportOutcome.NotModified, out)
        assertNull(sink.header)
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
        assertEquals("Sat, 14 Nov 2026 10:00:00 GMT", request.getHeader("If-Modified-Since"))
    }

    @Test
    fun `m3u comprimat gzip`() = runTest {
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(m3u.toByteArray()) } }
        server.enqueue(MockResponse().setBody(Buffer().write(gz.toByteArray())))
        val out = importer.import(ImportSource.M3uUrl("1", server.url("/pl.m3u.gz").toString(), null), MemorySink())
        assertEquals(2, (out as ImportOutcome.Imported).channels)
    }

    @Test
    fun `fisier local, fara retea`() = runTest {
        val out = importer.import(ImportSource.M3uLocal("1") { m3u.byteInputStream() }, MemorySink())
        assertEquals(2, (out as ImportOutcome.Imported).channels)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `eroare http cu mesaj fara adresa si fara parola`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            importer.import(ImportSource.M3uUrl("1", server.url("/get.php?username=u&password=secret").toString(), null), MemorySink())
            fail("trebuia să arunce")
        } catch (e: PlaylistException) {
            assertTrue(e.message!!.contains("403"))
            assertTrue(!e.message!!.contains("secret") && !e.message!!.contains("http"))
        }
        assertTrue(workDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `o pagina de eroare nu sterge canalele vechi`() = runTest {
        server.enqueue(MockResponse().setBody("<html>Not found</html>"))
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Film\nhttp://h/movie/1.mp4\n"))
        val sink = MemorySink()
        for (expected in listOf("M3U", "live")) {
            try {
                importer.import(ImportSource.M3uUrl("1", server.url("/x").toString(), null), sink)
                fail("trebuia să arunce")
            } catch (e: PlaylistException) {
                assertTrue(e.message!!.contains(expected))
            }
        }
        assertNull(sink.header) // sink-ul nu a fost apelat
    }

    @Test
    fun `xtream - cereri secventiale, doar live, categorii si format`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"1","active_cons":0}}"""))
        server.enqueue(MockResponse().setBody("""[{"category_id":"10","category_name":"UK | SPORTS"},{"category_id":11,"category_name":"RO | SPORT"}]"""))
        server.enqueue(
            MockResponse().setBody(
                """
                [
                 {"num":1,"name":"UK: Sky Sport Main Event UHD","stream_type":"live","stream_id":101,"stream_icon":"","epg_channel_id":"sky.me.uk","category_id":"10","tv_archive":1,"tv_archive_duration":"7"},
                 {"num":"2","name":"RO: Digi Sport 1 FHD","stream_type":"live","stream_id":"202","category_id":11,"tv_archive":0},
                 {"num":3,"name":"Film","stream_type":"movie","stream_id":303,"category_id":"10"},
                 {"num":4,"name":"","stream_type":"live","stream_id":404},
                 {"num":5,"name":"Fără categorie","stream_type":"live","stream_id":505,"category_id":null,"extra":{"a":[1,2]}}
                ]
                """.trimIndent(),
            ),
        )
        val sink = MemorySink()
        val src = ImportSource.Xtream("7", server.url("/").toString(), "user", "pa ss", format = "m3u8")
        val out = importer.import(src, sink) as ImportOutcome.Imported

        assertEquals(3, out.channels)
        assertEquals(1, out.account?.maxConnections)
        assertEquals(1800000000L, out.account?.expiresAt)
        assertEquals(listOf("UK | SPORTS", "RO | SPORT", null), sink.channels.map { it.source.group })
        assertEquals(Quality.UHD, sink.channels[0].quality)
        assertEquals(7, sink.channels[0].source.catchupDays)
        assertNull(sink.channels[1].source.catchupDays)
        assertEquals("sky.me.uk", sink.channels[0].source.tvgId)
        assertEquals(server.url("/live/user/pa%20ss/101.m3u8").toString(), sink.channels[0].source.streamUrl)
        assertTrue(out.epgUrls.single().contains("xmltv.php"))

        val actions = (1..3).map { server.takeRequest().requestUrl!!.queryParameter("action") }
        assertEquals(listOf(null, "get_live_categories", "get_live_streams"), actions)
    }

    @Test
    fun `xtream cu parola gresita`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        try {
            importer.import(ImportSource.Xtream("7", server.url("/").toString(), "u", "p"), MemorySink())
            fail("trebuia să arunce")
        } catch (e: PlaylistException) {
            assertTrue(e.message!!.contains("parolă"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `xtream cu lista goala sau obiect gol`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("{}"))
        val sink = MemorySink()
        try {
            importer.import(ImportSource.Xtream("7", server.url("/").toString(), "u", "p"), sink)
            fail("trebuia să arunce")
        } catch (e: PlaylistException) {
            assertTrue(e.message!!.contains("niciun canal"))
        }
        assertNull(sink.header)
    }

    @Test
    fun `actualizarea programata renunta daca playlistul e folosit, cea manuala asteapta`() = runTest {
        server.enqueue(MockResponse().setBody(m3u))
        val src = ImportSource.M3uUrl("1", server.url("/pl.m3u").toString(), null)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Simulează playerul care ține singura conexiune a playlistului.
        val player = launch { gate.withConnection("1") { holding.complete(Unit); release.await() } }
        holding.await()

        assertEquals(ImportOutcome.Busy, importer.import(src, MemorySink(), waitForConnection = false))
        assertEquals(0, server.requestCount)

        val manual = launch {
            val out = importer.import(src, MemorySink(), waitForConnection = true)
            assertEquals(2, (out as ImportOutcome.Imported).channels)
        }
        release.complete(Unit)
        player.join()
        manual.join()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `adresa serverului xtream e normalizata`() {
        assertEquals("http://host.example:8080/", XtreamEndpoints.normalizeServer("host.example:8080")?.toString())
        assertEquals("http://host.example:8080/", XtreamEndpoints.normalizeServer(" http://host.example:8080/player_api.php?x=1 ")?.toString())
        assertEquals("https://h.example/c", XtreamEndpoints.normalizeServer("https://h.example/c/")?.toString())
        assertNull(XtreamEndpoints.normalizeServer(""))
    }
}
