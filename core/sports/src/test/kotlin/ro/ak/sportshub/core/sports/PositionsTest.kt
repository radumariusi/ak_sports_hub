package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionsTest {
    @Test
    fun `prescurtările posturilor`() {
        assertEquals("GK", Positions.short("Goalkeeper"))
        assertEquals("LCB", Positions.short("Center Left Defender"))
        assertEquals("CB", Positions.short("Center Defender"))
        assertEquals("LB", Positions.short("Left Back"))
        assertEquals("RWB", Positions.short("Right Wing Back"))
        assertEquals("DM", Positions.short("Defensive Midfielder"))
        assertEquals("AM", Positions.short("Attacking Midfielder"))
        assertEquals("LM", Positions.short("Left Midfielder"))
        assertEquals("RCM", Positions.short("Center Right Midfielder"))
        assertEquals("LF", Positions.short("Left Forward"))
        assertEquals("CF", Positions.short("Forward"))
        assertEquals("CD-L", Positions.short("cd-l"))
        assertNull(Positions.short(" "))
    }
}
