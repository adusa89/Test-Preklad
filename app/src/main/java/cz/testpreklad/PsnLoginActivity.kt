package cz.testpreklad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.Ps5
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Přihlášení účtem PlayStation (e-mail a heslo) přímo na stránce Sony. Aplikace z něj potřebuje
 * jedinou věc: číslo účtu, které konzole vyžaduje při párování. Heslo aplikace nečte ani neukládá.
 * Stejný postup používá otevřený klient chiaki-ng. Stránka běží v přibaleném prohlížeči
 * (viz [LoginBrowser]), protože prohlížeče televizí bývají zastaralé.
 *
 * Na televizi se stránka ovládá ukazatelem: šipky jím pohybují, OK klikne.
 */
class PsnLoginActivity : Activity() {

    companion object {
        /** Výsledek (zakódované ID účtu) – vyzvedne si ho hlavní obrazovka. */
        @Volatile var result: String? = null
        /** Skončilo chybou (hlavní obrazovka ukáže hlášku). */
        @Volatile var failed = false

        private const val TAG = "TestPreklad"
        private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
        private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
        private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
        private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
        private const val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
            "?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
            "&redirect_uri=$REDIRECT&scope=psn:clientapp&request_locale=en_US&ui=pr&service_logo=ps" +
            "&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

        /** Verze vestavěného prohlížeče televize ("?" = nejde zjistit). */
        fun webViewVersion(): String = try {
            if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "?" else "?"
        } catch (_: Throwable) { "?" }

        /** Balíček vestavěného prohlížeče (kvůli odkazu na aktualizaci v Google Play). */
        fun webViewPackage(): String = try {
            (if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.packageName else null) ?: "com.google.android.webview"
        } catch (_: Throwable) { "com.google.android.webview" }

        /** Vymění jednorázový kód za číslo účtu. Volat mimo hlavní vlákno. */
        fun accountIdForCode(code: String): String? {
            val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
            return try {
                val body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") + "&redirect_uri=$REDIRECT&"
                val c = URL(TOKEN_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Authorization", auth)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
                val tokenJson = try {
                    if (c.responseCode != 200) { DiagLog.add("PSN|token http ${c.responseCode}"); return null }
                    c.inputStream.bufferedReader().use { it.readText().take(8000) }
                } finally { c.disconnect() }
                val token = Ps5.accessTokenFrom(tokenJson) ?: run { DiagLog.add("PSN|no token"); return null }

                val c2 = URL(TOKEN_URL + "/" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
                c2.connectTimeout = 10000; c2.readTimeout = 10000
                c2.setRequestProperty("Authorization", auth)
                val info = try {
                    if (c2.responseCode != 200) { DiagLog.add("PSN|info http ${c2.responseCode}"); return null }
                    c2.inputStream.bufferedReader().use { it.readText().take(8000) }
                } finally { c2.disconnect() }
                Ps5.accountIdFromUserInfo(info).also { DiagLog.add("PSN|account " + if (it != null) "ok" else "missing") }
            } catch (e: Exception) {
                Log.w(TAG, "psn", e)
                DiagLog.add("PSN|error " + e.javaClass.simpleName)
                null
            }
        }
    }

    private fun log(msg: String) { Log.i("TP_TEST", "PSN|$msg"); DiagLog.add("PSN|$msg") }

    private val main = Handler(Looper.getMainLooper())
    private var browser: LoginBrowser? = null
    private lateinit var root: FrameLayout
    private lateinit var cursor: View
    private lateinit var note: TextView
    private lateinit var reloadBtn: TextView
    private var cx = 0f
    private var cy = 0f
    private var done = false
    private var loadStarted = 0L
    private var loaded = false
    private var tries = 0

    /** Hlídá, že se stránka Sony vůbec načetla. */
    private val watchdog = Runnable {
        if (!loaded && !done) problem("timeout")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = null
        failed = false
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val b = LoginBrowser.create(this, object : LoginBrowser.Events {
            override fun onUrl(url: String): Boolean = intercept(url)
            override fun onLoaded(ok: Boolean, detail: String) {
                runOnUiThread {
                    log("page loaded ok=$ok after " + (SystemClock.uptimeMillis() - loadStarted) / 1000 + "s $detail")
                    if (ok) { loaded = true; main.removeCallbacks(watchdog) } else problem(detail.ifEmpty { "load" })
                }
            }
        })
        if (b == null) {
            // zařízení bez jakéhokoli prohlížeče – přihlášení tu nejde
            log("no browser")
            failed = true
            Toast.makeText(this, tx("ps5_acc_err"), Toast.LENGTH_LONG).show()
            finish(); return
        }
        browser = b
        log("open engine=" + b.name + " webview=" + webViewVersion())
        root.addView(b.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // horní lišta (kliká se na ni ukazatelem)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF0101319.toInt())
            setPadding(16, 10, 16, 10)
        }
        reloadBtn = TextView(this).apply {
            text = tx("psn_reload")
            setTextColor(Theme.ACCENT_DARK)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Theme.ACCENT); cornerRadius = 14f }
            setPadding(28, 12, 28, 12)
        }
        bar.addView(reloadBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        note = TextView(this).apply {
            text = tx("ps5_acc_hint")
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xF0101319.toInt())
            setPadding(24, 12, 24, 12)
        }
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF4C24D.toInt())
                setStroke(5, Color.BLACK)
            }
        }
        root.addView(cursor, FrameLayout.LayoutParams(36, 36))
        setContentView(root)
        root.post {
            cx = root.width / 2f; cy = root.height / 2f
            // stránka leží mezi horní lištou a nápovědou dole
            (b.view.layoutParams as FrameLayout.LayoutParams).apply { topMargin = bar.height; bottomMargin = note.height }
            b.view.requestLayout()
            placeCursor()
        }
        b.view.requestFocus()
        load()
    }

    private fun load() {
        val b = browser ?: return
        loaded = false
        loadStarted = SystemClock.uptimeMillis()
        note.text = tx("ps5_acc_hint"); note.setTextColor(Color.WHITE)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 45_000)
        log("load try " + (++tries))
        b.load(LOGIN_URL)
    }

    /** Stránka se nenačetla – řekni to a nabídni Načíst znovu. */
    private fun problem(why: String) {
        log("problem $why")
        note.text = tx("psn_err") + "  (" + why.take(60) + ")"
        note.setTextColor(Theme.WARN)
    }

    /** Sony po přihlášení přesměruje na známou adresu s jednorázovým kódem. */
    private fun intercept(url: String): Boolean {
        if (!url.startsWith(REDIRECT)) return false
        if (done) return true
        done = true
        val code = Ps5.authCodeFromUrl(url)
        runOnUiThread {
            main.removeCallbacks(watchdog)
            note.text = tx("ps5_acc_wait"); note.setTextColor(Color.WHITE)
        }
        Thread({
            val id = if (code != null) accountIdForCode(code) else null
            if (id != null) { Prefs.setPsnId(applicationContext, id); result = id } else failed = true
            log("finish ok=" + (id != null))
            runOnUiThread { finish() }
        }, "tp-psn").start()
        return true
    }

    private fun placeCursor() {
        cursor.translationX = cx - 18
        cursor.translationY = cy - 18
        cursor.bringToFront()
    }

    private fun inside(v: View): Boolean {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val x = loc[0] - rl[0]; val y = loc[1] - rl[1]
        return cx >= x && cx <= x + v.width && cy >= y && cy <= y + v.height
    }

    private fun tap() {
        val b = browser ?: return
        if (inside(reloadBtn)) { load(); return }
        b.tap(cx - b.view.left, cy - b.view.top)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val b = browser ?: return super.dispatchKeyEvent(event)
        val downEv = event.action == KeyEvent.ACTION_DOWN
        // delší držení šipky = rychlejší pohyb
        val step = if (event.repeatCount > 6) 70f else if (event.repeatCount > 0) 36f else 22f
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (downEv) { cx = (cx - step).coerceAtLeast(4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (downEv) { cx = (cx + step).coerceAtMost(root.width - 4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // u horního okraje stránky se místo ukazatele posouvá stránka
                if (downEv) { if (cy <= b.view.top + 40f && event.repeatCount > 0) b.scroll(-120) else cy = (cy - step).coerceAtLeast(4f); placeCursor() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (downEv) { if (cy >= b.view.bottom - 40f) b.scroll(120) else cy += step; placeCursor() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) tap()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() { super.onResume(); UiState.shown() }
    override fun onPause() { UiState.hidden(); super.onPause() }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        browser?.destroy()
        browser = null
        super.onDestroy()
    }
}
