package ro.ak.sportshub.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import ro.ak.sportshub.AkApp

/**
 * Actualizarea programată a playlisturilor și a EPG-ului. Rulează la 2 ore și reîncarcă doar ce are
 * intervalul trecut (setat pe fiecare playlist, respectiv în setările EPG). Nu așteaptă după player: dacă un playlist
 * e folosit, rămâne pentru rularea următoare.
 */
class PlaylistUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as AkApp
        app.container.playlists.refreshDue()
        app.container.epg.refreshDue()
        return Result.success()
    }

    companion object {
        private const val NAME = "actualizare-playlisturi"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PlaylistUpdateWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
