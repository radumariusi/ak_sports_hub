package ro.ak.sportshub.core.common.player

/**
 * Adaptarea automată la sacadare: playerul urmărește cadrele pierdute și, dacă le pierde mereu (nu doar la pornire),
 * încearcă pe rând câte o schimbare ([Step]). O schimbare care scade pierderile rămâne; una care nu ajută se anulează
 * și se trece la următoarea. Pierderile de la pornire (primele [WARMUP_MS]) nu contează.
 *
 * Se apelează cu [sample] la câteva secunde, cu totalul cadrelor pierdute de la pornirea stream-ului.
 */
class DropAdapter(private val clock: () -> Long = System::currentTimeMillis) {

    enum class Step(val label: String) {
        /** Ecranul trece pe frecvența cadrelor stream-ului (50 Hz pentru 25/50 fps). */
        MATCH_RATE("ecranul potrivit cu cadrele stream-ului"),

        /** Imaginea direct pe ecran (SurfaceView), fără copia în plus. */
        SURFACE_VIEW("imagine fluidă"),

        /** Redarea tunelată: decodorul televizorului ține imaginea în pas cu sunetul (doar cu imaginea fluidă). */
        TUNNELING("redarea tunelată"),

        /** Potrivirea frecvenței, pornită din Setări, oprită pentru canalul acesta (unele televizoare sacadează cu ea). */
        NO_RATE_MATCH("ecranul fără schimbarea frecvenței"),

        /** Imaginea fluidă, pornită din Setări, oprită pentru canalul acesta. */
        TEXTURE_VIEW("imaginea obișnuită (fără imagine fluidă)"),
    }

    private var startedAt = -1L
    private var lastT = 0L
    private var lastDropped = 0
    private var badWindows = 0
    private val tried = HashSet<Step>()

    /** Schimbările păstrate (au ajutat sau sunt în probă). */
    var active: Set<Step> = emptySet()
        private set

    /** Schimbările care au ajutat (se pot ține minte pentru canal). */
    var kept: Set<Step> = emptySet()
        private set

    private var trial: Step? = null
    private var trialSince = 0L
    private var trialDropped = 0
    private var rateBefore = 0f

    /** Ultimul lucru făcut, pentru un anunț scurt în player; null dacă nimic nou. */
    var note: String? = null
        private set

    /**
     * Un eșantion: [dropped] = cadrele pierdute de la pornire; [available] = pașii care se pot încerca acum
     * (de exemplu potrivirea frecvenței doar dacă se știu cadrele pe secundă și setarea nu e deja pornită).
     * Întoarce true dacă [active] s-a schimbat.
     */
    fun sample(dropped: Int, available: Set<Step>, judder: Boolean = false): Boolean {
        val now = clock()
        if (startedAt < 0) {
            startedAt = now
            lastT = now
            lastDropped = dropped
            return false
        }
        if (now - startedAt < WARMUP_MS) {
            lastT = now
            lastDropped = dropped
            return false
        }
        val dt = now - lastT
        if (dt < WINDOW_MS) return false
        val rate = (dropped - lastDropped).coerceAtLeast(0) * 1000f / dt
        lastT = now
        lastDropped = dropped

        val t = trial
        if (t != null) {
            if (now - trialSince < TRIAL_MS) return false
            val trialRate = (dropped - trialDropped).coerceAtLeast(0) * 1000f / (now - trialSince)
            trial = null
            tried += t
            return if (trialRate <= BAD_RATE || trialRate < rateBefore * 0.5f) {
                kept = kept + t
                note = "Sacadare redusă: am păstrat ${t.label}"
                badWindows = 0
                false
            } else {
                active = active - t
                note = "${t.label.replaceFirstChar { it.uppercase() }} n-a ajutat; încerc altceva"
                badWindows = 0
                true
            }
        }

        // Cadrele stream-ului nu se potrivesc cu ecranul (25 fps pe 60 Hz): mișcarea e inegală chiar fără cadre
        // pierdute, deci se încearcă potrivirea frecvenței fără să se aștepte pierderi.
        val judderOnly = rate < BAD_RATE && judder && Step.MATCH_RATE in available && Step.MATCH_RATE !in tried && Step.MATCH_RATE !in active
        if (rate < BAD_RATE && !judderOnly) {
            badWindows = 0
            return false
        }
        badWindows++
        if (badWindows < BAD_WINDOWS) return false
        val next = (if (judderOnly) Step.MATCH_RATE else Step.entries.firstOrNull { it in available && it !in tried && it !in active }) ?: return false
        trial = next
        trialSince = now
        trialDropped = dropped
        rateBefore = rate
        active = active + next
        note = if (judderOnly) "Cadrele nu se potrivesc cu ecranul: încerc ${next.label}" else "Pierd cadre (${"%.0f".format(rate)}/s): încerc ${next.label}"
        badWindows = 0
        return true
    }

    /** Citește anunțul o singură dată. */
    fun takeNote(): String? = note.also { note = null }

    companion object {
        const val WARMUP_MS = 15_000L
        const val WINDOW_MS = 5_000L
        const val TRIAL_MS = 15_000L

        /** Peste un cadru pierdut pe secundă, în două ferestre la rând, e sacadare. */
        const val BAD_RATE = 1f
        const val BAD_WINDOWS = 2
    }
}
