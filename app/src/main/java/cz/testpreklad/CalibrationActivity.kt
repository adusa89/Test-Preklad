package cz.testpreklad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import cz.testpreklad.core.ScreenLayout

/**
 * Nastavení oblastí ovladačem od TV – nad skutečným snímkem ze hry.
 *
 *  OK     = přepnout, co upravuješ
 *  šipky  = posun / změna velikosti
 *  ZPĚT   = uložit a odejít
 */
class CalibrationActivity : Activity() {

    private lateinit var editor: ZoneEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        editor = ZoneEditorView(this, Prefs.layout(this), TranslatorService.snapshot, Prefs.textSize(this))
        setContentView(editor)
        editor.isFocusable = true
        editor.requestFocus()
    }

    override fun onResume() { super.onResume(); UiState.shown() }
    override fun onPause() { UiState.hidden(); super.onPause() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = if (event.repeatCount > 2) 0.03f else 0.01f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> { if (event.repeatCount == 0) editor.nextMode(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { editor.adjust(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { editor.adjust(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { editor.adjust(0f, -step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { editor.adjust(0f, step); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { saveAndExit(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = saveAndExit()

    private fun saveAndExit() {
        Prefs.setLayout(this, editor.layout)
        if (TranslatorService.running) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_RELOAD))
        }
        Toast.makeText(this, tx("z_saved"), Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Kreslí snímek hry, modrý rámeček (čtení) a pruh s překladem. */
class ZoneEditorView(
    private val ctx: Context,
    initial: ScreenLayout,
    private val snapshot: Bitmap?,
    private val textSizeSp: Int,
) : View(ctx) {

    private enum class Mode(val title: String, val help: String) {
        READ_MOVE("cal_read_move", "cal_read_move_h"),
        READ_SIZE("cal_read_size", "cal_read_size_h"),
        BAR_MOVE("cal_bar_move", "cal_bar_move_h"),
        BAR_WIDTH("cal_bar_width", "cal_bar_width_h"),
    }

    var layout: ScreenLayout = initial
        private set

    /** V režimu "přes titulky" se poloha pruhu nenastavuje – pruh jde vždy přes text. */
    private val modes: List<Mode>
        get() = if (layout.cover) listOf(Mode.READ_MOVE, Mode.READ_SIZE, Mode.BAR_WIDTH) else Mode.values().toList()

    private var modeIndex = 0
    private val mode: Mode get() = modes[modeIndex % modes.size]
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val readFill = Paint().apply { color = Color.argb(60, 60, 160, 255) }
    private val readStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 170, 255); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF2, 0xC1, 0x4E); style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val panel = Paint().apply { color = Color.argb(215, 14, 16, 20) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF2, 0xC1, 0x4E) }
    private val bg = Paint().apply { color = Color.rgb(30, 34, 40) }

    fun nextMode() {
        modeIndex = (modeIndex + 1) % modes.size
        invalidate()
    }

    fun adjust(dx: Float, dy: Float) {
        layout = when (mode) {
            Mode.READ_MOVE -> layout.copy(read = layout.read.moved(dx, dy))
            Mode.READ_SIZE -> layout.copy(read = layout.read.resized(dx * 2, dy * 2))
            Mode.BAR_MOVE -> if (dy != 0f && height > 0) {
                val current = if (layout.isAuto) barRect().centerY() / height else layout.barCenterY
                layout.withBarCenter(current + dy)
            } else layout
            Mode.BAR_WIDTH -> if (dx != 0f) layout.withBarWidth(layout.barWidth + dx * 2) else layout
        }
        invalidate()
    }

    private fun barHeightPx(): Float = textSizeSp * scaledDensity * 1.3f * 2 + 18 * density

    private fun barRect(): RectF {
        val h = barHeightPx()
        val w = width * layout.barWidth
        val hf = if (height > 0) h / height else 0.1f
        val top = if (layout.cover) {
            // ukázka: pruh přes střed modrého rámečku
            val r = layout.read
            val tTop = r.top + r.height * 0.35f
            val tBottom = r.top + r.height * 0.75f
            layout.coverTop(tTop, tBottom, maxOf(hf, layout.coverHeight(tTop, tBottom))) * height
        } else layout.barTop(hf) * height
        val left = (width - w) / 2
        return RectF(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val snap = snapshot
        if (snap != null && !snap.isRecycled) {
            canvas.drawBitmap(snap, null, RectF(0f, 0f, w, h), null)
        } else {
            canvas.drawRect(0f, 0f, w, h, bg)
            text.textSize = 18 * scaledDensity
            text.textAlign = Paint.Align.CENTER
            text.color = Color.rgb(150, 160, 170)
            canvas.drawText(ctx.tx("cal_no_snapshot"), w / 2, h / 2, text)
            text.color = Color.WHITE
        }

        // modrý rámeček – kde se čtou titulky
        val r = layout.read
        val rr = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
        canvas.drawRect(rr, readFill)
        readStroke.strokeWidth = if (mode == Mode.READ_MOVE || mode == Mode.READ_SIZE) 6 * density else 2 * density
        canvas.drawRect(rr, readStroke)
        text.textSize = 15 * scaledDensity
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(ctx.tx("cal_read_label"), rr.left + 10 * density, rr.top + 20 * density, text)

        // pruh s překladem
        val br = barRect()
        val sel = mode == Mode.BAR_MOVE || mode == Mode.BAR_WIDTH
        barFill.color = Color.argb(if (layout.cover) 235 else 215, 0, 0, 0)
        canvas.drawRoundRect(br, 6 * density, 6 * density, barFill)
        barStroke.strokeWidth = if (sel) 5 * density else 1.5f * density
        canvas.drawRoundRect(br, 6 * density, 6 * density, barStroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = textSizeSp * scaledDensity
        canvas.drawText(ctx.tx("cal_bar_label"), br.centerX(), br.centerY() + text.textSize / 3, text)

        // horní panel s nápovědou
        val ph = 104 * density
        canvas.drawRect(0f, 0f, w, ph, panel)
        accent.textSize = 22 * scaledDensity
        accent.textAlign = Paint.Align.LEFT
        canvas.drawText("${modeIndex % modes.size + 1}/${modes.size}  ${ctx.tx(mode.title)}", 32 * density, 36 * density, accent)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 15 * scaledDensity
        canvas.drawText(ctx.tx(mode.help), 32 * density, 62 * density, text)
        text.color = Color.rgb(170, 178, 186)
        val keys = ctx.tx("cal_keys") + if (layout.cover) "   •   " + ctx.tx("cal_cover_note") else ""
        canvas.drawText(keys, 32 * density, 88 * density, text)
        text.color = Color.WHITE
    }
}
