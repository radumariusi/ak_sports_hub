package ro.ak.sportshub.core.common.player

/** Zapping în lista curentă (grupul canalului), cu trecere de la capăt la început. */
object Zapping {

    /**
     * Canalul aflat la [step] poziții de [current] în [ids] (pozitiv = următorul, CH+).
     * Dacă [current] nu mai e în listă (playlistul s-a actualizat), întoarce primul canal.
     */
    fun <T> neighbor(ids: List<T>, current: T, step: Int): T? {
        if (ids.isEmpty()) return null
        val index = ids.indexOf(current)
        if (index < 0) return ids.first()
        val size = ids.size
        return ids[((index + step) % size + size) % size]
    }

    /** Poziția „12 / 80” a canalului în listă, pentru bara de informații. */
    fun position(ids: List<*>, current: Any?): String? {
        val index = ids.indexOf(current)
        return if (index < 0) null else "${index + 1} / ${ids.size}"
    }
}

/** Canalul curent și cel dinainte, pentru butonul „canalul anterior” (comută între ultimele două). */
class ChannelHistory<T>(current: T? = null, previous: T? = null) {
    var current: T? = current
        private set
    var previous: T? = previous
        private set

    /** Înregistrează canalul pornit acum. Repornirea aceluiași canal nu schimbă nimic. */
    fun visit(channel: T) {
        if (channel == current) return
        previous = current
        current = channel
    }

    /** Uită un canal care nu mai există. */
    fun forget(channel: T) {
        if (previous == channel) previous = null
        if (current == channel) current = null
    }
}

/** Numărul tastat cu cifrele telecomenzii; se confirmă cu OK sau după o scurtă pauză. */
class NumberInput(private val maxDigits: Int = 5) {
    var text: String = ""
        private set

    val isActive: Boolean get() = text.isNotEmpty()

    /** Adaugă o cifră. Zerourile de la început se ignoră. */
    fun type(digit: Int): String {
        require(digit in 0..9)
        if (text.isEmpty() && digit == 0) return text
        if (text.length < maxDigits) text += digit
        return text
    }

    /** Numărul tastat (și golește câmpul), sau null dacă nu s-a tastat nimic. */
    fun take(): Int? = text.toIntOrNull().also { text = "" }

    fun clear() {
        text = ""
    }
}
