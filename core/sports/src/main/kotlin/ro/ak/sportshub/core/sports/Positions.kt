package ro.ak.sportshub.core.sports

/**
 * Prescurtarea postului unui jucător („Center Left Defender” → LCB, „Goalkeeper” → GK, „Attacking Midfielder” → AM),
 * pentru listele scurte de la Formații. Ce e deja scurt („CD-L”, „G”) rămâne, cu litere mari.
 */
object Positions {

    fun short(position: String?): String? {
        val raw = position?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.length <= 4 && ' ' !in raw) return raw.uppercase()
        val w = Sport.fold(raw).split(' ').filter { it.isNotEmpty() }.toSet()
        val side = when {
            "left" in w -> "L"
            "right" in w -> "R"
            else -> ""
        }
        val center = "center" in w || "centre" in w || "central" in w
        return when {
            "goalkeeper" in w || "keeper" in w || "goalie" in w -> "GK"
            "wing" in w && "back" in w || "wingback" in w -> side + "WB"
            "defender" in w || "back" in w || "fullback" in w -> when {
                center || side.isEmpty() -> side + "CB"
                else -> side + "B"
            }
            "midfielder" in w || "midfield" in w -> when {
                "defensive" in w -> side + "DM"
                "attacking" in w -> side + "AM"
                center || side.isEmpty() -> side + "CM"
                else -> side + "M"
            }
            "winger" in w || "wing" in w -> (side.ifEmpty { "" }) + "W"
            "striker" in w -> "ST"
            "forward" in w || "attacker" in w -> when {
                center || side.isEmpty() -> side + "CF"
                else -> side + "F"
            }
            "substitute" in w || "sub" in w -> "SUB"
            else -> w.joinToString("") { it.take(1) }.uppercase().take(3)
        }
    }
}
