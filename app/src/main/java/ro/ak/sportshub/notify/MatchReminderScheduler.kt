package ro.ak.sportshub.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.core.sports.MatchReminders

/** O notificare programată: ce se arată și când. */
data class PlannedReminder(val matchId: String, val at: Long, val title: String, val text: String, val hasSources: Boolean)

/**
 * Programează notificările pentru meciurile favorite (cu [ro.ak.sportshub.data.SportsSettings.reminderMinutes]
 * înainte de start), din lista Sports Hub calculată local: fără rețea și fără conexiuni la furnizorul IPTV.
 * Alarmele sunt aproximative (`setWindow`, un minut), deci nu cer permisiunea de alarme exacte.
 */
class MatchReminderScheduler(private val context: Context, private val container: AppContainer) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    fun start() {
        container.scope.launch {
            combine(container.sports.snapshot, container.sportsSettings.settings) { snap, s ->
                if (!s.reminders || snap.loading) {
                    emptyList<PlannedReminder>()
                } else {
                    val byId = snap.matches.associateBy { it.match.id }
                    val notified = notified()
                    MatchReminders.plan(
                        matches = snap.matches.map { it.match },
                        isFavorite = s.favorites::isFavorite,
                        now = System.currentTimeMillis(),
                        leadMs = TimeUnit.MINUTES.toMillis(s.reminderMinutes.toLong()),
                    ).filter { it.matchId !in notified }.mapNotNull { r ->
                        val e = byId[r.matchId] ?: return@mapNotNull null
                        val m = e.match
                        val time = Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault()).format(TimeFormat)
                        PlannedReminder(
                            matchId = m.id,
                            at = r.at,
                            title = m.title,
                            text = listOfNotNull("Începe la $time", m.competition, sourcesText(e.summary.count)).joinToString(" · "),
                            hasSources = e.summary.count > 0,
                        )
                    }
                }
            }.distinctUntilChanged().collect { schedule(it) }
        }
    }

    private fun schedule(plan: List<PlannedReminder>) {
        val am = alarms ?: return
        val previous = prefs.getStringSet(SCHEDULED, emptySet()).orEmpty()
        val next = plan.map { it.matchId }.toSet()
        // Meciurile scoase din favorite (sau din listă) nu mai primesc notificare.
        (previous - next).forEach { id ->
            PendingIntent.getBroadcast(context, 0, intent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { am.cancel(it); it.cancel() }
        }
        plan.forEach { r ->
            val i = intent(r.matchId)
                .putExtra(ReminderReceiver.EXTRA_TITLE, r.title)
                .putExtra(ReminderReceiver.EXTRA_TEXT, r.text)
                .putExtra(ReminderReceiver.EXTRA_HAS_SOURCES, r.hasSources)
            val pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, r.at, WINDOW_MS, pi) }
        }
        prefs.edit().putStringSet(SCHEDULED, next).apply()
    }

    private fun notified(): Set<String> = prefs.getStringSet(NOTIFIED, emptySet()).orEmpty()

    private fun intent(matchId: String) = Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION)
        .setData(Uri.parse("aksportshub-reminder://match/" + Uri.encode(matchId)))
        .putExtra(ReminderReceiver.EXTRA_MATCH, matchId)

    private fun sourcesText(count: Int) = when (count) {
        0 -> "fără surse găsite încă"
        1 -> "1 sursă"
        else -> "$count surse"
    }

    companion object {
        const val STATE = "reminders_state"
        private const val SCHEDULED = "scheduled"
        private const val NOTIFIED = "notified"
        private const val WINDOW_MS = 60_000L
        private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ro"))

        /** Meciul a primit notificarea: nu se mai programează (se păstrează ultimele 200). */
        fun markNotified(context: Context, matchId: String) {
            val prefs = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
            val list = (prefs.getStringSet(NOTIFIED, emptySet()).orEmpty() + matchId).toList().takeLast(200).toSet()
            prefs.edit().putStringSet(NOTIFIED, list).apply()
        }
    }
}
