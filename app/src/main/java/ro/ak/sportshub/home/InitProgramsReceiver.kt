package ro.ak.sportshub.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Launcherul Android TV cere rândurile aplicației (după instalare sau repornire). Pornirea procesului
 * e de ajuns: aplicația ([ro.ak.sportshub.AkApp]) refă rândul din lista Sports Hub.
 */
class InitProgramsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
