package cz.testpreklad.core

import kotlin.math.max
import kotlin.math.min

/**
 * Automatické hledání titulků – uživatel nemusí nic nastavovat.
 *
 * 1) HLEDÁNÍ: aplikace čte skoro celou obrazovku a bere jen vycentrovaný text (titulky).
 * 2) Jakmile několikrát po sobě najde titulky ve stejné výšce, ZAPAMATUJE si to místo
 *    a dál čte jen úzký pás kolem něj (rychlejší, méně omylů).
 * 3) Když se v zapamatovaném pásu dlouho nic neobjeví (jiná hra, jiné umístění titulků),
 *    začne znovu hledat po celé obrazovce.
 */
class AutoZone(
    private val needSamples: Int = 2,
    private val rescanAfterMs: Long = 120_000L,
) {
    private val samples = ArrayDeque<Pair<Float, Float>>()   // (horní, dolní) okraj titulku, 0..1
    private var lastSeen = 0L

    /** Zapamatovaný pás, nebo null = ještě se hledá. */
    var learned: Zone? = null
        private set

    val current: Zone get() = learned ?: SCAN

    /** Zobrazil se titulek v dané výšce. Vrací true, když se oblast čtení změnila. */
    fun onSubtitle(top: Float, bottom: Float, now: Long): Boolean {
        lastSeen = now
        if (top < 0f || bottom <= top || bottom - top > 0.4f) return false
        val center = (top + bottom) / 2
        // titulek úplně jinde než dosavadní vzorky → začni sbírat znovu
        if (samples.isNotEmpty() && kotlin.math.abs(center - sampleCenter()) > SPREAD) samples.clear()
        samples.addLast(top to bottom)
        while (samples.size > 8) samples.removeFirst()
        if (samples.size < needSamples) return false

        val t = samples.minOf { it.first }
        val b = samples.maxOf { it.second }
        var zt = t - MARGIN
        var zb = b + MARGIN
        if (zb - zt < MIN_HEIGHT) { val mid = (zt + zb) / 2; zt = mid - MIN_HEIGHT / 2; zb = mid + MIN_HEIGHT / 2 }
        zt = max(0f, zt); zb = min(1f, zb)
        val z = Zone(SCAN.left, zt, SCAN.right, zb)
        val old = learned
        // drobné změny ignoruj, ať se oblast pořád nepřepíná
        if (old != null && kotlin.math.abs(old.top - z.top) < 0.03f && kotlin.math.abs(old.bottom - z.bottom) < 0.03f) return false
        learned = z
        return true
    }

    /** Pravidelná kontrola. Vrací true, když se aplikace vrátila k hledání po celé obrazovce. */
    fun onTick(now: Long): Boolean {
        if (lastSeen == 0L) { lastSeen = now; return false }
        if (learned != null && now - lastSeen > rescanAfterMs) {
            learned = null; samples.clear(); lastSeen = now
            return true
        }
        return false
    }

    fun reset() { learned = null; samples.clear(); lastSeen = 0L }

    private fun sampleCenter(): Float = samples.map { (it.first + it.second) / 2 }.average().toFloat()

    companion object {
        /**
         * Oblast při hledání: spodní polovina obrazovky – tam mají hry titulky. Celá obrazovka by se
         * na slabších televizích četla příliš dlouho (zkouška v emulátoru). Titulky nahoře → ruční oblast.
         */
        val SCAN = Zone(0.05f, 0.50f, 0.95f, 0.98f)
        const val MARGIN = 0.06f
        const val MIN_HEIGHT = 0.18f
        const val SPREAD = 0.15f
    }
}
