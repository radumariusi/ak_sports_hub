package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

/**
 * F4, telefonul ca al doilea ecran: pornești / oprești serverul local și scanezi codul QR cu telefonul. Adresa rămâne
 * aceeași (o poți salva pe telefon); serverul merge doar cât e aplicația pe ecran.
 */
@Composable
fun SecondScreenScreen(onDone: () -> Unit) {
    val host = appContainer().secondScreen
    val state by host.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val qr = remember(state.url) { state.url?.let { qrBitmap(it) } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(text = "Telefonul ca al doilea ecran", fontSize = 32.sp, fontFamily = AkFonts.Condensed, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            if (qr != null) {
                Box(modifier = Modifier.size(300.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Image(bitmap = qr, contentDescription = "Cod QR", modifier = Modifier.fillMaxSize())
                }
            }
            Column(modifier = Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Cât te uiți la meci, pe telefon vezi ce e pe televizor, meciurile live și de azi–mâine, iar pe un meci " +
                        "scorul, fazele, statisticile și formațiile. „Pornește pe televizor” pornește sursa recomandată.",
                    fontSize = 18.sp,
                )
                when {
                    !state.enabled -> Text(text = "Oprit. Pornește-l, apoi scanează codul cu telefonul.", fontSize = 18.sp, color = AkColors.TextDim)
                    state.url == null && state.noNetwork -> Text(
                        text = "Televizorul nu pare conectat la o rețea locală (Wi-Fi sau cablu).",
                        fontSize = 18.sp,
                        color = AkColors.Live,
                    )
                    state.url == null -> Text(text = "Se pornește…", fontSize = 18.sp)
                    else -> {
                        Text(text = "Telefonul în aceeași rețea Wi-Fi, apoi scanează codul (sau deschide adresa):", fontSize = 18.sp)
                        Text(text = state.url.orEmpty(), fontSize = 20.sp, color = AkColors.Accent)
                        Text(
                            text = "Pagina merge doar cât e aplicația deschisă pe televizor. Telefonul nu primește adresele " +
                                "stream-urilor și nici datele playlisturilor; scorul ține cont de întârzierea stream-ului și de „Fără spoilere”.",
                            fontSize = 14.sp,
                            color = AkColors.TextDim,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    ActionButton(
                        text = if (state.enabled) "Oprește" else "Pornește",
                        onClick = { host.setEnabled(!state.enabled) },
                        modifier = Modifier.focusRequester(focus),
                    )
                    if (state.enabled) ActionButton(text = "Cod nou (adresă nouă)", onClick = { host.newToken() })
                    ActionButton(text = "Înapoi", onClick = onDone)
                }
            }
        }
    }
}
