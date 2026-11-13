package cz.testpreklad.core

/** Statistika: kolik titulků se přeložilo a kolik jich šlo okamžitě z paměti překladů. */
class UsageStats {
    var lines = 0L; private set
    var cacheHits = 0L; private set

    @Synchronized fun line() { lines++ }
    @Synchronized fun cacheHit() { cacheHits++ }

    @Synchronized fun serialize(): String = "$lines,$cacheHits"

    @Synchronized fun reset() { lines = 0; cacheHits = 0 }

    companion object {
        fun parse(s: String?): UsageStats {
            val u = UsageStats()
            val v = s?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return u
            // starší verze ukládala 5 čísel (i údaje o AI) – první dvě platí dál
            if ((v.size != 2 && v.size != 5) || v.any { it < 0 }) return u
            u.lines = v[0]; u.cacheHits = v[1]
            return u
        }
    }
}
