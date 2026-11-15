package ro.ak.sportshub.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import ro.ak.sportshub.core.sports.FormResult
import ro.ak.sportshub.core.sports.TeamCodes
import ro.ak.sportshub.core.sports.TeamLook
import ro.ak.sportshub.core.sports.TeamLookBook
import ro.ak.sportshub.core.sports.TeamLooks
import ro.ak.sportshub.ui.appContainer
import ro.ak.sportshub.ui.theme.AkColors
import ro.ak.sportshub.ui.theme.AkFonts

/** Culorile formei: victorie verde, egal gri, înfrângere roșu (aceleași în Pre-meci și în panouri). */
val FormWin = Color(0xFF35C46A)
val FormDraw = Color(0xFFB0B7C3)
val FormLoss = Color(0xFFE5484D)

fun FormResult.color(): Color = when (this) {
    FormResult.WIN -> FormWin
    FormResult.DRAW -> FormDraw
    FormResult.LOSS -> FormLoss
}

/** Cartea siglelor și a culorilor (se schimbă doar după un calcul al listei). */
@Composable
fun rememberTeamLooks(): TeamLookBook {
    val book by appContainer().teamLooks.book.collectAsStateWithLifecycle()
    return book
}

/** Culoarea benzii echipei pe fundalul închis, sau null dacă nu se știe ori nu s-ar vedea. */
fun TeamLook?.bandColor(): Color? = TeamLooks.band(this)?.let { Color(it.toInt()) }

/** O imagine de siglă: din memorie, de pe disc sau descărcată o dată (nimic cât rulează playerul). */
@Composable
private fun rememberLogo(url: String?, size: Dp): ImageBitmap? {
    val logos = appContainer().logos
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(16)
    // La schimbarea adresei (panoul din dreapta trece la alt meci) starea veche nu trebuie să rămână: fără asta,
    // Saints aveau sigla Falcons de la meciul focusat înainte.
    val bmp by produceState(initialValue = logos.cached(url), url) {
        value = logos.cached(url)
        if (url != null && value == null) value = logos.load(url, px)
    }
    return bmp
}

/**
 * Sigla unei echipe. Fără siglă: codul echipei ([TeamCodes]) pe culoarea ei, dacă [codeFallback]; altfel un loc gol
 * cât se descarcă sigla, sau nimic dacă echipa nu are siglă cunoscută.
 */
@Composable
fun TeamLogo(team: String, size: Dp, modifier: Modifier = Modifier, codeFallback: Boolean = false, look: TeamLook? = rememberTeamLooks().team(team)) {
    val bmp = rememberLogo(look?.logo, size)
    // Fără adresă de siglă și fără cod: nu ocupă loc (cine vrea rânduri aliniate pune sigla într-o cutie fixă).
    if (look?.logo == null && !codeFallback) return
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = team, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else if (codeFallback && team.isNotBlank()) {
            val bg = look.bandColor() ?: AkColors.Panel
            Box(
                modifier = Modifier.size(size).background(bg, RoundedCornerShape(size / 5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = look?.code?.take(4) ?: TeamCodes.of(team),
                    color = if (TeamLooks.band(look) != null && TeamLooks.luminance(TeamLooks.band(look)!!) > 0.45) AkColors.OnFocus else AkColors.Text,
                    fontSize = (size.value * 0.32f).sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Sigla unei competiții (sau nimic). */
@Composable
fun CompetitionLogo(competition: String?, size: Dp, modifier: Modifier = Modifier) {
    val url = rememberTeamLooks().competition(competition)
    val bmp = rememberLogo(url, size)
    if (bmp != null) Image(bitmap = bmp, contentDescription = competition, contentScale = ContentScale.Fit, modifier = modifier.size(size))
}

/**
 * Banda verticală a meciului: sus culoarea gazdelor, jos a oaspeților (stilul Reflector: drept, subțire).
 * Fără culori cunoscute rămâne un loc gol, ca rândurile să stea aliniate.
 */
@Composable
fun MatchBand(home: TeamLook?, away: TeamLook?, height: Dp, modifier: Modifier = Modifier) {
    val h = home.bandColor()
    val a = away.bandColor()
    Column(modifier = modifier.width(4.dp)) {
        Box(Modifier.width(4.dp).size(width = 4.dp, height = height / 2).background(h ?: Color.Transparent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
        Box(Modifier.width(4.dp).size(width = 4.dp, height = height / 2).background(a ?: Color.Transparent, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)))
    }
}

/** Forma ca pătrățele V / E / Î (cel mult 5). */
@Composable
fun FormSquares(form: List<FormResult>, modifier: Modifier = Modifier, square: Dp = 18.dp) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        form.forEach { f ->
            Box(modifier = Modifier.size(square).background(f.color(), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(
                    text = f.letter,
                    color = AkColors.OnFocus,
                    fontSize = (square.value * 0.62f).sp,
                    fontFamily = AkFonts.Condensed,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
