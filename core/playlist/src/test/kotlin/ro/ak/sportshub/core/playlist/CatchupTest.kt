package ro.ak.sportshub.core.playlist

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    private val start = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun archiveFromLiveUrl() {
        val url = Catchup.archiveUrl("http://srv.example:8080/live/ana/secret/1234.ts", start, 120, ZoneId.of("Europe/Bucharest"))
        assertEquals("http://srv.example:8080/timeshift/ana/secret/120/2026-11-14:22-00/1234.ts", url)
    }

    @Test
    fun archiveFromM3uStyleUrlWithoutExtension() {
        val url = Catchup.archiveUrl("http://srv.example/ana/secret/77", start, 90, ZoneId.of("UTC"))
        assertEquals("http://srv.example/timeshift/ana/secret/90/2026-11-14:20-00/77.ts", url)
    }

    @Test
    fun keepsHlsExtensionAndPathPrefix() {
        val url = Catchup.archiveUrl("https://srv.example/panel/live/ana/secret/5.m3u8", start, 60, ZoneId.of("UTC"))
        assertEquals("https://srv.example/panel/timeshift/ana/secret/60/2026-11-14:20-00/5.m3u8", url)
    }

    @Test
    fun rejectsNonXtreamUrls() {
        assertNull(Catchup.archiveUrl("http://cdn.example/hls/sport1/index.m3u8", start, 60, ZoneId.of("UTC")))
        assertNull(Catchup.archiveUrl("http://srv.example/movie/ana/secret/12.mkv", start, 60, ZoneId.of("UTC")))
        assertNull(Catchup.archiveUrl("nu e o adresă", start, 60, ZoneId.of("UTC")))
    }

    @Test
    fun availabilityWindow() {
        val now = start + TimeUnit.HOURS.toMillis(1)
        assertTrue(Catchup.isAvailable(3, start, now))
        assertFalse(Catchup.isAvailable(null, start, now))
        assertFalse(Catchup.isAvailable(3, now + 1, now))
        assertFalse(Catchup.isAvailable(1, now - TimeUnit.DAYS.toMillis(2), now))
    }

    @Test
    fun minutesIncludeMargin() {
        assertEquals(125, Catchup.minutes(start, start + TimeUnit.HOURS.toMillis(2)))
        assertEquals(5, Catchup.minutes(start, start))
    }

    @Test
    fun accountReadsServerTimezone() {
        val body = """{"user_info":{"auth":1,"status":"Active"},"server_info":{"timezone":"Europe/London"}}"""
        assertEquals("Europe/London", XtreamParser.account(body).timezone)
        assertNull(XtreamParser.account("""{"user_info":{"auth":1}}""").timezone)
    }
}
