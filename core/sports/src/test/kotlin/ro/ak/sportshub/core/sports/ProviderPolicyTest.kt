package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPolicyTest {
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun `nivelurile si ordinea`() {
        assertEquals(ProviderPolicy.Tier.OPEN, ProviderPolicy.tier(TheSportsDb.PROVIDER))
        assertEquals(ProviderPolicy.Tier.OPEN, ProviderPolicy.tier(UsLeagues.ESPN))
        assertEquals(ProviderPolicy.Tier.SITE, ProviderPolicy.tier(FutbolEnLaTv.PROVIDER))
        assertEquals(ProviderPolicy.Tier.GUARDED, ProviderPolicy.tier(Sofascore.PROVIDER))
        assertEquals(ProviderPolicy.Tier.GUARDED, ProviderPolicy.tier(LiveSoccerTv.PROVIDER))
        assertTrue(ProviderPolicy.rank(TheSportsDb.PROVIDER) < ProviderPolicy.rank(Sofascore.PROVIDER))
        assertTrue(ProviderPolicy.rank(Sofascore.PROVIDER) < ProviderPolicy.rank(MatchAssembler.EPG_PROVIDER))
        assertEquals(3 * hour, ProviderPolicy.freshMs(TheSportsDb.PROVIDER, 1))
        assertEquals(6 * hour, ProviderPolicy.freshMs(Sofascore.PROVIDER, 0))
        assertEquals(12 * hour, ProviderPolicy.freshMs(Sofascore.PROVIDER, 1))
    }

    @Test
    fun `pauza se dubleaza dupa refuzuri si are limita`() {
        assertEquals(6 * hour, ProviderPolicy.pauseMs(1))
        assertEquals(12 * hour, ProviderPolicy.pauseMs(2))
        assertEquals(24 * hour, ProviderPolicy.pauseMs(3))
        assertEquals(48 * hour, ProviderPolicy.pauseMs(4))
        assertEquals(48 * hour, ProviderPolicy.pauseMs(20))
        val b = ProviderPolicy.Block().refused(1_000).refused(2_000)
        assertEquals(2, b.strikes)
        assertTrue(b.active(2_000 + 11 * hour))
        assertFalse(b.active(2_000 + 13 * hour))
        assertEquals(b, ProviderPolicy.Block.decode(b.encode()))
        assertEquals(null, ProviderPolicy.Block.decode("x"))
    }

    @Test
    fun `la unire datele vin de la furnizorul de incredere`() {
        val start = 1_800_000_000_000L
        val sofa = SportEvent(provider = Sofascore.PROVIDER, providerId = "9", sport = "Soccer", competition = null, home = "Arsenal", away = "Chelsea", start = start)
        val db = SportEvent(provider = TheSportsDb.PROVIDER, providerId = "1", sport = "Soccer", competition = "English Premier League", home = "Arsenal", away = "Chelsea", start = start + 60_000)
        val merged = MatchAssembler.dedupe(listOf(sofa, db))
        assertEquals(1, merged.size)
        assertEquals(TheSportsDb.PROVIDER, merged[0].provider)
        assertEquals("1", merged[0].providerId)
        assertEquals(start, merged[0].start)
        assertEquals("English Premier League", merged[0].competition)
    }
}
