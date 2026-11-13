package cz.testpreklad.core

import kotlin.math.max
import kotlin.math.min

/** Obdélník na obrazovce v poměrných souřadnicích 0..1 (nezávislé na rozlišení TV). */
data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Posun – zůstane celý na obrazovce a nezmění velikost. */
    fun moved(dx: Float, dy: Float): Zone {
        val nl = (left + dx).coerceIn(0f, 1f - width)
        val nt = (top + dy).coerceIn(0f, 1f - height)
        return Zone(nl, nt, nl + width, nt + height)
    }

    /** Změna velikosti kolem středu, s minimální a maximální velikostí. */
    fun resized(dw: Float, dh: Float): Zone {
        val w = (width + dw).coerceIn(MIN, 1f)
        val h = (height + dh).coerceIn(MIN, 1f)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val l = (cx - w / 2).coerceIn(0f, 1f - w)
        val t = (cy - h / 2).coerceIn(0f, 1f - h)
        return Zone(l, t, l + w, t + h)
    }

    fun normalized(): Zone {
        val l = min(left, right).coerceIn(0f, 1f - MIN)
        val t = min(top, bottom).coerceIn(0f, 1f - MIN)
        val r = max(max(left, right).coerceAtMost(1f), l + MIN)
        val b = max(max(top, bottom).coerceAtMost(1f), t + MIN)
        return Zone(l, t, r, b)
    }

    companion object { const val MIN = 0.05f }
}

/**
 * Rozložení: kde se čtou titulky hry (modrý rámeček) a kde se ukazují překlady.
 *
 * [cover] = true (výchozí): černý pruh se položí PŘÍMO PŘES anglické titulky.
 * [cover] = false: pruh je jinde – barCenterY < 0 znamená "těsně nad modrým rámečkem".
 */
data class ScreenLayout(
    val read: Zone = DEFAULT_READ,
    val barCenterY: Float = AUTO,
    val barWidth: Float = 0.84f,
    val cover: Boolean = true,
) {
    val isAuto: Boolean get() = barCenterY < 0f

    /**
     * Horní hrana českého pruhu (0..1) pro danou výšku pruhu.
     * Pruh nikdy nepřekryje modrý rámeček – jinak by aplikace neviděla další titulky.
     */
    fun barTop(barHeight: Float, gap: Float = 0.006f): Float {
        val wanted = if (isAuto) read.top - gap - barHeight else barCenterY - barHeight / 2
        return keepOutOfReadZone(wanted, barHeight, gap).coerceIn(0f, max(0f, 1f - barHeight))
    }

    /**
     * Režim "přes titulky": pruh vycentrovaný na anglický text (textTop..textBottom),
     * vždy alespoň tak vysoký, aby ho celý zakryl ([coverHeight]).
     */
    fun coverTop(textTop: Float, textBottom: Float, barHeight: Float): Float {
        val center = (textTop + textBottom) / 2
        return (center - barHeight / 2).coerceIn(0f, max(0f, 1f - barHeight))
    }

    /** Minimální výška pruhu, aby zakryl anglický text i s okrajem. */
    fun coverHeight(textTop: Float, textBottom: Float, margin: Float = 0.012f): Float =
        (textBottom - textTop + 2 * margin).coerceIn(0.02f, 1f)

    private fun keepOutOfReadZone(top: Float, h: Float, gap: Float): Float {
        val bottom = top + h
        val overlaps = bottom > read.top - gap && top < read.bottom + gap
        if (!overlaps) return top
        val above = read.top - gap - h
        val below = read.bottom + gap
        // vyber stranu, které je pruh blíž; když se nahoru nevejde, dej ho dolů
        val center = top + h / 2
        val readCenter = (read.top + read.bottom) / 2
        return when {
            above >= 0f && (center <= readCenter || below + h > 1f) -> above
            below + h <= 1f -> below
            else -> max(0f, above)
        }
    }

    fun withBarWidth(w: Float) = copy(barWidth = w.coerceIn(0.3f, 1f))
    fun withBarCenter(y: Float) = copy(barCenterY = y.coerceIn(0.02f, 0.98f))

    /** Uložení do jednoho řádku textu. */
    fun serialize(): String =
        listOf(read.left, read.top, read.right, read.bottom, barCenterY, barWidth, if (cover) 1f else 0f).joinToString(",")

    companion object {
        const val AUTO = -1f
        val DEFAULT_READ = Zone(0.08f, 0.74f, 0.92f, 0.98f)

        fun parse(s: String?): ScreenLayout {
            if (s.isNullOrBlank()) return ScreenLayout()
            val v = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if ((v.size != 6 && v.size != 7) || v.any { it.isNaN() || it.isInfinite() }) return ScreenLayout()
            val read = Zone(v[0], v[1], v[2], v[3]).normalized()
            val center = if (v[4] < 0f) AUTO else v[4].coerceIn(0.02f, 0.98f)
            val cover = if (v.size == 7) v[6] >= 0.5f else true
            return ScreenLayout(read, center, v[5].coerceIn(0.3f, 1f), cover)
        }
    }
}
