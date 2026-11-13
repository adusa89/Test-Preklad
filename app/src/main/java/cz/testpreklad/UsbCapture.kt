package cz.testpreklad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import cz.testpreklad.core.Source

/**
 * Čtení obrazu z USB capture klíčenky (HDMI → USB).
 *
 * Zapojení bez ztráty kvality: PS5 → HDMI splitter → (1) televize v plné kvalitě,
 * (2) USB klíčenka → USB port televize → tato třída. Aplikace čte titulky z kopie obrazu,
 * hra samotná jde do televize přímo a nijak se nemění.
 *
 * Klíčenka se v Androidu hlásí jako externí (USB) kamera.
 */
class UsbCapture(private val ctx: Context) {

    companion object {
        private const val TAG = "TestPreklad"

        /** Výsledek kontroly USB pro obrazovku aplikace. */
        enum class Status { NO_USB_VIDEO, USB_VIDEO_NO_CAMERA, READY }

        /** Je připojené USB zařízení třídy „video“ (capture klíčenka, webkamera)? */
        fun usbVideoDevices(ctx: Context): List<String> = try {
            val um = ctx.getSystemService(UsbManager::class.java)
            um.deviceList.values.filter { d ->
                d.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                    (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
            }.map { d -> d.productName ?: d.deviceName }
        } catch (e: Exception) { emptyList() }

        /** Id externí kamery (klíčenky), kterou umí systém televize otevřít, nebo null. */
        fun externalCameraId(ctx: Context): String? = try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_EXTERNAL
            } ?: cm.cameraIdList.firstOrNull()   // TV bez vlastní kamery: jediná kamera = klíčenka
        } catch (e: Exception) { null }

        fun status(ctx: Context): Status = when {
            externalCameraId(ctx) != null -> Status.READY
            usbVideoDevices(ctx).isNotEmpty() -> Status.USB_VIDEO_NO_CAMERA
            else -> Status.NO_USB_VIDEO
        }

        /** Vybere velikost obrazu co nejblíž 1280×720 (na titulky ideální, nezatěžuje TV). */
        fun pickSize(sizes: List<Size>): Size? {
            val p = Source.pickCaptureSize(sizes.map { it.width to it.height }) ?: return null
            return sizes.first { it.width == p.first && it.height == p.second }
        }
    }

    private val thread = HandlerThread("usb-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var error: String? = null; private set

    private val lock = Any()
    private var luma: ByteArray? = null
    private var lumaFresh = false
    private var pixels: IntArray? = null
    private var bitmap: Bitmap? = null

    /** Otevře klíčenku. [onReady] dostane true/false (na vlákně klíčenky). */
    @Suppress("DEPRECATION", "MissingPermission")
    fun open(onReady: (Boolean) -> Unit) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = externalCameraId(ctx)
        if (id == null) { error = "USB klíčenka nenalezena"; onReady(false); return }
        try {
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = pickSize(map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList())
                ?: run { error = "Klíčenka neposílá podporovaný obraz"; onReady(false); return }
            width = size.width; height = size.height
            val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
            reader = r
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(r.surface)
                                    s.setRepeatingRequest(req.build(), null, handler)
                                    onReady(true)
                                } catch (e: Exception) { fail(e, onReady) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                error = "Klíčenku se nepodařilo nastavit"; onReady(false)
                            }
                        }, handler)
                    } catch (e: Exception) { fail(e, onReady) }
                }
                override fun onDisconnected(cam: CameraDevice) { error = "Klíčenka odpojena"; cam.close(); device = null }
                override fun onError(cam: CameraDevice, code: Int) { error = "Chyba klíčenky ($code)"; cam.close(); device = null; onReady(false) }
            }, handler)
        } catch (e: Exception) { fail(e, onReady) }
    }

    private fun fail(e: Exception, onReady: (Boolean) -> Unit) {
        Log.w(TAG, "usb", e)
        error = e.javaClass.simpleName + ": " + (e.message ?: "")
        onReady(false)
    }

    /** Z každého snímku si vezme jen jas (Y) – na čtení bílých titulků úplně stačí a je to rychlé. */
    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val w = img.width
            val h = img.height
            synchronized(lock) {
                var y = luma
                if (y == null || y.size != w * h) { y = ByteArray(w * h); luma = y }
                if (rowStride == w) {
                    buf.get(y, 0, minOf(y.size, buf.remaining()))
                } else {
                    for (row in 0 until h) {
                        buf.position(row * rowStride)
                        buf.get(y, row * w, w)
                    }
                }
                lumaFresh = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "usb frame", e)
        } finally {
            img.close()
        }
    }

    /** Nejnovější snímek jako (šedotónová) bitmapa, nebo null, když nic nového nepřišlo. */
    fun latest(): Bitmap? = synchronized(lock) {
        val y = luma ?: return null
        if (!lumaFresh) return null
        lumaFresh = false
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || y.size != w * h) return null
        var px = pixels
        if (px == null || px.size != y.size) { px = IntArray(y.size); pixels = px }
        for (i in y.indices) {
            val v = y[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bitmap = bmp
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        thread.quitSafely()
    }
}
