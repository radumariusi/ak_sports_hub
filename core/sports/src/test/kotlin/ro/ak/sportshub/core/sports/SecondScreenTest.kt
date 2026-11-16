package ro.ak.sportshub.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun match(id: String, status: MatchStatus, start: Long, hs: Int? = null, aw: Int? = null) =
        Match(id = id, sport = Sport.FOOTBALL, competition = "Premier League", home = "Arsenal", away = "Chelsea", start = start, end = start + 2 * hour, status = status, homeScore = hs, awayScore = aw)

    private fun row(m: Match, hide: Boolean = false, fav: Boolean = false) = SecondScreen.Row(m, fav, hide, 3, 2, "Sky Sports Main Event · FHD")

    @Test
    fun `rutele cer codul si metoda potrivita`() {
        val t = "abcd2345"
        assertEquals(SecondScreen.Route.Page, SecondScreen.route("GET", "/abcd2345", t))
        assertEquals(SecondScreen.Route.Page, SecondScreen.route("GET", "/abcd2345/?x=1", t))
        assertEquals(SecondScreen.Route.Now, SecondScreen.route("GET", "/abcd2345/api/now", t))
        assertEquals(SecondScreen.Route.Matches, SecondScreen.route("GET", "/abcd2345/api/matches", t))
        assertEquals(SecondScreen.Route.Details("espn:EPL-1#2"), SecondScreen.route("GET", "/abcd2345/api/match/espn%3AEPL-1%232", t))
        assertEquals(SecondScreen.Route.Play("a+b"), SecondScreen.route("POST", "/abcd2345/api/play/a%2Bb", t))
        assertEquals(SecondScreen.Route.Reveal("x"), SecondScreen.route("POST", "/abcd2345/api/reveal/x", t))
        assertEquals(SecondScreen.Route.WrongMethod, SecondScreen.route("GET", "/abcd2345/api/play/x", t))
        assertEquals(SecondScreen.Route.NotFound, SecondScreen.route("GET", "/altcod/api/now", t))
        assertEquals(SecondScreen.Route.NotFound, SecondScreen.route("GET", "/abcd23456", t))
        assertEquals(SecondScreen.Route.NotFound, SecondScreen.route("GET", "/", t))
        assertEquals(SecondScreen.Route.NotFound, SecondScreen.route("GET", "/x", ""))
    }

    @Test
    fun `lista arata live, urmatoarele 36 h si terminatele recente`() {
        assertTrue(SecondScreen.shown(match("a", MatchStatus.LIVE, now - hour), now))
        assertTrue(SecondScreen.shown(match("b", MatchStatus.SCHEDULED, now + 30 * hour), now))
        assertFalse(SecondScreen.shown(match("c", MatchStatus.SCHEDULED, now + 40 * hour), now))
        assertTrue(SecondScreen.shown(match("d", MatchStatus.FINISHED, now - 3 * hour), now))
        assertFalse(SecondScreen.shown(match("e", MatchStatus.FINISHED, now - 8 * hour), now))
        val ordered = SecondScreen.order(
            listOf(
                row(match("fin", MatchStatus.FINISHED, now - 3 * hour)),
                row(match("later", MatchStatus.SCHEDULED, now + 5 * hour)),
                row(match("soon", MatchStatus.SCHEDULED, now + hour)),
                row(match("live", MatchStatus.LIVE, now - hour)),
            ),
        )
        assertEquals(listOf("live", "soon", "later", "fin"), ordered.map { it.match.id })
    }

    @Test
    fun `fara spoilere ascunde scorul, fazele si statisticile`() {
        val m = match("m", MatchStatus.LIVE, now - hour, 2, 1)
        val details = MatchDetails(
            event = EventDetails(eventId = "1", home = "Arsenal", away = "Chelsea", status = MatchStatus.LIVE, progress = "67", homeScore = 2, awayScore = 1),
            timeline = listOf(TimelineEntry(23, TimelineKind.GOAL, true, "Saka", null)),
            stats = listOf(StatLine("Posesie", "55", "45")),
            lineup = listOf(LineupPlayer("Raya", "G", "1", home = true, substitute = false)),
        )
        val open = Json.parseToJsonElement(SecondScreen.detailsJson(row(m), details, loading = false)).jsonObject
        assertEquals("2", open["homeScore"]!!.jsonPrimitive.content)
        assertEquals("67", open["progress"]!!.jsonPrimitive.content)
        assertEquals(1, open["events"]!!.jsonArray.size)
        assertEquals(1, open["stats"]!!.jsonArray.size)

        val hidden = Json.parseToJsonElement(SecondScreen.detailsJson(row(m, hide = true), details, loading = false)).jsonObject
        assertEquals("null", hidden["homeScore"].toString())
        assertEquals("null", hidden["progress"].toString())
        assertEquals(0, (hidden["events"] as JsonArray).size)
        assertEquals(0, (hidden["stats"] as JsonArray).size)
        assertEquals(1, (hidden["lineup"] as JsonArray).size)

        val list = Json.parseToJsonElement(SecondScreen.matchesJson(listOf(row(m, hide = true)))).jsonObject["matches"]!!.jsonArray
        assertEquals("null", list[0].jsonObject["homeScore"].toString())
        assertEquals("true", list[0].jsonObject["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ce e pe televizor si pagina`() {
        val off = Json.parseToJsonElement(SecondScreen.nowJson(null, null)).jsonObject
        assertEquals("false", off["playing"]!!.jsonPrimitive.content)
        val on = Json.parseToJsonElement(SecondScreen.nowJson("Sky \"Sports\" <1>", row(match("m", MatchStatus.SCHEDULED, now + hour)))).jsonObject
        assertEquals("Sky \"Sports\" <1>", on["channel"]!!.jsonPrimitive.content)
        assertEquals("null", on["match"]!!.jsonObject["homeScore"].toString())
        val page = SecondScreen.page()
        assertTrue(page.startsWith("<!DOCTYPE html>"))
        assertFalse(page.contains("innerHTML"))
        assertFalse(page.contains("http://") || page.contains("https://"))
    }
}
