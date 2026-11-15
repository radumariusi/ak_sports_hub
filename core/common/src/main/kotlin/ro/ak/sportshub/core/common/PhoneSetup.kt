package ro.ak.sportshub.core.common

import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Configurarea din telefon prin cod QR: televizorul pornește un server mic în rețeaua locală (doar cât e deschis
 * ecranul), iar codul QR duce telefonul la o pagină cu formularul. Adresa și parola Xtream (sau linkul M3U) se
 * scriu pe telefon, nu cu telecomanda. Adresa are un cod aleator, ca alt dispozitiv din rețea să nu ghicească pagina.
 * Datele trimise nu se loghează și nu ies din rețeaua locală.
 */
object PhoneSetup {

    /** Ce s-a trimis din telefon: un playlist nou (și, opțional, un EPG extern). */
    sealed interface Submission {
        val name: String
        val epgUrl: String?

        data class Xtream(override val name: String, val server: String, val username: String, val password: String, override val epgUrl: String?) : Submission
        data class M3u(override val name: String, val url: String, override val epgUrl: String?) : Submission
    }

    /** Un cod aleator pentru adresa paginii (8 caractere, fără litere care se confundă). */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** „a=1&b=x%20y” → map (ultima valoare câștigă). */
    fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Formularul citit și verificat: playlistul trimis sau mesajul de eroare (în română, pentru telefon). */
    fun read(form: Map<String, String>): Result<Submission> {
        fun field(k: String) = form[k]?.trim().orEmpty()
        val epg = field("epg").takeIf { it.isNotEmpty() }
        if (epg != null && !epg.startsWith("http://") && !epg.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("Adresa EPG trebuie să înceapă cu http:// sau https://."))
        }
        return when (field("type")) {
            "xtream" -> {
                val server = field("server")
                val user = field("username")
                val pass = form["password"].orEmpty() // parola poate avea spații la capete
                when {
                    server.isEmpty() -> Result.failure(IllegalArgumentException("Completează adresa serverului."))
                    server.contains(' ') -> Result.failure(IllegalArgumentException("Adresa serverului nu poate avea spații."))
                    user.isEmpty() || pass.isEmpty() -> Result.failure(IllegalArgumentException("Completează utilizatorul și parola."))
                    else -> Result.success(Submission.Xtream(field("name"), server, user, pass, epg))
                }
            }
            "m3u" -> {
                val url = field("url")
                when {
                    url.isEmpty() -> Result.failure(IllegalArgumentException("Completează linkul playlistului."))
                    !url.startsWith("http://") && !url.startsWith("https://") -> Result.failure(IllegalArgumentException("Linkul trebuie să înceapă cu http:// sau https://."))
                    else -> Result.success(Submission.M3u(field("name"), url, epg))
                }
            }
            else -> Result.failure(IllegalArgumentException("Alege tipul playlistului."))
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val STYLE = "body{font-family:sans-serif;background:#101318;color:#eee;margin:0;padding:20px;max-width:520px}" +
        "h1{font-size:22px;color:#4ade80}label{display:block;margin:14px 0 4px;font-size:14px;color:#aab}" +
        "input,select{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:8px;border:1px solid #334;background:#1b2028;color:#fff}" +
        "button{margin-top:22px;width:100%;padding:14px;font-size:17px;border:0;border-radius:8px;background:#4ade80;color:#000;font-weight:bold}" +
        ".err{background:#7f1d1d;padding:10px;border-radius:8px}.hint{font-size:13px;color:#889}.x{display:none}"

    /** Pagina cu formularul (pentru telefon). [error] apare deasupra, cu valorile trimise păstrate (fără parolă). */
    fun formPage(error: String? = null, previous: Map<String, String> = emptyMap()): String {
        fun v(k: String) = esc(previous[k].orEmpty())
        val xtream = previous["type"] != "m3u"
        return """<!DOCTYPE html><html lang="ro"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AK Sports Hub</title><style>$STYLE</style></head><body>
<h1>AK Sports Hub · playlist nou</h1>
${if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""}
<form method="post">
<label>Tip</label>
<select name="type" id="type" onchange="sw()">
<option value="xtream"${if (xtream) " selected" else ""}>Xtream Codes (server, utilizator, parolă)</option>
<option value="m3u"${if (!xtream) " selected" else ""}>Link M3U</option>
</select>
<label>Nume (opțional)</label><input name="name" value="${v("name")}" autocomplete="off">
<div id="xt"><label>Server</label><input name="server" value="${v("server")}" placeholder="http://exemplu.com:8080" autocapitalize="off" autocorrect="off" inputmode="url">
<label>Utilizator</label><input name="username" value="${v("username")}" autocapitalize="off" autocorrect="off" autocomplete="off">
<label>Parolă</label><input name="password" type="password" autocomplete="off"></div>
<div id="m3"><label>Link M3U</label><input name="url" value="${v("url")}" placeholder="http://…/get.php?…" autocapitalize="off" autocorrect="off" inputmode="url"></div>
<label>EPG extern (opțional)</label><input name="epg" value="${v("epg")}" placeholder="https://…/guide.xml.gz" autocapitalize="off" autocorrect="off" inputmode="url">
<button type="submit">Trimite la televizor</button>
<p class="hint">Datele merg direct la televizor, în rețeaua ta locală, și rămân doar acolo.</p>
</form>
<script>function sw(){var x=document.getElementById('type').value=='xtream';document.getElementById('xt').className=x?'':'x';document.getElementById('m3').className=x?'x':'';}sw();</script>
</body></html>"""
    }

    /** Pagina de după trimitere. */
    fun donePage(name: String): String = """<!DOCTYPE html><html lang="ro"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AK Sports Hub</title><style>$STYLE</style></head><body>
<h1>Gata</h1><p>Playlistul „${esc(name)}” a ajuns la televizor și se încarcă. Poți închide pagina.</p></body></html>"""
}
