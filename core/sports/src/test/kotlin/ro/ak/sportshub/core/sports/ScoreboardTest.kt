package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreboardTest {

    @Test
    fun teamCodes() {
        assertEquals("ARS", TeamCodes.of("Arsenal"))
        assertEquals("CHE", TeamCodes.of("Chelsea"))
        assertEquals("MCI", TeamCodes.of("Manchester City"))
        assertEquals("RMA", TeamCodes.of("Real Madrid"))
        assertEquals("MIL", TeamCodes.of("AC Milan"))
        assertEquals("CFR", TeamCodes.of("CFR Cluj"))
        assertEquals("FCS", TeamCodes.of("FCSB"))
        assertEquals("KOL", TeamCodes.of("1. FC Köln"))
        assertEquals("BOU", TeamCodes.of("AFC Bournemouth"))
    }

    @Test
    fun clockFromLabel() {
        assertEquals("67′", Scoreboard.clock("Repriza 2 · 67'"))
        assertEquals("45+2′", Scoreboard.clock("Repriza 1 · 45+2'"))
        assertEquals("23′", Scoreboard.clock("Reluare · 23'"))
        assertEquals("PAUZĂ", Scoreboard.clock("Pauză"))
        assertEquals("PAUZĂ", Scoreboard.clock("Reluare · pauză"))
        assertEquals("FINAL", Scoreboard.clock("Final după prelungiri"))
        assertEquals("Q3", Scoreboard.clock("Sfertul 3"))
        assertNull(Scoreboard.clock("În desfășurare"))
        assertNull(Scoreboard.clock(""))
    }

    @Test
    fun possession() {
        assertEquals(58 to 42, Scoreboard.possession(listOf(StatLine("Cornere", "5", "2"), StatLine("Posesie (%)", "58", "42"))))
        assertEquals(55 to 45, Scoreboard.possession(listOf(StatLine("Posesie (%)", "55%", "45%"))))
        assertNull(Scoreboard.possession(listOf(StatLine("Cornere", "5", "2"))))
        assertNull(Scoreboard.possession(listOf(StatLine("Posesie (%)", "-", "-"))))
        assertEquals(53, Scoreboard.jumpMinute(54))
        assertEquals(0, Scoreboard.jumpMinute(0))
    }

    @Test
    fun countdown() {
        val now = 1_800_000_000_000L
        val min = 60_000L
        assertEquals("începe în 38 de minute", Countdown.text(now + 38 * min, now, MatchStatus.SCHEDULED))
        assertEquals("începe în 5 minute", Countdown.text(now + 5 * min - 10_000, now, MatchStatus.SCHEDULED))
        assertEquals("începe în 2 h 10 min", Countdown.text(now + 130 * min, now, MatchStatus.SCHEDULED))
        assertEquals("începe în o zi", Countdown.text(now + 25 * 60 * min, now, MatchStatus.SCHEDULED))
        assertEquals("a început acum 12 minute", Countdown.text(now - 12 * min, now, MatchStatus.LIVE))
        assertEquals("încheiat", Countdown.text(now - 200 * min, now, MatchStatus.FINISHED))
    }
}
