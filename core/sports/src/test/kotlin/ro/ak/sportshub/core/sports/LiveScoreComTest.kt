package ro.ak.sportshub.core.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveScoreComTest {

    @Test
    fun `programul zilei`() {
        val body = """{"Ts":1794762389,"Stages":[{"Sid":"24566","Snm":"League A: Group 1","Cnm":"UEFA Nations League","CompN":"UEFA Nations League",
            "Events":[{"Eid":"1738343","T1":[{"Nm":"Belgium"}],"T2":[{"Nm":"Italy"}],"Eps":"NS","Esd":20261115194500}]}]}"""
        val g = LiveScoreCom.parseSchedule(body).single()
        assertEquals("1738343", g.providerId)
        assertEquals("Belgium", g.home)
        assertEquals(MatchStatus.SCHEDULED, g.status)
        assertEquals(java.time.Instant.parse("2026-11-15T19:45:00Z").toEpochMilli(), g.start)
        assertEquals("UEFA Nations League", g.competition)
    }

    @Test
    fun `statisticile echipelor`() {
        val body = """{"Eid":"1738343","Stat":[
            {"Tnb":1,"Fls":6,"Ths":13,"Ofs":1,"Crs":20,"Ycs":0,"Rcs":0,"Shof":8,"Shwd":0,"Shbl":4,"Gks":3,"Pss":47,"Cos":7,"Shon":10},
            {"Tnb":2,"Fls":11,"Ths":9,"Ofs":2,"Crs":23,"Ycs":3,"Rcs":0,"Shof":4,"Shwd":0,"Shbl":4,"Gks":6,"Pss":53,"Cos":4,"Shon":4}],
            "PStat":[]}"""
        val s = LiveScoreCom.parseStats(body).associateBy { it.name }
        assertEquals("47", s.getValue("Posesie (%)").home)
        assertEquals("53", s.getValue("Posesie (%)").away)
        assertEquals("6", s.getValue("Parade").away)
        assertEquals("10", s.getValue("Șuturi pe poartă").home)
    }

    @Test
    fun `formatiile fara antrenor`() {
        val body = """{"Eid":"1","Lu":[
            {"Tnb":1,"Fo":[4,2,3,1],"Ps":[{"Pid":"19341","Fn":"Senne","Ln":"Lammens","Pon":"Goalkeeper","Snu":1,"Fp":"1:1"},
                                          {"Pid":"9","Fn":"Rudi","Ln":"Garcia","Pon":"Coach"}]},
            {"Tnb":2,"Fo":[4,3,3],"Ps":[{"Pid":"2139","Fn":"Gianluigi","Ln":"Donnarumma","Pon":"Goalkeeper","Snu":1,"Fp":"1:1"}]}],
            "Subs":{"1":[{"Pid":"5","Fn":"Bench","Ln":"Player","Pon":"Defender","Snu":15}]}}"""
        val l = LiveScoreCom.parseLineup(body)
        assertEquals(listOf("Senne Lammens", "Bench Player", "Gianluigi Donnarumma"), l.map { it.name })
        assertEquals("Goalkeeper", l[0].position)
        assertEquals(true, l[1].substitute)
        assertEquals(false, l[2].home)
    }
}
