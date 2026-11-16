package ro.ak.sportshub.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.sportshub.core.common.player.TimeshiftIndex

/**
 * B1: înregistrarea locală a stream-ului live, pentru pauză și derulare.
 *
 * [TimeshiftRecorder] citește stream-ul (singura conexiune către furnizor, ca înainte) și îl scrie aici, în bucăți;
 * playerul redă din [TimeshiftBuffer] prin [TimeshiftDataSource], care așteaptă octeții noi la capătul înregistrării.
 * Așa pauza nu oprește citirea din rețea (serverul nu închide stream-ul), iar „înapoi 10 s” reia de pe disc.
 * Bucățile cele mai vechi se șterg când se depășește capacitatea; totul se șterge la oprirea stream-ului.
 */
class TimeshiftBuffer(root: File, capacityBytes: Long) {

    /** Un dosar nou pe sesiune: firul unei înregistrări vechi care încă se oprește nu atinge fișierele noi. */
    private val dir = File(root, "s${System.nanoTime()}")

    private val lock = ReentrantLock()
    private val grew = lock.newCondition()
    private val index = TimeshiftIndex(capacityBytes)
    private var out: FileOutputStream? = null
    private var outChunk = -1L

    @Volatile private var failure: IOException? = null
    @Volatile private var ended = false
    @Volatile private var deleted = false

    /** Primul octet e 0x47 (pachet TS): doar atunci se poate relua de la mijloc. */
    @Volatile var looksTs: Boolean? = null
        private set

    init {
        dir.mkdirs()
    }

    val chunkBytes: Long get() = index.chunkBytes

    fun file(chunk: Long): File = File(dir, "b$chunk.ts")

    /** Pe firul înregistrării. */
    fun write(buf: ByteArray, off: Int, len: Int) {
        if (deleted) throw IOException("înregistrarea s-a oprit")
        if (looksTs == null && len > 0) looksTs = buf[off] == 0x47.toByte()
        var o = off
        var left = len
        while (left > 0) {
            val pos = lock.withLock { index.written }
            val chunk = pos / chunkBytes
            if (chunk != outChunk) {
                out?.close()
                out = FileOutputStream(file(chunk))
                outChunk = chunk
            }
            val n = minOf(left.toLong(), chunkBytes - pos % chunkBytes).toInt()
            out!!.write(buf, o, n)
            o += n
            left -= n
            val dropped = lock.withLock {
                val d = index.onWrite(SystemClock.elapsedRealtime(), pos + n)
                grew.signalAll()
                d
            }
            // Un fișier deschis de player rămâne citibil până îl închide (Linux), deci ștergerea e sigură.
            dropped.forEach { file(it).delete() }
        }
    }

    /** Înregistrarea s-a terminat: [error] = de ce (null = serverul a închis stream-ul). */
    fun finish(error: IOException?) {
        lock.withLock {
            if (!ended) failure = error
            ended = true
            grew.signalAll()
        }
        runCatching { out?.close() }
    }

    fun delete() {
        deleted = true
        finish(null)
        dir.deleteRecursively()
    }

    fun written(): Long = lock.withLock { index.written }
    fun first(): Long = lock.withLock { index.first }
    fun timeAt(byte: Long): Long? = lock.withLock { index.timeAt(byte) }
    fun byteAt(time: Long): Long = lock.withLock { index.byteAt(time) }
    fun oldestTime(): Long? = lock.withLock { index.oldestTime }

    /**
     * Așteaptă până există octeți după [position]: întoarce totalul scris, sau −1 la capăt (înregistrarea s-a
     * terminat fără eroare). O eroare a rețelei ajunge la player abia când a redat tot ce era pe disc.
     */
    fun awaitData(position: Long): Long {
        lock.withLock {
            while (index.written <= position) {
                if (deleted) throw IOException("înregistrarea s-a oprit")
                if (ended) {
                    failure?.let { throw IOException(it.message, it) }
                    return -1
                }
                try {
                    grew.await(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            return index.written
        }
    }

    private companion object {
        const val WAIT_STEP_MS = 200L
    }
}

/** Citește stream-ul live și îl scrie în [buffer], pe un fir separat. O singură cerere; [stop] o închide. */
class TimeshiftRecorder(http: OkHttpClient, url: String, userAgent: String, private val buffer: TimeshiftBuffer) {

    private val call: Call = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())

    private val thread = Thread({ run() }, "timeshift").apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        call.cancel()
        buffer.delete()
    }

    private fun run() {
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) {
                    // Același tip de eroare ca la redarea directă: playerul o tratează la fel (definitivă sau reîncercare).
                    throw HttpDataSource.InvalidResponseCodeException(
                        r.code,
                        r.message,
                        null,
                        r.headers.toMultimap(),
                        DataSpec(Uri.parse(TimeshiftDataSource.URI)),
                        ByteArray(0),
                    )
                }
                val input = r.body?.byteStream() ?: throw IOException("răspuns gol")
                val chunk = ByteArray(READ_BYTES)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n > 0) buffer.write(chunk, 0, n)
                }
            }
            buffer.finish(null)
        } catch (e: IOException) {
            buffer.finish(e)
        } catch (e: RuntimeException) {
            buffer.finish(IOException(e))
        }
    }

    private companion object {
        const val READ_BYTES = 64 * 1024
    }
}

/**
 * Sursa playerului pentru redarea din înregistrare: de la octetul din adresă („…?from=N”) până la capăt,
 * așteptând octeții noi ca la un stream live. Dacă octetul cerut a fost deja șters (pauză mai lungă decât
 * capacitatea), reia de la cel mai vechi păstrat.
 */
class TimeshiftDataSource(private val buffer: TimeshiftBuffer) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var position = 0L
    private var file: RandomAccessFile? = null
    private var fileChunk = -1L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val from = dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L
        position = maxOf(from + dataSpec.position, buffer.first())
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val written = buffer.awaitData(position)
            if (written < 0) return C.RESULT_END_OF_INPUT
            if (position < buffer.first()) {
                // Pauză mai lungă decât capacitatea: octeții ceruți s-au șters, se reia de la cel mai vechi păstrat.
                position = buffer.first()
                closeFile()
                continue
            }
            val chunkBytes = buffer.chunkBytes
            val chunk = position / chunkBytes
            if (chunk != fileChunk) {
                closeFile()
                val f = buffer.file(chunk)
                if (!f.exists()) {
                    // Bucata s-a șters (capacitatea sau sistemul a golit cache-ul): sari la următoarea.
                    position = maxOf((chunk + 1) * chunkBytes, buffer.first())
                    continue
                }
                file = RandomAccessFile(f, "r")
                fileChunk = chunk
            }
            val raf = file!!
            raf.seek(position % chunkBytes)
            val max = minOf(length.toLong(), written - position, chunkBytes - position % chunkBytes).toInt()
            val n = raf.read(target, offset, max)
            if (n <= 0) throw IOException("înregistrarea e incompletă")
            position += n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeFile()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeFile() {
        runCatching { file?.close() }
        file = null
        fileChunk = -1L
    }

    class Factory(private val buffer: TimeshiftBuffer) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(buffer)
    }

    companion object {
        const val URI = "aktimeshift://live"

        fun uri(fromByte: Long): Uri = Uri.parse("$URI?from=$fromByte")
    }
}
