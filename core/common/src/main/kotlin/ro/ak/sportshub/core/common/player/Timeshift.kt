package ro.ak.sportshub.core.common.player

/**
 * B1: pauza și derularea pe live. Playerul scrie pe disc stream-ul pe care îl vezi oricum (aceeași conexiune,
 * niciun stream în plus), în bucăți de [chunkBytes], și îl redă de acolo; [TimeshiftIndex] ține legătura
 * dintre ora la care au sosit octeții și poziția lor, ca „înapoi 10 s” să știe de unde să reia.
 *
 * Orele sunt în ms ale unui ceas monoton (`elapsedRealtime`), nu ora din calendar.
 * Clasa nu e sincronizată: o folosește [TimeshiftBuffer] sub lacătul lui.
 */
class TimeshiftIndex(
    /** Cât se ține pe disc (cele mai vechi bucăți se șterg). */
    val capacityBytes: Long,
    val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    /** Cel mult un reper pe interval: o oră de stream = câteva mii de repere. */
    private val markEveryMs: Long = MARK_EVERY_MS,
) {
    init {
        require(chunkBytes > 0 && capacityBytes >= 2 * chunkBytes) { "capacitate prea mică" }
    }

    /** Câți octeți s-au scris de la începutul sesiunii. */
    var written: Long = 0
        private set

    /** Primul octet încă păstrat (începutul celei mai vechi bucăți rămase). */
    var first: Long = 0
        private set

    private var times = LongArray(256)
    private var bytes = LongArray(256)
    private var start = 0
    private var size = 0

    /** Au sosit octeți: totalul scris e acum [total], la ora [nowMs]. Întoarce bucățile de șters (cele mai vechi). */
    fun onWrite(nowMs: Long, total: Long): List<Long> {
        val before = written
        written = maxOf(written, total)
        if (size == 0 || nowMs - times[at(size - 1)] >= markEveryMs) add(nowMs, before)
        return trim()
    }

    /** Bucata în care stă octetul [byte]. */
    fun chunkOf(byte: Long): Long = byte / chunkBytes

    /** Ora la care a sosit octetul [byte] (aproximată între repere); null dacă nu s-a scris nimic. */
    fun timeAt(byte: Long): Long? {
        if (size == 0) return null
        val i = lastMarkAtOrBefore(byte)
        if (i < 0) return times[at(0)]
        val t0 = times[at(i)]
        val b0 = bytes[at(i)]
        if (i == size - 1) return t0
        val t1 = times[at(i + 1)]
        val b1 = bytes[at(i + 1)]
        if (b1 <= b0) return t0
        return t0 + (t1 - t0) * (byte - b0) / (b1 - b0)
    }

    /**
     * De unde se reia redarea ca să arate ce a sosit la ora [timeMs]: reperul de la ora aceea sau dinainte
     * (puțin mai devreme e mai bine decât să ratezi golul), cuprins între [first] și [written].
     */
    fun byteAt(timeMs: Long): Long {
        if (size == 0) return written
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[at(mid)] <= timeMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val b = if (found < 0) first else bytes[at(found)]
        return b.coerceIn(first, written)
    }

    /** Ora celui mai vechi octet păstrat (cât de departe se poate da înapoi). */
    val oldestTime: Long? get() = timeAt(first)

    /** Ora ultimului reper (cât de proaspăt e ce s-a scris). */
    val newestTime: Long? get() = if (size == 0) null else times[at(size - 1)]

    private fun trim(): List<Long> {
        if (written - first <= capacityBytes) return emptyList()
        val dropped = ArrayList<Long>()
        // Se șterg bucăți întregi, niciodată cea în care se scrie acum.
        while (written - first > capacityBytes && chunkOf(first) < chunkOf(written)) {
            dropped += chunkOf(first)
            first = (chunkOf(first) + 1) * chunkBytes
        }
        // Reperele de dinaintea primului octet păstrat nu mai folosesc (îl păstrăm pe ultimul, pentru oră).
        while (size > 1 && bytes[at(1)] <= first) {
            start = (start + 1) % times.size
            size--
        }
        return dropped
    }

    private fun lastMarkAtOrBefore(byte: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bytes[at(mid)] <= byte) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun add(time: Long, byte: Long) {
        if (size == times.size) {
            val t = LongArray(times.size * 2)
            val b = LongArray(times.size * 2)
            for (i in 0 until size) {
                t[i] = times[at(i)]
                b[i] = bytes[at(i)]
            }
            times = t
            bytes = b
            start = 0
        }
        times[at(size)] = time
        bytes[at(size)] = byte
        size++
    }

    private fun at(i: Int): Int = (start + i) % times.size

    companion object {
        const val DEFAULT_CHUNK_BYTES = 8L * 1024 * 1024
        const val MARK_EVERY_MS = 500L
    }
}

