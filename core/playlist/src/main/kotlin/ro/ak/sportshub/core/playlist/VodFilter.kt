package ro.ak.sportshub.core.playlist

import ro.ak.sportshub.core.common.ChannelNameParser

/**
 * Aplicația păstrează doar canalele live. Filmele și serialele (VOD) se recunosc după:
 * 1. adresă: `/movie/`, `/series/`, `/vod/` sau un fișier video (`.mp4`, `.mkv`…), deci sigur VOD;
 * 2. grup: „VOD”, „Movies”, „Filme”, „Series”, „Seriale”…, dar numai dacă adresa nu arată a live
 *    (`/live/`, `.ts`, `.m3u8`, sau formatul Xtream `/user/pass/123`). Așa nu pierzi canale ca
 *    „Sky Cinema” puse de furnizor într-un grup „UK | MOVIES”.
 */
object VodFilter {

    private val vodPath = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
    private val vodExtension = Regex(
        """\.(mp4|mkv|avi|mov|wmv|flv|m4v|webm|mpg|mpeg|3gp|divx|xvid)$""",
        RegexOption.IGNORE_CASE,
    )
    private val livePath = Regex("""/live/""", RegexOption.IGNORE_CASE)
    private val liveExtension = Regex("""\.(ts|m3u8|m3u|mpd)$""", RegexOption.IGNORE_CASE)
    private val xtreamLive = Regex("""^[a-z]+://[^/]+/[^/]+/[^/]+/\d+$""", RegexOption.IGNORE_CASE)

    private val vodGroup = Regex(
        """\b(vod|movie|movies|film|filme|films|series|seriale|serial|serie|tv ?shows?|box ?office|""" +
            """cinema ?vod|peliculas|filmes)\b""",
    )

    fun isVod(streamUrl: String, group: String?): Boolean {
        val path = streamUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        if (vodPath.containsMatchIn(path) || vodExtension.containsMatchIn(path)) return true
        if (group == null) return false
        val looksLive = livePath.containsMatchIn(path) || liveExtension.containsMatchIn(path) ||
            xtreamLive.matches(path)
        if (looksLive) return false
        return vodGroup.containsMatchIn(ChannelNameParser.fold(group))
    }
}
