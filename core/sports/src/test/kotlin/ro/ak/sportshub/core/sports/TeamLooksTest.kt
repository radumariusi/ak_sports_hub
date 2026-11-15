package ro.ak.sportshub.core.sports

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLooksTest {

    private val scoreboard = """
        {"leagues":[{"id":"700","slug":"eng.1","logos":[{"href":"https://a.espncdn.com/i/leaguelogos/soccer/500/23.png","rel":["full","default"]}]}],
         "events":[{"id":"9001","date":"2026-11-21T15:00Z","status":{"type":{"state":"pre"}},
           "competitions":[{"competitors":[
             {"homeAway":"home","form":"DWWLL","team":{"id":"331","abbreviation":"BHA","displayName":"Brighton & Hove Albion",
               "color":"0606fa","alternateColor":"005F60","logo":"https://a.espncdn.com/i/teamlogos/soccer/500/331.png"}},
             {"homeAway":"away","team":{"id":"359","abbreviation":"ARS","displayName":"Arsenal","color":"000000","alternateColor":"ef0107",
               "logos":[{"href":"https://a.espncdn.com/i/teamlogos/soccer/500/359.png"}]}}]}]}]}
    """.trimIndent()

    private val epl = Espn.league("EPL")!!

    @Test
    fun `ESPN - sigla, culorile, codul, forma si sigla ligii`() {
        val e = Espn.parse(scoreboard, epl, LocalDate.of(2026, 11, 21)).single()
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/331.png", e.homeLook?.logo)
        assertEquals("0606fa", e.homeLook?.color)
        assertEquals("BHA", e.homeLook?.code)
        assertEquals("DWWLL", e.homeLook?.form)
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/359.png", e.awayLook?.logo)
        assertNull(e.awayLook?.form)
        assertEquals("https://a.espncdn.com/i/leaguelogos/soccer/500/23.png", e.competitionLogo)
    }

    @Test
    fun `cartea gaseste echipa dupa numele din EPG si competitia`() {
        val events = Espn.parse(scoreboard, epl, LocalDate.of(2026, 11, 21))
        val book = TeamLookBook().learn(events)
        assertEquals(2, book.teamCount)
        assertNotNull(book.team("Brighton"))
        assertEquals("ARS", book.team("Arsenal FC")?.code)
        assertEquals("https://a.espncdn.com/i/leaguelogos/soccer/500/23.png", book.competition("English Premier League"))
        // Același program: aceeași carte (nu se rescrie fișierul).
        assertSame(book, book.learn(events))
        // Fișierul păstrează tot.
        val back = TeamLookBook.decode(book.encode())
        assertEquals(book.team("Arsenal"), back.team("Arsenal"))
        assertEquals(book.competition("English Premier League"), back.competition("English Premier League"))
        assertEquals(0, TeamLookBook.decode("alt format\nT\tx\ty\t\t\t\t").teamCount)
    }

    @Test
    fun `ESPN trece peste TheSportsDB, campurile lipsa raman`() {
        val db = SportEvent("thesportsdb", "1", "Soccer", "English Premier League", "Arsenal", "Chelsea", 0L,
            homeLook = TeamLook(logo = "https://www.thesportsdb.com/images/media/team/badge/a.png"))
        val espn = SportEvent(Espn.PROVIDER, "EPL-1", "Soccer", "English Premier League", "Arsenal", "Chelsea", 0L,
            homeLook = TeamLook(color = "ef0107", code = "ARS"))
        val look = TeamLookBook().learn(listOf(espn, db)).team("Arsenal")!!
        assertEquals("https://www.thesportsdb.com/images/media/team/badge/a.png", look.logo)
        assertEquals("ef0107", look.color)
    }

    @Test
    fun `banda - negrul pe fundal inchis trece la culoarea secundara`() {
        assertEquals(0xFF0606FAL, TeamLooks.band(TeamLook(color = "0606fa")))
        assertEquals(0xFFEF0107L, TeamLooks.band(TeamLook(color = "000000", altColor = "ef0107")))
        assertNull(TeamLooks.band(TeamLook(color = "000000", altColor = "0b0f14")))
        assertNull(TeamLooks.band(TeamLook(color = "zz")))
        assertTrue(TeamLooks.luminance(0xFFFFFFFFL) > 0.99)
    }

    @Test
    fun `forma si siglele mici`() {
        assertEquals(listOf(FormResult.DRAW, FormResult.WIN, FormResult.WIN, FormResult.LOSS, FormResult.LOSS), TeamLooks.form("DWWLL"))
        assertEquals(5, TeamLooks.form("WWWWWWL").size)
        assertEquals(FormResult.LOSS, TeamLooks.form("WWWWWWL").last())
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/331.png&w=96&h=96",
            TeamLooks.smallLogo("https://a.espncdn.com/i/teamlogos/soccer/500/331.png"),
        )
        assertEquals(
            "https://www.thesportsdb.com/images/media/team/badge/a.png/small",
            TeamLooks.smallLogo("https://www.thesportsdb.com/images/media/team/badge/a.png"),
        )
    }
}
