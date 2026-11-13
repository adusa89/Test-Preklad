package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class SourceTest {
    @Test fun parsingAndCycle() {
        assertEquals(Source.HDMI, Source.of(null))
        assertEquals(Source.USB, Source.of("usb"))
        assertEquals(Source.HDMI, Source.of("nesmysl"))
        assertEquals(Source.USB, Source.HDMI.next())
        assertEquals(Source.REMOTE_PLAY, Source.USB.next())
        assertEquals(Source.HDMI, Source.REMOTE_PLAY.next())
        assertTrue(Source.HDMI.direct && Source.USB.direct)
        assertFalse(Source.REMOTE_PLAY.direct)
    }

    @Test fun capturePrefers720p() {
        val sizes = listOf(640 to 480, 1920 to 1080, 1280 to 720, 3840 to 2160)
        assertEquals(1280 to 720, Source.pickCaptureSize(sizes))
        assertEquals(1920 to 1080, Source.pickCaptureSize(listOf(1920 to 1080, 3840 to 2160, 640 to 480)))
        assertEquals(800 to 600, Source.pickCaptureSize(listOf(640 to 480, 800 to 600)))   // jen malé → největší blízké
        assertNull(Source.pickCaptureSize(emptyList()))
    }
}
