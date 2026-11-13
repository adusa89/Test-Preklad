package cz.testpreklad.core

/**
 * Hlídá, jestli snímání obrazovky "vidí" i náš vlastní černý pruh.
 *
 * - Když sdílíš jen aplikaci PS Remote Play (Android 14+), náš pruh ve snímku není
 *   a pod ním se dají číst další anglické titulky – pruh může zůstat trvale.
 * - Když se sdílí celá obrazovka, OCR pod pruhem přečte náš vlastní překlad.
 *   Pak se pruh vždy na okamžik (zlomek sekundy) skryje, přečte se, co je pod ním,
 *   a hned se zase zobrazí ("nakouknutí").
 */
class CaptureMonitor(private val peekIntervalMs: Long = 500L) {

    /** True = snímek obsahuje náš pruh, je potřeba nakukovat. */
    var overlayCaptured: Boolean = false
        private set

    private var lastPeek: Long = 0L

    /**
     * Je přečtený text ve skutečnosti náš vlastní zobrazený překlad?
     * Krátké texty a texty shodné s originálem ("Arthur!" = "Arthur!") se nepočítají –
     * podle nich se nedá poznat, jestli snímek obsahuje náš pruh.
     */
    fun isOwnText(ocr: String, displayed: Collection<String>, original: String = ""): Boolean {
        if (TextLogic.simplify(ocr).length < 10) return false
        if (original.isNotBlank() && TextLogic.looselySimilar(ocr, original, 0.8f)) return false
        return displayed.any { it.isNotBlank() && TextLogic.looselySimilar(ocr, it) }
    }

    /**
     * Pod pruhem se něco změnilo (nový titulek se teprve ustaluje) – nakoukni znovu
     * hned, ne až za celý interval. Nový titulek tak naskočí rychleji.
     */
    fun hurry(now: Long, quickMs: Long = 120L) {
        if (overlayCaptured) lastPeek = minOf(lastPeek, now - peekIntervalMs + quickMs)
    }

    fun markCaptured() { overlayCaptured = true }

    /**
     * Jak dlouho nechat pruh při nakouknutí skrytý, než se vezme snímek. Začíná krátce (40 ms,
     * zhruba 2 snímky obrazu) a samo se přizpůsobí televizi: když snímek pořád obsahoval náš pruh,
     * prodlouží se; když ne, pomalu se zkracuje. Co nejkratší probliknutí a nejrychlejší odezva.
     */
    var peekWaitMs: Long = MIN_WAIT
        private set

    fun onPeekResult(stillSawOwnBar: Boolean) {
        peekWaitMs = if (stillSawOwnBar) minOf(MAX_WAIT, peekWaitMs + 30)
        else maxOf(MIN_WAIT, peekWaitMs - 5)
    }

    companion object {
        const val MIN_WAIT = 40L
        const val MAX_WAIT = 200L
    }

    /**
     * Rozhodnutí pro aktuální snímek:
     * NORMAL = číst běžně, PEEK = skrýt pruh a číst, SKIP = nečíst (čekáme na další nakouknutí).
     */
    fun decide(now: Long, barVisible: Boolean): Decision = when {
        !barVisible || !overlayCaptured -> Decision.NORMAL
        now - lastPeek >= peekIntervalMs -> { lastPeek = now; Decision.PEEK }
        else -> Decision.SKIP
    }

    enum class Decision { NORMAL, PEEK, SKIP }
}
