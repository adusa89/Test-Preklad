package cz.testpreklad

import android.util.Log
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEvent
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.Ps5
import cz.testpreklad.core.Ps5Host

/**
 * Jednorázové spárování s PlayStationem: najít konzoli v domácí síti a zaregistrovat televizi u konzole číslem, které konzole ukáže.
 */
object Ps5Pairing {
    private const val TAG = "TestPreklad"

    /** Nalezená konzole v síti. */
    data class Found(val ip: String, val name: String, val ps5: Boolean, val standby: Boolean, val id: String = "")

    private fun log(msg: String) { Log.i("TP_TEST", "PAIR|$msg"); DiagLog.add("PAIR|$msg") }

    /**
     * Hledá PlayStation v domácí síti. Volat mimo hlavní vlákno. Vrací nalezené konzole, PS5 první.
     * Posílá dotaz třemi cestami (všeobecné vysílání, vysílání sítě a každé adrese zvlášť),
     * protože některé routery a televize vysílání nepropouštějí.
     */
    fun discover(timeoutMs: Long = 5000): List<Found> {
        val found = LinkedHashMap<String, Found>()
        var sock: java.net.DatagramSocket? = null
        var nets = ""
        try {
            sock = java.net.DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 300
            val targets = LinkedHashSet<String>()
            try {
                for (ni in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address as? java.net.Inet4Address ?: continue
                        nets += a.hostAddress + "/" + ia.networkPrefixLength + " "
                        targets.addAll(Ps5.sweepTargets(a.hostAddress ?: continue, ia.networkPrefixLength.toInt()))
                    }
                }
            } catch (e: Throwable) { log("interfaces " + e.javaClass.simpleName) }
            if (targets.isEmpty()) targets.add("255.255.255.255")
            val ps5 = Ps5.searchPacket(true)
            val ps4 = Ps5.searchPacket(false)
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            var round = 0
            while (System.currentTimeMillis() < end) {
                // dotaz se pošle dvakrát (pakety se mohou ztratit), pak se jen poslouchá
                if (round < 2) for (t in targets) {
                    try {
                        val addr = java.net.InetAddress.getByName(t)
                        sock.send(java.net.DatagramPacket(ps5, ps5.size, addr, Ps5.DISCOVERY_PORT_PS5))
                        sock.send(java.net.DatagramPacket(ps4, ps4.size, addr, Ps5.DISCOVERY_PORT_PS4))
                    } catch (_: Throwable) {}
                }
                round++
                val listenUntil = System.currentTimeMillis() + 900
                while (System.currentTimeMillis() < listenUntil) {
                    try {
                        val p = java.net.DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val r = Ps5.parseDiscoveryReply(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                        val ip = p.address.hostAddress ?: continue
                        found[ip] = Found(ip, r.name, r.type.equals("PS5", true), r.standby, r.id)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Throwable) { break }
                }
                if (found.values.any { it.ps5 }) break
            }
        } catch (e: Throwable) {
            log("discover error $e")
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
        }
        val list = found.values.sortedByDescending { it.ps5 }
        log("discover nets=$nets→ " + list.joinToString { "${it.ip} ${it.name} ps5=${it.ps5} standby=${it.standby}" })
        return list
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
