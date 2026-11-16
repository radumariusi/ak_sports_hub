package ro.ak.sportshub.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NflStatsTest {

    @Test
    fun `statistica scrisa de doua ori la fotbal american apare o singura data`() {
        val body = """
            {"boxscore":{"teams":[
              {"team":{"id":"1","displayName":"Bills"},"homeAway":"home","statistics":[
                {"name":"interceptions","label":"Interceptions thrown","displayValue":"1"},
                {"name":"totalYards","label":"Total Yards","displayValue":"350"},
                {"name":"interceptions","label":"Interceptions thrown","displayValue":"1"},
                {"name":"possessionTime","label":"Possession","displayValue":"31:20"}]},
              {"team":{"id":"2","displayName":"Jets"},"homeAway":"away","statistics":[
                {"name":"interceptions","label":"Interceptions thrown","displayValue":"0"},
                {"name":"totalYards","label":"Total Yards","displayValue":"290"},
                {"name":"interceptions","label":"Interceptions thrown","displayValue":"0"},
                {"name":"possessionTime","label":"Possession","displayValue":"28:40"}]}]}}
        """.trimIndent()
        val d = Espn.parseSummary(body, "x")!!
        val all = d.stats + d.seasonStats
        assertEquals(all.size, all.distinctBy { it.name }.size, all.toString())
        assertEquals(3, all.size, all.toString())
    }

    @Test
    fun `valori care nu sunt numere nu fac bara`() {
        assertNull(StatGroups.Row(StatLine("x", "Infinity", "1"), StatGroups.number("Infinity"), 1.0).homeShare)
        assertNull(StatGroups.number("31:20"))
    }
}
