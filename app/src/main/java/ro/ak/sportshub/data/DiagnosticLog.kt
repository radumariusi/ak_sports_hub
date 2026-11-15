package ro.ak.sportshub.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import ro.ak.sportshub.core.common.DiagnosticText

/**
 * Jurnalul de diagnostic al furnizorilor de meciuri și de statistici: fiecare cerere (adresa fără chei, codul HTTP,
 * mărimea, durata, rezultatul), ce s-a citit din fișiere și erorile de citire. Se ține în fișier (cel mult
 * [MAX_BYTES]), iar ultimul răspuns refuzat al fiecărui furnizor (pagină de verificare, JSON cu „error”) se
 * păstrează separat, ca să se vadă în raport ce a primit aplicația.
 *
 * Nu intră aici nimic din playlisturi sau EPG (adrese, nume de utilizator, parole); se trimite pe GitHub doar
 * când apeși „Trimite jurnalul”.
 */
class DiagnosticLog(context: Context) {
    private val dir = File(context.noBackupFilesDir, "jurnal")
    private val file = File(dir, "jurnal.txt")
    private val samples = File(dir, "mostre")
    private val lock = Any()
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    /** Un rând în jurnal: „11-15 08:32:10 [sofascore] …” (ora UTC). */
    fun log(tag: String, message: String) {
        val line = "${time.format(Instant.now())} [$tag] ${message.replace('\n', ' ')}\n"
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                file.appendText(line)
                if (file.length() > MAX_BYTES) trim()
            }
        }
    }

    /** O cerere HTTP: adresa (fără chei), codul, mărimea, durata, rezultatul. */
    fun request(provider: String, url: String, code: Int?, bytes: Int?, ms: Long, outcome: String, via: String = "http") {
        val c = code?.toString() ?: "-"
        val b = bytes?.let { "${it}B" } ?: "-"
        log(provider, "$via $c $b ${ms}ms $outcome · ${DiagnosticText.sanitizeUrl(url)}")
    }

    /** Ultimul răspuns refuzat al furnizorului (se suprascrie), pentru raport. */
    fun sample(provider: String, url: String, body: String) {
        synchronized(lock) {
            runCatching {
                samples.mkdirs()
                File(samples, "$provider.txt").writeText(
                    "${time.format(Instant.now())} UTC · ${DiagnosticText.sanitizeUrl(url)}\n" + DiagnosticText.cut(body, MAX_SAMPLE),
                )
            }
        }
    }

    /** Răspunsurile refuzate păstrate: furnizor → (text, data). */
    fun samples(): Map<String, Pair<String, Long>> = synchronized(lock) {
        samples.listFiles().orEmpty().filter { it.name.endsWith(".txt") }
            .associate { it.name.removeSuffix(".txt") to (runCatching { it.readText() }.getOrDefault("") to it.lastModified()) }
    }

    /** Ultimele [max] caractere din jurnal. */
    fun tail(max: Int): String = synchronized(lock) {
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.length <= max) text else "…\n" + text.substring(text.length - max).substringAfter('\n')
    }

    fun clear() = synchronized(lock) {
        file.delete()
        samples.listFiles()?.forEach { it.delete() }
    }

    /** Păstrează a doua jumătate a jurnalului. */
    private fun trim() {
        val text = file.readText()
        file.writeText(text.substring(text.length / 2).substringAfter('\n'))
    }

    private companion object {
        const val MAX_BYTES = 400_000L
        const val MAX_SAMPLE = 300_000
    }
}
