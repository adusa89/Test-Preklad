package cz.testpreklad

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.Ps5
import cz.testpreklad.core.Ps5Host
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Spojení s PlayStationem: aplikace se připojí stejně jako Remote Play, ale obraz nezobrazuje –
 * jen ho předává čtečce titulků. Neposílá žádné ovládání, hraje se dál ovladačem u konzole
 * a obraz i zvuk jdou do televize kabelem HDMI.
 * Když spojení spadne (konzole usnula, výpadek Wi-Fi), samo se zkouší obnovit.
 */
class Ps5Link(
    private val ctx: Context,
    private var host: Ps5Host,
    private val onVideo: (ByteArray) -> Boolean,
) {
    companion object {
        private const val TAG = "TestPreklad"

        /** Stav spojení pro obrazovku aplikace. */
        @Volatile var state: Ps5.Link = Ps5.Link.OFF
            private set
        /** Poslední důvod odpojení (text z konzole), nebo null. */
        @Volatile var reason: String? = null
            private set
        @Volatile var needsLoginPin = false
            private set

        fun logFile(ctx: Context) = File(ctx.cacheDir, "ps5-spojeni.txt")

        /** Je nativní knihovna pro spojení v pořádku? (zkouší se i v emulátoru) */
        fun selfTest(): String = try {
            val p = ConnectVideoProfile.preset(VideoResolutionPreset.RES_720P, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
            "ok ${p.width}x${p.height}@${p.maxFPS} ${p.bitrate}kb"
        } catch (e: Throwable) { "FAIL " + e.javaClass.simpleName + ": " + e.message }
    }

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tp-ps5") }
    private var session: Session? = null
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    @Volatile private var connectedAt = 0L
    @Volatile private var everConnected = false

    private fun log(msg: String) { Log.i("TP_TEST", "PS5|$msg"); DiagLog.add("PS5|$msg") }

    fun start() {
        stopped = false
        exec.execute { connect() }
    }

    private fun connect() {
        if (stopped || session != null) return
        state = Ps5.Link.CONNECTING
        needsLoginPin = false
        try {
            val f = logFile(ctx)
            try { if (f.length() > 400_000) f.delete() } catch (_: Exception) {}
            val profile = ConnectVideoProfile.preset(VideoResolutionPreset.RES_720P, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
            val s = Session(ConnectInfo(true, host.ip, host.registKey, host.morning, profile), f.absolutePath, false)
            s.eventCallback = { e -> onEvent(s, e) }
            s.videoCallback = onVideo
            session = s
            val err = s.start()
            log("connect ${host.ip} → $err")
            if (!err.isSuccess) { reason = err.toString(); drop(s); failed() }
        } catch (e: CreateError) {
            reason = e.errorCode.toString()
            log("create error ${e.errorCode}")
            failed()
        } catch (e: Throwable) {
            reason = e.javaClass.simpleName
            log("error $e")
            failed()
        }
    }

    /** Volá se z vlákna knihovny – nic těžkého tady, jen předat dál. */
    private fun onEvent(s: Session, e: Event) {
        when (e) {
            is ConnectedEvent -> {
                log("connected")
                state = Ps5.Link.CONNECTED
                reason = null
                connectedAt = SystemClock.uptimeMillis()
                everConnected = true
                exec.execute { failures = 0 }
            }
            is LoginPinRequestEvent -> {
                log("login pin requested")
                needsLoginPin = true
                val pin = Prefs.ps5LoginPin(ctx)
                if (pin.length == 4 && !e.pinIncorrect) s.setLoginPin(pin)
                else exec.execute { reason = "login-pin"; drop(s); state = Ps5.Link.FAILED }
            }
            is QuitEvent -> {
                log("quit ${e.reason} ${e.reasonString ?: ""}")
                reason = e.reasonString?.takeIf { it.isNotBlank() } ?: e.reason.toString()
                exec.execute { drop(s); failed() }
            }
            else -> {}
        }
    }

    /** Ukončí a uvolní relaci (jen z pracovního vlákna – čeká na vlákno knihovny). */
    private fun drop(s: Session) {
        if (session === s) session = null
        try { s.eventCallback = null; s.videoCallback = null; s.stop(); s.dispose() } catch (e: Throwable) { Log.w(TAG, "ps5 drop", e) }
    }

    private fun failed() {
        if (stopped) return
        failures++
        state = if (failures >= 3) Ps5.Link.FAILED else Ps5.Link.WAITING
        val delay = Ps5.retryDelayMs(failures)
        retry?.cancel(false)
        retry = exec.schedule({
            if (stopped) return@schedule
            // Budit konzoli jen na začátku (uživatel právě spustil překlad). Jakmile jednou běžela
            // a pak se odmlčela, nejspíš ji uživatel vypnul – a tu už aplikace znovu zapínat nesmí.
            if (!everConnected && failures <= 2) wake()
            if (failures == 2 || failures % 5 == 0) relocate()
            connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Router mohl dát konzoli jinou adresu → najdi ji v síti znovu (podle jejího čísla MAC). */
    private fun relocate() {
        try {
            val mac = Ps5.hex(host.mac)
            val list = Ps5Pairing.discover(3000)
            val hit = list.firstOrNull { it.id.equals(mac, ignoreCase = true) }
                ?: list.filter { it.ps5 }.singleOrNull()?.takeIf { mac.isEmpty() || it.id.isEmpty() }
            if (hit != null && hit.ip != host.ip) {
                log("address changed ${host.ip} → ${hit.ip}")
                host = host.withIp(hit.ip)
                Prefs.setPs5Host(ctx, host)
            }
        } catch (e: Throwable) { Log.w(TAG, "relocate", e) }
    }

    /** Probudí konzoli z klidového režimu (když to má v nastavení povolené). */
    private fun wake() {
        val cred = Ps5.wakeCredential(host.registKey) ?: return
        try { DiscoveryService.wakeup(null, host.ip, cred, true) } catch (e: Throwable) { Log.w(TAG, "wake", e) }
    }

    /** Spojení „visí“ (připojeno, ale video nechodí) → začni znovu. */
    fun restart(why: String) {
        exec.execute {
            if (stopped) return@execute
            log("restart: $why")
            session?.let { drop(it) }
            reason = why
            failed()
        }
    }

    val connectedForMs: Long get() = if (state == Ps5.Link.CONNECTED) SystemClock.uptimeMillis() - connectedAt else 0

    fun stop() {
        stopped = true
        retry?.cancel(false)
        exec.execute {
            session?.let { drop(it) }
            state = Ps5.Link.OFF
        }
        exec.shutdown()
    }
}
