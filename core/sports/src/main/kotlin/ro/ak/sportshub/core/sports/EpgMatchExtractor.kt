package ro.ak.sportshub.core.sports

/**
 * Recunoaște meciurile în titlurile EPG. Exemple tratate:
 * `Live: Arsenal v Chelsea`, `Premier League: Arsenal - Chelsea`, `Fotbal: FCSB – CFR Cluj (live)`,
 * `NBA: Lakers @ Celtics`, `Liga 1 | FCSB vs. Rapid`.
 *
 * Reluările, rezumatele și emisiunile de studio nu sunt meciuri. Separatorul „-” e ambiguu („Știri - ediția
 * de seară”), așa că se acceptă doar pe un canal de sport, cu o categorie de sport sau cu sportul în titlu.
 */
object EpgMatchExtractor {

    private val liveMarker = Regex("""(?i)(\(\s*(live|direct|d)\s*\)|\[\s*(live|direct)\s*]|^\s*(live|direct|in direct|în direct)\s*[:|\-–]?\s+|\s+(live|direct)\s*$)""")
    private val replayWords = listOf(
        "replay", "reluare", "rezumat", "highlights", "best of", "magazin", "magazine", "preview", "review",
        "studio", "analysis", "analiza", "stiri", "news", "talk", "show", "goals", "goluri", "classic", "clasic",
        "documentar", "documentary", "(r)", "[r]", "repeat", "delayed", "deferred", "înregistrare", "inregistrare",
        // Reluările în alte limbi (Sky Italia, DAZN, Canal+, Polsat…): fără ele, o reluare de dimineață părea „LIVE”.
        "replica", "differita", "sintesi", "repeticion", "repetición", "diferido", "resumen", "rediffusion", "differe",
        "différé", "resume", "résumé", "wiederholung", "zusammenfassung", "aufzeichnung", "reprise", "resumo",
        "powtorka", "powtórka", "retransmisja", "skrot", "skrót", "(rpt)", "(replay)", "(rep)",
        // Studio înainte și după meci (C4).
        "pre match", "prematch", "post match", "postmatch", "build up", "countdown", "reaction", "debrief", "kick off show",
    )
    private val strongSeparators = listOf(
        Regex("""\s+(?i:vs\.?|v\.?)\s+"""),
        Regex("""\s+@\s+"""),
    )
    private val dashSeparator = Regex("""\s+[-–—]\s+""")
    private val contextSplit = Regex("""\s*[:|]\s+|\s*[:|]\s*(?=\p{Lu})""")
    private val trailingNoise = Regex("""\s*(\(.*?\)|\[.*?]|,.*|\s+\d+\s*[-:]\s*\d+\s*$)\s*$""")
    private val nonTeamWords = setOf(
        "highlights", "magazin", "news", "stiri", "studio", "preview", "rezumat", "analysis", "talk", "show",
        "episode", "episodul", "sezonul", "season", "film", "serial", "the movie", "part",
    )

    /**
     * @param channelIsSport canalul (din playlist) e de sport: atunci se acceptă și separatorul „-”.
     * @return meciul citit din emisiune, sau null dacă nu arată a meci.
     */
    fun extract(b: EpgBroadcast, channelIsSport: Boolean): ExtractedMatch? {
        if (nonEvent(b.category)) return null
        if (datedEarlier(b.title, b.start) || b.subtitle?.let { datedEarlier(it, b.start) } == true) return null
        val title = withoutDate(b.title)
        return extract(title, b.category, channelIsSport, context = null)
            ?: b.subtitle?.let { extract(withoutDate(it), b.category, channelIsSport, context = title) }
            ?: descriptionLine(b, channelIsSport)?.let { extract(it, b.category, channelIsSport, context = title) }
    }

    /** Categoria spune că nu e o transmisie („Sports non-event”, „Sport magazine”, „Talk”). */
    fun nonEvent(category: String?): Boolean {
        val c = category?.let { " " + Sport.fold(it) + " " } ?: return false
        return nonEventCategories.any { c.contains(it) }
    }

    private val nonEventCategories = listOf(" non event ", " nonevent ", " sports magazine ", " sport magazine ", " talk ", " news ")

    /**
     * Prima linie a descrierii, ca subtitlu: multe EPG-uri pun acolo echipele („Arsenal v Chelsea”) sub un titlu
     * generic („Premier League Football”). Doar pe canalele de sport, doar un rând scurt cu „v”, „vs” sau „@”
     * (o frază din descriere nu e un meci).
     */
    private fun descriptionLine(b: EpgBroadcast, channelIsSport: Boolean): String? {
        if (!channelIsSport) return null
        val line = b.description?.trim()?.lineSequence()?.firstOrNull()?.trim()?.trimEnd('.') ?: return null
        if (line.length !in 5..70 || datedEarlier(line, b.start)) return null
        if (strongSeparators.none { it.containsMatchIn(line) }) return null
        return withoutDate(line)
    }

