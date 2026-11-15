package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadProgressTest {

    @Test
    fun formatsCountsAndSizes() {
        assertEquals("1.234.567", LoadFormat.count(1_234_567))
        assertEquals("45,0 MB", LoadFormat.size(45L shl 20))
        assertEquals("10,0 MB din 40,0 MB (25%)", LoadFormat.bytes(10L shl 20, 40L shl 20))
        assertEquals("512 B", LoadFormat.bytes(512, -1))
    }

    @Test
    fun describesTask() {
        val t = LoadTask("k", "EPG", "se citește programul").withCount("canale", 3145).withCount("emisiuni", 150_012)
        assertEquals("se citește programul · 3.145 canale · 150.012 emisiuni", t.describe())
        assertEquals(listOf("canale" to 3146L, "emisiuni" to 150_012L), t.withCount("canale", 3146).counts)
    }

    @Test
    fun throttlesButPublishesPhaseChanges() {
        var now = 1_000L
        val progress = LoadProgress(clock = { now }, minIntervalMs = 250)
        val r = progress.start("p", "Strong 8K", "se descarcă")
        r.count("canale", 1)
        assertTrue(progress.tasks.value.single().counts.isEmpty()) // prea devreme
        now += 300
        r.count("canale", 2)
        assertEquals(listOf("canale" to 2L), progress.tasks.value.single().counts)
        r.phase("se salvează")
        assertEquals("se salvează", progress.tasks.value.single().phase)
        progress.finish("p")
        assertTrue(progress.tasks.value.isEmpty())
    }

    @Test
    fun trackRemovesTaskOnError() {
        val progress = LoadProgress()
        runCatching { progress.track("x", "X", "pas") { error("stricat") } }
        assertTrue(progress.tasks.value.isEmpty())
    }
}

class CountingInputStreamTest {
    @Test
    fun countsBytes() {
        var last = 0L
        val s = CountingInputStream(ByteArray(10_000).inputStream()) { last = it }
        s.readBytes()
        assertEquals(10_000L, last)
        assertEquals(10_000L, s.count)
    }
}

class AppVersionsTest {
    @Test
    fun readsReleaseTitle() {
        assertEquals(42, AppVersions.fromTitle("AK Sports Hub v1.0.42"))
        assertEquals(39, AppVersions.fromTitle("Ultima versiune (build 39)"))
        assertEquals(null, AppVersions.fromTitle("altceva"))
        assertEquals("1.1.7", AppVersions.name(7))
        assertEquals(115, AppVersions.fromTitle("AK Sports Hub v1.1.115 (build 115)"))
    }
}
