package ro.ak.sportshub.core.playlist

import java.io.BufferedReader

/**
 * Parser M3U / M3U8 extins, în flux: citește linie cu linie, deci merge și pe playlisturi
 * de sute de mii de intrări fără să încarce fișierul în memorie.
 *
 * Înțelege: `#EXTM3U url-tvg="…"`, `#EXTINF:-1 tvg-id="…" tvg-name="…" tvg-logo="…"
 * group-title="…" tvg-language="…" tvg-country="…" tvg-chno="…" catchup-days="…",Nume`,
 * `#EXTGRP:Grup`, `#EXTVLCOPT:http-user-agent=…`, `#KODIPROP` (ignorat).
 * Intrările VOD sunt aruncate (vezi [VodFilter]); [skipped] spune câte.
 */
class M3uParser {

    var header: PlaylistHeader = PlaylistHeader()
        private set

    var skipped: Int = 0
        private set

    /** A găsit măcar un `#EXTINF` (deci e un M3U, chiar dacă fără canale live). */
    var sawEntries: Boolean = false
        private set

    fun parse(reader: BufferedReader): Sequence<ParsedChannel> = sequence {
        var info: String? = null
        var extGroup: String? = null
        var userAgent: String? = null
        var first = true

        while (true) {
            val raw = reader.readLine() ?: break
            val line = (if (first) raw.removePrefix("﻿") else raw).trim()
            first = false
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    val urls = listOfNotNull(attrs["url-tvg"], attrs["x-tvg-url"], attrs["tvg-url"])
                        .flatMap { it.split(',') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    header = PlaylistHeader(epgUrls = urls)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    sawEntries = true
                    extGroup = null
                    userAgent = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                        userAgent = opt.substringAfter('=').trim().ifEmpty { null }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val extinf = info
                    info = null
                    val entry = if (extinf != null) entry(extinf, line, extGroup, userAgent) else null
                    extGroup = null
                    userAgent = null
                    if (entry == null) {
                        // URL fără #EXTINF: nu știm ce e, nu-l afișăm.
                        skipped++
                    } else if (VodFilter.isVod(entry.streamUrl, entry.group)) {
                        skipped++
                    } else {
                        yield(entry)
                    }
                }
            }
        }
    }

    private fun entry(extinf: String, url: String, extGroup: String?, userAgent: String?): ParsedChannel? {
        if (!url.contains("://")) return null
        val attrs = attributes(extinf)
        val name = title(extinf).ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { return null }
        return ParsedChannel(
            name = name,
            group = (attrs["group-title"] ?: extGroup)?.trim()?.ifEmpty { null },
            streamUrl = url,
            tvgId = attrs["tvg-id"]?.ifBlank { null },
            tvgName = attrs["tvg-name"]?.ifBlank { null },
            logo = attrs["tvg-logo"]?.ifBlank { null },
            tvgLanguage = attrs["tvg-language"]?.ifBlank { null },
            tvgCountry = attrs["tvg-country"]?.ifBlank { null },
            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            userAgent = userAgent ?: attrs["user-agent"]?.ifBlank { null },
            number = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
        )
    }

    companion object {
        private val attribute = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

        /** Atributele `cheie="valoare"` (cheile cu litere mici). */
        fun attributes(line: String): Map<String, String> =
            attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

        /** Numele de după prima virgulă aflată în afara ghilimelelor. */
        fun title(extinf: String): String {
            var quoted = false
            for (i in extinf.indices) {
                when (extinf[i]) {
                    '"' -> quoted = !quoted
                    ',' -> if (!quoted) return extinf.substring(i + 1).trim()
                }
            }
            return ""
        }
    }
}
