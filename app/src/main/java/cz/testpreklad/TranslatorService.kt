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
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.testpreklad.core.AutoZone
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.FrameSignature
import cz.testpreklad.core.Glossary
import cz.testpreklad.core.I18n
import cz.testpreklad.core.Lang
import cz.testpreklad.core.OcrBlock
import cz.testpreklad.core.OcrLine
import cz.testpreklad.core.Perf
import cz.testpreklad.core.Ps5
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Segment
import cz.testpreklad.core.SubtitleHistory
import cz.testpreklad.core.SubtitleTracker
import cz.testpreklad.core.TextLogic
import cz.testpreklad.core.TranslationCache
import cz.testpreklad.core.UsageStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.math.max

/**
 * Srdce aplikace. Připojí se k PlayStationu po domácí síti (stejně jako Remote Play), z kopie
 * obrazu několikrát za sekundu přečte anglické titulky, přeloží je a položí přes obraz
 * televize černý pruh s překladem. Hra samotná běží do televize kabelem HDMI v plné kvalitě.
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_STOP = "cz.testpreklad.STOP"
        const val ACTION_PAUSE = "cz.testpreklad.PAUSE"
        const val ACTION_RELOAD = "cz.testpreklad.RELOAD"
        /** Spustit nad ukázkovým videem místo PlayStationu (zkouška bez konzole). */
        const val ACTION_START_DEMO = "cz.testpreklad.START_DEMO"

        /** Šířka a výška videa, které aplikace čte (na titulky bohatě stačí, šetří síť i výkon). */
        const val VIDEO_W = 1280
        const val VIDEO_H = 720

        /** Poslední chyba při spuštění – pro obrazovku aplikace. */
        @Volatile var startError: String? = null
            private set

        @Volatile var running = false
            private set
        @Volatile var paused = false
            private set
        /** Běží nad ukázkovým videem (ne nad PlayStationem)? */
        @Volatile var demo = false
            private set

        @Volatile private var activeReader: StreamReader? = null

        /** Poslední obrázek ze hry (zmenšený, barevný) – pro náhled a nastavení oblastí. */
        val snapshot: Bitmap? get() = activeReader?.colorSnapshot

        /** Kolik snímků videa už aplikace dostala a dekódovala (pro obrazovku aplikace). */
        val framesDecoded: Long get() = activeReader?.decoded ?: 0

        /** Statistika sdílená s obrazovkou aplikace. */
        @Volatile private var stats: UsageStats? = null

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
    private var reader: StreamReader? = null
    private var link: Ps5Link? = null
    private var feeder: DemoFeeder? = null
    @Volatile private var overlay: SubtitleOverlay? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val cache = TranslationCache()
    private val tracker = SubtitleTracker()
    @Volatile private var layout = ScreenLayout()
    @Volatile private var lang = Lang.CS
    private var screenW = 0
    private var screenH = 0
    private var capW = VIDEO_W
    private var capH = VIDEO_H
    @Volatile private var debug = false
    @Volatile private var onlySubtitles = true
    @Volatile private var autoFind = true
    private val autoZone = AutoZone()

    private var lastLicenseCheck = 0L

    /** Výkon podle paměti zařízení – slabší televize čtou méně často. */
    private val perf: Perf by lazy {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Perf.forDevice(mi.totalMem / (1024 * 1024), am.isLowRamDevice)
        } catch (_: Exception) { Perf.forDevice(0, false) }
    }

    /** Kdy schovat pruh (překlad zůstane chvíli po zmizení angličtiny, ať se dá dočíst). 0 = nic. */
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
    private var lastOcrMs = 0L
    private var ticksLogged = 0
    private var lastLinkLogged: Ps5.Link? = null
    private var lastVideoLog = 0L

    /** Slovník: jména podle hry, předpřipravené hlášky a vlastní slovník uživatele. */
    @Volatile private var glossary: Glossary? = null

    /** Záznam pro automatickou zkoušku i pro hlášení chyby. */
    private fun tp(msg: String) { Log.i("TP_TEST", msg); DiagLog.add(msg) }

    @Volatile private var lastError: String? = null

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

        val wantDemo = intent?.action == ACTION_START_DEMO
        startForegroundCompat(buildNotification())
        if (running) {
            // už běží → jen případně přepni zdroj (ukázka ↔ PlayStation)
            if (wantDemo != demo) startSource(wantDemo)
            return START_NOT_STICKY
        }
        startError = null
        if (!Prefs.license(this).canRun) { startError = "licence"; stopSelf(); return START_NOT_STICKY }
        if (!wantDemo && Prefs.ps5Host(this) == null) { startError = "ps5"; stopSelf(); return START_NOT_STICKY }

        setupScreenSize()
        val r = StreamReader(VIDEO_W, VIDEO_H)
        if (!r.start()) {
            startError = "video: " + (r.lastError ?: "?")
            tp("ERROR|decoder|" + r.lastError)
            r.stop(); stopSelf(); return START_NOT_STICKY
        }
        reader = r
        activeReader = r
        beginRunning()
        startSource(wantDemo)
        return START_NOT_STICKY
    }

    /** Zapne zdroj videa: PlayStation po síti, nebo ukázkové video. */
    private fun startSource(wantDemo: Boolean) {
        val r = reader ?: return
        link?.stop(); link = null
        feeder?.stop(); feeder = null
        demo = wantDemo
        overlay?.hide(); tracker.reset(); lastSig = null
        if (wantDemo) {
            feeder = DemoFeeder(VIDEO_W, VIDEO_H) { r.feed(it) }.also { it.start() }
            tp("SOURCE|demo")
        } else {
            val host = Prefs.ps5Host(this) ?: return
            link = Ps5Link(applicationContext, host) { r.feed(it) }.also { it.start() }
            tp("SOURCE|ps5 ${host.ip}")
        }
        updateNotification()
    }

    private fun beginRunning() {
        lastLicenseCheck = SystemClock.uptimeMillis()
        tp("A11Y|" + (TitulkyAccessibility.instance != null) + "|launchers=" + launchers)
        tp("START|video=${VIDEO_W}x${VIDEO_H}|screen=${screenW}x${screenH}|eco=${perf.eco}|decoder=${reader?.decoderName}|lib=${Ps5Link.selfTest()}")
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
        glossary = Prefs.glossary(this)
        // změna hry nebo slovníku → dřívější překlady už neplatí
        val sig = Prefs.glossarySignature(this)
        if (sig != Prefs.savedGlossarySignature(this)) {
            cache.clear(); try { cacheFile().delete() } catch (_: Exception) {}
            Prefs.setSavedGlossarySignature(this, sig)
        }
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

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    // ---------------- obrazovka ----------------

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


    // ---------------- hlavní smyčka ----------------

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val t0 = SystemClock.uptimeMillis()
            try {
                watchLink(t0)
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

    /** Hlídá spojení s konzolí: zapisuje změny stavu a obnoví spojení, když video přestane chodit. */
    private fun watchLink(now: Long) {
        val r = reader ?: return
        if (now - lastVideoLog > 5000) {
            lastVideoLog = now
            if (ticksLogged < 400) tp("VIDEO|fed=${r.fed}|decoded=${r.decoded}|${r.frameWidth}x${r.frameHeight}|err=${r.lastError}")
        }
        if (demo) return
        val st = Ps5Link.state
        if (st != lastLinkLogged) {
            lastLinkLogged = st
            tp("LINK|$st|" + (Ps5Link.reason ?: ""))
            // spojení spadlo → starý překlad nenechávej viset na obrazovce
            if (st != Ps5.Link.CONNECTED) { hideAt = 0; overlay?.hide(); tracker.reset(); lastSig = null }
            updateNotification()
        }
        val l = link ?: return
        if (st == Ps5.Link.CONNECTED && l.connectedForMs > 12_000 && now - r.lastFrameAt > 12_000) {
            l.restart("no video")
        }
    }

    /** Pruh se ukazuje jen nad hrou – ne v menu této aplikace ani na domovské obrazovce televize. */
    private fun appAllowed(): Boolean {
        val pkg = TitulkyAccessibility.foregroundPackage
        val allowed = if (demo) UiState.testCard
            else !UiState.visible && pkg != packageName && (pkg == null || pkg !in launchers)
        if (!allowed && !hiddenForOtherApp) {
            hideAt = 0; overlay?.hide(); tracker.reset(); lastSig = null
        }
        hiddenForOtherApp = !allowed
        return allowed
    }

    private suspend fun tick(now: Long) {
        val ov = overlay ?: return
        val frame = reader?.grab() ?: return      // žádný nový snímek videa
        capW = frame.width
        capH = frame.height
        frames++
        process(frame, ov)
        lastMs = SystemClock.uptimeMillis() - now
    }

    private suspend fun process(frame: Bitmap, ov: SubtitleOverlay) {
        val z = layout.read
        val x0 = (capW * z.left).toInt().coerceIn(0, capW - 1)
        val x1 = (capW * z.right).toInt().coerceIn(x0 + 1, capW)
        val y0 = (capH * z.top).toInt().coerceIn(0, capH - 1)
        val y1 = (capH * z.bottom).toInt().coerceIn(y0 + 1, capH)
        val crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)

        // přeskoč čtení, když se oblast od posledního čtení nezměnila
        val sig = signature(crop)
        val prev = lastSig
        if (prev != null && tracker.isSettled && FrameSignature.diff(prev, sig) < 1.5f) {
            crop.recycle(); return
        }
        lastSig = sig

        val ocrStart = SystemClock.uptimeMillis()
        val result = try {
            recognizer.process(InputImage.fromBitmap(crop, 0)).await()
        } finally {
            crop.recycle()
        }
        ocrRuns++
        lastOcrMs = SystemClock.uptimeMillis() - ocrStart

        val picked = TextLogic.assemble(
            toBlocks(result),
            minLineHeight = (capH * 0.016f).toInt(),
            subtitlesOnly = onlySubtitles || autoFind,   // automatika čte velkou plochu → vždy jen titulky
            screenCenterX = capW / 2 - x0,
            centerTolerance = (capW * 0.12f).toInt(),
        )
        if (picked.text.isNotEmpty()) lastOcrText = picked.text

        // text, který není anglicky (hra už je v jiném jazyce, nebo je to šum), se nepřekládá
        if (picked.text.isNotEmpty() && TextLogic.looksForeign(picked.text)) {
            if (ticksLogged++ < 400) tp("SKIP|" + picked.text.replace('\n', ' ').take(40))
            return
        }
        // záznam pro automatickou zkoušku (jen prvních pár set čtení)
        if (ticksLogged++ < 400) tp("READ|ocr=${lastOcrMs}ms|zone=${(z.height * 100).toInt()}%|" + picked.text.replace('\n', ' ').take(40))

        // slabé zařízení: jedno čtení trvá déle než půl sekundy → nečekej na druhé potvrzení
        when (val action = tracker.onOcr(picked.text, slowDevice = lastOcrMs > 500)) {
            is SubtitleTracker.Action.Show -> {
                // překlad už většinou běží (spuštěný po prvním přečtení) – jen se počká na výsledek
                val ready = inflight.remove(action.text)
                val segs = (if (ready != null) ready.await() else null) ?: translateSegments(action.text) ?: return
                tracker.confirmShown(action.text)
                val top = if (picked.top >= 0) (y0 + picked.top).toFloat() / capH else -1f
                val bottom = if (picked.bottom >= 0) (y0 + picked.bottom).toFloat() / capH else -1f
                hideAt = 0
                tp("SHOW|" + action.text.replace('\n', ' ') + "|" + segs.joinToString(" ") { (it.speaker?.plus(": ") ?: "") + it.body })
                ov.show(segs, if (showOriginal) action.text else null, top, bottom)
                statsFor(this).line()
                // automatika: zapamatuj si, kde na obrazovce titulky jsou
                if (autoFind && autoZone.onSubtitle(top, bottom, SystemClock.uptimeMillis())) zoneChanged()
                SubtitleHistory.add(action.text, segs.joinToString("\n") { s ->
                    if (s.speaker != null) "${s.speaker}: ${s.body}" else s.body
                })
            }
            // anglický titulek zmizel – překlad nech chvíli dočíst
            SubtitleTracker.Action.Hide -> {
                tp("HIDE")
                if (holdMs <= 0) ov.hide() else hideAt = SystemClock.uptimeMillis() + holdMs
            }
            SubtitleTracker.Action.None -> {
                // na obrazovce je už jiný titulek → starý překlad hned schovej
                if (tracker.isChanging && picked.text.isNotEmpty() && ov.isShown) { hideAt = 0; ov.hide() }
                prefetch(tracker.pendingText)
            }
        }
    }

    /** Automatika změnila oblast čtení (našla titulky, nebo se vrátila k hledání). */
    private fun zoneChanged() {
        tp("ZONE|" + (autoZone.learned?.let { "learned ${it.top}-${it.bottom}" } ?: "scan"))
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
        val out = try { OfflineTranslator.translate(lang, text, glossary) } catch (e: Exception) {
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
        val r = reader
        val src = if (demo) "ukázka" else "PS5 " + Ps5Link.state + (Ps5Link.reason?.let { " ($it)" } ?: "")
        val ecoInfo = if (perf.eco) " · úsporný režim" else ""
        val zoneInfo = if (!autoFind) "ručně" else if (autoZone.learned != null) "auto – titulky nalezeny" else "auto – hledám"
        val err = lastError?.let { "\nchyba překladu: ${it.take(60)}" } ?: ""
        ov.debug(
            "zdroj: $src · video ${r?.frameWidth}×${r?.frameHeight} · přijato ${r?.fed} · dekódováno ${r?.decoded}\n" +
                "čtení ${ocrRuns / secs}/s · ${lastMs} ms · překlad: $mode · paměť ${cache.size()}$ecoInfo\n" +
                "oblast: $zoneInfo · na obrazovce: " + (TitulkyAccessibility.foregroundPackage ?: "?") + "\n" +
                "čtu: ${lastOcrText.replace('\n', ' ').take(70)}$err"
        )
    }

    override fun onDestroy() {
        running = false
        paused = false
        demo = false
        scope.cancel()
        saveCache()
        overlay?.destroy(); overlay = null
        link?.stop(); link = null
        feeder?.stop(); feeder = null
        activeReader = null
        reader?.stop(); reader = null
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
