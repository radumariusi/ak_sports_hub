package ro.ak.sportshub.core.sports

import kotlin.math.roundToInt

/**
 * Datele în plus despre un meci (calupul 1 de idei, 16.11.2026): șansele de câștig, presiunea pe minute,
 * harta șuturilor, jucătorii indisponibili, stadionul, arbitrul și vremea. Toate vin din răspunsuri deja cerute
 * pentru Pre-meci și Live (ESPN `summary`, pagina meciului FotMob), fără cereri în plus.
 */

/**
 * Șansele de câștig, între 0 și 1. [forecast] = previziunea de dinainte de meci (ESPN „Matchup Predictor”);
 * altfel valoarea de acum din [series] (șansele gazdelor după fiecare fază, în ordine).
 */
data class WinProbability(
    val home: Float,
    val draw: Float?,
    val away: Float,
    val series: List<Float> = emptyList(),
    val forecast: Boolean = false,
)

/** Presiunea la minutul [minute]: pozitiv = gazdele, negativ = oaspeții (-100…100). */
data class MomentumPoint(val minute: Float, val value: Int)

enum class ShotOutcome(val label: String) {
    GOAL("Gol"),
    SAVED("Pe poartă"),
    POST("Bară"),
    BLOCKED("Blocat"),
    MISSED("Pe lângă"),
}

/**
 * Un șut. [x] = cât de aproape de poarta atacată (0 = propria linie de poartă, 1 = poarta adversă), [y] = poziția
 * pe lățime (0…1, de sus în jos, ca pe ecran); aceeași orientare pentru ambele echipe, desenul le întoarce.
 */
data class Shot(
    val x: Float,
    val y: Float,
    val minute: Int?,
    val added: Int?,
    val home: Boolean,
    val player: String?,
    val outcome: ShotOutcome,
    val xg: Float?,
) {
    val minuteLabel: String? get() = minute?.let { m -> if (added != null && added > 0) "$m+$added′" else "$m′" }
}

enum class AbsenceKind(val label: String) {
    INJURED("Accidentat"),
    SUSPENDED("Suspendat"),
    DOUBTFUL("Incert"),
    OTHER("Indisponibil"),
}

/** Un jucător care lipsește (sau e incert); [detail] = accidentarea și revenirea, în română când o știm. */
data class AbsentPlayer(val name: String, val home: Boolean, val kind: AbsenceKind, val detail: String? = null)

/** Vremea la stadion: temperatura în °C, descrierea în română, șansa de ploaie (%). */
data class Weather(val celsius: Int?, val description: String?, val rainChance: Int? = null) {
    val label: String?
        get() = listOfNotNull(
            celsius?.let { "$it °C" },
            description,
            rainChance?.takeIf { it >= 20 }?.let { "ploaie $it%" },
        ).joinToString(", ").ifEmpty { null }
}

data class VenueInfo(
    val stadium: String? = null,
    val city: String? = null,
    val capacity: Int? = null,
    val referee: String? = null,
    val attendance: Int? = null,
    val weather: Weather? = null,
) {
    /** Ce lipsește aici se ia din [other]. */
    fun orElse(other: VenueInfo?): VenueInfo = if (other == null) this else VenueInfo(
        stadium = stadium ?: other.stadium,
        city = city ?: other.city,
        capacity = capacity ?: other.capacity,
        referee = referee ?: other.referee,
        attendance = attendance ?: other.attendance,
        weather = weather ?: other.weather,
    )

    val isEmpty: Boolean
        get() = stadium == null && city == null && capacity == null && referee == null && attendance == null && weather == null
}

/** Traducerile mărunte pentru datele în plus (vremea, accidentările, revenirea). */
object MatchExtras {

    /** °F (ESPN, la meciurile din SUA) → °C. */
    fun celsius(fahrenheit: Double): Int = ((fahrenheit - 32) * 5 / 9).roundToInt()

    private val weatherWords = listOf(
        "thunder" to "Furtună",
        "snow" to "Ninsoare",
        "sleet" to "Lapoviță",
        "drizzle" to "Burniță",
        "light rain" to "Ploaie slabă",
        "shower" to "Averse",
        "rain" to "Ploaie",
        "fog" to "Ceață",
        "mist" to "Ceață",
        "haze" to "Ceață ușoară",
        "partly cloudy" to "Parțial noros",
        "partly sunny" to "Parțial însorit",
        "mostly cloudy" to "Mai mult noros",
        "mostly sunny" to "Mai mult însorit",
        "mostly clear" to "Mai mult senin",
        "overcast" to "Cer acoperit",
        "cloudy" to "Noros",
        "clouds" to "Noros",
        "sunny" to "Însorit",
        "clear" to "Senin",
        "wind" to "Vânt",
    )

