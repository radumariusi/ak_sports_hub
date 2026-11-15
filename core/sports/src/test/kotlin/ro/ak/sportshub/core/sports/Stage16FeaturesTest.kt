package ro.ak.sportshub.core.sports

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16FeaturesTest {

    private val kickoff = 1_800_000_000_000L
    private fun min(m: Long) = TimeUnit.MINUTES.toMillis(m)

    private val match = Match(
        id = "espn:EPL-1", sport = Sport.FOOTBALL, competition = "English Premier League",
        home = "Arsenal", away = "Chelsea", start = kickoff, end = kickoff + min(115),
        status = MatchStatus.SCHEDULED,
    )
    private val fav = SportsFavorites(teams = listOf("Arsenal"))

    // ---------- F1: comutarea automată ----------

    @Test
    fun `comutarea pornește cu un minut înainte de start, o dată pe meci`() {
        val other = match.copy(id = "x", home = "Inter", away = "Milan")
        assertNull(AutoSwitch.due(listOf(match), fav, kickoff - min(2), emptySet(), null))
        assertEquals(match, AutoSwitch.due(listOf(match, other), fav, kickoff - min(1), emptySet(), null))
        assertEquals(match, AutoSwitch.due(listOf(match), fav, kickoff + min(2), emptySet(), null))
        assertNull(AutoSwitch.due(listOf(match), fav, kickoff + min(6), emptySet(), null))
        assertNull(AutoSwitch.due(listOf(match), fav, kickoff, setOf(match.id), null))
        assertNull(AutoSwitch.due(listOf(match), fav, kickoff, emptySet(), match.id))
        assertNull(AutoSwitch.due(listOf(other), fav, kickoff, emptySet(), null))
    }

    @Test
    fun `numărătoarea inversă de 10 secunde`() {
        assertEquals(10, AutoSwitch.secondsLeft(1_000, 1_000))
        assertEquals(10, AutoSwitch.secondsLeft(1_000, 1_500))
        assertEquals(1, AutoSwitch.secondsLeft(1_000, 10_500))
        assertEquals(0, AutoSwitch.secondsLeft(1_000, 11_000))
        assertEquals(0, AutoSwitch.secondsLeft(1_000, 99_000))
    }

    // ---------- F2: alertele de gol ----------

    @Test
    fun `gol, gol anulat și final`() {
        val live = match.copy(status = MatchStatus.LIVE)
        assertTrue(GoalAlerts.diff(live, null, ScoreState(1, 0, MatchStatus.LIVE)).isEmpty())
        val goal = GoalAlerts.diff(live, ScoreState(0, 0, MatchStatus.LIVE), ScoreState(1, 0, MatchStatus.LIVE)).single()
        assertEquals(MatchAlert.Kind.GOAL, goal.kind)
        assertEquals("⚽ GOL Arsenal! Arsenal 1–0 Chelsea", goal.text)
        val away = GoalAlerts.diff(live, ScoreState(1, 0, MatchStatus.LIVE), ScoreState(1, 1, MatchStatus.LIVE)).single()
        assertEquals("⚽ GOL Chelsea! Arsenal 1–1 Chelsea", away.text)
        val var0 = GoalAlerts.diff(live, ScoreState(1, 1, MatchStatus.LIVE), ScoreState(1, 0, MatchStatus.LIVE)).single()
        assertEquals(MatchAlert.Kind.CORRECTION, var0.kind)
        assertTrue(GoalAlerts.diff(live, ScoreState(1, 0, MatchStatus.LIVE), ScoreState(1, 0, MatchStatus.LIVE)).isEmpty())
        val end = GoalAlerts.diff(live, ScoreState(2, 1, MatchStatus.LIVE), ScoreState(2, 1, MatchStatus.FINISHED)).single()
        assertEquals("Final: Arsenal 2–1 Chelsea", end.text)
        // Finalul se anunță o singură dată.
        assertTrue(GoalAlerts.diff(live, ScoreState(2, 1, MatchStatus.FINISHED), ScoreState(2, 1, MatchStatus.FINISHED)).isEmpty())
    }

    @Test
    fun `la baschet doar finalul`() {
        val nba = match.copy(sport = Sport.BASKETBALL, home = "Lakers", away = "Celtics", status = MatchStatus.LIVE)
        assertTrue(GoalAlerts.diff(nba, ScoreState(50, 48, MatchStatus.LIVE), ScoreState(52, 48, MatchStatus.LIVE)).isEmpty())
        assertEquals(1, GoalAlerts.diff(nba, ScoreState(100, 98, MatchStatus.LIVE), ScoreState(101, 98, MatchStatus.FINISHED)).size)
    }

    @Test
    fun `fără spoilere nu se urmărește nimic`() {
        val now = kickoff + min(10)
        assertTrue(GoalAlerts.shouldWatch(match, fav, now, noSpoilers = false, revealed = false))
        assertFalse(GoalAlerts.shouldWatch(match, fav, now, noSpoilers = true, revealed = false))
        assertTrue(GoalAlerts.shouldWatch(match, fav, now, noSpoilers = true, revealed = true))
        assertFalse(GoalAlerts.shouldWatch(match, fav, kickoff - min(1), noSpoilers = false, revealed = false))
        assertFalse(GoalAlerts.shouldWatch(match, SportsFavorites(), now, noSpoilers = false, revealed = false))
    }

    // ---------- F3: clasamente ----------

    private val soccerTable = """
        {"name":"English Premier League","children":[{"name":"2026-27 English Premier League","standings":{"entries":[
          {"team":{"id":"382","displayName":"Manchester City","abbreviation":"MNC","logos":[{"href":"https://a.espncdn.com/mci.png"}]},
           "stats":[{"name":"gamesPlayed","value":10.0},{"name":"wins","value":7.0},{"name":"ties","value":2.0},{"name":"losses","value":1.0},
                    {"name":"pointsFor","value":20.0},{"name":"pointsAgainst","value":8.0},{"name":"points","value":23.0},
                    {"name":"rank","value":2.0},{"name":"rankChange","value":-1.0}]},
          {"team":{"id":"359","displayName":"Arsenal","abbreviation":"ARS"},"note":{"color":"#81D6AC","description":"Champions League","rank":1},
           "stats":[{"name":"gamesPlayed","value":10.0},{"name":"wins","value":9.0},{"name":"ties","value":0.0},{"name":"losses","value":1.0},
                    {"name":"points","value":27.0},{"name":"rank","value":1.0},{"name":"rankChange","value":0.0}]},
          {"team":{"id":"363","displayName":"Chelsea"},"note":{"color":"##B5E7CE","description":"Europa League","rank":5},
           "stats":[{"name":"points","value":17.0},{"name":"rank","value":5.0}]}
        ]}}]}
    """.trimIndent()

    @Test
    fun `clasamentul ESPN de fotbal`() {
        val g = Standings.parse(soccerTable).single()
        assertEquals("2026-27 English Premier League", g.name)
        assertEquals(listOf("Arsenal", "Manchester City", "Chelsea"), g.rows.map { it.team })
        val ars = g.rows[0]
        assertEquals("27", ars.points)
        assertNull(ars.change)
        assertEquals("Champions League", ars.zone?.description)
        assertEquals(0xFF81D6AC, ars.zone?.color)
        val city = g.rows[1]
        assertEquals(-1, city.change)
        assertEquals(10, city.played)
        assertEquals(2, city.drawn)
        assertEquals(20, city.goalsFor)
        assertEquals("https://a.espncdn.com/mci.png", city.logo)
        // Culoarea „##B5E7CE” (greșeală ESPN) se citește totuși.
        assertEquals(0xFFB5E7CE, g.rows[2].zone?.color)
    }

    @Test
    fun `NBA pe conferințe, ordonat după locul din playoff`() {
        val body = """
            {"name":"NBA","children":[
              {"name":"Eastern Conference","standings":{"entries":[
                {"team":{"id":"15","displayName":"Milwaukee Bucks"},"stats":[{"name":"playoffSeed","value":15.0},{"name":"winPercent","value":0.077,"displayValue":".077"},{"name":"points","value":-5.5}]},
                {"team":{"id":"2","displayName":"Boston Celtics"},"stats":[{"name":"playoffSeed","value":1.0},{"name":"winPercent","value":0.8,"displayValue":".800"}]}
              ]}},
              {"name":"Western Conference","standings":{"entries":[
                {"team":{"id":"13","displayName":"Los Angeles Lakers"},"stats":[{"name":"playoffSeed","value":1.0},{"name":"winPercent","value":0.7,"displayValue":".700"}]}
              ]}}
            ]}
        """.trimIndent()
        val groups = Standings.parse(body, Sport.BASKETBALL)
        assertEquals(listOf("Eastern Conference", "Western Conference"), groups.map { it.name })
        assertEquals(listOf("Boston Celtics", "Milwaukee Bucks"), groups[0].rows.map { it.team })
        assertEquals(".800", groups[0].rows[0].points)
    }

    @Test
    fun `schimbarea de loc față de clasamentul salvat`() {
        val groups = Standings.parse(soccerTable)
        val previous = mapOf("2026-27 English Premier League|359" to 3, "2026-27 English Premier League|363" to 5)
        val changed = Standings.withChanges(groups, previous).single().rows
        assertEquals(2, changed[0].change) // Arsenal: de pe 3 pe 1
        assertEquals(-1, changed[1].change) // City: dat de ESPN
        assertNull(changed[2].change) // Chelsea: același loc
        assertEquals(3, Standings.ranks(groups).size)
    }

    @Test
    fun `ligile favoritelor întâi`() {
        val f = SportsFavorites(competitions = listOf("Romanian Liga I"))
        val order = Standings.ordered(f, teamLeagues = setOf("SERIEA"), withMatches = emptySet())
        assertEquals("ROMANIA", order[0].key)
        assertEquals("SERIEA", order[1].key)
        assertTrue(Standings.leagues.none { it.kind != Espn.Kind.TEAM })
        assertTrue(Standings.leagues.none { it.key == "FRIENDLY" })
    }

    // ---------- F4: numele urmărite ----------

    @Test
    fun `numele urmărit, pe cuvinte întregi și fără diacritice`() {
        assertTrue(FollowedNames.mentions("Djokovic", "Tenis: Novak Djoković – Carlos Alcaraz"))
        assertTrue(FollowedNames.mentions("max verstappen", "F1 cu Max Verstappen în pole"))
        assertFalse(FollowedNames.mentions("Ion", "Ionescu, interviu"))
        assertFalse(FollowedNames.mentions("Ion", null))
        assertNull(FollowedNames.normalize("  a "))
        assertEquals("Max Verstappen", FollowedNames.normalize(" Max   Verstappen "))
        val f = SportsFavorites(names = listOf("Halep"))
        assertTrue(f.isFavorite(match.copy(home = "Simona Halep", away = "Iga Swiatek", sport = Sport.TENNIS)))
        assertFalse(f.isFavorite(match))
    }

    @Test
    fun `emisiunile care pomenesc numele devin meciuri, una pe oră și titlu`() {
        val now = kickoff - min(60)
        fun b(id: Long, ch: Long, title: String, desc: String? = null, start: Long = kickoff) =
            EpgBroadcast(id, ch, start, start + min(120), title, null, desc, "Sport")
        val list = listOf(
            b(1, 10, "UFC 320: Jones vs Aspinall"),
            b(2, 11, "UFC 320: Jones vs Aspinall"),
            b(3, 12, "Magazin sportiv", desc = "Invitat: Jon Jones, despre gala de la Las Vegas"),
            b(4, 13, "Știri"),
            b(5, 14, "UFC 319: Jones (reluare)", start = now - min(300)),
        )
        val out = FollowedNames.matches(list, listOf("Jon Jones", "Jones"), now)
        assertEquals(2, out.size)
        val ufc = out.first { it.home.startsWith("UFC") }
        assertEquals(listOf(10L, 11L), ufc.broadcasts.map { it.epgChannelId })
        assertTrue(ufc.isSingleEvent)
        assertEquals(Sport.FIGHTING, ufc.sport)
        assertEquals(MatchStatus.SCHEDULED, ufc.status)
        assertEquals(setOf(FollowedNames.PROVIDER), ufc.providers)
        assertTrue(FollowedNames.matches(list, emptyList(), now).isEmpty())
        // Comutarea automată: doar pe emisiunea cu numele în titlu, nu pe magazinul care îl pomenește în descriere.
        val f = SportsFavorites(names = listOf("Jon Jones", "Jones"))
        val talk = out.first { !it.home.startsWith("UFC") }
        assertNull(AutoSwitch.due(listOf(talk), f, kickoff, emptySet(), null))
        assertEquals(ufc, AutoSwitch.due(listOf(talk, ufc), f, kickoff, emptySet(), null))
    }
}

