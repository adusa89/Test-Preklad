package cz.testpreklad

/**
 * Je právě vidět některá obrazovka této aplikace (hlavní menu, nastavení oblastí)?
 * Služba pak obrazovku nečte – nepřekládala by vlastní menu a nepřepsala si snímek ze hry.
 */
object UiState {
    /** Je vidět ukázková „herní“ obrazovka? Tu aplikace čte jako hru. */
    @Volatile var testCard = false

    @Volatile private var screens = 0
    @Volatile private var hiddenAt = 0L

    /** Obrazovka aplikace je vidět, nebo teprve před chvilkou zmizela (přechod ještě může být ve snímku). */
    val visible: Boolean get() = screens > 0 || android.os.SystemClock.uptimeMillis() - hiddenAt < 900

    @Synchronized fun shown() { screens++ }
    @Synchronized fun hidden() {
        screens = maxOf(0, screens - 1)
        if (screens == 0) hiddenAt = android.os.SystemClock.uptimeMillis()
    }
}
