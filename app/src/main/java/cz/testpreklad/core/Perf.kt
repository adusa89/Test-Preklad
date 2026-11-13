package cz.testpreklad.core

/**
 * Nastavení výkonu podle paměti zařízení. Slabší televize (1–2 GB RAM) čtou menší obraz
 * a méně často – aplikace jim nebere paměť ani výkon a nespadne.
 */
data class Perf(val captureWidth: Int, val intervalMs: Long, val eco: Boolean) {
    companion object {
        fun forDevice(totalRamMb: Long, lowRamFlag: Boolean): Perf = when {
            lowRamFlag || totalRamMb in 1..1600 -> Perf(854, 220, true)     // ~1 GB: úsporný režim
            totalRamMb in 1601..2600 -> Perf(1024, 150, true)               // ~2 GB
            else -> Perf(1280, 100, false)                                  // 3 GB a víc (nebo neznámo)
        }
    }
}
