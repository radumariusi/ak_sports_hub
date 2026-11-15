package ro.ak.sportshub.core.epg

import ro.ak.sportshub.core.common.ChannelNameParser

/**
 * Cheile de potrivire pentru nume de canale. „UK: Sky Sports Main Event FHD”, „Sky Sports Main Event”
 * și id-ul `SkySportsMainEvent.uk` ajung toate la `skysportsmainevent` (cu țara `uk` unde se știe).
 */
object EpgNames {

    private val idCountry = Regex("""\.([a-z]{2})$""", RegexOption.IGNORE_CASE)
    private val camel = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=\d)""")
    private val countryKey = Regex("""^([a-z]{2,3}):(.+)$""")

    /** Numele fără calitate, țară, semne și spații; null dacă nu rămâne nimic. */
    data class Key(val name: String, val country: String?)

    fun ofName(name: String?, group: String? = null): Key? {
        if (name.isNullOrBlank()) return null
        val parsed = ChannelNameParser.parse(name, group)
        val m = countryKey.find(parsed.groupKey)
        val core = (m?.groupValues?.get(2) ?: parsed.groupKey).replace(" ", "")
        if (core.isEmpty()) return null
        return Key(core, parsed.country?.lowercase())
    }

    /** Id-ul XMLTV ca nume: `SkySportsMainEvent.uk` → `skysportsmainevent` + `uk`. */
    fun ofXmltvId(id: String): Key? {
        val country = idCountry.find(id)?.groupValues?.get(1)?.lowercase()
        val base = if (country != null) id.dropLast(3) else id
        return ofName(base.replace(camel, " ").replace('_', ' ').replace('.', ' '))?.let {
            it.copy(country = it.country ?: country)
        }
    }

    /** Toate cheile sub care poate fi găsit un canal EPG. */
    fun keysOf(channel: XmltvChannel): List<Key> = buildList {
        channel.displayNames.forEach { n -> ofName(n)?.let(::add) }
        ofXmltvId(channel.id)?.let(::add)
    }.distinct()
}

/** Un canal din EPG-urile salvate. [ref] e id-ul lui în baza de date. */
data class EpgChannelRef(
    val ref: Long,
    val sourceKey: String,
    val xmltvId: String,
    val displayNames: List<String>,
)

/** Ce știm despre un canal din playlist, fără să citim stream-ul. */
data class ChannelForEpg(
    val id: Long,
    val tvgId: String?,
    val tvgName: String?,
    val name: String,
    val group: String?,
)

/**
 * Cheile de potrivire ale unui canal din playlist, calculate o singură dată pe rulare
 * (analiza numelui e partea scumpă a potrivirii; cu 30.000 de canale se făcea de câteva ori la rând).
 */
class ChannelKeys(
    val id: Long,
    val playlistId: Long,
    /** `tvg-id` cu litere mici, sau null. */
    val tvgId: String?,
    /** Cheile din `tvg-name`, apoi din nume (în ordinea încercării). */
    val names: List<EpgNames.Key>,
) {
    companion object {
        fun of(c: ChannelForEpg, playlistId: Long = 0): ChannelKeys = ChannelKeys(
            id = c.id,
            playlistId = playlistId,
            tvgId = c.tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            names = listOfNotNull(c.tvgName, c.name).mapNotNull { EpgNames.ofName(it, c.group) },
        )
    }
}

/**
 * Potrivirea canal ↔ EPG, pe trepte (prima treaptă cu un rezultat câștigă):
 * 1. `tvg-id` identic cu id-ul XMLTV (fără să conteze literele mari);
 * 2. `tvg-name`, apoi numele canalului, comparate cu numele din EPG după normalizare
 *    (fără calitate, fără diacritice, fără spații), întâi cu aceeași țară, apoi fără țară.
 *    Dacă ambele țări sunt cunoscute și diferă, nu se potrivesc.
 * La egalitate câștigă sursa cu prioritatea mai bună (EPG-ul propriu al playlistului, apoi cele externe).
 */
class EpgMatcher(channels: List<EpgChannelRef>, private val defaultPriority: (sourceKey: String) -> Int = { 0 }) {

    private val byId = HashMap<String, MutableList<EpgChannelRef>>()
    private val byName = HashMap<String, MutableList<Pair<EpgChannelRef, String?>>>()

    init {
        for (c in channels) {
            byId.getOrPut(c.xmltvId.lowercase()) { ArrayList(1) } += c
            val keys = EpgNames.keysOf(XmltvChannel(c.xmltvId, c.displayNames))
            // Țara canalului EPG (din id sau dintr-un nume) se aplică tuturor numelor lui.
            val country = keys.firstNotNullOfOrNull { it.country }
            for (name in keys.map { it.name }.distinct()) {
                byName.getOrPut(name) { ArrayList(1) } += c to country
            }
        }
    }

    /** @param priority prioritatea surselor pentru playlistul canalului (vezi `EpgSources.priorityFor`) */
    fun match(channel: ChannelForEpg, priority: (sourceKey: String) -> Int = defaultPriority): EpgChannelRef? =
        match(ChannelKeys.of(channel), priority)

    /** La fel, cu cheile calculate dinainte. */
    fun match(channel: ChannelKeys, priority: (sourceKey: String) -> Int = defaultPriority): EpgChannelRef? {
        channel.tvgId?.let { id ->
            byId[id]?.let { return best(it, priority) }
        }
        for (key in channel.names) {
            val candidates = byName[key.name] ?: continue
            if (key.country != null) {
                candidates.filter { it.second == key.country }.takeIf { it.isNotEmpty() }
                    ?.let { list -> return best(list.map { it.first }, priority) }
            }
            candidates.filter { key.country == null || it.second == null }.takeIf { it.isNotEmpty() }
                ?.let { list -> return best(list.map { it.first }, priority) }
        }
        return null
    }

    private fun best(list: List<EpgChannelRef>, priority: (String) -> Int): EpgChannelRef =
        list.minBy { priority(it.sourceKey) }

    companion object {
        /**
         * Cheile canalelor din playlisturi, ca la citirea unui EPG să păstrăm doar canalele folosite.
         * E mai permisiv decât [match] (fără verificarea țării); potrivirea exactă se face după salvare.
         */
        fun wantedKeys(channels: Sequence<ChannelForEpg>): WantedKeys =
            WantedKeys.Builder().apply { channels.forEach(::add) }.build()
    }
}

/** Ce canale EPG merită păstrate (vezi [EpgMatcher.wantedKeys]). */
class WantedKeys(private val ids: Set<String>, private val names: Set<String>) {
    val isEmpty: Boolean get() = ids.isEmpty() && names.isEmpty()

    /** Se schimbă când se schimbă canalele care contează; atunci EPG-ul se citește din nou. */
    val signature: String get() = "${ids.size}-${names.size}-${ids.hashCode()}-${names.hashCode()}"

    fun wants(channel: XmltvChannel): Boolean =
        channel.id.lowercase() in ids || EpgNames.keysOf(channel).any { it.name in names }

    fun wantsId(xmltvId: String): Boolean = xmltvId.lowercase() in ids

    /** Adună cheile canal cu canal (playlisturile mari se citesc pe pagini). */
    class Builder {
        private val ids = HashSet<String>()
        private val names = HashSet<String>()

        fun add(c: ChannelForEpg) = add(ChannelKeys.of(c))

        fun add(k: ChannelKeys) {
            k.tvgId?.let(ids::add)
            k.names.forEach { names += it.name }
        }

        fun build() = WantedKeys(ids, names)
    }
}
