package ro.ak.sportshub.data

import java.util.concurrent.TimeUnit
import ro.ak.sportshub.core.common.ObservedVideo
import ro.ak.sportshub.core.common.VariantOrder
import ro.ak.sportshub.core.database.ChannelDao
import ro.ak.sportshub.core.database.ChannelWithPlaylist
import ro.ak.sportshub.core.database.PlayableChannel
import ro.ak.sportshub.core.model.ChannelCorrection
import ro.ak.sportshub.core.model.ChannelLabels
import ro.ak.sportshub.core.model.Quality
import ro.ak.sportshub.core.model.SourcePreferences

/**
 * Variantele canalelor: aceeași cheie de grupare (numele normalizat + țara), din toate playlisturile active.
 * Ordinea: varianta preferată, apoi rezoluția, apoi limbile preferate; cele care n-au pornit ultima oară
 * coboară la final. Nu se citește niciun stream separat: totul vine din playlist, din corecțiile tale și din
 * rezoluția văzută la redările obișnuite.
 */
class VariantRepository(
    private val dao: ChannelDao,
    private val store: VariantStore,
    private val preferences: () -> SourcePreferences = { SourcePreferences() },
    /** Rezoluțiile văzute la redare ([ObservedVideoStore]): ordinea folosește rezoluția reală. */
    private val observed: () -> Map<CorrectionKey, ObservedVideo> = { emptyMap() },
    /** Variantele semnalate „canal posibil greșit” ([SuspectChannelsStore]): rămân, dar după celelalte. */
    private val suspect: () -> Set<CorrectionKey> = { emptySet() },
) {

    /** Calitatea pentru ordine: corecția ta, altfel rezoluția văzută la redare, altfel eticheta din playlist. */
    fun effectiveQuality(v: ChannelWithPlaylist, s: VariantState = store.current, seen: Map<CorrectionKey, ObservedVideo> = observed()): Quality =
        qualityView(v.playlistId, v.name, v.quality, seen, s).quality

    fun isPreferred(v: ChannelWithPlaylist, s: VariantState = store.current): Boolean =
        s.preferred[v.groupKey]?.let { it.playlistId == v.playlistId && it.name == v.name } == true

    fun failedRecently(v: ChannelWithPlaylist, s: VariantState = store.current, now: Long = System.currentTimeMillis()): Boolean =
        s.failures[ref(v)]?.let { now - it < FAILURE_MEMORY_MS } == true

    fun order(variants: List<ChannelWithPlaylist>, s: VariantState = store.current): List<ChannelWithPlaylist> {
        val now = System.currentTimeMillis()
        val seen = observed()
        val flagged = suspect()
        val sorted = VariantOrder.sort(
            variants,
            quality = { effectiveQuality(it, s, seen) },
            language = { it.language },
            isPreferred = { isPreferred(it, s) },
            failedRecently = { failedRecently(it, s, now) },
            prefs = preferences(),
        )
        return if (flagged.isEmpty()) sorted else sorted.sortedBy { CorrectionKey(it.playlistId, it.name) in flagged }
    }

    /** Variantele unui canal, în ordinea de pornire. */
    suspend fun variants(groupKey: String): List<ChannelWithPlaylist> = order(dao.variants(listOf(groupKey)))

    /** Variantele ordonate pentru mai multe canale deodată (listele din Live TV). */
    suspend fun families(keys: Collection<String>): Map<String, List<ChannelWithPlaylist>> {
        val s = store.current
        return keys.distinct().chunked(IN_CHUNK)
            .flatMap { dao.variants(it) }
            .groupBy { it.groupKey }
            .mapValues { order(it.value, s) }
    }

    /** Ce pornește OK pe un canal grupat: varianta preferată sau cea mai bună. */
    suspend fun representative(groupKey: String): ChannelWithPlaylist? = variants(groupKey).firstOrNull()

    fun setPreferred(v: ChannelWithPlaylist) =
        store.update { it.copy(preferred = it.preferred + (v.groupKey to ref(v))) }

    fun clearPreferred(groupKey: String) = store.update { it.copy(preferred = it.preferred - groupKey) }

    fun correctionOf(v: ChannelWithPlaylist): CorrectionEntry? = store.current.corrections[CorrectionKey(v.playlistId, v.name)]

    /**
     * Schimbă corecția manuală a unei variante și o aplică pe loc. O corecție care ajunge la valorile
     * din playlist se șterge. Se păstrează la actualizările playlistului (după numele original).
     */
    suspend fun correct(v: ChannelWithPlaylist, transform: (ChannelCorrection) -> ChannelCorrection) {
        val key = CorrectionKey(v.playlistId, v.name)
        val existing = store.current.corrections[key]
        val original = existing?.original ?: ChannelLabels(v.groupKey, v.quality, v.language)
        val next = transform(existing?.correction ?: ChannelCorrection())
        val cleaned = ChannelCorrection(
            groupKey = next.groupKey?.takeIf { it != original.groupKey },
            quality = next.quality?.takeIf { it != original.quality },
            language = next.language?.takeIf { it.ifEmpty { null } != original.language },
        )
        val labels = cleaned.applyTo(original)
        store.update { s ->
            var preferred = s.preferred
            // Varianta preferată se mută odată cu ea în noul canal (dacă acolo nu era alta aleasă).
            if (labels.groupKey != v.groupKey && isPreferred(v, s)) {
                preferred = preferred - v.groupKey
                if (labels.groupKey !in preferred) preferred = preferred + (labels.groupKey to ref(v))
            }
            s.copy(
                corrections = if (cleaned.isEmpty) s.corrections - key else s.corrections + (key to CorrectionEntry(cleaned, original)),
                preferred = preferred,
            )
        }
        dao.relabel(v.playlistId, v.name, labels.groupKey, labels.quality, labels.language)
    }

    /** Toate variantele canalului [fromKey] trec în canalul [intoKey] (unire manuală). */
    suspend fun merge(fromKey: String, intoKey: String) {
        if (fromKey == intoKey) return
        dao.variants(listOf(fromKey)).distinctBy { it.playlistId to it.name }.forEach { v ->
            correct(v) { it.copy(groupKey = intoKey) }
        }
    }

    /** Scoate varianta din canalul ei: devine un canal separat. */
    suspend fun separate(v: ChannelWithPlaylist) =
        correct(v) { it.copy(groupKey = ChannelCorrection.separateKey(v.playlistId, v.name)) }

    suspend fun revert(v: ChannelWithPlaylist) = correct(v) { ChannelCorrection() }

    // ---------- starea surselor (doar din redare) ----------

    fun markFailed(c: PlayableChannel) {
        val r = ChannelRef.of(c)
        store.update { s ->
            val failures = (s.failures + (r to System.currentTimeMillis()))
                .entries.sortedByDescending { it.value }.take(MAX_FAILURES).associate { it.key to it.value }
            s.copy(failures = failures)
        }
    }

    fun markWorked(c: PlayableChannel) {
        val r = ChannelRef.of(c)
        if (r in store.current.failures) store.update { it.copy(failures = it.failures - r) }
    }

    private fun ref(v: ChannelWithPlaylist) = ChannelRef(v.playlistId, v.name, v.groupName)

    private companion object {
        const val IN_CHUNK = 500
        const val MAX_FAILURES = 300
        val FAILURE_MEMORY_MS = TimeUnit.HOURS.toMillis(24)
    }
}
