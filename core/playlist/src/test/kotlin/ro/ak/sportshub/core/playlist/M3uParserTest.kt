package ro.ak.sportshub.core.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    // Date inventate; niciun playlist real nu intră în teste.
    private val sample = """
        |﻿#EXTM3U url-tvg="http://epg.example/a.xml.gz,http://epg.example/b.xml" x-tvg-url="http://epg.example/a.xml.gz"
        |#EXTINF:-1 tvg-id="sky.me.uk" tvg-name="UK: Sky Sport Main Event FHD" tvg-logo="http://logo.example/sky.png" group-title="UK | SPORTS" tvg-chno="101",UK: Sky Sport Main Event FHD
        |http://host.example:8080/live/u/p/101.ts
        |
        |#EXTINF:-1 tvg-language="Romanian" group-title="RO, Sport",RO: Digi Sport 1, HD
        |#EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
        |http://host.example:8080/u/p/202
        |#EXTINF:-1,Fără grup
        |#EXTGRP:Diverse
        |https://cdn.example/stream/index.m3u8
        |#EXTINF:-1 group-title="VOD | Movies",Un film (2020)
        |http://host.example:8080/movie/u/p/999.mkv
        |#EXTINF:-1 group-title="Seriale",Un serial S01E01
        |http://host.example:8080/series/u/p/555.mp4
        |#EXTINF:-1 group-title="UK | MOVIES",Sky Cinema Premiere HD
        |http://host.example:8080/u/p/303
        |#EXTINF:-1 group-title="Filme",Film fără extensie live
        |http://other.example/play?id=1
        |http://orfan.example/fara-extinf.ts
    """.trimMargin()

    private fun parse(text: String = sample): Pair<M3uParser, List<ParsedChannel>> {
        val parser = M3uParser()
        val list = parser.parse(text.reader().buffered()).toList()
        return parser to list
    }

    @Test
    fun `pastreaza doar canalele live si numara ce a sarit`() {
        val (parser, list) = parse()
        assertEquals(
            listOf("UK: Sky Sport Main Event FHD", "RO: Digi Sport 1, HD", "Fără grup", "Sky Cinema Premiere HD"),
            list.map { it.name },
        )
        // film .mkv, serial .mp4, grup „Filme” fără semn de live, URL fără #EXTINF
        assertEquals(4, parser.skipped)
    }

    @Test
    fun `citeste atributele, grupul si user-agentul`() {
        val (parser, list) = parse()
        val sky = list[0]
        assertEquals("sky.me.uk", sky.tvgId)
        assertEquals("UK | SPORTS", sky.group)
        assertEquals("http://logo.example/sky.png", sky.logo)
        assertEquals(101, sky.number)

        val digi = list[1]
        assertEquals("RO, Sport", digi.group)
        assertEquals("Romanian", digi.tvgLanguage)
        assertEquals("Mozilla/5.0 Test", digi.userAgent)

        assertEquals("Diverse", list[2].group)
        assertNull(list[2].userAgent)

        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), parser.header.epgUrls)
    }

    @Test
    fun `numele poate contine virgule, iar virgulele din ghilimele nu conteaza`() {
        assertEquals("A, B", M3uParser.title("""#EXTINF:-1 group-title="x, y",A, B"""))
        assertEquals("", M3uParser.title("#EXTINF:-1"))
    }

    @Test
    fun `fisier fara antet si cu terminatii windows`() {
        val (_, list) = parse("#EXTINF:-1,Canal 1\r\nhttp://a.example/1.ts\r\n")
        assertEquals(listOf("Canal 1"), list.map { it.name })
    }

    @Test
    fun `filtrul vod`() {
        assertTrue(VodFilter.isVod("http://h/movie/u/p/1.mp4", null))
        assertTrue(VodFilter.isVod("http://h/u/p/1.mkv?token=x", "Sport"))
        assertTrue(VodFilter.isVod("http://h/stream?id=1", "VOD English"))
        assertFalse(VodFilter.isVod("http://h/live/u/p/1.ts", "VOD English"))
        assertFalse(VodFilter.isVod("http://h:80/u/p/1", "UK | MOVIES"))
        assertFalse(VodFilter.isVod("http://h/stream?id=1", "Sport"))
        assertFalse(VodFilter.isVod("http://h/cinema/index.m3u8", "Filme"))
    }
}
