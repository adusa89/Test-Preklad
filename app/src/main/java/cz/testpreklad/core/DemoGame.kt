package cz.testpreklad.core

/**
 * Ukázková „hra“: anglické titulky, které se střídají podle času. Stejný rozvrh používá
 * obrazovka ukázky i ukázkové video, takže pruh s překladem sedí na to, co je vidět.
 */
object DemoGame {
    val lines = listOf(
        "Dutch: We need money, Arthur.",
        "",
        "Arthur: I reckon we ain't gonna find nothin' in Valentine.",
        "",
        "Get on your horse, we ride at dawn.",
        "",
        "Hosea: If we keep going like this,\nthe Pinkertons will find us.",
        "",
    )
    const val SHOWN_MS = 6000L
    const val GAP_MS = 2000L
    val cycleMs: Long = lines.sumOf { if (it.isEmpty()) GAP_MS else SHOWN_MS }

    /** Pořadí titulku, který je vidět v čase [ms] od začátku. */
    fun indexAt(ms: Long): Int {
        var t = ((ms % cycleMs) + cycleMs) % cycleMs
        for ((i, l) in lines.withIndex()) {
            val d = if (l.isEmpty()) GAP_MS else SHOWN_MS
            if (t < d) return i
            t -= d
        }
        return 0
    }

    fun subtitleAt(ms: Long): String = lines[indexAt(ms)]
}
