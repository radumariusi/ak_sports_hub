package ro.ak.sportshub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ro.ak.sportshub.ui.AppNavigation
import ro.ak.sportshub.ui.theme.AkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AkTheme {
                AppNavigation()
            }
        }
    }
}
