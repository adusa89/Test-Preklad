package cz.testpreklad.core

import cz.testpreklad.core.HdmiProbe.Verdict
import org.junit.Assert.*
import org.junit.Test

class HdmiProbeTest {
    private val black = IntArray(32 * 18)
    private val game = IntArray(32 * 18) { (it * 7) % 200 + 20 }          // běžná scéna
    private val night = IntArray(32 * 18) { if (it % 3 == 0) 40 else 5 } // noční scéna: třetina obrazu viditelná
    private val blackWithBar = IntArray(32 * 18) { if (it in 450..520) 200 else 0 } // černo + náš pruh/hlášení TV

    @Test fun contentRatio() {
        assertEquals(0f, HdmiProbe.contentRatio(black), 0.001f)
        assertEquals(1f, HdmiProbe.contentRatio(game), 0.001f)
        assertTrue(HdmiProbe.contentRatio(night) > HdmiProbe.CONTENT_MIN)
        assertTrue(HdmiProbe.contentRatio(blackWithBar) < HdmiProbe.CONTENT_MIN)
        assertEquals(0f, HdmiProbe.contentRatio(IntArray(0)), 0f)
    }

    @Test fun unknownUntilEnoughFrames() {
        val p = HdmiProbe()
        repeat(9) { p.add(0f) }
        assertEquals(Verdict.UNKNOWN, p.verdict)
        p.add(0f)
        assertEquals(Verdict.BLOCKED, p.verdict)
    }

    @Test fun worksWhenGameVisible() {
        val p = HdmiProbe()
        repeat(4) { p.add(HdmiProbe.contentRatio(game)) }
        assertEquals(Verdict.UNKNOWN, p.verdict)
        p.add(HdmiProbe.contentRatio(night))
        assertEquals(Verdict.WORKS, p.verdict)
    }

    @Test fun blockedEvenWithOverlayAndTvBanner() {
        val p = HdmiProbe()
        repeat(50) { p.add(HdmiProbe.contentRatio(blackWithBar)) }
        assertEquals(Verdict.BLOCKED, p.verdict)
    }

    @Test fun workingTvWithSomeBlackFramesStillWorks() {
        val p = HdmiProbe()
        repeat(30) { p.add(0f) }                 // např. načítací obrazovka hry
        assertEquals(Verdict.BLOCKED, p.verdict)
        repeat(5) { p.add(1f) }                  // pak se objeví hra
        assertEquals(Verdict.WORKS, p.verdict)
        p.reset()
        assertEquals(Verdict.UNKNOWN, p.verdict)
    }
}
