package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FotMobDetailsTest {

    @Test
    fun `statisticile si formatiile din pagina meciului`() {
        val html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"content":{
              "stats":{"Periods":{
                "All":{"stats":[{"title":"Top stats","stats":[
                    {"title":"Ball possession","stats":[56,44]},
                    {"title":"Total shots","stats":[12,7]}]},
                  {"title":"Shots","stats":[{"title":"Total shots","stats":[12,7]},{"title":"Shots on target","stats":[5,2]}]}]},
                "FirstHalf":{"stats":[{"title":"Top stats","stats":[{"title":"Ball possession","stats":[60,40]}]}]}}},
              "lineup":{"homeTeam":{"name":"Arsenal","starters":[{"name":"David Raya","shirtNumber":22,"usualPlayingPositionId":0}],
                                    "subs":[{"name":"Kepa","shirtNumber":13}]},
                        "awayTeam":{"name":"Chelsea","starters":[{"name":"Cole Palmer","shirtNumber":20,"usualPlayingPositionId":2}],"subs":[]}}
            }}}}
            </script></html>
        """.trimIndent()
        val d = FotMobDetails.parse(html)!!
        assertEquals(3, d.stats.size)
        assertEquals("56", d.stats.first().home)
        assertEquals(3, d.lineup.size)
        assertEquals("Goalkeeper", d.lineup.first().position)
        assertEquals(true, d.lineup[1].substitute)
        assertEquals(false, d.lineup[2].home)
    }

    @Test
    fun `id-ul meciului`() {
        assertEquals("4506263", FotMobDetails.matchId("gb-4506263"))
        assertNull(FotMobDetails.matchId("gb-1731700000 arsenal|chelsea"))
        assertNull(FotMobDetails.parse("<html>nimic</html>"))
    }
}
