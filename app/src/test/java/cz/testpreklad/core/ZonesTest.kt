package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class ZonesTest {
    private val eps = 0.0001f

    @Test fun moveStaysOnScreen() {
        val z = Zone(0.1f, 0.7f, 0.9f, 0.95f)
        val down = z.moved(0f, 0.5f)
        assertEquals(1f, down.bottom, eps)
        assertEquals(z.height, down.height, eps)
        val left = z.moved(-1f, 0f)
        assertEquals(0f, left.left, eps)
        assertEquals(z.width, left.width, eps)
    }

    @Test fun resizeHasMinimumAndStaysOnScreen() {
        val z = Zone(0.4f, 0.4f, 0.6f, 0.6f)
        val tiny = z.resized(-1f, -1f)
        assertEquals(Zone.MIN, tiny.width, eps)
        assertEquals(Zone.MIN, tiny.height, eps)
        val big = Zone(0.8f, 0.8f, 1f, 1f).resized(0.5f, 0.5f)
        assertTrue(big.right <= 1f + eps && big.bottom <= 1f + eps && big.left >= -eps && big.top >= -eps)
        assertEquals(0.7f, big.width, eps)
    }

    @Test fun normalizedFixesBrokenValues() {
        val z = Zone(0.9f, 1.2f, 0.1f, -0.3f).normalized()
        assertTrue(z.left < z.right && z.top < z.bottom)
        assertTrue(z.left >= 0f && z.right <= 1f && z.top >= 0f && z.bottom <= 1f)
        val flat = Zone(1f, 1f, 1f, 1f).normalized()
        assertTrue(flat.width >= Zone.MIN - eps && flat.right <= 1f + eps)
    }

    @Test fun autoBarSitsAboveReadZone() {
        val l = ScreenLayout()
        val top = l.barTop(0.08f)
        assertTrue(top + 0.08f <= l.read.top + eps)
    }

    @Test fun barNeverCoversReadZone() {
        val read = Zone(0.1f, 0.7f, 0.9f, 0.95f)
        // uživatel chtěl pruh přímo přes titulky hry
        for (center in listOf(0.72f, 0.8f, 0.83f, 0.9f, 0.94f)) {
            val l = ScreenLayout(read, center, 0.8f)
            val h = 0.1f
            val t = l.barTop(h)
            val overlaps = t + h > read.top && t < read.bottom
            assertFalse("center=$center top=$t", overlaps)
            assertTrue(t >= 0f && t + h <= 1f + eps)
        }
    }

    @Test fun barGoesBelowWhenNoRoomAbove() {
        val read = Zone(0.1f, 0.02f, 0.9f, 0.2f)
        val l = ScreenLayout(read, 0.1f, 0.8f)
        val t = l.barTop(0.1f)
        assertTrue(t >= read.bottom)
    }

    @Test fun customPositionRespectedWhenFree() {
        val l = ScreenLayout(barCenterY = 0.3f)
        assertEquals(0.25f, l.barTop(0.1f), eps)
    }

    @Test fun serializeRoundTrip() {
        val l = ScreenLayout(Zone(0.2f, 0.6f, 0.8f, 0.9f), 0.4f, 0.7f)
        assertEquals(l, ScreenLayout.parse(l.serialize()))
        assertEquals(ScreenLayout(), ScreenLayout.parse(ScreenLayout().serialize()))
    }

    @Test fun parseGarbageGivesDefaults() {
        assertEquals(ScreenLayout(), ScreenLayout.parse(null))
        assertEquals(ScreenLayout(), ScreenLayout.parse(""))
        assertEquals(ScreenLayout(), ScreenLayout.parse("a,b,c"))
        assertEquals(ScreenLayout(), ScreenLayout.parse("1,2,3"))
        assertEquals(ScreenLayout(), ScreenLayout.parse("NaN,0,1,1,0,0.5"))
        val clamped = ScreenLayout.parse("0.1,0.7,0.9,0.95,5,9")
        assertEquals(0.98f, clamped.barCenterY, eps)
        assertEquals(1f, clamped.barWidth, eps)
    }

    @Test fun positionPresetsCycle() {
        var l = ScreenLayout()
        assertEquals(ScreenLayout.Position.OVER, l.position)
        l = l.nextPosition(); assertEquals(ScreenLayout.Position.ABOVE, l.position); assertFalse(l.cover)
        l = l.nextPosition(); assertEquals(ScreenLayout.Position.TOP, l.position)
        assertTrue("pruh je nahoře", l.barTop(0.1f) < 0.1f)
        l = l.nextPosition(); assertEquals(ScreenLayout.Position.OVER, l.position); assertTrue(l.cover)
    }

    @Test fun customPositionFromCalibrationSurvivesAndCyclesBack() {
        val l = ScreenLayout(cover = false, barCenterY = 0.4f)
        assertEquals(ScreenLayout.Position.CUSTOM, l.position)
        assertEquals(l, ScreenLayout.parse(l.serialize()))
        assertEquals(ScreenLayout.Position.OVER, l.nextPosition().position)
    }

    @Test fun positionKeepsReadZoneAndWidth() {
        val l = ScreenLayout(Zone(0.2f, 0.6f, 0.8f, 0.9f), barWidth = 0.6f).nextPosition().nextPosition()
        assertEquals(Zone(0.2f, 0.6f, 0.8f, 0.9f), l.read)
        assertEquals(0.6f, l.barWidth, 0.0001f)
    }
}