class EspnPreMatchTest {

    @Test
    fun `summary fara header - echipele, forma, sezonul si golgheterii`() {
        // Forma reală a unui `summary` ESPN înainte de meci (LaLiga 2): fără `header`, totul în `boxscore`.
        val body = """
            {"boxscore":{
              "teams":[
                {"team":{"id":"245","displayName":"Tenerife"},"homeAway":"home","statistics":[
                  {"name":"totalGoals","label":"Goals","displayValue":"21"},{"name":"goalsConceded","displayValue":"9"}]},
                {"team":{"id":"97","displayName":"Racing Santander"},"homeAway":"away","statistics":[
                  {"name":"totalGoals","label":"Goals","displayValue":"25"},{"name":"goalsConceded","displayValue":"14"}]}],
              "gameInfo":{"venue":{"fullName":"Heliodoro Rodríguez López","address":{"city":"Santa Cruz de Tenerife"}}},
              "lastFiveGames":[
                {"team":{"id":"245","displayName":"Tenerife"},"events":[
                  {"id":"1","gameDate":"2026-11-08T15:00Z","score":"2-0","homeTeamId":"245","awayTeamId":"9",
                   "homeTeamScore":"2","awayTeamScore":"0","gameResult":"W","opponent":{"displayName":"Cadiz"},"atVs":"vs"}]},
                {"team":{"id":"97","displayName":"Racing Santander"},"events":[
                  {"id":"2","gameDate":"2026-11-09T15:00Z","score":"1-1","homeTeamId":"8","awayTeamId":"97",
                   "homeTeamScore":"1","awayTeamScore":"1","gameResult":"D","opponent":{"displayName":"Malaga"},"atVs":"@"}]}],
              "leaders":[
                {"team":{"id":"245"},"leaders":[{"name":"goals","displayName":"Goals","leaders":[{"displayValue":"9","athlete":{"displayName":"Enric Gallego"}}]}]},
                {"team":{"id":"97"},"leaders":[{"name":"goals","displayName":"Goals","leaders":[{"displayValue":"7, 1 A","athlete":{"displayName":"Andrés Martín"}}]}]}]
            }}
        """.trimIndent()
        val d = Espn.parseSummary(body, "x")!!
        val ev = d.event!!
        assertEquals("Tenerife", ev.home)
        assertEquals("Racing Santander", ev.away)
        assertEquals("Heliodoro Rodríguez López", ev.venue)
        assertTrue(d.stats.isEmpty())
        assertEquals(StatLine("Goluri marcate", "21", "25"), d.seasonStats[0])
        assertEquals(StatLine("Golgheter", "Enric Gallego (9)", "Andrés Martín (7)"), d.seasonStats.last())
        assertEquals("Tenerife", d.homeRecent.single().home)
        assertEquals("Malaga", d.awayRecent.single().home)
        assertEquals(1, d.awayRecent.single().awayScore)
    }

