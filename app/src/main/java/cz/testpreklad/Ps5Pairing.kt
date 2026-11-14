package cz.testpreklad

import android.util.Log
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEvent
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.Ps5
import cz.testpreklad.core.Ps5Host
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Jednorázové spárování s PlayStationem: najít konzoli v domácí síti, zjistit ID účtu PSN
 * a zaregistrovat televizi u konzole číslem, které konzole ukáže.
 */
object Ps5Pairing {
    private const val TAG = "TestPreklad"

    /** Nalezená konzole v síti. */
    data class Found(val ip: String, val name: String, val ps5: Boolean, val standby: Boolean, val id: String = "")

    private fun log(msg: String) { Log.i("TP_TEST", "PAIR|$msg"); DiagLog.add("PAIR|$msg") }

    /**
     * Hledá PlayStation v domácí síti (asi 4 sekundy). Volat mimo hlavní vlákno.
     * Vrací nalezené konzole, PS5 první.
     */
    fun discover(timeoutMs: Long = 4000): List<Found> {
        val found = LinkedHashMap<String, Found>()
        var service: DiscoveryService? = null
        try {
            service = DiscoveryService(
                DiscoveryServiceOptions(16U, 3U, 500U, InetSocketAddress("255.255.255.255", 987))
            ) { hosts: List<DiscoveryHost> ->
                synchronized(found) {
                    for (h in hosts) {
                        val ip = h.hostAddr ?: continue
                        found[ip] = Found(ip, h.hostName ?: "PlayStation", h.isPS5, h.state == DiscoveryHost.State.STANDBY, h.hostId ?: "")
                    }
                }
            }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                Thread.sleep(250)
                // PS5 zapnutá a nalezená → není na co čekat
                if (synchronized(found) { found.values.any { it.ps5 } }) { Thread.sleep(300); break }
            }
        } catch (e: Throwable) {
            log("discover error $e")
        } finally {
            try { service?.dispose() } catch (_: Throwable) {}
        }
        val list = synchronized(found) { found.values.sortedByDescending { it.ps5 } }
        log("discover → " + list.joinToString { "${it.ip} ${it.name} ps5=${it.ps5} standby=${it.standby}" })
        return list
    }

    /**
     * Podle přezdívky na PSN zjistí ID účtu (veřejná vyhledávací služba; funguje jen
     * pro veřejné profily). Volat mimo hlavní vlákno. Vrací zakódované ID, nebo null.
     */
    fun lookupAccountId(onlineId: String): String? {
        if (!Ps5.validOnlineId(onlineId)) return null
        return try {
            val url = URL("https://psn.flipscreen.games/search.php?username=" + URLEncoder.encode(onlineId, "UTF-8"))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode != 200) { log("lookup http ${c.responseCode}"); null }
                else Ps5.parseLookup(c.inputStream.bufferedReader().use { it.readText().take(4000) })
            } finally { c.disconnect() }
        } catch (e: Exception) {
            log("lookup error ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Zaregistruje televizi u konzole. [done] dostane uložitelné údaje o konzoli, nebo null,
     * když se to nepovedlo (špatné číslo, špatný účet, konzole nedostupná). Volá se z jiného vlákna.
     */
    fun pair(ip: String, accountId: ByteArray, pin: Int, done: (Ps5Host?) -> Unit) {
        var regist: Regist? = null
        var finished = false
        val lock = Any()
        fun finish(h: Ps5Host?) {
            synchronized(lock) { if (finished) return; finished = true }
            // uvolnit až mimo vlákno knihovny, které tuhle zprávu poslalo
            Thread({
                try { Thread.sleep(200); regist?.dispose() } catch (_: Throwable) {}
                done(h)
            }, "tp-pair-done").start()
        }
        try {
            val chLog = ChiakiLog(ChiakiLog.Level.ALL.value and ChiakiLog.Level.VERBOSE.value.inv() and ChiakiLog.Level.DEBUG.value.inv()) { level, text ->
                Log.i(TAG, "regist: " + ChiakiLog.formatLog(level, text))
                if (level == ChiakiLog.Level.ERROR.value || level == ChiakiLog.Level.WARNING.value) DiagLog.add("PAIR|lib " + text.take(120))
            }
            val info = RegistInfo(Target.PS5_1, ip, false, null, accountId, pin)
            regist = Regist(info, chLog) { e: RegistEvent ->
                if (e is RegistEventSuccess) {
                    val h = e.host
                    log("success ${h.serverNickname}")
                    finish(Ps5Host(ip, h.serverNickname, h.rpRegistKey, h.rpKey, h.serverMac))
                } else {
                    log("failed " + e.javaClass.simpleName)
                    finish(null)
                }
            }
            // pojistka: kdyby knihovna neodpověděla vůbec
            Thread({
                try { Thread.sleep(25_000) } catch (_: Throwable) {}
                val pending = synchronized(lock) { !finished }
                if (pending) { log("timeout"); try { regist.stop() } catch (_: Throwable) {}; finish(null) }
            }, "tp-pair-timeout").start()
        } catch (e: Throwable) {
            log("error $e")
            finish(null)
        }
    }
}
