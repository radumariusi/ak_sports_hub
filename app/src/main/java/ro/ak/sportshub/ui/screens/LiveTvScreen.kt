package ro.ak.sportshub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ro.ak.sportshub.ui.components.EmptyState
import ro.ak.sportshub.ui.components.SectionLabel
import ro.ak.sportshub.ui.theme.AkColors

/** Grupuri în stânga, canale în dreapta, totul ca liste. Se umple în etapa 2. */
@Composable
fun LiveTvScreen() {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(AkColors.Panel, RoundedCornerShape(8.dp))
                .padding(vertical = 8.dp),
        ) {
            SectionLabel("Grupuri")
            EmptyState(title = "—", hint = "Niciun grup încă")
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxSize()) {
            SectionLabel("Canale")
            Box(modifier = Modifier.fillMaxSize()) {
                EmptyState(
                    title = "Niciun playlist",
                    hint = "Adăugarea playlisturilor M3U și Xtream vine în etapa 2.",
                )
            }
        }
    }
}