    @Test
    fun `liga ESPN dupa numele competitiei`() {
        assertEquals("LALIGA2", Espn.leaguesFor(Sport.FOOTBALL, "LaLiga Hypermotion").first().key)
        assertEquals("LALIGA2", Espn.leaguesFor(Sport.FOOTBALL, "Spain - LaLiga 2").first().key)
        assertEquals("SERIEA", Espn.leaguesFor(Sport.FOOTBALL, "Serie A").first().key)
        assertEquals("BRAZIL", Espn.leaguesFor(Sport.FOOTBALL, "Brasileirão Série A").first().key)
        assertEquals("SERIEA", Espn.leaguesFor(null, "Italian Serie A").first().key)
        assertEquals("AUSTRIA", Espn.leaguesFor(Sport.FOOTBALL, "Austrian Bundesliga").first().key)
        assertTrue(Espn.leaguesFor(Sport.BASKETBALL, "Serie A").isEmpty())
        assertTrue(Espn.leaguesFor(Sport.FOOTBALL, null).isEmpty())
    }
}

class StatGroupsTest {
    @Test
    fun `statisticile pe grupuri, cu partea fiecarei echipe`() {
        val g = StatGroups.group(
            listOf(
                StatLine("Faulturi", "12", "9"),
                StatLine("Posesie (%)", "58.2", "41.8"),
                StatLine("Șuturi pe poartă", "5", "1"),
                StatLine("Pase de gol", "3", "2"),
                StatLine("Pase reușite", "420", "300"),
                StatLine("Golgheter", "Enric Gallego (9)", "Andrés Martín (7)"),
                StatLine("Ceva nou", "x", "y"),
            ),
        )
        assertEquals(listOf("Posesie", "Atac", "Pase", "Disciplină", "Jucători", "Altele"), g.map { it.title })
        assertEquals(0.582f, g[0].rows[0].homeShare!!, 0.001f)
        assertEquals(listOf("Șuturi pe poartă", "Pase de gol"), g[1].rows.map { it.line.name })
        assertNull(g.last().rows[0].homeShare)
        assertEquals(0.5f, StatGroups.Row(StatLine("Cartonașe roșii", "0", "0"), 0.0, 0.0).homeShare!!, 0f)
    }
}
