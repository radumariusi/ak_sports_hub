package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.model.Quality

class NowOnChannelsTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun match(id: String, start: Long, status: MatchStatus = MatchStatus.LIVE) =
        Match(id, Sport.FOOTBALL, "Premier League", "A$id", "B$id", start, start + 2 * hour, status)

    private fun src(key: String, c: Confidence, rejected: Boolean = false, q: Quality = Quality.FHD) =
        MatchSource("$key-${q.name}", key, "en", q, c, rejected = rejected)

    @Test
    fun `fiecare canal cu meciul de acum, cel mai sigur, fara respinse`() {
        val live = match("1", now - hour)
        val soon = match("2", now + 5 * 60_000, MatchStatus.SCHEDULED)
        val later = match("3", now + 3 * hour, MatchStatus.SCHEDULED)
        val done = match("4", now - 3 * hour, MatchStatus.FINISHED)
        val rows = NowOnChannels.build(
            listOf(
                live to listOf(src("sky", Confidence.EPG), src("sky", Confidence.EPG, q = Quality.UHD), src("tnt", Confidence.RIGHTS), src("bbc", Confidence.EPG, rejected = true)),
                soon to listOf(src("tnt", Confidence.STATION), src("dazn", Confidence.RIGHTS)),
                later to listOf(src("espn", Confidence.EPG)),
                done to listOf(src("bt", Confidence.EPG)),
            ),
            now,
        )
        assertEquals(listOf("sky", "tnt", "dazn"), rows.map { it.groupKey })
        assertEquals("1", rows[0].match.id)
        assertEquals(2, rows[0].sources.size)
        // Pe TNT: postul anunțat la meciul care începe bate presupunerea de la meciul live.
        assertEquals("2", rows[1].match.id)
        assertEquals(Confidence.STATION, rows[1].confidence)
        assertFalse(NowOnChannels.build(listOf(soon to listOf(src("dazn", Confidence.RIGHTS))), now, includeGuesses = false).any())
    }

    @Test
    fun `rezumatele si magazinele din EPG`() {
        assertTrue(Highlights.isHighlights("Premier League Highlights"))
        assertTrue(Highlights.isHighlights("Match of the Day"))
        assertTrue(Highlights.isHighlights("Liga 1: Rezumatul etapei"))
        assertTrue(Highlights.isHighlights("LaLiga", "Resumen de la jornada"))
        assertTrue(Highlights.isHighlights("UEFA Nations League in 60"))
        assertFalse(Highlights.isHighlights("Live: Arsenal v Chelsea highlights"))
        assertFalse(Highlights.isHighlights("Arsenal v Chelsea"))
        assertTrue(Highlights.mentions("Highlights: Arsenal v Chelsea", null, listOf("Arsenal")))
        assertFalse(Highlights.mentions("Highlights: Arsenal v Chelsea", null, listOf("FCSB")))
    }
}
