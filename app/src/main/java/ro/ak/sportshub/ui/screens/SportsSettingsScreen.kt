package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.sports.Sport
import ro.ak.sportshub.core.sports.FollowedNames
import ro.ak.sportshub.data.EventZones
import ro.ak.sportshub.data.StreamDelays
import ro.ak.sportshub.data.SportsSettings
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ChoiceDialog
import ro.ak.sportshub.ui.components.ListRow
import ro.ak.sportshub.ui.components.SettingsPane
import ro.ak.sportshub.ui.components.SettingsSection
import ro.ak.sportshub.ui.components.TextInputDialog
import ro.ak.sportshub.data.providerLabel
import ro.ak.sportshub.core.sports.FutbolEnLaTv
import ro.ak.sportshub.core.sports.FotMob
import ro.ak.sportshub.core.sports.LiveOnSat
import ro.ak.sportshub.core.sports.UsLeagues
import ro.ak.sportshub.core.sports.TheSportsDb
import ro.ak.sportshub.core.sports.ProviderPolicy
import ro.ak.sportshub.data.ReportState

private enum class SportsDialog { LANGUAGES, QUALITY, KEY, DAYS, TEAMS, COMPETITIONS, NAMES, EVENT_ZONE, STREAM_DELAY }

/** Setările Sports Hub: ordinea surselor, sursele de meciuri și favoritele. */
@Composable
fun SportsSettingsScreen(onOpenKnowledge: () -> Unit = {}) {
    val container = appContainer()
    val store = container.sportsSettings
    val sports = container.sports
    val s by store.settings.collectAsStateWithLifecycle()
    val status by sports.status.collectAsStateWithLifecycle()
    val health by sports.health.collectAsStateWithLifecycle()
    val reporter = container.diagnosticReporter
    val report by reporter.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SportsDialog?>(null) }

    // Schimbările care privesc TheSportsDB cer din nou doar ce lipsește (fără să forțeze descărcarea).
    val update: (Boolean, (SportsSettings) -> SportsSettings) -> Unit = { refetch, transform ->
        store.update(transform)
        if (refetch) sports.refreshInBackground(force = false)
    }

    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
    Text(text = "Sports Hub", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
    SettingsPane(
        sections = listOf(
            SettingsSection("Ordinea surselor", "Limbi preferate, calitatea bună") {
                item {
                    ListRow(
                        title = "Limbi preferate",
                        subtitle = "La meciurile favorite contează peste prag; la celelalte doar la aceeași rezoluție",
                        onClick = { dialog = SportsDialog.LANGUAGES },
                        trailing = { SettingValue(s.sourcePreferences.preferredLanguages.joinToString(", ") { it.uppercase() }) },
                    )
                }
                item {
                    ListRow(
                        title = "Calitate bună",
                        subtitle = "Pragul de la care limba preferată urcă în față, la meciurile favorite",
                        onClick = { dialog = SportsDialog.QUALITY },
                        trailing = { SettingValue(s.sourcePreferences.goodQuality.label + " sau mai mult") },
                    )
                }
                item {
                    ListRow(
                        title = "Cum se ordonează",
                        subtitle = "Favorite: limbi preferate 4K, FHD → alte limbi 4K, FHD → limbi preferate HD/SD → restul. " +
                            "Alte meciuri: rezoluția întâi, orice limbă. Nicio sursă nu e ascunsă.",
                        onClick = {},
                    )
                }

            },
            SettingsSection("Afișare", "Fără spoilere, comutare automată, alerte") {
                item {
                    ListRow(
                        title = "Fără spoilere",
                        subtitle = "Ascunde scorul, evenimentele și statisticile meciurilor începute; le poți arăta pe fiecare meci",
                        onClick = { update(false) { it.copy(noSpoilers = !it.noSpoilers) } },
                        trailing = { SettingValue(if (s.noSpoilers) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Comutare automată pe meciul favorit",
                        subtitle = "Cu un minut înainte de start: 10 secunde ca să anulezi (Back), apoi pornește sursa recomandată",
                        onClick = { update(false) { it.copy(autoSwitch = !it.autoSwitch) } },
                        trailing = { SettingValue(if (s.autoSwitch) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Alerte de gol și „Final”",
                        subtitle = "Pentru meciurile favorite de la ESPN, cât aplicația e pe ecran (și peste player). Niciodată cu „Fără spoilere”",
                        onClick = { update(false) { it.copy(goalAlerts = !it.goalAlerts) } },
                        trailing = { SettingValue(if (!s.goalAlerts) "Nu" else if (s.noSpoilers) "oprite de „Fără spoilere”" else "Da") },
                    )
                }
                item {
                    ListRow(
                        title = "Întârzierea stream-ului live",
                        subtitle = "În panoul meciului din player, golurile și scorul apar abia după atât, cât să le vezi întâi pe ecran",
                        onClick = { dialog = SportsDialog.STREAM_DELAY },
                        trailing = { SettingValue("${s.streamDelaySeconds} s") },
                    )
                }

            },
            SettingsSection("Surse de meciuri", "ESPN, TheSportsDB, site-uri opționale") {
                item {
                    ListRow(
                        title = "EPG-ul tău",
                        subtitle = "Mereu activ: emisiunile de pe canalele de sport care arată a meci (de exemplu „Arsenal v Chelsea”)",
                        onClick = {},
                        trailing = { SettingValue("Da") },
                    )
                }
                item {
                    ListRow(
                        title = "TheSportsDB",
                        subtitle = "Doar pentru sporturile fără ESPN (handbal, volei, darts, snooker, ciclism…): azi și mâine, la 3 ore. Și rezervă pentru Pre-meci/Live",
                        onClick = { update(true) { it.copy(sportsDbEnabled = !it.sportsDbEnabled) } },
                        trailing = { SettingValue(if (s.sportsDbEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Posturi TV de la TheSportsDB",
                        subtitle = "Pentru potrivirea pe nume de post; doar cu o cheie proprie (cheia gratuită dă un singur rezultat)",
                        onClick = { update(true) { it.copy(sportsDbTv = !it.sportsDbTv) } },
                        trailing = { SettingValue(if (s.sportsDbTv) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Cheie TheSportsDB",
                        subtitle = "123 e cheia gratuită; o cheie proprie dă mai multe rezultate",
                        onClick = { dialog = SportsDialog.KEY },
                        trailing = { SettingValue(if (s.sportsDbKey == "123") "gratuită" else "proprie") },
                    )
                }
                item {
                    ListRow(
                        title = "LiveSoccerTV",
                        subtitle = "Neoficial, opțional: meciurile de fotbal de azi și mâine cu posturile TV (o pagină pe zi, la 3 ore)",
                        onClick = { update(true) { it.copy(liveSoccerTvEnabled = !it.liveSoccerTvEnabled) } },
                        trailing = { SettingValue(if (s.liveSoccerTvEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Sofascore",
                        subtitle = "Neoficial, opțional: programul complet și scorurile pentru sporturile alese (azi și mâine, la 3 ore); sursa cu cele mai multe meciuri",
                        onClick = { update(true) { it.copy(sofascoreEnabled = !it.sofascoreEnabled) } },
                        trailing = { SettingValue(if (s.sofascoreEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Posturi TV de la Sofascore",
                        subtitle = "Posturile pe țară, toate sporturile, doar din țările canalelor tale; cel mult 25 de meciuri pe rundă " +
                            "(favoritele, apoi cele fără sursă sigură). Merge doar cu Sofascore pornit",
                        onClick = { update(true) { it.copy(sofascoreTv = !it.sofascoreTv) } },
                        trailing = { SettingValue(if (!s.sofascoreEnabled) "oprit" else if (s.sofascoreTv) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "FutbolEnLaTV",
                        subtitle = "Neoficial, opțional: ~18 sporturi pe zi cu posturile din Spania, plus site-urile surori ale țărilor canalelor tale (Marea Britanie, SUA, Germania, Italia, Franța, Portugalia…; cel mult 3); o pagină pe site, la 3 ore",
                        onClick = { update(true) { it.copy(futbolEnLaTvEnabled = !it.futbolEnLaTvEnabled) } },
                        trailing = { SettingValue(if (s.futbolEnLaTvEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "LiveOnSat",
                        subtitle = "Neoficial, opțional: fotbal, zeci de posturi pe meci din toată lumea (Balcani, Orientul Mijlociu, Asia); o pagină la 3 ore",
                        onClick = { update(true) { it.copy(liveOnSatEnabled = !it.liveOnSatEnabled) } },
                        trailing = { SettingValue(if (s.liveOnSatEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "FotMob (ghid TV)",
                        subtitle = "Neoficial, opțional: fotbal, posturile din țările canalelor tale (România, Marea Britanie…); o pagină pe țară, cel mult 4, la 3 ore",
                        onClick = { update(true) { it.copy(fotMobEnabled = !it.fotMobEnabled) } },
                        trailing = { SettingValue(if (s.fotMobEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "ESPN (API deschis), plus NHL și MLB",
                        subtitle = "Furnizorul principal: ~100 de ligi (fotbal pe două niveluri și cupele, NBA, NHL, NFL, MLB, tenis ATP/WTA, F1, IndyCar, NASCAR, golf, UFC, rugby), " +
                            "doar în zilele cu meciuri; și Pre-meci/Live. NHL și MLB: posturile din SUA și Canada, dacă ai canale de acolo",
                        onClick = { update(true) { it.copy(usLeaguesEnabled = !it.usLeaguesEnabled) } },
                        trailing = { SettingValue(if (s.usLeaguesEnabled) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "EuroLeague (API oficial)",
                        subtitle = "Baschet: tot programul EuroLeague și EuroCup, cerut la 12 ore",
                        onClick = { update(true) { it.copy(euroLeagueEnabled = !it.euroLeagueEnabled) } },
                        trailing = { SettingValue(if (s.euroLeagueEnabled) "Da" else "Nu") },
                    )
                }
            },
            SettingsSection("Filtre și potrivire", "Competițiile importante, canale EVENT, dicționar") {
                item {
                    ListRow(
                        title = "Doar competițiile importante",
                        subtitle = "Fotbal: primele ligi din Europa (două niveluri; Anglia mai multe, cu U18, U21 și feminin), primul nivel în rest, cupele și naționalele. Alte sporturi: doar ligile și turneele mari, plus mondialele și europenele",
                        onClick = { update(false) { it.copy(topCompetitionsOnly = !it.topCompetitionsOnly) } },
                        trailing = { SettingValue(if (s.topCompetitionsOnly) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Meciuri și din EPG / canale EVENT",
                        subtitle = "Oprit: lista vine doar din furnizori (ESPN, LiveSoccerTV…), iar EPG-ul și canalele doar găsesc sursele. Pornit: apar și meciurile văzute doar în EPG sau în numele canalelor",
                        onClick = { update(false) { it.copy(channelOnlyMatches = !it.channelOnlyMatches) } },
                        trailing = { SettingValue(if (s.channelOnlyMatches) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Canale EVENT / PPV din playlisturi",
                        subtitle = "Meciul citit din numele canalului („EVENT 03: UFC 310 20:00”), ca sursă sigură și în tabul Evenimente. Fără rețea",
                        onClick = { update(false) { it.copy(eventChannels = !it.eventChannels) } },
                        trailing = { SettingValue(if (s.eventChannels) "Da" else "Nu") },
                    )
                }
                item {
                    ListRow(
                        title = "Fusul orelor din numele canalelor EVENT",
                        subtitle = "Pentru orele scrise fără fus („20:00”); „20:00 UK” sau „7 PM ET” se citesc oricum corect",
                        onClick = { dialog = SportsDialog.EVENT_ZONE },
                        trailing = { SettingValue(EventZones.firstOrNull { it.first == s.eventZone }?.second ?: s.eventZone) },
                    )
                }
                item {
                    ListRow(
                        title = "Dicționar de posturi, drepturi TV și alias-uri",
                        subtitle = "Posturile anunțate fără canal, regulile „competiție → posturi”, alias-urile de echipe și ce s-a învățat",
                        onClick = onOpenKnowledge,
                    )
                }
                item {
                    ListRow(
                        title = "Zile cerute",
                        subtitle = "Doar pentru TheSportsDB (1 sau 2: azi și mâine); ESPN cere oricum azi și mâine",
                        onClick = { dialog = SportsDialog.DAYS },
                        trailing = { SettingValue(s.daysAhead.toString()) },
                    )
                }
            },
            SettingsSection("Starea furnizorilor", "Ce a mers, pauze, actualizare, jurnal") {
                val active = buildList {
                    if (s.sportsDbEnabled) add(TheSportsDb.PROVIDER)
                    if (s.liveSoccerTvEnabled) add("livesoccertv")
                    if (s.sofascoreEnabled) add("sofascore")
                    if (s.futbolEnLaTvEnabled) add(FutbolEnLaTv.PROVIDER)
                    if (s.liveOnSatEnabled) add(LiveOnSat.PROVIDER)
                    if (s.fotMobEnabled) add(FotMob.PROVIDER)
                    if (s.usLeaguesEnabled) addAll(listOf(UsLeagues.ESPN, UsLeagues.NHL_API, UsLeagues.MLB_API))
                    if (s.euroLeagueEnabled) add(ro.ak.sportshub.core.sports.EuroLeague.PROVIDER)
                }
                active.sortedBy { ProviderPolicy.rank(it) }.forEach { p ->
                    item(key = "health-$p") {
                        val h = health[p]
                        val block = sports.blockOf(p)
                        val now = System.currentTimeMillis()
                        val pause = if (block.active(now)) {
                            " · În pauză până la " + java.time.Instant.ofEpochMilli(block.until).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " (a refuzat de ${block.strikes} ori la rând)"
                        } else ""
                        ListRow(
                            title = providerLabel(p) + (h?.let { " · ${it.events} meciuri salvate" } ?: ""),
                            subtitle = ProviderPolicy.tier(p).label.replaceFirstChar { it.uppercase() } + " · " + when {
                                h == null || h.ok + h.failed == 0 -> "Nicio cerere de la pornire; se folosesc datele salvate"
                                h.failed == 0 -> "Ultima rundă: ${h.ok} cereri reușite"
                                else -> "Ultima rundă: ${h.ok} reușite, ${h.failed} nereușite · ${h.lastError.orEmpty()}"
                            } + pause,
                            onClick = {},
                        )
                    }
                }
                item {
                    ListRow(
                        title = "Actualizează acum",
                        subtitle = status ?: "Cere din nou programul de la furnizorii activi",
                        onClick = { sports.refreshInBackground(force = true) },
                    )
                }
                item {
                    ListRow(
                        title = "Trimite jurnalul pe GitHub",
                        subtitle = when (val r = report) {
                            ReportState.Idle -> "Cererile la furnizori și ultimul răspuns al fiecăruia, ca issue în repo (fără nimic din playlisturi)"
                            is ReportState.Sending -> r.step
                            is ReportState.Sent -> "Trimis: issue #${r.number}"
                            is ReportState.Failed -> r.reason
                        },
                        onClick = { reporter.send() },
                    )
                }

            },
            SettingsSection("Sporturi", "Sporturile cerute de la TheSportsDB și Sofascore (OK schimbă)") {
                Sport.entries.forEach { sport ->
                    item(key = sport.name) {
                        val on = sport in s.sports
                        ListRow(
                            title = sport.label,
                            onClick = {
                                update(true) { st ->
                                    st.copy(sports = if (on) st.sports - sport else Sport.entries.filter { it in st.sports || it == sport })
                                }
                            },
                            trailing = { SettingValue(if (on) "Da" else "Nu") },
                        )
                    }
                }

            },
            SettingsSection("Favorite", "Echipe, competiții, nume urmărite") {
                item {
                    ListRow(
                        title = "Echipe favorite",
                        subtitle = s.favorites.teams.joinToString(", ").ifEmpty { "Niciuna. Le poți marca și din ecranul unui meci" },
                        onClick = { dialog = SportsDialog.TEAMS },
                        trailing = { SettingValue(s.favorites.teams.size.toString()) },
                    )
                }
                item {
                    ListRow(
                        title = "Competiții favorite",
                        subtitle = s.favorites.competitions.joinToString(", ").ifEmpty { "Niciuna" },
                        onClick = { dialog = SportsDialog.COMPETITIONS },
                        trailing = { SettingValue(s.favorites.competitions.size.toString()) },
                    )
                }
                item {
                    ListRow(
                        title = "Nume urmărite",
                        subtitle = s.favorites.names.joinToString(", ").ifEmpty {
                            "Jucători, piloți, luptători: orice emisiune din EPG care îi pomenește apare în Sports Hub, la Favorite"
                        },
                        onClick = { dialog = SportsDialog.NAMES },
                        trailing = { SettingValue(s.favorites.names.size.toString()) },
                    )
                }
            },
        ),
        modifier = Modifier.padding(top = 4.dp),
    )
    }

    when (dialog) {
        SportsDialog.LANGUAGES -> TextInputDialog(
            title = "Limbi preferate",
            initial = s.sourcePreferences.preferredLanguages.joinToString(", "),
            hint = "Coduri de limbă, în ordinea preferinței (de exemplu: en, ro)",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                val langs = text.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.length in 2..3 }.distinct()
                if (langs.isNotEmpty()) update(false) { it.copy(sourcePreferences = it.sourcePreferences.copy(preferredLanguages = langs)) }
                dialog = null
            },
        )
        SportsDialog.QUALITY -> {
            val options = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            ChoiceDialog(
                title = "Calitate bună",
                options = options.map { it.label + " sau mai mult" },
                selectedIndex = options.indexOf(s.sourcePreferences.goodQuality),
                onDismiss = { dialog = null },
                onSelect = { i ->
                    update(false) { it.copy(sourcePreferences = it.sourcePreferences.copy(goodQuality = options[i])) }
                    dialog = null
                },
            )
        }
        SportsDialog.KEY -> TextInputDialog(
            title = "Cheie TheSportsDB",
            initial = s.sportsDbKey,
            hint = "Gol = cheia gratuită 123",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                update(true) { it.copy(sportsDbKey = text.ifBlank { "123" }) }
                dialog = null
            },
        )
        SportsDialog.DAYS -> {
            val options = (1..7).toList()
            ChoiceDialog(
                title = "Zile cerute de la TheSportsDB",
                options = options.map { if (it == 1) "1 zi (azi)" else "$it zile" },
                selectedIndex = options.indexOf(s.daysAhead),
                onDismiss = { dialog = null },
                onSelect = { i ->
                    update(true) { it.copy(daysAhead = options[i]) }
                    dialog = null
                },
            )
        }
        SportsDialog.TEAMS -> TextInputDialog(
            title = "Echipe favorite",
            initial = s.favorites.teams.joinToString(", "),
            hint = "Separate prin virgulă (de exemplu: FCSB, Arsenal, Man Utd)",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                val list = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                update(false) { it.copy(favorites = it.favorites.copy(teams = list)) }
                dialog = null
            },
        )
        SportsDialog.COMPETITIONS -> TextInputDialog(
            title = "Competiții favorite",
            initial = s.favorites.competitions.joinToString(", "),
            hint = "Numele ca în listă, separate prin virgulă (de exemplu: Premier League, Liga 1)",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                val list = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                update(false) { it.copy(favorites = it.favorites.copy(competitions = list)) }
                dialog = null
            },
        )
        SportsDialog.NAMES -> TextInputDialog(
            title = "Nume urmărite",
            initial = s.favorites.names.joinToString(", "),
            hint = "Separate prin virgulă (de exemplu: Djokovic, Max Verstappen, Simona Halep). Se caută în titlul și descrierea emisiunilor de sport",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                val list = text.split(',').mapNotNull { FollowedNames.normalize(it) }.distinctBy { Sport.fold(it) }
                update(false) { it.copy(favorites = it.favorites.copy(names = list)) }
                dialog = null
            },
        )
        SportsDialog.EVENT_ZONE -> ChoiceDialog(
            title = "Fusul orelor din numele canalelor EVENT",
            options = EventZones.map { it.second },
            selectedIndex = EventZones.indexOfFirst { it.first == s.eventZone },
            onDismiss = { dialog = null },
            onSelect = { i ->
                update(false) { it.copy(eventZone = EventZones[i].first) }
                dialog = null
            },
        )
        SportsDialog.STREAM_DELAY -> ChoiceDialog(
            title = "Întârzierea stream-ului live",
            hint = "Cât e în urmă imaginea față de realitate. Dacă afli golul din panou înainte să-l vezi, mărește valoarea.",
            options = StreamDelays.map { if (it == 0) "Fără întârziere" else "$it secunde" },
            selectedIndex = StreamDelays.indexOf(s.streamDelaySeconds),
            onDismiss = { dialog = null },
            onSelect = { i ->
                update(false) { it.copy(streamDelaySeconds = StreamDelays[i]) }
                dialog = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun SettingValue(text: String) {
    Text(text = text, fontSize = 14.sp)
}
