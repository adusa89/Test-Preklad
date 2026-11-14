package cz.testpreklad

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.io.File

/**
 * Prohlížeč pro přihlašovací stránku Sony. Televize mívají vestavěný prohlížeč zastaralý
 * (stránka Sony v něm hlásí „Something went wrong“) a nejde aktualizovat, proto má aplikace
 * přibalený vlastní moderní prohlížeč (GeckoView, jádro Firefoxu). Vestavěný prohlížeč televize
 * se použije jen tam, kde přibalený není k dispozici.
 */
interface LoginBrowser {
    val view: View
    val name: String
    fun load(url: String)
    /** Klepnutí na místo [x],[y] (v souřadnicích [view]). */
    fun tap(x: Float, y: Float)
    /** Posun stránky o [dy] bodů (kladné = dolů). */
    fun scroll(dy: Int)
    fun destroy()

    interface Events {
        /** Stránka jde na adresu [url]; true = aplikace si ji vyřídí sama (stránka se nenačte). */
        fun onUrl(url: String): Boolean
        fun onLoaded(ok: Boolean, detail: String)
    }

    companion object {
        /** Přibalený prohlížeč je v tomto sestavení a pro tento procesor k dispozici? */
        fun bundledAvailable(a: Activity): Boolean = try {
            File(a.applicationInfo.nativeLibraryDir, "libxul.so").exists()
        } catch (_: Throwable) { false }

        fun create(a: Activity, ev: Events): LoginBrowser? {
            if (bundledAvailable(a)) try { return GeckoBrowser(a, ev) } catch (e: Throwable) { android.util.Log.w("TestPreklad", "gecko", e) }
            return try { SystemBrowser(a, ev) } catch (e: Throwable) { android.util.Log.w("TestPreklad", "webview", e); null }
        }

        internal fun touch(v: View, action: Int, x: Float, y: Float, down: Long, at: Long) {
            val e = MotionEvent.obtain(down, at, action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(e)
            e.recycle()
        }

        internal fun tapOn(v: View, x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            touch(v, MotionEvent.ACTION_DOWN, x, y, t, t)
            touch(v, MotionEvent.ACTION_UP, x, y, t, t + 60)
        }
    }
}

/** Přibalený prohlížeč (GeckoView). */
private class GeckoBrowser(a: Activity, private val ev: LoginBrowser.Events) : LoginBrowser {
    companion object {
        /** Jádro prohlížeče smí v aplikaci vzniknout jen jednou. */
        private var runtime: GeckoRuntime? = null
    }

    override val name = "gecko"
    private val gecko = GeckoView(a)
    override val view: View get() = gecko
    private val session = GeckoSession(GeckoSessionSettings.Builder().build())

    init {
        // Ochrana proti sledování musí být vypnutá: blokuje i ochranný skript přihlašovací stránky Sony
        // a přihlášení pak končí hláškou „The connection to the server timed out“ (stejně jako
        // v prohlížečích s blokováním reklam).
        val rt = runtime ?: GeckoRuntime.create(
            a.applicationContext,
            GeckoRuntimeSettings.Builder().contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.NONE)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                    .build()
            ).build()
        ).also { runtime = it }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
                GeckoResult.fromValue(if (ev.onUrl(request.uri)) AllowOrDeny.DENY else AllowOrDeny.ALLOW)

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                ev.onLoaded(false, "chyba sítě " + error.category + "/" + error.code)
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { ev.onUrl(url) }
            override fun onPageStop(session: GeckoSession, success: Boolean) { ev.onLoaded(success, "") }
        }
        session.open(rt)
        gecko.setSession(session)
        gecko.isFocusable = true
        gecko.isFocusableInTouchMode = true
    }

    override fun load(url: String) = session.loadUri(url)
    override fun tap(x: Float, y: Float) { gecko.requestFocus(); LoginBrowser.tapOn(gecko, x, y) }

    /** Posun tažením prstu (stejně jako na dotykové obrazovce). */
    override fun scroll(dy: Int) {
        val x = gecko.width / 2f
        val y0 = gecko.height / 2f
        val t = SystemClock.uptimeMillis()
        LoginBrowser.touch(gecko, MotionEvent.ACTION_DOWN, x, y0, t, t)
        for (i in 1..4) LoginBrowser.touch(gecko, MotionEvent.ACTION_MOVE, x, y0 - dy * i / 4f, t, t + i * 16)
        LoginBrowser.touch(gecko, MotionEvent.ACTION_UP, x, y0 - dy, t, t + 80)
    }

    override fun destroy() {
        try { gecko.releaseSession() } catch (_: Throwable) {}
        try { session.close() } catch (_: Throwable) {}
        // Po zavření nesmí v televizi zůstat přihlášení ani cookies. Mezipaměť stránky se nechává:
        // na pomalé televizi se díky ní stránka Sony podruhé načte výrazně rychleji.
        try {
            runtime?.storageController?.clearData(
                StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.DOM_STORAGES
            )
        } catch (_: Throwable) {}
    }
}

/** Vestavěný prohlížeč televize (jen jako náhrada, když přibalený není k dispozici). */
private class SystemBrowser(a: Activity, private val ev: LoginBrowser.Events) : LoginBrowser {
    override val name = "webview"
    private val web = WebView(a)
    override val view: View get() = web

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) } catch (_: Throwable) {}
        web.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = ev.onUrl(url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { ev.onUrl(url) }
            override fun onPageFinished(view: WebView, url: String) { ev.onLoaded(true, "") }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) ev.onLoaded(false, error.errorCode.toString() + " " + error.description)
            }
        }
    }

    override fun load(url: String) = web.loadUrl(url)
    override fun tap(x: Float, y: Float) { web.requestFocus(); LoginBrowser.tapOn(web, x, y) }
    override fun scroll(dy: Int) = web.scrollBy(0, dy)
    override fun destroy() {
        try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {}
        try { web.destroy() } catch (_: Throwable) {}
    }
}
