package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reluările de dimineață ale meciurilor de aseară nu mai apar „LIVE”. */
class ReplayStatusTest {
    private val h = TimeUnit.HOURS.toMillis(1)
    private val evening = 1_800_000_000_000L // meciul de aseară
    private val morning = evening + 12 * h // reluarea de dimineață
    private val now = morning + h

    private fun br(id: Long, title: String, start: Long, stop: Long): MatchBroadcast {
        val b = EpgBroadcast(id, id, start, stop, title)
        return MatchBroadcast(b, EpgMatchExtractor.extract(b, channelIsSport = true)!!)
    }

    @Test
    fun `reluarea doar din EPG se uneste cu transmisia si nu e LIVE`() {
        val live = br(1, "Norvegia - Galles", evening, evening + 2 * h)
        val rerun = br(2, "Norvegia - Galles", morning, morning + 2 * h)
        val matches = MatchAssembler.assemble(emptyList(), listOf(live, rerun), now)
        assertEquals(1, matches.size)
        assertEquals(MatchStatus.FINISHED, matches[0].status)
        assertEquals(evening, matches[0].start)
        assertEquals(2, matches[0].broadcasts.size)
    }

    @Test
    fun `reluarea unui meci cunoscut devine sursa lui, nu meci nou`() {
        val e = SportEvent("thesportsdb", "1", "Soccer", "Nations League", "Norvegia", "Galles", evening)
        val rerun = br(2, "Norvegia - Galles", morning, morning + 2 * h)
        val matches = MatchAssembler.assemble(listOf(e), listOf(rerun), now)
        assertEquals(1, matches.size)
        assertEquals("thesportsdb:1", matches[0].id)
        assertEquals(MatchStatus.FINISHED, matches[0].status)
    }

    @Test
    fun `LIVE vechi din fisier expira dupa durata meciului`() {
        val e = SportEvent("fotmob", "1", "Soccer", null, "Arsenal", "Chelsea", evening, status = MatchStatus.LIVE)
        val m = MatchAssembler.assemble(listOf(e), emptyList(), evening + 4 * h).single()
        assertEquals(MatchStatus.FINISHED, m.status)
        val still = MatchAssembler.assemble(listOf(e), emptyList(), evening + h).single()
        assertEquals(MatchStatus.LIVE, still.status)
    }

    @Test
    fun `reluarea in alte limbi nu e meci`() {
        assertNull(EpgMatchExtractor.extract(EpgBroadcast(1, 1, 0, 1, "Norvegia - Galles (replica)"), channelIsSport = true))
        assertNull(EpgMatchExtractor.extract(EpgBroadcast(1, 1, 0, 1, "Real Madrid - Getafe (diferido)"), channelIsSport = true))
        assertTrue(EpgMatchExtractor.extract(EpgBroadcast(1, 1, 0, 1, "Norvegia - Galles"), channelIsSport = true) != null)
    }
}
