package cz.testpreklad.core

/**
 * Paměť překladů (LRU). Ukládá se do souboru, takže opakované texty
 * (nápovědy, menu, časté hlášky) se při příštím hraní zobrazí okamžitě a zdarma.
 */
class TranslationCache(private val maxSize: Int = 3000) {

    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxSize
    }

    /** Počet nových záznamů od posledního uložení. */
    var dirty: Int = 0
        private set

    @Synchronized fun get(key: String): String? = map[normalize(key)]

    @Synchronized fun put(key: String, value: String) {
        val k = normalize(key)
        if (k.isEmpty() || value.isBlank()) return
        if (map.put(k, value) != value) dirty++
    }

    @Synchronized fun size(): Int = map.size

    @Synchronized fun clear() { map.clear(); dirty++ }

    /** Serializace: jeden záznam na řádek, "anglicky<TAB>česky", s escapováním. */
    @Synchronized fun dump(): String {
        dirty = 0
        val sb = StringBuilder()
        for ((k, v) in map) sb.append(esc(k)).append('\t').append(esc(v)).append('\n')
        return sb.toString()
    }

    @Synchronized fun load(data: String) {
        for (line in data.split('\n')) {
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val k = unesc(line.substring(0, i))
            val v = unesc(line.substring(i + 1))
            if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
        }
        dirty = 0
    }

    companion object {
        fun normalize(s: String): String = s.trim().replace(Regex("\\s+"), " ")

        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }

        fun unesc(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        '\\' -> append('\\')
                        else -> { append(c); append(s[i + 1]) }
                    }
                    i += 2
                } else { append(c); i++ }
            }
        }
    }
}
