package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class TranslationCacheTest {

    @Test fun roundTripWithSpecialChars() {
        val c = TranslationCache()
        c.put("Hello\tthere\nfriend \\ ok", "Ahoj\ttam\nkámo \\ jo")
        c.put("Dutch: \"Faith!\"", "Dutch: „Víru!“ ěščřžýáíé")
        val c2 = TranslationCache()
        c2.load(c.dump())
        assertEquals("Ahoj\ttam\nkámo \\ jo", c2.get("Hello\tthere\nfriend \\ ok".let { TranslationCache.normalize(it) }))
        assertEquals("Dutch: „Víru!“ ěščřžýáíé", c2.get("Dutch: \"Faith!\""))
        assertEquals(2, c2.size())
    }

    @Test fun normalizesWhitespaceInKeys() {
        val c = TranslationCache()
        c.put("  We   need money. ", "Potřebujeme peníze.")
        assertEquals("Potřebujeme peníze.", c.get("We need money."))
    }

    @Test fun lruEvictsOldest() {
        val c = TranslationCache(maxSize = 3)
        c.put("a1", "1"); c.put("a2", "2"); c.put("a3", "3")
        c.get("a1")             // a1 je teď nejčerstvější
        c.put("a4", "4")
        assertNull(c.get("a2"))
        assertNotNull(c.get("a1"))
        assertEquals(3, c.size())
    }

    @Test fun dirtyCounter() {
        val c = TranslationCache()
        c.put("x", "y"); c.put("x", "y")
        assertEquals(1, c.dirty)
        c.dump()
        assertEquals(0, c.dirty)
    }

    @Test fun ignoresEmptyAndCorruptLines() {
        val c = TranslationCache()
        c.put("", "něco"); c.put("něco", " ")
        assertEquals(0, c.size())
        c.load("bez tabulatoru\n\t prazdny klic\nok\tdobře\n\n")
        assertEquals(1, c.size())
        assertEquals("dobře", c.get("ok"))
    }
}


class FrameSignatureTest {
    @Test fun identicalIsZeroDifferentIsLarge() {
        val white = IntArray(64) { 0xFFFFFFFF.toInt() }
        val black = IntArray(64) { 0xFF000000.toInt() }
        val lw = FrameSignature.luminance(white)
        val lb = FrameSignature.luminance(black)
        assertEquals(255, lw[0])
        assertEquals(0, lb[0])
        assertEquals(0f, FrameSignature.diff(lw, lw), 0.001f)
        assertEquals(255f, FrameSignature.diff(lw, lb), 0.001f)
        assertEquals(Float.MAX_VALUE, FrameSignature.diff(lw, IntArray(3)), 0f)
    }

    @Test fun brightMaskIgnoresDarkSceneChanges() {
        val a = FrameSignature.brightMask(intArrayOf(10, 50, 250, 90))
        val b = FrameSignature.brightMask(intArrayOf(80, 20, 240, 30))
        assertEquals(0f, FrameSignature.diff(a, b), 0.001f)
    }
}

class SubtitleHistoryTest {
    @Test fun newestFirstAndDedup() {
        SubtitleHistory.clear()
        SubtitleHistory.add("Hello.", "Ahoj.", 1)
        SubtitleHistory.add("Hello.", "Ahoj.", 2)
        SubtitleHistory.add("Bye now.", "Zatím.", 3)
        val all = SubtitleHistory.all()
        assertEquals(2, all.size)
        assertEquals("Bye now.", all[0].original)
        SubtitleHistory.clear()
    }
}
