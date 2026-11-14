package cz.testpreklad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import cz.testpreklad.core.DemoGame
import cz.testpreklad.core.Yuv

/**
 * Ukázkový zdroj videa: kreslí „hru“ s anglickými titulky, zakóduje ji do H.264 a posílá
 * do čtečky úplně stejnou cestou, jakou chodí video z PlayStationu. Díky tomu jde celá
 * aplikace vyzkoušet bez konzole (uživatelem i automaticky v emulátoru).
 */
class DemoFeeder(private val width: Int, private val height: Int, private val sink: (ByteArray) -> Boolean) {

    companion object {
        private const val TAG = "TestPreklad"
        private const val MIME = "video/avc"
        private const val FPS = 10

        /** Společný začátek času pro ukázkovou obrazovku i ukázkové video. */
        val epoch: Long = SystemClock.uptimeMillis()
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var error: String? = null
        private set
    @Volatile var sent = 0L
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "tp-demo").apply { start() }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: Exception) {}
        thread = null
    }

    private fun run() {
        var enc: MediaCodec? = null
        try {
            enc = MediaCodec.createEncoderByType(MIME)
            val f = MediaFormat.createVideoFormat(MIME, width, height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val argb = IntArray(width * height)
            val y = ByteArray(width * height)
            val u = ByteArray(width * height / 4)
            val v = ByteArray(width * height / 4)
            var drawn: String? = null
            val info = MediaCodec.BufferInfo()
            var frame = 0L

            while (running) {
                val t0 = SystemClock.uptimeMillis()
                val sub = DemoGame.subtitleAt(t0 - epoch)
                if (sub != drawn) {
                    TestCard.draw(canvas, width.toFloat(), height.toFloat(), sub)
                    bmp.getPixels(argb, 0, width, 0, 0, width, height)
                    Yuv.fromArgb(argb, width, height, y, u, v)
                    drawn = sub
                }
                val i = enc.dequeueInputBuffer(20_000)
                if (i >= 0) {
                    val img = enc.getInputImage(i)
                    if (img != null) {
                        fill(img.planes[0], y, width, height)
                        fill(img.planes[1], u, width / 2, height / 2)
                        fill(img.planes[2], v, width / 2, height / 2)
                        enc.queueInputBuffer(i, 0, width * height * 3 / 2, frame * 1_000_000L / FPS, 0)
                        frame++
                    } else {
                        enc.queueInputBuffer(i, 0, 0, frame * 1_000_000L / FPS, 0)
                    }
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val ob = enc.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        val out = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(out, 0, info.size)
                        sink(out)
                        sent++
                    }
                    enc.releaseOutputBuffer(o, false)
                }
                val spent = SystemClock.uptimeMillis() - t0
                Thread.sleep(maxOf(5L, 1000L / FPS - spent))
            }
        } catch (e: Exception) {
            error = e.javaClass.simpleName + ": " + (e.message ?: "")
            Log.e(TAG, "ukázkové video", e)
        } finally {
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
        }
    }

    /** Zapíše jednu složku obrazu do vstupu kodéru (respektuje rozložení, které si kodér určil). */
    private fun fill(p: android.media.Image.Plane, src: ByteArray, w: Int, h: Int) {
        val b = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        if (ps == 1) {
            for (row in 0 until h) {
                b.position(row * rs)
                b.put(src, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                var pos = row * rs
                val base = row * w
                for (x in 0 until w) {
                    b.put(pos, src[base + x])
                    pos += ps
                }
            }
        }
    }
}
