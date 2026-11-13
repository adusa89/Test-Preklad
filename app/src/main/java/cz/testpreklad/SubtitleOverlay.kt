package cz.testpreklad

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Segment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Černý pruh s překladem, zobrazený přes všechny aplikace (i přes PS Remote Play).
 *
 * Okno se vytváří přes službu Usnadnění, když je zapnutá (funguje na Google TV / Chromecastu
 * bez ADB), jinak přes povolení „Zobrazení přes jiné aplikace“.
 */
class SubtitleOverlay(
    appContext: Context,
    private val screenW: Int,
    private val screenH: Int,
    layout: ScreenLayout,
    textSizeSp: Int,
    opacityPercent: Int,
    private val showDebug: Boolean,
    private val coloredSpeaker: Boolean,
    private val textColor: Int = Color.WHITE,
    private val bold: Boolean = false,
) {
    companion object {
        private const val TAG = "TestPreklad"

        /** Dá se teď vůbec něco zobrazit přes jiné aplikace? */
        fun canShow(c: Context): Boolean = TitulkyAccessibility.instance != null || Settings.canDrawOverlays(c)
    }

    private val a11y = TitulkyAccessibility.instance
    private val host: Context = a11y ?: appContext
    private val windowType = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val wm = host.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density
    private val speakerColor = Color.rgb(0xF2, 0xC1, 0x4E)
    private val originalColor = Color.rgb(0xA8, 0xB0, 0xB8)

    @Volatile private var bar: TextView? = null
    @Volatile private var barParams: WindowManager.LayoutParams? = null
    @Volatile private var debugView: TextView? = null
    @Volatile private var layout: ScreenLayout = layout
    @Volatile private var textSize = textSizeSp
    @Volatile private var bgAlpha = (255 * opacityPercent / 100).coerceIn(0, 255)

    /** Kde pruh na obrazovce leží (vlevo, nahoře, vpravo, dole; 0..1), nebo null, když není vidět. */
    @Volatile var barRect: FloatArray? = null
        private set

    /** Je pruh úplně neprůhledný (čistě černý)? Pak jde ve snímku spolehlivě poznat. */
    val isOpaque: Boolean get() = bgAlpha == 255

    /** Je pruh právě zobrazený (bez ohledu na krátké nakouknutí)? */
    @Volatile var isShown = false
        private set

    /** Text, který je teď v pruhu (bez formátování) – pro rozpoznání vlastního textu ve snímku. */
    @Volatile var displayedPlain: String = ""
        private set

    init {
        main.post {
            val tv = TextView(host).apply {
                setTextColor(textColor)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
                setLineSpacing(0f, 1.08f)
                setPadding(dp(28), dp(8), dp(28), dp(10))
                visibility = View.GONE
            }
            val lp = params((screenW * this.layout.barWidth).toInt()).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            if (add(tv, lp)) { bar = tv; barParams = lp }

            if (showDebug) {
                val dv = TextView(host).apply {
                    setTextColor(Color.rgb(0x9E, 0xF0, 0x9E))
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(Color.argb(170, 0, 0, 0))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    maxLines = 6
                }
                val dlp = params(WindowManager.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(8); y = dp(8)
                }
                if (add(dv, dlp)) debugView = dv
            }
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun params(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0; y = 0
    }

    private fun add(v: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(v, lp); true
    } catch (e: Exception) {
        Log.e(TAG, "Nelze zobrazit překryv", e); false
    }

    fun setLayout(l: ScreenLayout) { layout = l }

    private fun format(segments: List<Segment>, original: String?): CharSequence {
        val sb = SpannableStringBuilder()
        segments.forEachIndexed { i, s ->
            if (i > 0) sb.append('\n')
            if (s.speaker != null) {
                val start = sb.length
                sb.append(s.speaker).append(": ")
                if (coloredSpeaker) {
                    sb.setSpan(ForegroundColorSpan(speakerColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            sb.append(s.body)
        }
        if (!original.isNullOrBlank()) {
            val start = sb.length
            sb.append('\n').append(original.replace('\n', ' '))
            sb.setSpan(ForegroundColorSpan(originalColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.62f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /**
     * Zobrazí překlad. [textTop]/[textBottom] = kde na obrazovce (0..1) je anglický text;
     * v režimu "přes titulky" se pruh položí přesně přes něj.
     */
    fun show(segments: List<Segment>, original: String?, textTop: Float, textBottom: Float) {
        val plain = segments.joinToString("\n") { s -> if (s.speaker != null) "${s.speaker}: ${s.body}" else s.body }
        displayedPlain = plain
        isShown = true
        main.post {
            val tv = bar ?: return@post
            val lp = barParams ?: return@post
            val l = layout
            val width = (screenW * l.barWidth).toInt()
            tv.textSize = textSize.toFloat()
            tv.setBackgroundColor(Color.argb(bgAlpha, 0, 0, 0))
            tv.text = format(segments, original)

            val hasText = textTop >= 0f && textBottom > textTop
            val tTop = if (hasText) textTop else l.read.top
            val tBottom = if (hasText) textBottom else l.read.bottom
            // v režimu "přes titulky" musí pruh zakrýt celou angličtinu
            tv.minHeight = if (l.cover) (l.coverHeight(tTop, tBottom) * screenH).toInt() else 0
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = tv.measuredHeight
            val hf = h.toFloat() / screenH
            val top = when {
                l.cover -> l.coverTop(tTop, tBottom, hf)
                // automatika ještě hledá (čte se celá obrazovka) → pruh těsně nad nalezený text
                l.isAuto && hasText && l.read.height > 0.6f -> (tTop - 0.006f - hf).coerceAtLeast(0f)
                else -> l.barTop(hf)
            }
            lp.width = width
            lp.y = (top * screenH).toInt().coerceIn(0, max(0, screenH - h))
            val left = (screenW - width) / 2f
            barRect = floatArrayOf(left / screenW, lp.y.toFloat() / screenH, (left + width) / screenW, (lp.y + h).toFloat() / screenH)
            tv.visibility = View.VISIBLE
            try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
        }
    }

    fun hide() {
        isShown = false
        barRect = null
        displayedPlain = ""
        main.post { bar?.visibility = View.GONE }
    }

    /**
     * Krátké skrytí pruhu kvůli přečtení textu pod ním. Čeká, až se změna opravdu provede,
     * aby další snímek obrazovky pruh už neobsahoval.
     */
    fun setPeekHidden(hidden: Boolean) {
        if (!isShown) return
        val done = CountDownLatch(1)
        main.post {
            bar?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            done.countDown()
        }
        try { done.await(150, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    fun debug(text: String) {
        val dv = debugView ?: return
        main.post { dv.text = text }
    }

    fun destroy() {
        isShown = false
        main.post {
            listOfNotNull(bar, debugView).forEach { v -> try { wm.removeView(v) } catch (_: Exception) {} }
            bar = null; barParams = null; debugView = null
        }
    }
}
