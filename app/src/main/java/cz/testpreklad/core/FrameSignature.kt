package cz.testpreklad.core

import kotlin.math.abs

/**
 * Otisk malého náhledu pásu obrazovky. Slouží k přeskočení OCR,
 * když se obraz nezměnil (šetří výkon slabších boxů/TV).
 */
object FrameSignature {

    /** Převod ARGB pixelů na jas 0..255. */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
    }

    /** Průměrný rozdíl jasu mezi dvěma otisky (0 = stejné). */
    fun diff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return Float.MAX_VALUE
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toFloat() / a.size
    }

    /**
     * Otisk jen ze světlých pixelů (titulky jsou bílé). Pohyb tmavší scény za
     * titulky pak otisk tolik nemění.
     */
    fun brightMask(lum: IntArray, threshold: Int = 200): IntArray =
        IntArray(lum.size) { if (lum[it] >= threshold) 255 else 0 }
}
