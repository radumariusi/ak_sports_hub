package ro.ak.sportshub.ui.screens

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.sportshub.AppContainer
import ro.ak.sportshub.core.common.PhoneSetup
import ro.ak.sportshub.core.database.PlaylistEntity
import ro.ak.sportshub.core.database.PlaylistType
import ro.ak.sportshub.core.epg.EpgSources
import ro.ak.sportshub.setup.PhoneSetupServer
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.components.ActionButton
import ro.ak.sportshub.ui.theme.AkColors

/**
 * Adaugă un playlist din telefon: televizorul arată un cod QR cu adresa unei pagini servite de el în rețeaua locală
 * (doar cât e deschis ecranul), iar pe telefon scrii serverul, utilizatorul și parola Xtream (sau linkul M3U).
 * Playlistul se salvează și începe să se încarce; serverul se oprește când ieși din ecran.
 */
@Composable
fun QrSetupScreen(onDone: () -> Unit) {
    val container = appContainer()
    val token = remember { PhoneSetup.newToken() }
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }

    DisposableEffect(token) {
        val server = PhoneSetupServer(token) { submission ->
            container.scope.launch {
                val name = save(container, submission)
                withContext(Dispatchers.Main) { received = received + name }
            }
        }
        url = server.start(container.scope)
        started = true
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val qr = remember(url) { url?.let { qrBitmap(it) } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(text = "Adaugă un playlist din telefon", fontSize = 32.sp, fontFamily = ro.ak.sportshub.ui.theme.AkFonts.Condensed, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Box(modifier = Modifier.size(300.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (qr != null) Image(bitmap = qr, contentDescription = "Cod QR", modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !started -> Text(text = "Se pornește…", fontSize = 18.sp)
                    url == null -> Text(
                        text = "Televizorul nu pare conectat la o rețea locală (Wi-Fi sau cablu). Conectează-l și deschide din nou ecranul.",
                        fontSize = 18.sp,
                        color = AkColors.Live,
                    )
                    else -> {
                        Text(text = "1. Telefonul în aceeași rețea Wi-Fi cu televizorul.", fontSize = 18.sp)
                        Text(text = "2. Scanează codul cu camera telefonului (sau deschide adresa de mai jos).", fontSize = 18.sp)
                        Text(text = "3. Scrie serverul, utilizatorul și parola Xtream sau linkul M3U, apoi „Trimite”.", fontSize = 18.sp)
                        Text(text = url.orEmpty(), fontSize = 20.sp, color = AkColors.Accent, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            text = "Pagina merge doar cât e deschis ecranul acesta. Datele rămân pe televizor și nu trec prin internet.",
                            fontSize = 14.sp,
                            color = AkColors.TextDim,
                        )
                    }
                }
                received.forEach { name ->
                    Text(text = "✓ „$name” a fost adăugat și se încarcă.", fontSize = 18.sp, color = AkColors.Accent)
                }
                ActionButton(text = if (received.isEmpty()) "Înapoi" else "Gata", onClick = onDone, modifier = Modifier.padding(top = 12.dp).focusRequester(focus))
            }
        }
    }
}

/** Salvează playlistul trimis (și EPG-ul extern, dacă există) și pornește încărcarea. Întoarce numele. */
private suspend fun save(container: AppContainer, s: PhoneSetup.Submission): String {
    val entity = when (s) {
        is PhoneSetup.Submission.Xtream -> PlaylistEntity(
            name = s.name.ifBlank { hostOf(s.server) ?: "Xtream" },
            type = PlaylistType.XTREAM,
            url = s.server,
            username = s.username,
            password = s.password,
        )
        is PhoneSetup.Submission.M3u -> PlaylistEntity(name = s.name.ifBlank { hostOf(s.url) ?: "Playlist" }, type = PlaylistType.M3U_URL, url = s.url)
    }
    val id = container.playlists.save(entity)
    container.playlists.refreshInBackground(id, force = true)
    s.epgUrl?.let { epgUrl ->
        val e = container.epgSettings.addExternal(epgUrl, "EPG ${entity.name}")
        container.epg.refreshInBackground(EpgSources.externalKey(e.id))
    }
    return entity.name
}

private fun hostOf(url: String): String? =
    runCatching { Uri.parse(if (url.contains("://")) url else "http://$url").host }.getOrNull()?.ifEmpty { null }

/** Codul QR al adresei [text], negru pe alb. */
private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
