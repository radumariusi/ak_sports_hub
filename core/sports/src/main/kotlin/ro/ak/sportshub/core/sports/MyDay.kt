package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Raportul zilei (C4): câte meciuri favorite sunt azi și cum stau cu sursele, pentru cardul din Sports Hub.
 * [focus] e meciul care are nevoie de tine (fără surse, apoi doar cu presupuneri), cu salt la diagnosticul lui.
 */
data class DailyReport<T>(
    val total: Int,
    /** Cu cel puțin o sursă găsită efectiv (EPG, post anunțat, eveniment, confirmată). */
    val sure: Int,
    /** Doar cu presupuneri (drepturi TV, canale dedicate, învățate). */
    val guessOnly: Int,
    val none: Int,
    /** Câte meciuri se suprapun cu alt meci favorit (A2). */
    val overlapping: Int,
    val focus: T?,
) {
    val text: String
        get() = buildList {
            add("Azi: " + if (total == 1) "un meci favorit" else "$total meciuri favorite")
            if (sure > 0) add("$sure cu surse sigure")
            if (guessOnly > 0) add("$guessOnly doar presupuse")
            if (none > 0) add("$none fără surse")
            if (overlapping > 0) add("$overlapping se suprapun")
        }.joinToString(" · ")
}

object DailyReports {

    /**
     * Meciurile favorite de azi (și cele live) care nu s-au terminat. Null dacă nu e niciunul.
     * Doar calcul local, din lista deja calculată.
     */
    fun <T> build(
        items: List<T>,
        match: (T) -> Match,
        summary: (T) -> SourceSummary,
        isFavorite: (Match) -> Boolean,
        now: Long,
        zone: ZoneId,
    ): DailyReport<T>? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val mine = items.filter { t ->
            val m = match(t)
            m.status != MatchStatus.FINISHED && m.end > now && (m.isLive || MatchLists.day(m, zone) == today) && isFavorite(m)
        }.sortedWith(compareBy<T> { match(it).start }.thenBy { match(it).title })
        if (mine.isEmpty()) return null
        val sure = mine.count { summary(it).sure > 0 }
        val none = mine.count { summary(it).count == 0 }
        val guessOnly = mine.size - sure - none
        val ms = mine.map(match)
        val overlapping = ms.count { m -> FavoriteConflicts.overlapping(m, ms).isNotEmpty() }
        val focus = mine.firstOrNull { summary(it).count == 0 } ?: mine.firstOrNull { summary(it).sure == 0 }
        return DailyReport(mine.size, sure, guessOnly, none, overlapping, focus)
    }
}

/**
 * Conflictele dintre meciurile favorite (A2): două meciuri care se joacă în același timp. Cardul arată
 * „se suprapune cu …” și o ordine propusă. Doar calcul local; o singură conexiune, deci un singur meci odată.
 */
object FavoriteConflicts {

    private val time = DateTimeFormatter.ofPattern("HH:mm")

    /** Minutul pauzei (aproximativ, cu prelungirile primei reprize), pe sport; null = fără pauză lungă. */
    private fun breakAfterMinutes(sport: Sport?): Long? = when (sport) {
        Sport.FOOTBALL -> 47
        Sport.RUGBY -> 42
        Sport.BASKETBALL -> 70
        Sport.AMERICAN_FOOTBALL -> 95
        Sport.ICE_HOCKEY -> 35
        else -> null
    }

    /** Meciurile din [others] (deja favorite) care se suprapun cu [m], fără [m]. */
    fun overlapping(m: Match, others: List<Match>): List<Match> =
        if (m.status == MatchStatus.FINISHED) emptyList() else others.filter { o ->
            o.id != m.id && o.status != MatchStatus.FINISHED && o.start < m.end && m.start < o.end
        }.sortedBy { it.start }

    /** Ordinea propusă pentru două meciuri care se suprapun. */
    fun advice(a: Match, b: Match, zone: ZoneId): String {
        val (first, second) = if (a.start <= b.start) a to b else b to a
        fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(time)
        val pause1 = breakAfterMinutes(first.sport)?.let { first.start + TimeUnit.MINUTES.toMillis(it) }
        val pause2 = breakAfterMinutes(second.sport)?.let { second.start + TimeUnit.MINUTES.toMillis(it) }
        return when {
            // Al doilea începe după pauza primului: primul până la pauză, apoi al doilea.
            pause1 != null && second.start >= pause1 - TimeUnit.MINUTES.toMillis(5) ->
                "Propunere: ${first.title} până la pauză (~${at(pause1)}), apoi ${second.title}" +
                    (pause2?.let { "; la pauza lui (~${at(it)}) te poți întoarce" } ?: "") + "."
            // Încep aproape împreună: unul, iar la pauza lui celălalt (zapping-ul la pauză din player face asta singur).
            pause1 != null -> "Propunere: ${first.title}, iar la pauză (~${at(pause1)}) treci pe ${second.title}; " +
                "zapping-ul la pauză din player te mută și te întoarce singur."
            else -> "Propunere: ${first.title} (începe primul), apoi ${second.title}."
        }
    }
}

/**
 * Furnizorii în pauză după un refuz (E4), spus pe față în Sports Hub: „LiveSoccerTV în pauză până la 22:10,
 * sursele pot fi mai puține”. Pauza se vede altfel doar în jurnal.
 */
object PausedProviders {

    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val day = DateTimeFormatter.ofPattern("dd.MM")

    /** [paused]: (numele furnizorului, până când e în pauză, ms). Null dacă niciunul nu e în pauză acum. */
    fun notice(paused: List<Pair<String, Long>>, now: Long, zone: ZoneId): String? {
        val active = paused.filter { it.second > now }.sortedBy { it.second }
        if (active.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun until(ms: Long): String {
            val t = Instant.ofEpochMilli(ms).atZone(zone)
            return when (t.toLocalDate()) {
                today -> "până la " + t.format(time)
                today.plusDays(1) -> "până mâine la " + t.format(time)
                else -> "până pe " + t.format(day) + " la " + t.format(time)
            }
        }
        return if (active.size == 1) {
            val (name, at) = active.first()
            "$name în pauză ${until(at)} (a refuzat cererile): sursele și meciurile pot fi mai puține"
        } else {
            active.joinToString(", ") { (name, at) -> "$name ${until(at)}" } +
                " în pauză (au refuzat cererile): sursele și meciurile pot fi mai puține"
        }
    }
}
