package cz.testpreklad.core

import kotlin.math.abs

/** Odkud aplikace bere obraz hry. */
enum class Source(val code: String) {
    /** PS5 kabelem do TV, aplikace čte obraz obrazovky (plná kvalita, když to TV dovolí). */
    HDMI("hdmi"),
    /** PS5 → splitter → TV (plná kvalita) + USB klíčenka → aplikace. Funguje vždy, nebliká. */
    USB("usb"),
    /** PS Remote Play v televizi (obraz přes síť, nižší kvalita). */
    REMOTE_PLAY("rp");

    /** Hra jde do televize přímo kabelem (ne přes Remote Play). */
    val direct: Boolean get() = this != REMOTE_PLAY

    fun next(): Source = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(code: String?): Source = values().firstOrNull { it.code == code } ?: HDMI

        /** Z nabízených rozlišení klíčenky vybere to nejbližší 1280×720 (ideální na titulky). */
        fun pickCaptureSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
            if (sizes.isEmpty()) return null
            val good = sizes.filter { it.first >= 960 }
            val pool = if (good.isNotEmpty()) good else sizes
            return pool.minByOrNull { abs(it.first - 1280) + abs(it.second - 720) }
        }
    }
}
