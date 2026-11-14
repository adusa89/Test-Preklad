package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class Ps5Test {
    @Test fun accountIdFromBase64() {
        val b = Ps5.parseAccountId("AQIDBAUGBwg=")!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), b)
        assertEquals("AQIDBAUGBwg=", Ps5.accountIdToBase64(b))
    }

    @Test fun accountIdFromNumberIsLittleEndian() {
        // 0x0807060504030201 = 578437695752307201
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), Ps5.parseAccountId("578437695752307201"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), Ps5.parseAccountId("  578437695752307201 "))
    }

    @Test fun badAccountIdsRejected() {
        assertNull(Ps5.parseAccountId(""))
        assertNull(Ps5.parseAccountId("abc"))
        assertNull(Ps5.parseAccountId("AQIDBAUG"))          // jen 6 bajtů
        assertNull(Ps5.parseAccountId("!!!!"))
        assertNull(Ps5.parseAccountId("99999999999999999999999"))
    }

    @Test fun pin() {
        assertEquals(12345678, Ps5.parsePin("12345678"))
        assertEquals(12345678, Ps5.parsePin("1234 5678"))
        assertEquals(1234567, Ps5.parsePin("0123 4567"))
        assertNull(Ps5.parsePin("1234567"))
        assertNull(Ps5.parsePin("123456789"))
        assertNull(Ps5.parsePin("1234567a"))
    }

    @Test fun hosts() {
        assertTrue(Ps5.validHost("192.168.1.20"))
        assertTrue(Ps5.validHost("ps5.local"))
        assertFalse(Ps5.validHost("192.168.1.256"))
        assertFalse(Ps5.validHost("192.168.1"))
        assertFalse(Ps5.validHost(""))
        assertFalse(Ps5.validHost("a b"))
    }

    @Test fun hostRoundTrip() {
        val h = Ps5Host("192.168.1.20", "PS5 – obývák|x", ByteArray(16) { it.toByte() }, ByteArray(16) { (it * 3).toByte() }, byteArrayOf(1, 2, 3, 4, 5, 6))
        val back = Ps5Host.parse(h.serialize())
        assertEquals(h, back)
        assertEquals("PS5 – obývák|x", back!!.name)
        assertEquals("10.0.0.5", h.withIp("10.0.0.5").ip)
    }

    @Test fun brokenHostIsNull() {
        assertNull(Ps5Host.parse(null))
        assertNull(Ps5Host.parse(""))
        assertNull(Ps5Host.parse("a|b|c"))
        assertNull(Ps5Host.parse("192.168.1.20|eA==|zz|00|00"))
        assertNull(Ps5Host.parse("192.168.1.20|eA==|0011|0011|0011"))   // krátké klíče
    }

    @Test fun wakeCredential() {
        val key = ByteArray(16)
        "1a2b3c4d".toByteArray().copyInto(key)
        assertEquals(0x1a2b3c4dUL, Ps5.wakeCredential(key))
        assertNull(Ps5.wakeCredential("zzzz".toByteArray()))
    }

    @Test fun lookupAnswer() {
        assertEquals("AQIDBAUGBwg=", Ps5.parseLookup("""{"online_id":"x","user_id":"1","encoded_id":"AQIDBAUGBwg="}"""))
        assertEquals("AQIDBAUGBwg=", Ps5.parseLookup("""{ "encoded_id" : "AQIDBAUGBwg=" }"""))
        assertNull(Ps5.parseLookup("""{"error":"not found"}"""))
        assertNull(Ps5.parseLookup("<html>"))
    }

    @Test fun onlineId() {
        assertTrue(Ps5.validOnlineId("adusa_89"))
        assertFalse(Ps5.validOnlineId("ab"))
        assertFalse(Ps5.validOnlineId("has space"))
        assertFalse(Ps5.validOnlineId("příliš"))
    }

    @Test fun retryGrowsAndIsCapped() {
        var prev = 0L
        for (i in 0..10) { val d = Ps5.retryDelayMs(i); assertTrue(d >= prev); prev = d }
        assertEquals(30000, Ps5.retryDelayMs(100))
    }

    @Test fun hex() {
        assertEquals("00ff10", Ps5.hex(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), Ps5.unhex("00ff10"))
        assertNull(Ps5.unhex("0"))
        assertNull(Ps5.unhex("zz"))
    }
}

class YuvTest {
    @Test fun grayRespectsStride() {
        val y = byteArrayOf(10, 20, 99, 30, 40, 99)   // 2×2, řádek dlouhý 3
        val out = IntArray(4)
        Yuv.grayToArgb(y, 3, 2, 2, out)
        assertEquals(0xFF0A0A0A.toInt(), out[0])
        assertEquals(0xFF141414.toInt(), out[1])
        assertEquals(0xFF1E1E1E.toInt(), out[2])
        assertEquals(0xFF282828.toInt(), out[3])
    }

    @Test fun whiteAndBlackSurviveRoundTrip() {
        val w = 4; val h = 2
        val argb = IntArray(w * h) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val y = ByteArray(w * h); val u = ByteArray(2); val v = ByteArray(2)
        Yuv.fromArgb(argb, w, h, y, u, v)
        assertEquals(235, y[0].toInt() and 0xFF)   // bílá
        assertEquals(16, y[1].toInt() and 0xFF)    // černá
        val white = Yuv.pixel(235, 128, 128)
        val black = Yuv.pixel(16, 128, 128)
        assertTrue((white and 0xFF) >= 250)
        assertEquals(0, black and 0xFFFFFF)
    }

    @Test fun colourKeptRoughly() {
        val argb = IntArray(4) { 0xFFC83232.toInt() }   // červená
        val y = ByteArray(4); val u = ByteArray(1); val v = ByteArray(1)
        Yuv.fromArgb(argb, 2, 2, y, u, v)
        val p = Yuv.pixel(y[0].toInt() and 0xFF, u[0].toInt() and 0xFF, v[0].toInt() and 0xFF)
        assertTrue(Math.abs(((p shr 16) and 0xFF) - 0xC8) < 8)
        assertTrue(Math.abs(((p shr 8) and 0xFF) - 0x32) < 8)
        assertTrue(Math.abs((p and 0xFF) - 0x32) < 8)
    }
}

class DemoGameTest {
    @Test fun timelineFollowsLines() {
        assertEquals("Dutch: We need money, Arthur.", DemoGame.subtitleAt(0))
        assertEquals("Dutch: We need money, Arthur.", DemoGame.subtitleAt(5999))
        assertEquals("", DemoGame.subtitleAt(6000))
        assertEquals(DemoGame.lines[2], DemoGame.subtitleAt(8000))
    }

    @Test fun timelineRepeats() {
        assertEquals(32000, DemoGame.cycleMs)
        assertEquals(DemoGame.subtitleAt(100), DemoGame.subtitleAt(100 + DemoGame.cycleMs * 7))
        for (t in 0 until DemoGame.cycleMs step 500) assertTrue(DemoGame.indexAt(t) in DemoGame.lines.indices)
    }
}
