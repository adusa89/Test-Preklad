package cz.testpreklad.core

/** Převody obrazu mezi YUV (video) a RGB (čtení textu). Čistý výpočet – dá se testovat. */
object Yuv {

    /** Jasová složka (Y) → šedý obrázek ARGB. [stride] = délka řádku ve zdroji. */
    fun grayToArgb(y: ByteArray, stride: Int, w: Int, h: Int, out: IntArray) {
        var o = 0
        for (row in 0 until h) {
            var i = row * stride
            for (x in 0 until w) {
                val v = y[i++].toInt() and 0xFF
                out[o++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    /** Jeden bod YUV (BT.601, omezený rozsah) → ARGB. */
    fun pixel(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0) * 298
        val d = u - 128
        val e = v - 128
        val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * ARGB → YUV 4:2:0 (BT.601, omezený rozsah). [y] má w×h bajtů, [u] a [v] mají (w/2)×(h/2).
     * Používá se pro ukázkové video; w a h musí být sudé.
     */
    fun fromArgb(argb: IntArray, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        var yi = 0
        for (row in 0 until h) {
            for (x in 0 until w) {
                val p = argb[row * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if (row % 2 == 0 && x % 2 == 0) {
                    val ci = (row / 2) * (w / 2) + x / 2
                    u[ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    v[ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