    /** „Partly Cloudy” → „Parțial noros”; ce nu știm rămâne cum a venit. */
    fun weatherText(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val low = t.lowercase()
        return weatherWords.firstOrNull { (k, _) -> k in low }?.second ?: t
    }

    /** Starea ESPN a unui jucător („Out”, „Questionable”, „Suspension”…). */
    fun espnKind(status: String?, type: String?): AbsenceKind {
        val s = "${status.orEmpty()} ${type.orEmpty()}".lowercase()
        return when {
            "suspen" in s -> AbsenceKind.SUSPENDED
            "question" in s || "doubt" in s || "day-to-day" in s || "game time" in s || "probable" in s -> AbsenceKind.DOUBTFUL
            "out" in s || "injur" in s || "reserve" in s -> AbsenceKind.INJURED
            else -> AbsenceKind.OTHER
        }
    }

    /** `unavailability.type` din FotMob („injury”, „red_card”, „suspension”, „international_duty”…). */
    fun fotmobKind(type: String?): AbsenceKind {
        val s = type.orEmpty().lowercase()
        return when {
            "injur" in s -> AbsenceKind.INJURED
            "card" in s || "suspen" in s || "ban" in s -> AbsenceKind.SUSPENDED
            "doubt" in s -> AbsenceKind.DOUBTFUL
            else -> AbsenceKind.OTHER
        }
    }

    private val bodyParts = mapOf(
        "knee" to "genunchi",
        "ankle" to "gleznă",
        "foot" to "picior",
        "hamstring" to "ischiogambieri",
        "hip" to "șold",
        "groin" to "adductori",
        "calf" to "gambă",
        "thigh" to "coapsă",
        "shoulder" to "umăr",
        "back" to "spate",
        "neck" to "gât",
        "head" to "cap",
        "concussion" to "comoție",
        "wrist" to "încheietură",
        "hand" to "mână",
        "elbow" to "cot",
        "achilles" to "tendonul lui Ahile",
        "illness" to "boală",
        "rest" to "odihnă",
        "personal" to "motive personale",
        "forearm" to "antebraț",
        "thumb" to "degetul mare",
        "toe" to "deget de la picior",
        "finger" to "deget",
        "rib" to "coaste",
        "ribs" to "coaste",
        "abdomen" to "abdomen",
        "oblique" to "abdomen",
        "quadriceps" to "cvadriceps",
        "leg" to "picior",
    )

    /** „Knee” → „genunchi”; ce nu știm rămâne în engleză. */
    fun injuryText(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() && !it.equals("not specified", true) && !it.equals("other", true) } ?: return null
        return bodyParts[t.lowercase()] ?: t
    }

    private val months = mapOf(
        "january" to "ianuarie", "february" to "februarie", "march" to "martie", "april" to "aprilie",
        "may" to "mai", "june" to "iunie", "july" to "iulie", "august" to "august", "september" to "septembrie",
        "october" to "octombrie", "november" to "noiembrie", "december" to "decembrie",
    )

    /** FotMob „Late November 2026” → „sfârșitul lui noiembrie 2026”; ce nu înțelegem rămâne cum a venit. */
    fun returnText(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val words = t.split(' ').filter { it.isNotBlank() }
        val part = when (words.firstOrNull()?.lowercase()) {
            "early" -> "începutul lui "
            "mid" -> "mijlocul lui "
            "late" -> "sfârșitul lui "
            else -> null
        }
        val rest = if (part != null) words.drop(1) else words
        val month = rest.firstOrNull()?.lowercase()?.let { months[it] } ?: return t
        return (part ?: "") + month + rest.drop(1).joinToString(" ", prefix = if (rest.size > 1) " " else "")
    }

    /** Ordinea în listă: întâi cei siguri că lipsesc, apoi incerții. */
    fun sorted(players: List<AbsentPlayer>): List<AbsentPlayer> =
        players.distinctBy { (if (it.home) "h" else "a") + Sport.fold(it.name) }
            .sortedWith(compareBy<AbsentPlayer> { !it.home }.thenBy { it.kind == AbsenceKind.DOUBTFUL }.thenBy { it.kind.ordinal })
}
