package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuroLeagueBoxScoreTest {

    @Test
    fun `adresa din identifier`() {
        assertEquals("https://live.euroleague.net/api/BoxScore?gamecode=379&seasoncode=E2026", EuroLeague.boxScoreUrl("E2026_379"))
        assertNull(EuroLeague.boxScoreUrl("E-379"))
    }

    @Test
    fun `totalurile si jucatorii`() {
        val body = """{"Live":false,"Stats":[
            {"Team":"ANADOLU EFES ISTANBUL","PlayersStats":[
               {"Player":"LARKIN, SHANE","Dorsal":"0","IsStarter":1,"Points":14},
               {"Player":"BENCH, GUY","Dorsal":"12","IsStarter":0}],
             "totr":{"Points":85,"FieldGoalsMade2":23,"FieldGoalsAttempted2":40,"TotalRebounds":40,"Assistances":18,"Valuation":92}},
            {"Team":"MACCABI","PlayersStats":[],
             "totr":{"Points":80,"FieldGoalsMade2":20,"FieldGoalsAttempted2":44,"TotalRebounds":35,"Assistances":15,"Valuation":80}}]}"""
        val d = EuroLeague.parseBoxScore(body)!!
        val byName = d.stats.associateBy { it.name }
        assertEquals("23 din 40", byName.getValue("Aruncări de 2 puncte").home)
        assertEquals("35", byName.getValue("Recuperări").away)
        assertEquals("92", byName.getValue("Evaluare (PIR)").home)
        assertEquals(listOf("Shane Larkin", "Guy Bench"), d.lineup.map { it.name })
        assertEquals(true, d.lineup[1].substitute)
    }
}
