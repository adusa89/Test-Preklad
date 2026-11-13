package cz.testpreklad

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Jednotný vzhled aplikace – barvy, písmo a prvky ovladatelné ovladačem od TV. */
object Theme {
    val BG = Color.rgb(0x0E, 0x10, 0x14)
    val SIDEBAR = Color.rgb(0x14, 0x17, 0x1D)
    val CARD = Color.rgb(0x1B, 0x1F, 0x27)
    val CARD_FOCUS = Color.rgb(0x27, 0x2D, 0x38)
    val TEXT = Color.rgb(0xF2, 0xF2, 0xF2)
    val MUTED = Color.rgb(0x9A, 0xA4, 0xAE)
    val ACCENT = Color.rgb(0xF2, 0xC1, 0x4E)
    val ACCENT_DARK = Color.rgb(0x1A, 0x14, 0x05)
    val OK = Color.rgb(0x4A, 0xDE, 0x80)
    val WARN = Color.rgb(0xF5, 0x9E, 0x0B)
    val BAD = Color.rgb(0xEF, 0x44, 0x44)
}

class Ui(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    private fun rounded(color: Int, radius: Int = 12, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    /** Pozadí, které při zaměření ovladačem dostane zlatý rámeček. */
    fun focusBg(normal: Int, focused: Int = Theme.CARD_FOCUS, radius: Int = 12): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(focused, radius, 3, Theme.ACCENT))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(focused, radius))
            addState(intArrayOf(), rounded(normal, radius))
        }

    fun cardBg(): Drawable = rounded(Theme.CARD, 14)

    fun text(s: CharSequence, size: Float = 16f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(): LinearLayout = vbox().apply {
        background = cardBg()
        setPadding(dp(22), dp(18), dp(22), dp(18))
    }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    /** Velké tlačítko. primary = zlaté. */
    fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = if (primary) 20f else 17f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setTextColor(if (primary) Theme.ACCENT_DARK else Theme.TEXT)
            typeface = Typeface.create("sans-serif-medium", if (primary) Typeface.BOLD else Typeface.NORMAL)
            background = if (primary) focusBg(Theme.ACCENT, Color.rgb(0xFF, 0xD3, 0x6B))
            else focusBg(Theme.CARD)
            setPadding(dp(26), dp(14), dp(26), dp(14))
            setOnClickListener { onClick() }
        }

    /**
     * Řádek nastavení: vlevo popis, vpravo hodnota (ZAP/VYP, velikost…).
     * Kliknutí OK na ovladači hodnotu změní.
     */
    fun settingRow(label: String, value: String, valueColor: Int = Theme.ACCENT, onClick: () -> Unit): LinearLayout =
        hbox().apply {
            isFocusable = true
            isClickable = true
            background = focusBg(Theme.CARD)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(text(label, 17f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, 16f, valueColor, bold = true).apply { setPadding(dp(16), 0, 0, 0) })
            setOnClickListener { onClick() }
        }

    /** Položka kontrolního seznamu: tečka (zelená/oranžová), popis a případně tlačítko. */
    fun checkRow(ok: Boolean, optional: Boolean, label: String, okText: String, action: String?, onAction: (() -> Unit)?): LinearLayout =
        hbox().apply {
            setPadding(0, dp(8), 0, dp(8))
            val dotColor = if (ok) Theme.OK else if (optional) Theme.MUTED else Theme.WARN
            addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(14) })
            addView(text(label, 17f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (ok || action == null || onAction == null) {
                addView(text(okText, 15f, if (ok) Theme.OK else Theme.MUTED))
            } else {
                addView(button(action) { onAction() }.apply { textSize = 15f; setPadding(dp(18), dp(8), dp(18), dp(8)) })
            }
        }

    fun section(title: String): TextView = text(title, 15f, Theme.ACCENT, bold = true).apply {
        setPadding(0, dp(26), 0, dp(10))
    }
}
