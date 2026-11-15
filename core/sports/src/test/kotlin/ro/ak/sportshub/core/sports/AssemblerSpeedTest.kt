package ro.ak.sportshub.core.sports

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cu un EPG mare (zeci de mii de emisiuni de sport într-o săptămână) asamblarea trebuie să rămână rapidă:
 * înainte, fiecare emisiune se compara cu toate meciurile și grupurile, iar pe televizor lista nu mai apărea.
 */
class AssemblerSpeedTest {

    @Test(timeout = 30_000)
    fun `douazeci de mii de emisiuni se asambleaza repede`() {
        val rnd = Random(2)
        val teams = (0 until 800).map { "Team ${listOf("Real", "United", "City", "Sporting", "Dinamo", "Olympic")[it % 6]} ${'A' + it % 26}$it" }
        val now = 1_763_200_000_000L
        val h = 3_600_000L
        val fixtures = (0 until 3000).map { Triple(teams[rnd.nextInt(800)], teams[rnd.nextInt(800)], now - 12 * h + rnd.nextLong(180) * h) }
        val broadcasts = (0 until 20_000).mapNotNull { i ->
            val (home, away, t) = fixtures[rnd.nextInt(fixtures.size)]
            val start = t + if (rnd.nextInt(4) == 0) rnd.nextLong(48) * h else 0
            val b = EpgBroadcast(i.toLong(), rnd.nextLong(1500), start, start + 2 * h, "Live: $home vs $away", category = "Football")
            EpgMatchExtractor.extract(b, true)?.let { MatchBroadcast(b, it) }
        }
        val events = (0 until 360).map { val (home, away, t) = fixtures[it]; SportEvent("test", "$it", "Soccer", "Liga", home, away, t) }
        val started = System.nanoTime()
        val matches = MatchAssembler.assemble(events, broadcasts, now)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("$ms ms", ms < 15_000)
        // Fiecare meci al furnizorului își păstrează emisiunile.
        assertTrue(matches.count { it.broadcasts.isNotEmpty() && "test" in it.providers } > 300)
    }
}
