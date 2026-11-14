package ro.ak.sportshub.core.sports

/**
 * Dicționarul de posturi încorporat: nume vechi și prescurtări folosite de site-uri și EPG-uri,
 * pentru posturi care în playlisturi apar cu alt nume. Se compară în forma [StationIndex.compact]
 * (fără spații, fără plural, numerele în cifre), deci „Sky Sports Main Event” și „SkySport MainEvent” sunt la fel.
 *
 * Dicționarul tău (posturile pe care le legi de mână de un canal) e separat: [SportsKnowledge.stationAliases].
 */
object StationNames {

    /** Grupuri de nume pentru același post. */
    private val groups: List<List<String>> = listOf(
        listOf("Sky Sports Main Event", "Sky Sp Main Event", "Sky Sports ME", "Sky Sp ME", "Sky Main Event"),
        listOf("Sky Sports Premier League", "Sky Sp PL", "Sky Sports PL", "Sky Premier League"),
        listOf("Sky Sports Football", "Sky Sp Football", "Sky Sports Fball", "Sky Sp Fball"),
        listOf("Sky Sports F1", "Sky Sp F1", "Sky Sports Formula 1"),
        listOf("Sky Sports Arena", "Sky Sp Arena"),
        listOf("Sky Sports Action", "Sky Sp Action"),
        listOf("Sky Sports Mix", "Sky Sp Mix"),
        listOf("Sky Sports Golf", "Sky Sp Golf"),
        listOf("Sky Sports Cricket", "Sky Sp Cricket"),
        listOf("Sky Sports Tennis", "Sky Sp Tennis"),
        listOf("Sky Sports News", "Sky Sp News"),
        listOf("TNT Sports 1", "BT Sport 1"),
        listOf("TNT Sports 2", "BT Sport 2"),
        listOf("TNT Sports 3", "BT Sport 3"),
        listOf("TNT Sports 4", "BT Sport 4", "BT Sport ESPN"),
        listOf("TNT Sports Ultimate", "BT Sport Ultimate"),
        listOf("Eurosport 1", "Eurosport"),
        listOf("Digi Sport 1", "DigiSport 1", "Digi Sport"),
        listOf("Prima Sport 1", "Prima Sport", "Look Sport"),
        listOf("Prima Sport 2", "Look Sport Plus", "Look Plus"),
        listOf("Orange Sport 1", "Orange Sport", "Telekom Sport 1"),
        listOf("Premier Sports 1", "Premier Sport 1", "Premier Sports"),
        listOf("Sport TV 1", "SportTV 1", "Sport TV"),
        listOf("DAZN 1", "DAZN"),
        listOf("beIN Sports 1", "beIN Sport 1", "beIN Sports"),
        listOf("Canal+ Sport", "Canal Plus Sport", "Canal+ Sports"),
        listOf("Arena Sport 1", "Arena Sport"),
        listOf("Sport Klub 1", "SportKlub 1", "Sport Klub", "SK 1"),
        listOf("Nova Sport 1", "Nova Sport"),
        listOf("ESPN", "ESPN 1"),
        listOf("Viaplay Sports 1", "V Sport 1", "Viaplay Sport 1"),
    )

    private val byCompact: Map<String, List<String>> = buildMap {
        for (g in groups) for (n in g) put(StationIndex.compact(n), g)
    }

    /** Numele [name] și celelalte nume ale aceluiași post din dicționar (primul e [name]). */
    fun variants(name: String): List<String> {
        val same = byCompact[StationIndex.compact(name)].orEmpty()
        return (listOf(name) + same).distinctBy { StationIndex.compact(it) }
    }

    /**
     * „Digi Sport 1 Romania” → („Digi Sport 1”, „romania”); „Sky Sports Main Event (UK)” → („Sky Sports Main Event”, „uk”).
     * Fără țară la final: numele neschimbat și null.
     */
    fun splitCountry(name: String): Pair<String, String?> {
        val words = Sport.fold(name).split(' ').filter { it.isNotEmpty() }
        for (n in minOf(3, words.size - 1) downTo 1) {
            val tail = words.takeLast(n).joinToString(" ")
            if (Countries.isCountry(tail)) return words.dropLast(n).joinToString(" ") to tail
        }
        return name to null
    }
}
