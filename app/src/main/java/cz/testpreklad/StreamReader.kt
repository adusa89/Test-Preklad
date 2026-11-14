package cz.testpreklad

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import cz.testpreklad.core.Yuv
import kotlin.math.min

/**
 * Čtečka videa: dostává snímky videa (H.264) tak, jak chodí z PlayStationu po síti,
 * dekóduje je a drží poslední obrázek připravený pro čtení titulků.
 * Nic se nezobrazuje ani nepřehrává – obraz i zvuk hry jdou do televize kabelem HDMI.
 */
class StreamReader(private val width: Int, private val height: Int) {

    companion object {
        private const val TAG = "TestPreklad"
        private const val MIME = "video/avc"
    }

    private val thread = HandlerThread("tp-video").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var codec: MediaCodec? = null
    private val codecLock = Any()
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var failures = 0

    /** První snímek z konzole (hlavička videa) – při obnově dekodéru se pošle znovu. */
    private var header: ByteArray? = null

    private val frameLock = Any()
    private var yBuf = ByteArray(0)
    private var yStride = 0
    private var frameW = 0
    private var frameH = 0
    private var frameSerial = 0L
    private var grabbedSerial = -1L
    private var argb = IntArray(0)
    private var bmp: Bitmap? = null
    private var lastColorAt = 0L

    /** Počet dekódovaných snímků a čas posledního (pro diagnostiku a hlídání spojení). */
    @Volatile var decoded = 0L
        private set
    @Volatile var lastFrameAt = 0L
        private set
    @Volatile var fed = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var decoderName: String = "?"
        private set

    /** Barevný náhled (poloviční velikost) – pro obrazovku aplikace a nastavení oblastí. */
    @Volatile var colorSnapshot: Bitmap? = null
        private set

    val frameWidth: Int get() = synchronized(frameLock) { frameW }
    val frameHeight: Int get() = synchronized(frameLock) { frameH }

    fun start(): Boolean = synchronized(codecLock) {
        try {
            val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = r
            openCodec()
            true
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            Log.e(TAG, "dekodér", e)
            false
        }
    }

    /** Způsob, kterým se dekodér podařilo spustit (zapamatuje se pro případnou obnovu). */
    private var workingPlan: Plan? = null
    /** Dekodér vrací obrázky přímo (bez plochy ImageReaderu) – záloha pro zařízení, kde to jinak nejde. */
    private var bufferMode = false

    /** Způsoby, které se sice spustily, ale nevydaly žádný obraz. */
    private val badPlans = HashSet<Plan>()
    private var fedAtOpen = 0L
    private var decodedAtOpen = 0L

    private data class Plan(val codecName: String?, val surface: Boolean, val lowLatency: Boolean)

