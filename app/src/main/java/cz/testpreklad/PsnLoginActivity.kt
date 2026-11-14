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
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * Stejný postup používá otevřený klient chiaki-ng.
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

        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

    /** Verze vestavěného prohlížeče (stará verze bývá důvod, proč se stránka Sony nenačte). */
    private fun webViewVersion(): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName ?: "?" else "?"
    } catch (_: Throwable) { "?" }

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private lateinit var root: FrameLayout
    private lateinit var cursor: View
    private lateinit var note: TextView
    private lateinit var bar: LinearLayout
    private lateinit var reloadBtn: TextView
    private lateinit var modeBtn: TextView
    private var desktop = false
    private var cx = 0f
    private var cy = 0f
    private var done = false
    private var loadStarted = 0L
    private var loaded = false
    private var httpLogged = 0
    private var errLogged = 0
    private var consoleLogged = 0

    /** Hlídá, že se stránka Sony vůbec načetla. */
    private val poll = object : Runnable {
        override fun run() {
            val w = web ?: return
            if (done) return
            if (!loaded && loadStarted > 0 && SystemClock.uptimeMillis() - loadStarted > 25_000) {
                loaded = true
                showProblem("timeout")
            }
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = null
        failed = false
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val w = try { WebView(this) } catch (e: Throwable) {
            // televize bez vestavěného prohlížeče – tady to nejde, zbývá ruční zadání
            Log.w(TAG, "webview", e)
            DiagLog.add("PSN|no webview " + e.javaClass.simpleName)
            failed = true
            Toast.makeText(this, tx("ps5_acc_err"), Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = w
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (_: Throwable) {}
        log("open webview=" + webViewVersion() + " ua=" + (s.userAgentString ?: "").takeLast(60))
        w.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!intercept(url)) log("start " + url.substringBefore('?').substringBefore('#').takeLast(70))
            }
            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                log("page " + url.substringBefore('?').takeLast(60) + " title=" + (view.title ?: "").take(40))
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                // odmítnuté dotazy stránky Sony (jen adresa bez údajů) – pomůže najít, kde přihlášení selhalo
                if (httpLogged++ < 25) log("http " + response.statusCode + " " + (request.url.host ?: "") + (request.url.path ?: "").take(60))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame && errLogged++ < 15) log("neterr " + error.errorCode + " " + (request.url.host ?: "") + (request.url.path ?: "").take(50))
                if (request.isForMainFrame) showProblem(error.errorCode.toString() + " " + error.description)
            }
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && consoleLogged++ < 15)
                    log("js " + m.message().take(140) + " @" + m.sourceId().substringAfterLast('/').take(30) + ":" + m.lineNumber())
                return true
            }
        }
        root.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // horní lišta s tlačítky (kliká se na ně ukazatelem)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF0101319.toInt())
            setPadding(16, 10, 16, 10)
        }
        fun barButton(label: String) = TextView(this).apply {
            text = label
            setTextColor(Theme.ACCENT_DARK)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Theme.ACCENT); cornerRadius = 14f }
            setPadding(28, 12, 28, 12)
        }
        reloadBtn = barButton(tx("psn_reload"))
        modeBtn = barButton(tx("psn_mode"))
        bar.addView(reloadBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(modeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 16 })
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
            // stránka začíná pod horní lištou
            (w.layoutParams as FrameLayout.LayoutParams).topMargin = bar.height
            w.requestLayout()
            placeCursor()
        }
        w.requestFocus()
        load()
        main.postDelayed(poll, 1500)
    }

    private fun load() {
        val w = web ?: return
        loaded = false
        loadStarted = SystemClock.uptimeMillis()
        note.text = tx("ps5_acc_hint")
        note.setTextColor(Color.WHITE)
        log("load desktop=$desktop")
        w.loadUrl(LOGIN_URL)
    }

    /** Stránka se nenačetla – řekni to a nabídni, co s tím. */
    private fun showProblem(why: String) {
        log("problem desktop=$desktop $why")
        note.text = tx("psn_err") + "  (" + why.take(60) + ")"
        note.setTextColor(Theme.WARN)
    }

    /** Sony po přihlášení přesměruje na známou adresu s jednorázovým kódem. */
    private fun intercept(url: String): Boolean {
        if (!url.startsWith(REDIRECT)) return false
        if (done) return true
        done = true
        val code = Ps5.authCodeFromUrl(url)
        note.text = tx("ps5_acc_wait")
        note.setTextColor(Color.WHITE)
        try { web?.stopLoading() } catch (_: Throwable) {}
        Thread({
            val id = if (code != null) accountIdForCode(code) else null
            if (id != null) { Prefs.setPsnId(applicationContext, id); result = id } else failed = true
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
        val w = web ?: return
        when {
            inside(reloadBtn) -> { load(); return }
            inside(modeBtn) -> {
                // jiný režim stránky: tváří se jako prohlížeč v počítači (někdy pomůže)
                desktop = !desktop
                w.settings.userAgentString = if (desktop) UA_DESKTOP else null
                try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {}
                load(); return
            }
        }
        val t = SystemClock.uptimeMillis()
        val y = cy - w.top
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, y, 0)
        w.dispatchTouchEvent(down); w.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val w = web ?: return super.dispatchKeyEvent(event)
        val downEv = event.action == KeyEvent.ACTION_DOWN
        // delší držení šipky = rychlejší pohyb
        val step = if (event.repeatCount > 6) 70f else if (event.repeatCount > 0) 36f else 22f
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (downEv) { cx = (cx - step).coerceAtLeast(4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (downEv) { cx = (cx + step).coerceAtMost(root.width - 4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (downEv) { if (cy <= bar.height + 30f && w.scrollY > 0) w.scrollBy(0, -90) else cy = (cy - step).coerceAtLeast(4f); placeCursor() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (downEv) { if (cy >= root.height - note.height - 30f) w.scrollBy(0, 90) else cy += step; placeCursor() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) tap()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack() && !done) w.goBack() else { @Suppress("DEPRECATION") super.onBackPressed() }
    }

    override fun onResume() { super.onResume(); UiState.shown() }
    override fun onPause() { UiState.hidden(); super.onPause() }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        try { web?.destroy() } catch (_: Throwable) {}
        web = null
        super.onDestroy()
    }
}
