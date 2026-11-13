package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class AutoZoneTest {
    @Test fun scansWholeScreenAtStart() {
        val a = AutoZone()
        assertNull(a.learned)
        assertEquals(AutoZone.SCAN, a.current)
    }

    @Test fun learnsBottomSubtitlesAfterThreeHits() {
        val a = AutoZone()
        assertFalse(a.onSubtitle(0.86f, 0.90f, 1000))
        assertTrue(a.onSubtitle(0.84f, 0.92f, 2000))        // dvouřádkový
        a.onSubtitle(0.86f, 0.90f, 3000)
        val z = a.learned!!
        assertTrue("pás obsahuje titulky", z.top <= 0.84f && z.bottom >= 0.92f)
        assertTrue("pás je úzký", z.height < 0.35f)
        assertTrue(z.height >= AutoZone.MIN_HEIGHT - 0.001f)
        assertTrue(z.top >= 0f && z.bottom <= 1f)
    }

    @Test fun learnsTopSubtitlesToo() {
        val a = AutoZone()
        repeat(3) { a.onSubtitle(0.06f, 0.10f, it * 1000L) }
        val z = a.learned!!
        assertEquals(0f, z.top, 0.001f)
        assertTrue(z.bottom < 0.4f)
    }

    @Test fun smallJitterDoesNotKeepChangingZone() {
        val a = AutoZone()
        repeat(3) { a.onSubtitle(0.86f, 0.90f, it * 1000L) }
        val z = a.learned
        assertFalse(a.onSubtitle(0.865f, 0.905f, 5000))
        assertEquals(z, a.learned)
    }

    @Test fun oneStrayTextDoesNotMoveZone() {
        val a = AutoZone()
        repeat(3) { a.onSubtitle(0.86f, 0.90f, it * 1000L) }
        val z = a.learned
        assertFalse(a.onSubtitle(0.10f, 0.14f, 5000))       // např. název mise nahoře (našlo by se jen při hledání)
        assertEquals(z, a.learned)
    }

    @Test fun movesWhenSubtitlesReallyMove() {
        val a = AutoZone()
        repeat(3) { a.onSubtitle(0.86f, 0.90f, it * 1000L) }
        var changed = false
        repeat(3) { changed = a.onSubtitle(0.08f, 0.12f, 10_000L + it) || changed }
        assertTrue(changed)
        assertTrue(a.learned!!.bottom < 0.4f)
    }

    @Test fun rescansAfterLongSilence() {
        val a = AutoZone(rescanAfterMs = 120_000)
        repeat(3) { a.onSubtitle(0.86f, 0.90f, it * 1000L) }
        assertFalse(a.onTick(60_000))
        assertNotNull(a.learned)
        assertTrue(a.onTick(130_000))
        assertNull(a.learned)
        assertEquals(AutoZone.SCAN, a.current)
        assertFalse(a.onTick(131_000))
    }

    @Test fun ignoresNonsenseBounds() {
        val a = AutoZone()
        repeat(5) { assertFalse(a.onSubtitle(-1f, -1f, 1)) }
        repeat(5) { assertFalse(a.onSubtitle(0.05f, 0.9f, 1)) }   // "titulek" přes celou obrazovku
        assertNull(a.learned)
    }
}