    /** Všechny dekodéry H.264 v zařízení (hardwarové i softwarové). */
    private fun decoderNames(): List<String> = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
            .map { it.name }
    } catch (_: Exception) { emptyList() }

    /**
     * Spustí dekodér. Televize a boxy se liší, proto se zkouší několik způsobů po sobě:
     * výchozí dekodér zařízení, pak ostatní; nejdřív s výstupem do plochy, pak s přímým výstupem.
     */
    private fun openCodec() {
        val plans = ArrayList<Plan>()
        workingPlan?.let { plans.add(it) }
        if (Build.VERSION.SDK_INT >= 30) plans.add(Plan(null, surface = true, lowLatency = true))
        plans.add(Plan(null, surface = true, lowLatency = false))
        plans.add(Plan(null, surface = false, lowLatency = false))
        for (n in decoderNames()) { plans.add(Plan(n, true, false)); plans.add(Plan(n, false, false)) }
        var last: Exception? = null
        val tried = StringBuilder()
        if (plans.distinct().all { it in badPlans }) badPlans.clear()
        for (pl in plans.distinct()) {
            if (pl in badPlans) continue
            var c: MediaCodec? = null
            try {
                c = if (pl.codecName == null) MediaCodec.createDecoderByType(MIME) else MediaCodec.createByCodecName(pl.codecName)
                val f = MediaFormat.createVideoFormat(MIME, width, height)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                if (pl.lowLatency) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (!pl.surface) f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                c.configure(f, if (pl.surface) (reader ?: throw IllegalStateException("reader")).surface else null, null, 0)
                c.start()
                codec = c
                bufferMode = !pl.surface
                workingPlan = pl
                decoderName = (try { c.name } catch (_: Exception) { "?" }) + if (pl.surface) "" else " (přímý výstup)"
                pts = 0
                fedAtOpen = fed; decodedAtOpen = decoded
                if (tried.isNotEmpty()) Log.i(TAG, "dekodér: nešlo $tried→ jde $decoderName")
                return
            } catch (e: Exception) {
                last = e
                tried.append(pl.codecName ?: "výchozí").append(if (pl.surface) "/plocha " else "/přímo ")
                try { c?.release() } catch (_: Exception) {}
            }
        }
        workingPlan = null
        throw last ?: IllegalStateException("žádný dekodér H.264")
    }

    private fun closeCodec() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    /**
     * Jeden snímek videa ze sítě. Vrací false, když ho dekodér nepřijal
     * (volající si pak od konzole vyžádá nový klíčový snímek).
     */
    fun feed(buf: ByteArray): Boolean = synchronized(codecLock) {
        if (reader == null) return false
        if (header == null) header = buf
        fed++
        try {
            // dekodér běží, dostal už přes 90 snímků a nevydal ani jeden obrázek → zkus jiný způsob
            if (codec != null && fed - fedAtOpen > 90 && decoded == decodedAtOpen) {
                workingPlan?.let { badPlans.add(it) }
                Log.w(TAG, "dekodér $decoderName nevydává obraz, zkouším jiný")
                workingPlan = null
                closeCodec()
            }
            val c = codec ?: run { openCodec(); val h = header; if (h != null && h !== buf) push(codec!!, h); codec!! }
            val ok = push(c, buf)
            drain(c)
            if (ok) failures = 0
            ok
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            Log.w(TAG, "dekódování", e)
            // dekodér se rozbil → zavři ho, příští snímek ho otevře znovu
            closeCodec()
            failures++
            false
        }
    }

    private fun push(c: MediaCodec, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val i = c.dequeueInputBuffer(20_000)
            if (i < 0) { drain(c); return false }
            val ib = c.getInputBuffer(i) ?: return false
            ib.clear()
            val n = min(ib.remaining(), buf.size - off)
            ib.put(buf, off, n)
            c.queueInputBuffer(i, 0, n, pts++, 0)
            off += n
        }
        return true
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    if (bufferMode) {
                        if (info.size != 0) try { c.getOutputImage(o)?.let { handleImage(it) } } catch (e: Exception) { lastError = "obraz: " + e.javaClass.simpleName }
                        c.releaseOutputBuffer(o, false)
                    } else c.releaseOutputBuffer(o, info.size != 0)
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                @Suppress("DEPRECATION")
                o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                else -> return
            }
        }
    }

    /** Nový dekódovaný obrázek: zkopíruj jasovou složku (na čtení textu barvy nejsou potřeba). */
    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            handleImage(img)
        } catch (e: Exception) {
            lastError = "obraz: " + e.javaClass.simpleName
        } finally {
            img.close()
        }
    }

    /** Zkopíruje jas z dekódovaného obrázku a občas uloží barevný náhled. */
    private fun handleImage(img: Image) {
        run {
            val w = img.width
            val h = img.height
            val p = img.planes[0]
            val stride = p.rowStride
            val buf = p.buffer.duplicate()
            val need = min(buf.remaining(), stride * h)
            synchronized(frameLock) {
                if (yBuf.size < stride * h) yBuf = ByteArray(stride * h)
                buf.get(yBuf, 0, need)
                yStride = stride
                frameW = w
                frameH = h
                frameSerial++
            }
            decoded++
            val now = SystemClock.uptimeMillis()
            lastFrameAt = now
            if (now - lastColorAt > 1500) {
                lastColorAt = now
                colorSnapshot = colorHalf(img)
            }
        }
    }

    /**
     * Hotový obrázek bez dekódování (jen ukázka na zařízeních, která neumí video zakódovat).
     * [y] = jas, w×h bajtů.
     */
    fun feedRaw(y: ByteArray, w: Int, h: Int, color: Bitmap?) {
        synchronized(frameLock) {
            if (yBuf.size < w * h) yBuf = ByteArray(w * h)
            System.arraycopy(y, 0, yBuf, 0, w * h)
            yStride = w; frameW = w; frameH = h; frameSerial++
        }
        decoded++
        lastFrameAt = SystemClock.uptimeMillis()
        if (color != null) colorSnapshot = color
    }

    /** Barevný náhled v poloviční velikosti. */
    private fun colorHalf(img: Image): Bitmap? = try {
        val w = img.width / 2
        val h = img.height / 2
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val out = IntArray(w * h)
        var o = 0
        for (row in 0 until h) {
            val yRow = row * 2 * yP.rowStride
            val uRow = row * uP.rowStride
            val vRow = row * vP.rowStride
            for (x in 0 until w) {
                val yy = yB.get(yRow + x * 2 * yP.pixelStride).toInt() and 0xFF
                val uu = uB.get(uRow + x * uP.pixelStride).toInt() and 0xFF
                val vv = vB.get(vRow + x * vP.pixelStride).toInt() and 0xFF
                out[o++] = Yuv.pixel(yy, uu, vv)
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    } catch (_: Exception) { null }

    /**
     * Poslední obrázek jako šedá bitmapa (znovupoužívaná – volající ji nesmí recyklovat).
     * Vrací null, dokud žádný snímek nepřišel, nebo když od minula žádný nový není.
     */
    fun grab(onlyNew: Boolean = true): Bitmap? = synchronized(frameLock) {
        if (frameW <= 0 || frameH <= 0) return null
        if (onlyNew && grabbedSerial == frameSerial) return null
        grabbedSerial = frameSerial
        val n = frameW * frameH
        if (argb.size != n) argb = IntArray(n)
        Yuv.grayToArgb(yBuf, yStride, frameW, frameH, argb)
        var b = bmp
        if (b == null || b.width != frameW || b.height != frameH) {
            b = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            bmp = b
        }
        b.setPixels(argb, 0, frameW, 0, 0, frameW, frameH)
        b
    }

    fun stop() {
        synchronized(codecLock) {
            closeCodec()
            try { reader?.close() } catch (_: Exception) {}
            reader = null
        }
        thread.quitSafely()
    }
}
