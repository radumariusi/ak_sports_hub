package ro.ak.sportshub.core.epg

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideGridTest {

    private fun min(m: Long) = TimeUnit.MINUTES.toMillis(m)
    private val from = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, min(30))

    @Test
    fun `fereastra începe la jumătatea de oră și se mută cu o oră`() {
        assertEquals(from, GuideGrid.windowStart(from + min(17), 0))
        assertEquals(from + min(60), GuideGrid.windowStart(from + min(17), 1))
        assertEquals(6, GuideGrid.ticks(from).size)
    }

    @Test
    fun `blocurile unui canal, cu golurile și marginile tăiate`() {
        val list = listOf(
            Programme(from + min(60), from + min(120), "B"),
            Programme(from - min(30), from + min(30), "A"),
            Programme(from + min(150), from + min(300), "C"),
        )
        val b = GuideGrid.blocks(list, from)
        assertEquals(listOf("A", null, "B", null, "C"), b.map { it.programme?.title })
        assertEquals(0f, b[0].start)
        assertEquals(1f / 6, b[0].end, 0.001f)
        assertEquals(1f, b.last().end)
        assertNull(GuideGrid.blocks(emptyList(), from).single().programme)
    }
}
