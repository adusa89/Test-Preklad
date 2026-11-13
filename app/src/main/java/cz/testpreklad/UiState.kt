package cz.testpreklad

/**
 * Je právě vidět některá obrazovka této aplikace (hlavní menu, nastavení oblastí)?
 * Služba pak obrazovku nečte – nepřekládala by vlastní menu a nepřepsala si snímek ze hry.
 */
object UiState {
    @Volatile private var screens = 0
    val visible: Boolean get() = screens > 0

    @Synchronized fun shown() { screens++ }
    @Synchronized fun hidden() { screens = maxOf(0, screens - 1) }
}
