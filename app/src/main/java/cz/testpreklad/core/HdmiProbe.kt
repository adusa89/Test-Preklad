package cz.testpreklad.core

/**
 * Automatický test: dovolí televize aplikaci číst obraz z HDMI vstupu?
 *
 * Když televize HDMI blokuje, snímání obrazovky vrací místo hry úplně černý obraz.
 * Test sleduje snímky pořízené během hraní a pozná, jestli v nich je skutečný obraz.
 */
class HdmiProbe(private val needFrames: Int = 10) {

    enum class Verdict { UNKNOWN, WORKS, BLOCKED }

    var frames = 0; private set
    var contentFrames = 0; private set

    /** [contentRatio] = podíl pixelů, které nejsou černé (0..1). */
    @Synchronized fun add(contentRatio: Float) {
        frames++
        if (contentRatio >= CONTENT_MIN) contentFrames++
    }

    @Synchronized fun reset() { frames = 0; contentFrames = 0 }

    val verdict: Verdict
        @Synchronized get() = when {
            contentFrames >= WORKS_MIN -> Verdict.WORKS      // obraz byl vidět opakovaně
            frames >= needFrames -> Verdict.BLOCKED          // dost snímků a všechny černé
            else -> Verdict.UNKNOWN
        }

    companion object {
        /** Pixel tmavší než tohle se počítá jako černý (blokovaný obraz je přesně 0). */
        const val BLACK_LUMA = 12
        /** Aspoň 15 % obrazu musí být vidět (černý pruh ani hlášení TV to nepřekročí). */
        const val CONTENT_MIN = 0.15f
        const val WORKS_MIN = 5

        /** Podíl nečerných pixelů v náhledu (jas 0..255). */
        fun contentRatio(lum: IntArray): Float =
            if (lum.isEmpty()) 0f else lum.count { it > BLACK_LUMA }.toFloat() / lum.size
    }
}
