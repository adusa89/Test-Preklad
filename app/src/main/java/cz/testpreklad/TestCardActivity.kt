package cz.testpreklad

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Zkouška bez hry: ukázková „herní“ obrazovka s anglickými titulky, které se střídají.
 * Když běží překlad, aplikace je přečte, přeloží a položí přes ně pruh – uživatel (i automatický
 * test v emulátoru) tak ověří, že vše funguje, aniž by potřeboval PlayStation.
 */
class TestCardActivity : Activity() {

    private val lines = listOf(
        "Dutch: We need money, Arthur.",
        "",
        "Arthur: I reckon we ain't gonna find nothin' in Valentine.",
        "",
        "Get on your horse, we ride at dawn.",
        "",
        "Hosea: If we keep going like this,\nthe Pinkertons will find us.",
        "",
    )
    private var index = 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: CardView

    private val tick = object : Runnable {
        override fun run() {
            view.subtitle = lines[index % lines.size]
            view.invalidate()
            val shown = lines[index % lines.size].isNotEmpty()
            index++
            handler.postDelayed(this, if (shown) 6000 else 2000)
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
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // jednoduchá „scéna“: obloha, hory, země
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
            val parts = subtitle.split('\n')
            parts.forEachIndexed { i, s ->
                canvas.drawText(s, w / 2, h * 0.86f + i * h * 0.055f, p)
            }
            p.clearShadowLayer()
        }
    }
}
