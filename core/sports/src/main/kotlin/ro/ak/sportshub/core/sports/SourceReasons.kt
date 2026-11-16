package ro.ak.sportshub.core.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * „De ce e aici sursa asta?” (C3): o linie pentru OK lung pe o sursă, ca să decizi repede „e meciul / nu e”.
 * Totul din ce s-a găsit deja (emisiunea EPG, postul anunțat, regula de drepturi), fără nicio cerere.
 */
object SourceReasons {

    private val time = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * @param epgHit emisiunea din EPG-ul chiar al acestui canal care e meciul (null dacă EPG-ul a fost găsit pe altă
     *   variantă a canalului sau nu se mai știe)
     * @param providers numele furnizorilor meciului („ESPN, LiveSoccerTV”), pentru posturile anunțate
     */
    fun explain(
        match: Match,
        source: MatchSource<*>,
        channelName: String,
        epgHit: EpgBroadcast?,
        zone: ZoneId,
        providers: String? = null,
    ): String {
        val why = when {
            source.confidence == Confidence.CONFIRMED && source.origin != Confidence.CONFIRMED ->
                "Confirmat de tine. Găsit inițial: " + reason(match, source, source.origin, channelName, epgHit, zone, providers)
            else -> reason(match, source, source.confidence, channelName, epgHit, zone, providers)
        }
        return if (source.rejected) "Ai spus că nu e meciul. Găsit: $why" else why
    }

    private fun reason(
        match: Match,
        source: MatchSource<*>,
        kind: Confidence,
        channelName: String,
        epgHit: EpgBroadcast?,
        zone: ZoneId,
        providers: String?,
    ): String = when (kind) {
        Confidence.CONFIRMED -> "Adăugat sau confirmat de tine pentru meciul ăsta."
        Confidence.EPG -> when {
            epgHit != null -> "EPG-ul canalului: „${programme(epgHit)}” la ${at(epgHit.start, zone)}."
            match.broadcasts.isNotEmpty() -> match.broadcasts.first().let {
                "EPG-ul altei variante a canalului (același nume): „${programme(it)}” la ${at(it.start, zone)}."
            }
            else -> "EPG-ul canalului conține meciul."
        }
        Confidence.EVENT -> "Numele canalului EVENT / PPV arată meciul: „$channelName”."
        Confidence.STATION -> station(match, source.station, providers)
        Confidence.LEARNED -> "Ai confirmat canalul la alt meci din ${match.competition ?: "aceeași competiție"}" +
            (source.station?.let { " ($it)" } ?: "") + "."
        Confidence.DEDICATED -> "Canal dedicat: ${source.station ?: channelName} transmite de obicei " +
            (match.competition ?: "competiția") + " (presupus)."
        Confidence.RIGHTS -> "Drepturi TV: ${match.competition ?: "competiția"} → ${source.station ?: channelName} (presupus, după regulă)."
        Confidence.ELSEWHERE -> when (source.airing) {
            Verdict.NOT_AIRING_NOTE -> "Ai spus că acum nu transmite meciul."
            null -> "EPG-ul canalului arată altceva la ora meciului."
            else -> "EPG-ul canalului arată la ora meciului: „${source.airing}”."
        } + (source.station?.let { " Anunțat: $it." } ?: "")
    }

    private fun station(match: Match, name: String?, providers: String?): String {
        if (name == null) return "Post anunțat pentru meci."
        // Țara postului, dacă furnizorul a dat-o („Sky Sports Main Event (United Kingdom)”).
        val st = match.stations.firstOrNull { Sport.fold(it.name) == Sport.fold(name) }
        val label = name + (st?.country?.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }?.let { " ($it)" } ?: "")
        return (if (providers.isNullOrBlank()) "Post anunțat: " else "Post anunțat de $providers: ") + label + "."
    }

    private fun programme(b: EpgBroadcast): String = listOfNotNull(b.title, b.subtitle?.takeIf { it.isNotBlank() && it != b.title })
        .joinToString(" · ").let { if (it.length > 90) it.take(88) + "…" else it }

    private fun at(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(time)
}
