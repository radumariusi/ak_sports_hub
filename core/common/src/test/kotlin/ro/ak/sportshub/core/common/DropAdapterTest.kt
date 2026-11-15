package ro.ak.sportshub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.sportshub.core.common.player.DropAdapter
import ro.ak.sportshub.core.common.player.DropAdapter.Step

class DropAdapterTest {
    private var now = 0L
    private val all = setOf(Step.MATCH_RATE, Step.SURFACE_VIEW)

    @Test
    fun `pierderile de la pornire nu schimbă nimic`() {
        val a = DropAdapter { now }
        var dropped = 0
        a.sample(0, all)
        now += 5_000; dropped = 100; a.sample(dropped, all) // pornirea
        repeat(10) { now += 5_000; assertFalse(a.sample(dropped, all)) }
        assertEquals(emptySet<Step>(), a.active)
    }

    @Test
    fun `pierderi constante: încearcă pe rând, păstrează ce ajută`() {
        val a = DropAdapter { now }
        var dropped = 0
        a.sample(0, all)
        // 10 cadre pierdute pe secundă: după pornire (15 s) și două ferestre rele, prima încercare.
        var changed = false
        repeat(4) { now += 5_000; dropped += 50; changed = a.sample(dropped, all) || changed }
        assertTrue(changed)
        assertEquals(setOf(Step.MATCH_RATE), a.active)
        // Potrivirea nu ajută: după probă se anulează și se încearcă imaginea fluidă.
        var guard = 0
        while (Step.SURFACE_VIEW !in a.active && guard++ < 20) { now += 5_000; dropped += 50; a.sample(dropped, all) }
        assertEquals(setOf(Step.SURFACE_VIEW), a.active)
        // Imaginea fluidă ajută (nu se mai pierd cadre): rămâne și după probă.
        repeat(5) { now += 5_000; a.sample(dropped, all) }
        assertEquals(setOf(Step.SURFACE_VIEW), a.active)
    }
}