    /** O dată în titlu („Norway - Wales 14/11/26”, „15.11.2026”). */
    private val titleDate = Regex("""(?<!\d)(\d{1,2})[./-](\d{1,2})[./-](\d{4}|\d{2})(?!\d)""")

    /**
     * Titlul are o dată dinaintea zilei emisiunii: e înregistrarea unui meci jucat deja (o reluare), nu un meci LIVE.
     */
    fun datedEarlier(title: String, start: Long): Boolean {
        val m = titleDate.find(title) ?: return false
        val (d, mo, y) = m.destructured
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        val date = runCatching { java.time.LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull() ?: return false
        // Ziua de după dată, de la 10:00 UTC: un meci de seară din America (după miezul nopții UTC) e încă live.
        val replayFrom = date.plusDays(1).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        return start >= replayFrom
    }

    private val spaces = Regex("""\s+""")

    /** Titlul fără dată (ca data să nu intre în numele echipei). */
    private fun withoutDate(title: String): String =
        if (titleDate.containsMatchIn(title)) title.replace(titleDate, " ").replace(spaces, " ").trim() else title

    fun extract(title: String, category: String?, channelIsSport: Boolean, context: String? = null): ExtractedMatch? {
        val raw = title.trim()
        if (raw.isEmpty()) return null
        val folded = Sport.fold(raw)
        val lowerRaw = raw.lowercase()
        if (replayWords.any { w -> if (w.startsWith("(") || w.startsWith("[")) lowerRaw.contains(w) else " $folded ".contains(" $w ") }) {
            return null
        }

        var explicitLive = false
        var text = raw
        while (true) {
            val m = liveMarker.find(text) ?: break
            explicitLive = true
            text = text.removeRange(m.range).trim()
        }

        // Contextul (competiția sau sportul) stă înaintea ultimului „:” sau „|”.
        val segments = text.split(contextSplit).map { it.trim() }.filter { it.isNotEmpty() }
        val teamIndex = segments.indexOfLast { s -> strongSeparators.any { it.containsMatchIn(s) } || dashSeparator.containsMatchIn(s) }
        if (teamIndex < 0) return null
        val contextParts = (listOfNotNull(context) + segments.take(teamIndex)).toMutableList()
        val teamsPart = segments[teamIndex]

        val strong = strongSeparators.firstOrNull { it.containsMatchIn(teamsPart) }
        val pieces: List<String>
        var reversed = false
        if (strong != null) {
            pieces = teamsPart.split(strong, limit = 2)
            reversed = strong.pattern.contains("@")
            // „Fotbal - Liga 1 - Arsenal v Chelsea”: ce e înainte de ultima liniuță din echipa gazdă e context.
            val homeParts = pieces[0].split(dashSeparator)
            if (homeParts.size > 1) {
                contextParts += homeParts.dropLast(1)
                return build(homeParts.last(), pieces[1], contextParts, category, explicitLive, reversed, strongSeparator = true, channelIsSport)
            }
        } else {
            val parts = teamsPart.split(dashSeparator)
            if (parts.size < 2) return null
            contextParts += parts.dropLast(2)
            pieces = parts.takeLast(2)
        }
        return build(pieces[0], pieces[1], contextParts, category, explicitLive, reversed, strongSeparator = strong != null, channelIsSport)
    }

    /** Sporturile în care un program e de obicei un eveniment fără două echipe. */
    private val singleEventSports = setOf(Sport.MOTORSPORT, Sport.CYCLING, Sport.GOLF, Sport.FIGHTING, Sport.DARTS, Sport.SNOOKER, Sport.TENNIS)

    /** Cuvinte care arată o sesiune sau o zi de concurs (nu o emisiune despre sport). */
    private val sessionWords = listOf(
        "grand prix", "gp", "race", "cursa", "carrera", "qualifying", "calificari", "calificarile", "clasificacion", "sprint",
        "practice", "antrenamente", "libres", "stage", "etapa", "tappa", "round", "runda", "day", "ziua", "final", "finala",
        "semifinal", "semifinale", "gala", "fight night", "main card", "prelims", "tour de", "open", "masters", "championship",
        "campionatul", "world cup", "cupa mondiala", "classic",
    )
    private val numberedEvent = Regex("""\b(ufc|pfl|bellator|oktagon|ksw)\s*\d+""")

    /**
     * Un eveniment fără două echipe în EPG: „Formula 1: Marele Premiu al Qatarului - Cursa (live)”,
     * „Ciclism: Turul Franței, etapa 12”, „UFC 310: Main Card”. Doar pe canale de sport (sau cu categorie
     * de sport), doar pentru sporturile cu astfel de evenimente și doar cu „live” sau cu o sesiune în titlu.
     */
    fun extractEvent(b: EpgBroadcast, channelIsSport: Boolean): ExtractedMatch? {
        val categoryIsSport = b.category != null && Sport.fold(b.category).let { c -> SPORT_CATEGORY.any { c.contains(it) } }
        val categorySport = Sport.detect(b.category)
        if (!channelIsSport && !categoryIsSport && categorySport == null) return null
        if (nonEvent(b.category) || datedEarlier(b.title, b.start)) return null
        val raw = withoutDate(b.title).trim()
        if (raw.isEmpty()) return null
        val folded = Sport.fold(raw)
        val lowerRaw = raw.lowercase()
        if (replayWords.any { w -> if (w.startsWith("(") || w.startsWith("[")) lowerRaw.contains(w) else " $folded ".contains(" $w ") }) {
            return null
        }
        var explicitLive = false
        var text = raw
        while (true) {
            val m = liveMarker.find(text) ?: break
            explicitLive = true
            text = text.removeRange(m.range).trim()
        }
        val sport = Sport.detect(text, b.subtitle) ?: categorySport ?: return null
        if (sport !in singleEventSports) return null
        val f = " ${Sport.fold(text)} ${Sport.fold(b.subtitle.orEmpty())} "
        if (!explicitLive && sessionWords.none { f.contains(" $it ") } && !numberedEvent.containsMatchIn(f)) return null

        val segments = text.split(contextSplit).map { it.trim().trim('-', '–', '—', ' ') }.filter { it.isNotEmpty() && !isLiveWord(it) }
        if (segments.isEmpty()) return null
        var name = segments.last()
        var context = segments.dropLast(1)
        // „Formula 1 - Cursa”: ultima parte nu spune care eveniment e, deci numele e tot titlul.
        if (segments.size > 1 && EventNames.significant(name).isEmpty()) {
            name = segments.joinToString(" - ")
            context = segments.dropLast(1)
        }
        if (name.length > 80) return null
        // Subtitlul poate da locul („Abu Dhabi”) când titlul e generic.
        if (EventNames.significant(name).isEmpty() && !b.subtitle.isNullOrBlank() && b.subtitle.length <= 60) name = "$name - ${b.subtitle.trim()}"
        return ExtractedMatch(
            home = name,
            away = "",
            competition = context.lastOrNull()?.takeIf { it.length <= 50 },
            sport = sport,
            explicitLive = explicitLive,
        )
    }

    private fun build(
        homeRaw: String,
        awayRaw: String,
        contextParts: List<String>,
        category: String?,
        explicitLive: Boolean,
        reversed: Boolean,
        strongSeparator: Boolean,
        channelIsSport: Boolean,
    ): ExtractedMatch? {
        val home = cleanTeam(homeRaw) ?: return null
        val away = cleanTeam(awayRaw) ?: return null
        if (TeamNames.key(home) == TeamNames.key(away)) return null

        val contextSport = Sport.detect(*contextParts.toTypedArray())
        val categorySport = Sport.detect(category)
        val categoryIsSport = category != null && Sport.fold(category).let { c -> SPORT_CATEGORY.any { c.contains(it) } }
        if (!strongSeparator && !channelIsSport && contextSport == null && categorySport == null && !categoryIsSport) return null
        if (strongSeparator && !channelIsSport && contextSport == null && categorySport == null && !categoryIsSport && !explicitLive) {
            // „X v Y” pe un canal oarecare, fără nicio urmă de sport: mai degrabă un film sau o emisiune.
            return null
        }

        // Contextul care nu e doar numele sportului e competiția („Premier League”, „Liga 1”).
        val competition = contextParts
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isSportName(it) && !isLiveWord(it) }
            .lastOrNull()
        return ExtractedMatch(
            home = if (reversed) away else home,
            away = if (reversed) home else away,
            competition = competition,
            sport = contextSport ?: categorySport ?: Sport.detect(competition),
            explicitLive = explicitLive,
        )
    }

    private fun isSportName(text: String): Boolean {
        val f = Sport.fold(text)
        return f in SPORT_CATEGORY || Sport.entries.any { s -> Sport.fold(s.label) == f || s.sportsDbName.lowercase() == f || f in s.keywords.take(3) }
    }

    private fun isLiveWord(text: String): Boolean = Sport.fold(text) in setOf("live", "direct", "in direct")

    private fun cleanTeam(raw: String): String? {
        var t = raw.trim()
        repeat(3) { t = t.replace(trailingNoise, "").trim() }
        t = t.trim('-', '–', '—', ':', '|', '.', ' ')
        if (t.length < 2 || t.length > 40) return null
        if (t.none { it.isLetter() }) return null
        val words = t.split(Regex("""\s+"""))
        if (words.size > 5) return null
        val f = " ${Sport.fold(t)} "
        if (nonTeamWords.any { f.contains(" $it ") }) return null
        return t
    }

    private val SPORT_CATEGORY = listOf("sport", "sports", "sporturi", "football", "fotbal", "soccer")
}
