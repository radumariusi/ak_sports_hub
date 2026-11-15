package ro.ak.sportshub.core.common

import java.io.FilterInputStream
import java.io.InputStream

/** Numără octeții citiți (pentru progresul citirii unui fișier mare). */
class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) advance(it) }

    override fun markSupported(): Boolean = false

    private fun advance(n: Long) {
        count += n
        onRead(count)
    }
}
