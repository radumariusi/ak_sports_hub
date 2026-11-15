package ro.ak.sportshub.core.sports

/**
 * Cum arată o echipă: sigla (adresă), culoarea principală și cea secundară („0606fa”, fără #), codul scurt și forma
 * (ultimele rezultate, „WDLWW” cum le dă ESPN). Toate sunt opționale: vin doar de la furnizorii care le dau.
 */
data class TeamLook(
    val logo: String? = null,
    val color: String? = null,
    val altColor: String? = null,
    val code: String? = null,
    val form: String? = null,
) {
    /** Câmpurile lipsă le iau din [older] (un furnizor dă sigla, altul culorile). */
    fun over(older: TeamLook?): TeamLook = if (older == null) this else TeamLook(
        logo = logo ?: older.logo,
        color = color ?: older.color,
        altColor = altColor ?: older.altColor,
        code = code ?: older.code,
        form = form ?: older.form,
    )

    /** Forma ca rezultate (V/E/Î), cel mult ultimele 5. */
    fun formResults(): List<FormResult> = TeamLooks.form(form)

    val isEmpty: Boolean get() = logo == null && color == null && altColor == null && code == null && form == null
}

/**
 * Cartea siglelor și a culorilor: echipele după cheia de grupare ([TeamNames.key], aceeași pentru numele din EPG și
 * de la furnizori) și competițiile după numele împăturit. Se învață din programul furnizorilor, fără cereri în plus,
 * și se păstrează într-un fișier text ([encode] / [decode]).
 */
class TeamLookBook private constructor(
    private val teams: Map<String, TeamLook>,
    private val competitions: Map<String, String>,
) {
    constructor() : this(emptyMap(), emptyMap())

    val teamCount: Int get() = teams.size
    val competitionCount: Int get() = competitions.size

    /**
     * Echipa după nume: cheia exactă, altfel singura echipă din carte care e aceeași după [TeamNames.same]
     * („Brighton” din EPG → „Brighton & Hove Albion” de la ESPN). Rezultatele se țin minte (UI-ul întreabă des).
     */
    fun team(name: String?): TeamLook? {
        if (name.isNullOrBlank() || teams.isEmpty()) return null
        found[name]?.let { return it.orElse(null) }
        val look = teams[TeamNames.key(name)] ?: fuzzy(name)
        found[name] = java.util.Optional.ofNullable(look)
        return look
    }

    private val found = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<TeamLook>>()

    /** Cuvânt (de cel puțin 3 litere) → cheile echipelor care îl conțin; făcut o singură dată pe carte. */
    private val byToken: Map<String, List<String>> by lazy {
        val m = HashMap<String, MutableList<String>>()
        teams.keys.forEach { k -> TeamNames.parts(k).core.filter { it.length >= 3 }.forEach { m.getOrPut(it) { ArrayList(2) }.add(k) } }
        m
    }

    private fun fuzzy(name: String): TeamLook? {
        val p = TeamNames.parts(name)
        val candidates = p.core.filter { it.length >= 3 }.flatMap { byToken[it].orEmpty() }.toSet()
        val same = candidates.filter { TeamNames.same(p, TeamNames.parts(it)) }
        return if (same.size == 1) teams[same.first()] else null
    }

    /** Sigla competiției; „ATP · Paris Masters” găsește și sigla ATP. */
    fun competition(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return competitions[Sport.fold(name)] ?: competitions[Sport.fold(name.substringBefore(" · "))]
    }

    /**
     * O carte nouă cu ce aduc [events] (ESPN câștigă la culori, fiind singurul care le dă), sau aceeași carte dacă nu
     * s-a schimbat nimic.
     */
    fun learn(events: Collection<SportEvent>): TeamLookBook {
        var t: HashMap<String, TeamLook>? = null
        var c: HashMap<String, String>? = null
        // ESPN la final, ca datele lui să treacă peste celelalte.
        val ordered = events.filter { it.provider != Espn.PROVIDER } + events.filter { it.provider == Espn.PROVIDER }
        for (e in ordered) {
            listOf(e.home to e.homeLook, e.away to e.awayLook).forEach { (name, look) ->
                if (look == null || look.isEmpty || name.isBlank()) return@forEach
                val key = TeamNames.cachedKey(name)
                if (key.isBlank()) return@forEach
                val current = (t ?: teams)[key]
                val next = look.over(current)
                if (next != current) {
                    if (t == null) t = HashMap(teams)
                    t!![key] = next
                }
            }
            val logo = e.competitionLogo
            val comp = e.competition
            if (logo != null && !comp.isNullOrBlank()) {
                listOf(comp, comp.substringBefore(" · ")).forEach { n ->
                    val key = Sport.fold(n)
                    if (key.isNotBlank() && (c ?: competitions)[key] != logo) {
                        if (c == null) c = HashMap(competitions)
                        c!![key] = logo
                    }
                }
            }
        }
        return if (t == null && c == null) this else TeamLookBook(t ?: teams, c ?: competitions)
    }

    /** O linie pe echipă („T\tcheie\tsiglă\tculoare\tsecundară\tcod\tformă”) și pe competiție („C\tcheie\tsiglă”). */
    fun encode(): String = buildString {
        append(VERSION).append('\n')
        teams.forEach { (k, v) ->
            append("T\t").append(k).append('\t').append(v.logo.orEmpty()).append('\t').append(v.color.orEmpty()).append('\t')
                .append(v.altColor.orEmpty()).append('\t').append(v.code.orEmpty()).append('\t').append(v.form.orEmpty()).append('\n')
        }
        competitions.forEach { (k, v) -> append("C\t").append(k).append('\t').append(v).append('\n') }
    }

    companion object {
        private const val VERSION = "aspect-1"

        fun decode(text: String?): TeamLookBook {
            if (text == null) return TeamLookBook()
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != VERSION) return TeamLookBook()
            val teams = HashMap<String, TeamLook>()
            val comps = HashMap<String, String>()
            for (line in lines) {
                val p = line.split('\t')
                when (p.firstOrNull()) {
                    "T" -> if (p.size >= 7 && p[1].isNotBlank()) {
                        val look = TeamLook(
                            logo = p[2].ifBlank { null },
                            color = p[3].ifBlank { null },
                            altColor = p[4].ifBlank { null },
                            code = p[5].ifBlank { null },
                            form = p[6].ifBlank { null },
                        )
                        if (!look.isEmpty) teams[p[1]] = look
                    }
                    "C" -> if (p.size >= 3 && p[1].isNotBlank() && p[2].isNotBlank()) comps[p[1]] = p[2]
                }
            }
            return TeamLookBook(teams, comps)
        }
    }
}

