package cz.testpreklad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
        /** Výsledek posledního přihlášení (zakódované ID účtu) – vyzvedne si ho hlavní obrazovka. */
        @Volatile var result: String? = null
        /** Přihlášení skončilo chybou (hlavní obrazovka ukáže hlášku). */
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

    private var web: WebView? = null
    private lateinit var cursor: View
    private lateinit var note: TextView
    private var cx = 0f
    private var cy = 0f
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = null
        failed = false
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val w = try { WebView(this) } catch (e: Throwable) {
            // televize bez vestavěného prohlížeče – přihlášení tu nejde, zbývá ruční zadání
            Log.w(TAG, "webview", e)
            failed = true
            Toast.makeText(this, tx("ps5_acc_err"), Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (_: Throwable) {}
        w.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { intercept(url) }
        }
        root.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        note = TextView(this).apply {
            text = tx("ps5_acc_hint")
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0xE6101319.toInt())
            setPadding(24, 12, 24, 12)
        }
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF4C24D.toInt())
                setStroke(4, Color.BLACK)
            }
        }
        root.addView(cursor, FrameLayout.LayoutParams(28, 28))
        setContentView(root)
        root.post {
            cx = root.width / 2f; cy = root.height / 2f
            placeCursor()
        }
        w.requestFocus()
        w.loadUrl(LOGIN_URL)
    }

    /** Sony po přihlášení přesměruje na známou adresu s jednorázovým kódem. */
    private fun intercept(url: String): Boolean {
        if (!url.startsWith(REDIRECT)) return false
        if (done) return true
        done = true
        val code = Ps5.authCodeFromUrl(url)
        note.text = tx("ps5_acc_wait")
        try { web?.stopLoading() } catch (_: Throwable) {}
        Thread({
            val id = if (code != null) accountIdForCode(code) else null
            if (id != null) { Prefs.setPsnId(applicationContext, id); result = id } else failed = true
            runOnUiThread { finish() }
        }, "tp-psn").start()
        return true
    }

    private fun placeCursor() {
        cursor.translationX = cx - 14
        cursor.translationY = cy - 14
        cursor.bringToFront()
    }

    private fun tap() {
        val w = web ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        w.dispatchTouchEvent(down); w.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val w = web ?: return super.dispatchKeyEvent(event)
        val root = w.parent as? View ?: return super.dispatchKeyEvent(event)
        val downEv = event.action == KeyEvent.ACTION_DOWN
        // delší držení šipky = rychlejší pohyb
        val step = if (event.repeatCount > 6) 60f else if (event.repeatCount > 0) 30f else 18f
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (downEv) { cx = (cx - step).coerceAtLeast(4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (downEv) { cx = (cx + step).coerceAtMost(root.width - 4f); placeCursor() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (downEv) { if (cy <= 40f) w.scrollBy(0, -80) else cy -= step; placeCursor() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (downEv) { if (cy >= root.height - 40f) w.scrollBy(0, 80) else cy += step; placeCursor() }
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
        try { web?.destroy() } catch (_: Throwable) {}
        web = null
        super.onDestroy()
    }
}
