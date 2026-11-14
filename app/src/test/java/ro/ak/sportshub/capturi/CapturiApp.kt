package ro.ak.sportshub.capturi

import android.content.Context
import ro.ak.sportshub.AkApp

/**
 * Aplicația pentru capturile de ecran: aceeași ca pe televizor, dar fără actualizări, rețea,
 * notificări sau rândul din launcher. Datele sunt inventate (vezi [DemoData]); nimic real.
 */
class CapturiApp : AkApp() {
    override fun onCreate() {
        // Setările se scriu înainte ca aplicația să le citească.
        getSharedPreferences("sports", Context.MODE_PRIVATE).edit()
            .putString(
                "settings",
                """{"liveSoccerTv":false,"sofascore":false,"sportsDbEnabled":true,"sportsDbTv":true,""" +
                    """"favorites":{"teams":["Arsenal"],"competitions":[],"sports":[]}}""",
            )
            .commit()
        super.onCreate()
    }

    override fun startBackgroundWork() = Unit
}
