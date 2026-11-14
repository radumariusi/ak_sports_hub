package ro.ak.sportshub.core.model

/**
 * Calitatea unei variante de canal, așa cum o declară playlistul (nume, grup, atribute).
 * Nu se verifică stream-ul: eticheta e afișată mereu, ca să știi ce alegi.
 */
enum class Quality(val label: String, val rank: Int) {
    UHD("4K", 4),
    FHD("FHD", 3),
    HD("HD", 2),
    SD("SD", 1),
    UNKNOWN("?", 0);

    fun isAtLeast(other: Quality): Boolean = rank >= other.rank
}
