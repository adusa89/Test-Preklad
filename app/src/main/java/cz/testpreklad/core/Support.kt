package cz.testpreklad.core

/** Záznam posledních událostí – pro hlášení chyby. Drží jen posledních pár set řádků v paměti. */
object DiagLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()

    @Synchronized fun add(line: String, now: Long = System.currentTimeMillis()) {
        lines.addLast("${now / 1000 % 100000} $line".take(300))
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
    @Synchronized fun size(): Int = lines.size
    @Synchronized fun clear() = lines.clear()
}

/** Kontrola nové verze: na serveru leží malý soubor s číslem a názvem nejnovější verze. */
object UpdateCheck {
    data class Remote(val code: Int, val name: String)

    /** Obsah souboru: "12 0.12" (číslo verze, název). Vrací null, když obsah nedává smysl. */
    fun parse(text: String?): Remote? {
        val parts = (text ?: "").trim().split(Regex("\\s+"))
        val code = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (code <= 0 || code > 1_000_000) return null
        return Remote(code, parts.getOrNull(1)?.take(20) ?: code.toString())
    }

    fun isNewer(remote: Remote?, currentCode: Int): Boolean = remote != null && remote.code > currentCode
}
