package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsDbTeamLookTest {

    @Test
    fun `sigla si culorile echipei`() {
        val body = """{"teams":[{"strTeam":"SC Magdeburg","strTeamShort":"SCM","strTeamAlternate":"Magdeburg",
            "strBadge":"https://r2.thesportsdb.com/images/media/team/badge/x.png","strColour1":"#007A3D","strColour2":"#FFFFFF"}]}"""
        val look = TheSportsDb.parseTeamLook(body, "SC Magdeburg")!!
        assertEquals("https://r2.thesportsdb.com/images/media/team/badge/x.png", look.logo)
        assertEquals("#007A3D", look.color)
        assertEquals("SCM", look.code)
        assertNull(TheSportsDb.parseTeamLook(body, "Kiel"))
        assertNull(TheSportsDb.parseTeamLook("""{"teams":null}""", "SC Magdeburg"))
        assertEquals(true, TheSportsDb.searchTeamUrl("123", "SC Magdeburg").endsWith("searchteams.php?t=SC%20Magdeburg"))
    }
}