/** Ajutoare pure pentru siglele și culorile echipelor. */
object TeamLooks {

    /** „0606fa” / „#0606FA” → 0xFF0606FA; null dacă nu e o culoare. */
    fun argb(hex: String?): Long? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 || h.any { Character.digit(it, 16) < 0 }) return null
        return 0xFF000000L or h.toLong(16)
    }

    /** Luminanța relativă (0 = negru, 1 = alb). */
    fun luminance(argb: Long): Double {
        fun ch(v: Long): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = ch((argb shr 16) and 0xFF)
        val g = ch((argb shr 8) and 0xFF)
        val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Culoarea benzii echipei pe fundalul închis al aplicației (#0B0F14): principala, dacă se vede; altfel secundara;
     * altfel nimic (o bandă neagră pe negru nu spune nimic).
     */
    fun band(look: TeamLook?): Long? {
        look ?: return null
        return listOfNotNull(argb(look.color), argb(look.altColor)).firstOrNull { luminance(it) >= MIN_BAND_LUMINANCE }
    }

    private const val MIN_BAND_LUMINANCE = 0.035

    /** „WDLWW” (ESPN) sau „VEÎ” → rezultate, cel mult ultimele [max], în ordinea dată de furnizor. */
    fun form(text: String?, max: Int = 5): List<FormResult> {
        val t = text?.trim()?.uppercase() ?: return emptyList()
        return t.mapNotNull { ch ->
            when (ch) {
                'W', 'V' -> FormResult.WIN
                'D', 'E' -> FormResult.DRAW
                'L', 'Î' -> FormResult.LOSS
                else -> null
            }
        }.takeLast(max)
    }

    /**
     * Adresa unei sigle mici, ca descărcarea să fie de câțiva KB: ESPN prin serviciul lor de redimensionare,
     * TheSportsDB cu varianta „/small”. Altele rămân cum sunt.
     */
    fun smallLogo(url: String, px: Int = 96): String {
        val u = url.trim()
        val espn = "a.espncdn.com/i/"
        val at = u.indexOf(espn)
        if (at >= 0 && "combiner" !in u) {
            val path = u.substring(at + "a.espncdn.com".length).substringBefore('?')
            return "https://a.espncdn.com/combiner/i?img=$path&w=$px&h=$px"
        }
        if ("thesportsdb.com/images/" in u && !u.endsWith("/small") && !u.endsWith("/tiny") && !u.endsWith("/medium")) {
            return "$u/small"
        }
        return u
    }
}
