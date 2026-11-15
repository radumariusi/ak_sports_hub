package ro.ak.sportshub

import android.Manifest
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

    /** Legătura de deschis (din notificare sau din rândul de pe ecranul principal); null după ce s-a deschis. */
    private val deepLink = MutableStateFlow<DeepLinks.Target?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink.value = DeepLinks.parse(intent?.data)
        setContent {
            val variants by (application as AkApp).container.variantStore.state.collectAsStateWithLifecycle()
            AkTheme(textScale = variants.textScale) {
                AppNavigation(deepLink = deepLink)
            }
        }
        requestNotificationsOnce()
    }

    override fun onResume() {
        super.onResume()
        // O dată la câteva ore: e o versiune nouă a aplicației? (apare în Setări → Actualizare).
        (application as AkApp).container.updater.checkIfDue()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.parse(intent.data)?.let { deepLink.value = it }
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
