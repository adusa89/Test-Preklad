package cz.testpreklad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.testpreklad.core.AutoZone
import cz.testpreklad.core.CaptureMonitor
import cz.testpreklad.core.FrameSignature
import cz.testpreklad.core.HdmiProbe
import cz.testpreklad.core.I18n
import cz.testpreklad.core.Lang
import cz.testpreklad.core.OcrBlock
import cz.testpreklad.core.Perf
import cz.testpreklad.core.OcrLine
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Segment
import cz.testpreklad.core.Source
import cz.testpreklad.core.SubtitleHistory
import cz.testpreklad.core.SubtitleTracker
import cz.testpreklad.core.TextLogic
import cz.testpreklad.core.TranslationCache
import cz.testpreklad.core.UsageStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Srdce aplikace: běží na pozadí, několikrát za sekundu vezme snímek obrazovky,
 * v oblasti titulků přečte anglický text, přeloží ho a položí přes něj černý pruh s překladem.
 */
class TranslatorService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "cz.testpreklad.STOP"
        const val ACTION_PAUSE = "cz.testpreklad.PAUSE"
        const val ACTION_RELOAD = "cz.testpreklad.RELOAD"
        const val ACTION_START_USB = "cz.testpreklad.START_USB"

        /** Poslední chyba při spuštění (např. klíčenka nenalezena) – pro obrazovku aplikace. */
        @Volatile var startError: String? = null
            private set

        @Volatile var running = false
            private set
        @Volatile var paused = false
            private set

        /** Poslední snímek ze hry (zmenšený) – pro nastavení oblastí nad skutečným obrazem. */
        @Volatile var snapshot: Bitmap? = null
            private set

        /** Statistika sdílená s obrazovkou aplikace. */
        @Volatile private var stats: UsageStats? = null

        /** Automatický test, jestli televize dovolí číst obraz z HDMI. */
        val hdmiProbe = HdmiProbe()

        fun statsFor(c: Context): UsageStats = stats ?: Prefs.stats(c).also { stats = it }

        @Volatile private var clearCacheRequested = false

        /** Smazání paměti překladů z obrazovky aplikace (provede se v běžící službě). */
        fun requestCacheClear() { clearCacheRequested = true }

        private const val TAG = "TestPreklad"
        private const val CHANNEL = "test_preklad"
        private const val NOTIF_ID = 1

        private const val CACHE_PREFIX = "preklady_"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var overlay: SubtitleOverlay? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val cache = TranslationCache()
    private val tracker = SubtitleTracker()
    private val monitor = CaptureMonitor()
    @Volatile private var layout = ScreenLayout()
    @Volatile private var lang = Lang.CS
    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0
    @Volatile private var debug = false
    @Volatile private var onlySubtitles = true
    @Volatile private var autoFind = true
    private val autoZone = AutoZone()
    @Volatile private var onlyRemotePlay = true
    @Volatile private var source = Source.HDMI
    private var usb: UsbCapture? = null

    /** Kdy schovat pruh (překlad zůstane chvíli po zmizení angličtiny, ať se dá dočíst). 0 = nic. */
    private var lastLicenseCheck = 0L

    /** Výkon podle paměti zařízení – slabší televize čtou menší obraz a méně často. */
    private val perf: Perf by lazy {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Perf.forDevice(mi.totalMem / (1024 * 1024), am.isLowRamDevice)
        } catch (_: Exception) { Perf.forDevice(0, false) }
    }
    private var hideAt = 0L
    @Volatile private var holdMs = 1000L
    @Volatile private var showOriginal = false
    @Volatile private var lastSig: IntArray? = null
    private var hiddenForOtherApp = false

    // diagnostika
    private var frames = 0
    private var ocrRuns = 0
    private var lastOcrText = ""
    private var lastMs = 0L
    @Volatile private var lastError: String? = null

    /** Jeden znovupoužívaný snímek obrazovky – žádné velké alokace 10× za sekundu. */
    private var frameBmp: Bitmap? = null

    /** Překlady spuštěné dopředu, hned po prvním přečtení titulku (text → běžící překlad). */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<Segment>?>>()
    private var statsStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> {
                if (running) {
                    paused = !paused
                    if (paused) overlay?.hide() else { tracker.reset(); lastSig = null }
                    updateNotification()
                }
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (running) reloadSettings()
                return START_NOT_STICKY
            }
        }

        if (intent?.action == ACTION_START_USB) {
            // ---- režim USB klíčenky: žádné snímání obrazovky, čte se kopie obrazu z HDMI splitteru ----
            startForegroundCompat(buildNotification(), camera = true)
            if (running || usb != null) return START_NOT_STICKY
            startError = null
            setupScreenSize()
            val u = UsbCapture(this)
            usb = u
            u.open { ok ->
                if (!ok) {
                    startError = u.error ?: "USB"
                    stopSelf()
                } else {
                    capW = u.width; capH = u.height
                    Handler(Looper.getMainLooper()).post { beginRunning() }
                }
            }
            return START_NOT_STICKY
        }

        // Android vyžaduje, aby služba se snímáním obrazovky hned běžela v popředí
        startForegroundCompat(buildNotification(), camera = false)
        if (projection != null || running) return START_NOT_STICKY   // už běží

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { parcelIntent(it) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { Log.e(TAG, "projection", e); null }
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        projection = mp
        // Android 14+: callback musí být registrovaný před vytvořením virtuálního displeje
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        try {
            setupCapture(mp)
        } catch (e: Exception) {
            Log.e(TAG, "capture", e); stopSelf(); return START_NOT_STICKY
        }
        beginRunning()
        return START_NOT_STICKY
    }

    /** Společný start po připravení zdroje obrazu (obrazovka nebo USB klíčenka). */
    private fun beginRunning() {
        if (running) return
        if (!Prefs.license(this).canRun) { startError = "licence"; stopSelf(); return }
        lastLicenseCheck = SystemClock.uptimeMillis()
        lang = Prefs.lang(this)
        statsFor(this)
        loadCache()
        reloadSettings()
        running = true
        paused = false
        statsStart = SystemClock.uptimeMillis()
        scope.launch { loop() }
    }

    /** Načte nastavení (po spuštění i po změně v aplikaci) a znovu vytvoří pruh. */
    private fun reloadSettings() {
        val newLang = Prefs.lang(this)
        if (newLang != lang) {
            saveCache(); cache.clear(); lang = newLang; loadCache()
        }
        layout = Prefs.layout(this)
        debug = Prefs.debug(this)
        onlySubtitles = Prefs.onlySubtitles(this)
        val wasAuto = autoFind
        autoFind = Prefs.autoZone(this)
        if (autoFind != wasAuto) autoZone.reset()
        if (autoFind) layout = layout.copy(read = autoZone.current)
        onlyRemotePlay = Prefs.onlyRemotePlay(this)
        source = Prefs.source(this)
        holdMs = Prefs.holdMs(this).toLong()
        showOriginal = Prefs.showOriginal(this)
        hideAt = 0
        overlay?.destroy()
        overlay = SubtitleOverlay(
            this, screenW, screenH, layout, Prefs.textSize(this), Prefs.opacity(this),
            debug, Prefs.coloredSpeaker(this),
            Prefs.TEXT_COLORS[Prefs.textColorIndex(this)], Prefs.boldText(this)
        )
        tracker.reset()
        lastSig = null
        OfflineTranslator.ensureModel(lang) { }
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun parcelIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    // ---------------- notifikace ----------------

    private fun buildNotification(): Notification {
        val l = Prefs.lang(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Test Překlad", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP), flags)
        val pausePi = PendingIntent.getService(this, 2, Intent(this, TranslatorService::class.java).setAction(ACTION_PAUSE), flags)
        val openPi = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val noIcon: android.graphics.drawable.Icon? = null
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(I18n.t(l, if (paused) "n_paused" else "n_running"))
            .setContentText(I18n.t(l, "n_text"))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(noIcon, I18n.t(l, if (paused) "b_resume" else "b_pause"), pausePi).build())
            .addAction(Notification.Action.Builder(noIcon, I18n.t(l, "b_stop"), stopPi).build())
            .build()
    }

    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun startForegroundCompat(n: Notification, camera: Boolean) {
        when {
            camera && Build.VERSION.SDK_INT >= 30 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            !camera && Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- snímání obrazovky ----------------

    @Suppress("DEPRECATION")
    private fun setupScreenSize() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        if (screenW <= 0 || screenH <= 0) { screenW = 1920; screenH = 1080 }
    }

    private fun setupCapture(mp: MediaProjection) {
        setupScreenSize()
        capW = min(perf.captureWidth, screenW)
        capH = max(2, ((screenH.toLong() * capW) / screenW).toInt() and 1.inv())
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = mp.createVirtualDisplay(
            "test-preklad", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun grabFrame(): Bitmap? {
        usb?.let { return it.latest() }
        val img = try { reader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val bmpW = img.width + rowPadding / pixelStride
            var bmp = frameBmp
            if (bmp == null || bmp.width != bmpW || bmp.height != img.height) {
                bmp = Bitmap.createBitmap(bmpW, img.height, Bitmap.Config.ARGB_8888)
                frameBmp = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "frame", e); null
        } finally {
            img.close()
        }
    }

    // ---------------- hlavní smyčka ----------------

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val t0 = SystemClock.uptimeMillis()
            try {
                if (!paused && appAllowed()) tick(t0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "smyčka", e)
            }
            if (hideAt in 1..SystemClock.uptimeMillis()) { hideAt = 0; overlay?.hide() }
            if (autoFind && !paused && autoZone.onTick(t0)) zoneChanged()
            if (clearCacheRequested) {
                clearCacheRequested = false
                cache.clear(); cacheFile().delete()
            }
            val ov = overlay
            if (debug && ov != null) publishDebug(ov)
            if (cache.dirty >= 20) saveCache()
            // jednou za minutu: neskončila zkušební doba během hraní?
            if (t0 - lastLicenseCheck > 60_000) {
                lastLicenseCheck = t0
                if (!Prefs.license(this).canRun) { stopSelf(); return }
            }
            val spent = SystemClock.uptimeMillis() - t0
            delay(max(20L, perf.intervalMs - spent))
        }
    }

    /** Titulky jen v PS Remote Play – v jiných aplikacích (menu TV, YouTube…) se schovají. */
    private fun appAllowed(): Boolean {
        val pkg = TitulkyAccessibility.foregroundPackage
        // ve vlastní aplikaci nikdy nečti (jinak by se překládalo její menu)
        val allowed = !UiState.visible && pkg != packageName &&
            // HDMI: hra běží ve vstupu televize (aplikace výrobce TV) → čte se všude mimo tuto aplikaci
            (source.direct || !onlyRemotePlay || pkg == null || TitulkyAccessibility.isRemotePlay(pkg))
        if (!allowed && !hiddenForOtherApp) {
            hideAt = 0; overlay?.hide(); tracker.reset(); lastSig = null
        }
        hiddenForOtherApp = !allowed
        return allowed
    }

    private suspend fun tick(now: Long) {
        val ov = overlay ?: return
        val decision = monitor.decide(now, ov.isShown)
        val frame: Bitmap = when (decision) {
            CaptureMonitor.Decision.SKIP -> return
            CaptureMonitor.Decision.NORMAL -> grabFrame() ?: return
            CaptureMonitor.Decision.PEEK -> {
                // na zlomek sekundy skryj pruh, přečti, co je pod ním, a hned ho vrať
                ov.setPeekHidden(true)
                delay(monitor.peekWaitMs)
                val f = grabFrame()
                ov.setPeekHidden(false)
                lastSig = null
                f ?: return
            }
        }
        frames++
        if (frames % 30 == 1) takeSnapshot(frame)
        if (usb == null && countsForHdmiTest()) probeHdmi(frame)
        process(frame, ov, peek = decision == CaptureMonitor.Decision.PEEK)
        lastMs = SystemClock.uptimeMillis() - now
    }

    private suspend fun process(frame: Bitmap, ov: SubtitleOverlay, peek: Boolean) {
        val z = layout.read
        val x0 = (capW * z.left).toInt().coerceIn(0, capW - 1)
        val x1 = (capW * z.right).toInt().coerceIn(x0 + 1, capW)
        val y0 = (capH * z.top).toInt().coerceIn(0, capH - 1)
        val y1 = (capH * z.bottom).toInt().coerceIn(y0 + 1, capH)
        val crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)

        // přeskoč OCR, když se oblast od posledního čtení nezměnila
        val sig = signature(crop)
        val prev = lastSig
        if (prev != null && tracker.isSettled && FrameSignature.diff(prev, sig) < 1.5f) {
            crop.recycle(); return
        }
        lastSig = sig

        val result = try {
            recognizer.process(InputImage.fromBitmap(crop, 0)).await()
        } finally {
            crop.recycle()
        }
        ocrRuns++

        val picked = TextLogic.assemble(
            toBlocks(result),
            minLineHeight = (capH * 0.016f).toInt(),
            subtitlesOnly = onlySubtitles || autoFind,   // automatika čte velkou plochu → vždy jen titulky
            screenCenterX = capW / 2 - x0,
            centerTolerance = (capW * 0.12f).toInt(),
        )
        if (picked.text.isNotEmpty()) lastOcrText = picked.text

        // přečetli jsme vlastní překlad = snímek obsahuje náš pruh → přejdi na nakukování
        if (ov.isShown && monitor.isOwnText(picked.text, listOf(ov.displayedPlain), tracker.shown)) {
            // při nakouknutí: pruh byl ve snímku ještě vidět → příště počkej o chvilku déle
            if (peek) monitor.onPeekResult(stillSawOwnBar = true) else monitor.markCaptured()
            return
        }
        if (peek) monitor.onPeekResult(stillSawOwnBar = false)

        when (val action = tracker.onOcr(picked.text)) {
            is SubtitleTracker.Action.Show -> {
                // překlad už většinou běží (spuštěný po prvním přečtení) – jen se počká na výsledek
                val ready = inflight.remove(action.text)
                val segs = (if (ready != null) ready.await() else null) ?: translateSegments(action.text) ?: return
                tracker.confirmShown(action.text)
                val top = if (picked.top >= 0) (y0 + picked.top).toFloat() / capH else -1f
                val bottom = if (picked.bottom >= 0) (y0 + picked.bottom).toFloat() / capH else -1f
                hideAt = 0
                ov.show(segs, if (showOriginal) action.text else null, top, bottom)
                statsFor(this).line()
                // automatika: zapamatuj si, kde na obrazovce titulky jsou
                if (autoFind && autoZone.onSubtitle(top, bottom, SystemClock.uptimeMillis())) zoneChanged()
                SubtitleHistory.add(action.text, segs.joinToString("\n") { s ->
                    if (s.speaker != null) "${s.speaker}: ${s.body}" else s.body
                })
            }
            // anglický titulek zmizel – překlad nech chvíli dočíst
            SubtitleTracker.Action.Hide -> if (holdMs <= 0) ov.hide() else hideAt = SystemClock.uptimeMillis() + holdMs
            SubtitleTracker.Action.None -> {
                if (tracker.isChanging) monitor.hurry(SystemClock.uptimeMillis())
                prefetch(tracker.pendingText)
            }
        }
    }

    /** Automatika změnila oblast čtení (našla titulky, nebo se vrátila k hledání). */
    private fun zoneChanged() {
        layout = layout.copy(read = autoZone.current)
        overlay?.setLayout(layout)
        lastSig = null
    }

    private fun toBlocks(t: Text): List<OcrBlock> = t.textBlocks.map { block ->
        OcrBlock(block.lines.mapNotNull { l ->
            val bb = l.boundingBox ?: return@mapNotNull null
            OcrLine(l.text, bb.left, bb.top, bb.right, bb.bottom)
        })
    }

    /** Otisk oblasti: 64×16 náhled, jen světlé pixely (titulky jsou bílé). */
    private fun signature(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 64, 16, true)
        val px = IntArray(64 * 16)
        small.getPixels(px, 0, 64, 0, 0, 64, 16)
        if (small !== bmp) small.recycle()
        return FrameSignature.brightMask(FrameSignature.luminance(px))
    }

    /** Domovské obrazovky (launchery) – při testu HDMI se jejich obraz nepočítá. */
    private val launchers: Set<String> by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /** Počítat snímek do testu HDMI jen když je na obrazovce vstup TV (ne launcher, ne neznámo). */
    private fun countsForHdmiTest(): Boolean {
        val pkg = TitulkyAccessibility.foregroundPackage ?: return false
        return pkg != packageName && pkg !in launchers && !TitulkyAccessibility.isRemotePlay(pkg)
    }

    /** Je ve snímku skutečný obraz, nebo jen černo (televize HDMI blokuje)? */
    private fun probeHdmi(frame: Bitmap) {
        try {
            val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(frame, 0, 0, capW, capH), 32, 18, true)
            val px = IntArray(32 * 18)
            small.getPixels(px, 0, 32, 0, 0, 32, 18)
            small.recycle()
            hdmiProbe.add(HdmiProbe.contentRatio(FrameSignature.luminance(px)))
        } catch (_: Exception) {}
    }

    private fun takeSnapshot(frame: Bitmap) {
        try {
            val w = 960
            val h = max(1, (capH.toLong() * w / capW).toInt())
            val exact = Bitmap.createBitmap(frame, 0, 0, capW, capH)   // bez případného zarovnání řádků
            snapshot = Bitmap.createScaledBitmap(exact, w, h, true)
            if (exact !== frame) exact.recycle()
        } catch (_: Exception) {}
    }

    // ---------------- překlad ----------------

    /** Začne překládat titulek hned po prvním přečtení, ještě před potvrzením druhým snímkem. */
    private fun prefetch(text: String) {
        if (text.isEmpty() || inflight.containsKey(text)) return
        if (inflight.size > 6) inflight.clear()   // staré nepotřebné (titulek se mezitím změnil)
        inflight[text] = scope.async { translateSegments(text) }
    }

    /** Přeloží text po částech; jména mluvčích se nepřekládají. */
    private suspend fun translateSegments(text: String): List<Segment>? {
        val out = ArrayList<Segment>()
        for (s in TextLogic.segments(text)) {
            val tr = translate(s.body) ?: return null
            out.add(Segment(s.speaker, tr))
        }
        return out
    }

    private suspend fun translate(text: String): String? {
        cache.get(text)?.let { statsFor(this).cacheHit(); return it }
        val out = try { OfflineTranslator.translate(lang, text) } catch (e: Exception) {
            lastError = e.javaClass.simpleName; Log.w(TAG, "offline", e); null
        }
        if (out != null) { cache.put(text, out); lastError = null }
        return out
    }

    // ---------------- paměť překladů ----------------

    private fun cacheFile() = File(filesDir, "$CACHE_PREFIX${lang.code}.tsv")

    private fun loadCache() {
        try {
            val f = cacheFile()
            if (f.exists()) cache.load(f.readText(Charsets.UTF_8))
        } catch (e: Exception) { Log.w(TAG, "cache load", e) }
    }

    private fun saveCache() {
        try {
            val f = cacheFile()
            val tmp = File(filesDir, f.name + ".tmp")
            tmp.writeText(cache.dump(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.w(TAG, "cache save", e) }
        stats?.let { Prefs.setStats(this, it) }
    }

    // ---------------- diagnostika ----------------

    private fun publishDebug(ov: SubtitleOverlay) {
        val secs = max(1L, (SystemClock.uptimeMillis() - statsStart) / 1000)
        val mode = if (OfflineTranslator.isReady(lang)) "offline ${lang.code}" else "offline ${lang.code} – model se stahuje"
        val capture = when {
            usb != null -> "USB klíčenka ${capW}×${capH}"
            monitor.overlayCaptured -> "celá obrazovka (nakukuje)"
            else -> "OK"
        }
        val ecoInfo = if (perf.eco) " · úsporný režim" else ""
        val zoneInfo = if (!autoFind) "ručně" else if (autoZone.learned != null) "auto – titulky nalezeny" else "auto – hledám"
        val app = "oblast: $zoneInfo · " + (TitulkyAccessibility.foregroundPackage ?: "?") + " · HDMI test: " + hdmiProbe.verdict
        val err = lastError?.let { "\nchyba překladu: ${it.take(60)}" } ?: ""
        ov.debug(
            "snímky ${frames / secs}/s · OCR ${ocrRuns / secs}/s · ${lastMs} ms\n" +
                "překlad: $mode · paměť ${cache.size()} · snímání: $capture$ecoInfo\n" +
                "aplikace: $app\n" +
                "čtu: ${lastOcrText.replace('\n', ' ').take(70)}$err"
        )
    }

    override fun onDestroy() {
        running = false
        paused = false
        scope.cancel()
        saveCache()
        overlay?.destroy(); overlay = null
        virtualDisplay?.release(); virtualDisplay = null
        usb?.close(); usb = null
        reader?.close(); reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
