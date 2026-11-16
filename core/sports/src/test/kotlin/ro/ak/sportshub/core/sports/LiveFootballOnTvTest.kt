package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFootballOnTvTest {

    @Test
    fun `meciurile si posturile din UK`() {
        val html = """
            <html><body>
            <nav><a>Mon 16 NOV</a><a>Popular channels</a></nav>
            <div class="fixture-date">Monday 16th November 2026</div>
            <div class="fixture">
              <div class="fixture__time">19:45</div>
              <div class="fixture__teams">Germany v Netherlands</div>
              <div class="fixture__competition">UEFA Nations League Group Stage</div>
              <div class="fixture__channel"><span class="channel-pill">Amazon Prime PPV</span></div>
            </div>
            <div class="fixture">
              <div class="fixture__time">19:45</div>
              <div class="fixture__teams">Switzerland v Scotland</div>
              <div class="fixture__competition">UEFA Nations League Group Stage</div>
              <div class="fixture__channel"><span class="channel-pill">BBC Scotland</span><span class="channel-pill">BBC Two</span></div>
            </div>
            <div class="fixture-date">Tuesday 24th November 2026</div>
            <div class="fixture"><div>20:00</div><div>Arsenal v Borussia Dortmund</div><div>UEFA Champions League League Phase</div>
              <div><span>Amazon Prime Video</span></div></div>
            </body></html>
        """.trimIndent()
        assertTrue(LiveFootballOnTv.looksValid(html))
        val events = LiveFootballOnTv.parse(html)
        assertEquals(listOf("Germany", "Switzerland", "Arsenal"), events.map { it.home })
        assertEquals(listOf("BBC Scotland", "BBC Two"), events[1].stations.map { it.name })
        assertEquals("United Kingdom", events[1].stations[0].country)
        assertEquals("UEFA Champions League League Phase", events[2].competition)
        assertEquals(java.time.Instant.parse("2026-11-16T19:45:00Z").toEpochMilli(), events[0].start)
    }
}