/** Setarea și calculele mărunte pentru pauza pe live (fără Android). */
object Timeshift {
    /** Cât se poate ține pe disc, în MB (0 = oprit). Implicit 1 GB. */
    val OPTIONS_MB = listOf(0, 512, 1024, 2048)
    const val DEFAULT_MB = 1024

    /** Pașii de derulare. */
    const val STEP_MS = 10_000L
    const val REPLAY_MS = 30_000L

    /** Sub atât în urmă, ești „live” (pornirea stream-ului are oricum câteva secunde de întârziere). */
    const val LIVE_TOLERANCE_MS = 3_000L

    /** Cât din spațiul liber al dispozitivului poate lua înregistrarea, cel mult. */
    const val MAX_FREE_SHARE = 0.5

    fun nextOption(mb: Int): Int = OPTIONS_MB[(OPTIONS_MB.indexOf(mb).coerceAtLeast(0) + 1) % OPTIONS_MB.size]

    fun label(mb: Int): String = when {
        mb <= 0 -> "Oprit"
        mb % 1024 == 0 -> "${mb / 1024} GB"
        else -> "$mb MB"
    }

    /** Capacitatea reală: setarea, dar cel mult jumătate din spațiul liber; null = prea puțin loc, fără înregistrare. */
    fun capacityBytes(settingMb: Int, freeBytes: Long, chunkBytes: Long = TimeshiftIndex.DEFAULT_CHUNK_BYTES): Long? {
        if (settingMb <= 0) return null
        val wanted = settingMb * 1024L * 1024L
        val allowed = (freeBytes * MAX_FREE_SHARE).toLong()
        val cap = minOf(wanted, allowed)
        return if (cap >= 4 * chunkBytes) cap else null
    }

    /**
     * Doar stream-urile TS (cele obișnuite la IPTV, inclusiv adresele Xtream fără extensie) se pot relua de la
     * mijloc: alte formate au nevoie de antetul de la început. HLS are derularea lui (fereastra furnizorului).
     */
    fun canRecord(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return true
        return last.substringAfterLast('.') in setOf("ts", "mpegts", "mts", "m2ts")
    }

    /**
     * Unde ajungi la un salt de [deltaMs] față de [watchedMs]: cel mult până la cel mai vechi moment păstrat
     * ([oldestMs]); null = ai ajuns la live (saltul înainte trece de [liveMs] minus toleranța).
     */
    fun target(watchedMs: Long, deltaMs: Long, oldestMs: Long, liveMs: Long): Long? {
        val t = watchedMs + deltaMs
        if (t >= liveMs - LIVE_TOLERANCE_MS) return null
        return t.coerceAtLeast(oldestMs)
    }

    /** „−1:23” sau „−1:02:05”: cât e în urmă imaginea față de live. */
    fun behindLabel(behindMs: Long): String {
        val s = (behindMs / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "−%d:%02d:%02d".format(h, m, sec) else "−%d:%02d".format(m, sec)
    }
}
