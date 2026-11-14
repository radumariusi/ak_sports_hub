package ro.ak.sportshub.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ro.ak.sportshub.AkApp
import ro.ak.sportshub.DeepLinks
import ro.ak.sportshub.R

/** Arată notificarea unui meci favorit; „Pornește” deschide direct sursa recomandată. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val matchId = intent.getStringExtra(EXTRA_MATCH) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        MatchReminderScheduler.markNotified(context, matchId)

        // Dacă aplicația e deschisă (de exemplu în player), mesajul apare și pe ecran.
        (context.applicationContext as? AkApp)?.container?.inAppNotices?.tryEmit("$title · $text")

        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, matchId.hashCode(), DeepLinks.intent(context, DeepLinks.match(matchId)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (intent.getBooleanExtra(EXTRA_HAS_SOURCES, false)) {
            val play = PendingIntent.getActivity(
                context, matchId.hashCode() + 1, DeepLinks.intent(context, DeepLinks.play(matchId)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Pornește", play)
        }
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify(matchId.hashCode(), builder.build()) }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Meciuri favorite", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Cu câteva minute înainte de meciurile echipelor și competițiilor favorite"
                },
            )
        }
    }

    companion object {
        const val ACTION = "ro.ak.sportshub.REMINDER"
        const val EXTRA_MATCH = "match"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_HAS_SOURCES = "hasSources"
        private const val CHANNEL = "meciuri"
    }
}
