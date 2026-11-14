package cz.testpreklad

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import cz.testpreklad.core.DemoGame

/**
 * Zkouška bez hry: ukázková „herní“ obrazovka s anglickými titulky, které se střídají.
 * Stejný obraz zároveň teče do aplikace jako video (viz DemoFeeder) – přesně tou cestou, kudy
 * chodí obraz z PlayStationu. Aplikace ho přečte, přeloží a položí přes titulky pruh.
 */
class TestCardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: CardView

    private val tick = object : Runnable {
        override fun run() {
            // stejný rozvrh jako ukázkové video, které aplikace čte – pruh tak sedí na titulky
            val sub = DemoGame.subtitleAt(SystemClock.uptimeMillis() - DemoFeeder.epoch)
            if (sub != view.subtitle) { view.subtitle = sub; view.invalidate() }
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = CardView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        UiState.testCard = true
        handler.post(tick)
    }

    override fun onPause() {
        UiState.testCard = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    class CardView(ctx: Context) : View(ctx) {
        var subtitle = ""
        override fun onDraw(canvas: Canvas) = TestCard.draw(canvas, width.toFloat(), height.toFloat(), subtitle)
    }
}

/** Kresba ukázkové „hry“ – používá ji obrazovka ukázky i ukázkové video. */
object TestCard {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun draw(canvas: Canvas, w: Float, h: Float, subtitle: String) {
        // jednoduchá „scéna“: obloha, hory, země
        p.clearShadowLayer()
        p.color = Color.rgb(46, 58, 82); canvas.drawRect(0f, 0f, w, h * 0.55f, p)
        p.color = Color.rgb(70, 62, 54); canvas.drawRect(0f, h * 0.55f, w, h, p)
        p.color = Color.rgb(36, 44, 60)
        canvas.drawRect(w * 0.1f, h * 0.35f, w * 0.35f, h * 0.55f, p)
        canvas.drawRect(w * 0.55f, h * 0.28f, w * 0.9f, h * 0.55f, p)
        // HUD: peníze, čas, nápověda k ovládání (aplikace je má ignorovat)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.RIGHT
        p.textSize = h * 0.03f
        canvas.drawText("$12.50", w * 0.96f, h * 0.08f, p)
        canvas.drawText("10:45 PM", w * 0.96f, h * 0.12f, p)
        p.textAlign = Paint.Align.LEFT
        canvas.drawText("Press X to mount", w * 0.04f, h * 0.10f, p)
        // titulky: bílé, vycentrované, dole
        p.textAlign = Paint.Align.CENTER
        p.textSize = h * 0.042f
        p.setShadowLayer(4f, 0f, 2f, Color.BLACK)
        subtitle.split('\n').forEachIndexed { i, s ->
            canvas.drawText(s, w / 2, h * 0.86f + i * h * 0.055f, p)
        }
        p.clearShadowLayer()
    }
}
