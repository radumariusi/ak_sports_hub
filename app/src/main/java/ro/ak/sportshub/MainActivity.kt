package ro.ak.sportshub

import android.Manifest
import android.app.SearchManager
import android.view.KeyEvent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ro.ak.sportshub.ui.AppNavigation
import ro.ak.sportshub.ui.theme.AkTheme

class MainActivity : ComponentActivity() {

    private companion object {
        /** Căutarea din Google Assistant („caută … în AK Sports Hub”). */
        const val GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"
    }

    /** Legătura de deschis (din notificare sau din rândul de pe ecranul principal); null după ce s-a deschis. */
    private val deepLink = MutableStateFlow<DeepLinks.Target?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as AkApp).container.startup.screenOpened()
        if (savedInstanceState == null) deepLink.value = target(intent)
        setContent {
            val variants by (application as AkApp).container.variantStore.state.collectAsStateWithLifecycle()
            AkTheme(textScale = variants.textScale) {
                AppNavigation(deepLink = deepLink)
            }
        }
        requestNotificationsOnce()
    }

    override fun onStart() {
        super.onStart()
        (application as AkApp).container.secondScreen.setVisible(true)
    }

    override fun onStop() {
        (application as AkApp).container.secondScreen.setVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // O dată la câteva ore: e o versiune nouă a aplicației? (apare în Setări → Actualizare).
        (application as AkApp).container.updater.checkIfDue()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target(intent)?.let { deepLink.value = it }
    }

    /**
     * Ce deschide intentul: o legătură internă, sau o căutare (F1) venită din Google Assistant / căutarea televizorului
     * („caută Arsenal în AK Sports Hub”).
     */
    private fun target(intent: Intent?): DeepLinks.Target? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEARCH || intent.action == GMS_SEARCH) {
            val q = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
            return DeepLinks.Target.Search(q, voice = q.isEmpty())
        }
        return DeepLinks.parse(intent.data)
    }

    /** Butonul de microfon / căutare al telecomenzii: căutarea cu vocea, din orice ecran. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            deepLink.value = DeepLinks.Target.Search("", voice = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSearchRequested(): Boolean {
        deepLink.value = DeepLinks.Target.Search("", voice = true)
        return true
    }

    /** Android 13+: permisiunea pentru notificările meciurilor favorite se cere o singură dată. */
    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
