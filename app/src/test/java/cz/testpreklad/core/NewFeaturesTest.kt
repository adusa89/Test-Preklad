package cz.testpreklad.core

import cz.testpreklad.core.CaptureMonitor.Decision
import org.junit.Assert.*
import org.junit.Test

class SubtitleOnlyTest {
    private val zoneW = 1100
    private val center = 550        // střed obrazovky v souřadnicích oblasti
    private val tol = 130

    private fun l(t: String, left: Int, right: Int, top: Int) = OcrLine(t, left, top, right, top + 28)

    @Test fun controlPromptsRecognized() {
        listOf("Press X to mount", "Hold R2 to aim", "Tap @ for more", "Press [] to hogtie", "PRESS A TO CONTINUE",
            "Hold L2 + R2 to shoot", "Press (X) to skip").forEach {
            assertTrue("prompt: $it", TextLogic.isControlPrompt(it))
        }
        listOf("Hold on to me, Arthur.", "Press on, boys!", "Push them back!", "I'll hold it for you.",
            "Tap the barrel twice.", "Dutch: We need money.").forEach {
            assertFalse("replika: $it", TextLogic.isControlPrompt(it))
        }
    }

    @Test fun onlyCenteredLinesInSubtitleMode() {
        val blocks = listOf(
            OcrBlock(listOf(l("Dutch: We need money, Arthur.", 300, 800, 600))),   // vycentrovaný titulek
            OcrBlock(listOf(l("Inventory Satchel Weapons", 20, 380, 520))),       // menu vlevo
            OcrBlock(listOf(l("Ride to Valentine for supplies", 760, 1090, 660))), // hláška vpravo
            OcrBlock(listOf(l("Press X to mount", 430, 670, 700))),               // vycentrovaná nápověda
        )
        val p = TextLogic.assemble(blocks, 12, subtitlesOnly = true, screenCenterX = center, centerTolerance = tol)
        assertEquals("Dutch: We need money, Arthur.", p.text)
        assertEquals(600, p.top)
        assertEquals(628, p.bottom)
        val all = TextLogic.assemble(blocks, 12)
        assertEquals(4, all.lineCount)
    }

    @Test fun twoLineSubtitleBoundsCoverBothLines() {
        val blocks = listOf(OcrBlock(listOf(
            l("Arthur: I know, Dutch. I'll talk to", 260, 840, 600),
            l("Strauss about the debts.", 380, 720, 634),
        )))
        val p = TextLogic.assemble(blocks, 12, true, center, tol)
        assertEquals(600, p.top)
        assertEquals(662, p.bottom)
    }

    @Test fun emptyPickHasNoBounds() {
        val p = TextLogic.assemble(listOf(OcrBlock(listOf(l("Inventory", 10, 200, 500)))), 12, true, center, tol)
        assertEquals("", p.text); assertEquals(-1, p.top)
    }

    @Test fun simplifyRemovesDiacritics() {
        assertEquals("potrebujeme penize arthure", TextLogic.simplify("Potřebujeme peníze, Arthure!"))
        assertEquals("potrebujeme peniaze", TextLogic.simplify("Potrebujeme peniaze."))
    }

    @Test fun looseSimilarityRecognizesOwnTranslation() {
        val shown = "Dutch: Potřebujeme peníze, Arthure."
        assertTrue(TextLogic.looselySimilar("Dutch: Potrebujeme penize, Arthure", shown))
        assertTrue(TextLogic.looselySimilar("Potřebujeme peníze, Arthure.", shown))      // bez mluvčího
        assertTrue(TextLogic.looselySimilar("Dutch: Potrebujerne penize Arthurc", shown)) // chyby OCR
        assertFalse(TextLogic.looselySimilar("Dutch: We need money, Arthur.", shown))    // originál ≠ překlad
        assertFalse(TextLogic.looselySimilar("", shown))
    }
}

class CaptureMonitorTest {
    @Test fun normalUntilOwnTextSeen() {
        val m = CaptureMonitor(900)
        assertEquals(Decision.NORMAL, m.decide(0, barVisible = true))
        assertTrue(m.isOwnText("Potrebujeme penize Arthure", listOf("Potřebujeme peníze, Arthure.")))
        assertFalse(m.isOwnText("We need money, Arthur.", listOf("Potřebujeme peníze, Arthure.")))
        assertFalse(m.isOwnText("", listOf("x")))
        m.markCaptured()
        assertTrue(m.overlayCaptured)
    }

    @Test fun peeksAtIntervalOnlyWhenBarVisible() {
        val m = CaptureMonitor(900)
        m.markCaptured()
        assertEquals(Decision.NORMAL, m.decide(100, barVisible = false))
        assertEquals(Decision.PEEK, m.decide(1000, true))
        assertEquals(Decision.SKIP, m.decide(1300, true))
        assertEquals(Decision.SKIP, m.decide(1899, true))
        assertEquals(Decision.PEEK, m.decide(1900, true))
        assertEquals(Decision.NORMAL, m.decide(2000, false))
    }
}

class CoverLayoutTest {
    private val eps = 0.0001f

    @Test fun coverBarCenteredOnTextAndCoversIt() {
        val l = ScreenLayout()
        val tTop = 0.85f; val tBottom = 0.93f
        val h = maxOf(0.06f, l.coverHeight(tTop, tBottom))
        val top = l.coverTop(tTop, tBottom, h)
        assertTrue("pruh musí začínat nad textem", top <= tTop + eps)
        assertTrue("pruh musí končit pod textem", top + h >= tBottom - eps)
        assertEquals((tTop + tBottom) / 2, top + h / 2, eps)
    }

    @Test fun coverBarStaysOnScreen() {
        val l = ScreenLayout()
        val top = l.coverTop(0.95f, 0.99f, 0.2f)
        assertEquals(0.8f, top, eps)
        assertEquals(0f, l.coverTop(0.0f, 0.02f, 0.2f), eps)
    }

    @Test fun serializationKeepsCoverFlagAndReadsOldFormat() {
        val l = ScreenLayout(cover = false)
        assertEquals(l, ScreenLayout.parse(l.serialize()))
        assertTrue(ScreenLayout.parse("0.1,0.7,0.9,0.95,-1,0.8").cover)       // starý formát bez příznaku
        assertFalse(ScreenLayout.parse("0.1,0.7,0.9,0.95,-1,0.8,0").cover)
    }
}

class LangAndPromptTest {
    @Test fun langParsing() {
        assertEquals(Lang.SK, Lang.of("sk"))
        assertEquals(Lang.CS, Lang.of("xx"))
        assertEquals(Lang.CS, Lang.of(null))
    }


}

class UsageStatsTest {
    @Test fun countsAndRoundTrip() {
        val u = UsageStats()
        u.line(); u.line(); u.cacheHit()
        assertEquals(2, u.lines); assertEquals(1, u.cacheHits)
        assertEquals(u.serialize(), UsageStats.parse(u.serialize()).serialize())
        assertEquals("0,0", UsageStats.parse("rozbité").serialize())
        assertEquals("0,0", UsageStats.parse("1,-2").serialize())
        assertEquals("5,3", UsageStats.parse("5,3,9,9,9").serialize())   // starý formát s AI údaji
        u.reset(); assertEquals("0,0", u.serialize())
    }
}

class I18nTest {
    @Test fun everyKeyTranslatedInBothLanguages() {
        for (k in I18n.keys) {
            val cs = I18n.t(Lang.CS, k)
            val sk = I18n.t(Lang.SK, k)
            assertTrue("prázdné cs: $k", cs.isNotBlank())
            assertTrue("prázdné sk: $k", sk.isNotBlank())
        }
        assertEquals("neznámý_klíč", I18n.t(Lang.CS, "neznámý_klíč"))
    }

    @Test fun slovakDiffersFromCzech() {
        assertEquals("Domov", I18n.t(Lang.SK, "m_home"))
        assertEquals("Domů", I18n.t(Lang.CS, "m_home"))
    }
}

class PeekBehaviourTest {
    @Test fun hurryShortensWaitForNextPeek() {
        val m = CaptureMonitor(900)
        m.markCaptured()
        assertEquals(CaptureMonitor.Decision.PEEK, m.decide(1000, true))
        m.hurry(1100, quickMs = 250)
        assertEquals(CaptureMonitor.Decision.SKIP, m.decide(1200, true))
        assertEquals(CaptureMonitor.Decision.PEEK, m.decide(1350, true))   // 250 ms místo 900 ms
    }

    @Test fun hurryDoesNothingWithoutCapture() {
        val m = CaptureMonitor(900)
        m.hurry(0)
        assertEquals(CaptureMonitor.Decision.NORMAL, m.decide(10, true))
    }

    @Test fun shortOrIdenticalTextIsNotOwnText() {
        val m = CaptureMonitor()
        assertFalse(m.isOwnText("Arthur!", listOf("Arthur!")))                                   // krátké
        assertFalse(m.isOwnText("Mister Strauss, Valentine.", listOf("Mister Strauss, Valentine."),
            original = "Mister Strauss, Valentine."))                                            // shodné s originálem
        assertTrue(m.isOwnText("Potrebujeme penize Arthure", listOf("Potřebujeme peníze, Arthure."),
            original = "We need money, Arthur."))
    }

    /** Celý cyklus režimu "celá obrazovka": pruh přes titulky, nakukování, výměna repliky, konec. */
    @Test fun fullScreenCaptureCycle() {
        val m = CaptureMonitor(900)
        val t = SubtitleTracker()
        var shownCz = ""
        var now = 0L
        val decisions = ArrayList<CaptureMonitor.Decision>()
        // co OCR uvidí: pod pruhem originál, když pruh není skrytý (a snímek ho obsahuje) → náš český text
        var underBar = "We need money, Arthur."
        fun step() {
            val d = m.decide(now, shownCz.isNotEmpty())
            decisions += d
            if (d != CaptureMonitor.Decision.SKIP) {
                val ocr = if (shownCz.isNotEmpty() && d == CaptureMonitor.Decision.NORMAL) shownCz else underBar
                if (shownCz.isNotEmpty() && m.isOwnText(ocr, listOf(shownCz), t.shown)) m.markCaptured()
                else when (val a = t.onOcr(ocr)) {
                    is SubtitleTracker.Action.Show -> { t.confirmShown(a.text); shownCz = "CZ: přeloženo – ${a.text.length} znaků textu" }
                    SubtitleTracker.Action.Hide -> shownCz = ""
                    SubtitleTracker.Action.None -> if (t.isChanging) m.hurry(now)
                }
            }
            now += 300
        }
        repeat(3) { step() }                      // 2 snímky → zobrazení, 3. snímek přečte náš pruh
        assertTrue("pozná, že snímek obsahuje pruh", m.overlayCaptured)
        underBar = "Get on your horse, we ride now."
        val before = t.shown
        repeat(12) { step() }
        assertNotEquals(before, t.shown)
        assertEquals("Get on your horse, we ride now.", t.shown)
        underBar = ""
        repeat(12) { step() }
        assertEquals("", shownCz)                 // pruh zmizel, když titulek skončil
        assertTrue(decisions.contains(CaptureMonitor.Decision.PEEK))
        assertTrue(decisions.contains(CaptureMonitor.Decision.SKIP))
    }
}


class LatencyTest {
    @Test fun newSubtitleCanBePrefetchedAfterFirstRead() {
        val t = SubtitleTracker()
        assertEquals("", t.pendingText)
        assertEquals(SubtitleTracker.Action.None, t.onOcr("We need money, Arthur."))
        assertEquals("We need money, Arthur.", t.pendingText)       // překlad může začít už teď
        val a = t.onOcr("We need money, Arthur.")
        assertTrue(a is SubtitleTracker.Action.Show)
        t.confirmShown("We need money, Arthur.")
        assertEquals("", t.pendingText)
        t.onOcr("We need money, Arthur.")
        assertEquals("", t.pendingText)                              // už zobrazený text se nepřekládá znovu
    }

    @Test fun emptyScreenHasNothingPending() {
        val t = SubtitleTracker()
        t.onOcr("")
        assertEquals("", t.pendingText)
    }

    @Test fun peekWaitAdaptsToTv() {
        val m = CaptureMonitor()
        assertEquals(CaptureMonitor.MIN_WAIT, m.peekWaitMs)
        repeat(3) { m.onPeekResult(stillSawOwnBar = true) }
        assertEquals(CaptureMonitor.MIN_WAIT + 90, m.peekWaitMs)
        repeat(100) { m.onPeekResult(stillSawOwnBar = true) }
        assertEquals(CaptureMonitor.MAX_WAIT, m.peekWaitMs)
        repeat(100) { m.onPeekResult(stillSawOwnBar = false) }
        assertEquals(CaptureMonitor.MIN_WAIT, m.peekWaitMs)
    }

    /** Simulace časové osy: kontrola každých 100 ms, titulek se objeví v čase 1000 ms. */
    @Test fun subtitleShownWithin250ms() {
        val t = SubtitleTracker()
        var shownAt = -1L
        var time = 0L
        while (time < 3000 && shownAt < 0) {
            val text = if (time >= 1000) "Dutch: We need money, Arthur." else ""
            val a = t.onOcr(text)
            if (a is SubtitleTracker.Action.Show) { t.confirmShown(a.text); shownAt = time }
            time += 100
        }
        val delay = shownAt - 1000
        assertTrue("zpoždění $delay ms", delay in 0..250)
    }
}

class PerfTest {
    @Test fun weakTvGetsEcoMode() {
        assertEquals(Perf(854, 220, true), Perf.forDevice(1024, false))
        assertEquals(Perf(854, 220, true), Perf.forDevice(4096, true))      // systém hlásí "low RAM"
        assertEquals(Perf(1024, 150, true), Perf.forDevice(2048, false))
        assertEquals(Perf(1280, 100, false), Perf.forDevice(3072, false))
        assertEquals(Perf(1280, 100, false), Perf.forDevice(0, false))      // neznámá paměť
    }
}

class AllLanguagesTest {
    @Test fun everyLanguageHasEveryText() {
        for (lang in Lang.values()) for (k in I18n.keys) {
            val v = I18n.t(lang, k)
            assertTrue("$lang/$k prázdné", v.isNotBlank())
            if (lang != Lang.CS && lang != Lang.SK) assertTrue("$lang/$k chybí překlad", I18n.hasOwn(lang, k))
        }
    }

    @Test fun noExtraKeysAndPlaceholdersKept() {
        for (lang in Lang.values()) {
            assertTrue("$lang %d", I18n.t(lang, "lic_trial").contains("%d"))
            assertTrue("$lang klíče navíc: " + (I18n.ownKeys(lang) - I18n.keys), I18n.keys.containsAll(I18n.ownKeys(lang)))
        }
    }

    @Test fun menuReallyDiffersByLanguage() {
        assertEquals("Ustawienia", I18n.t(Lang.PL, "m_settings"))
        assertEquals("Einstellungen", I18n.t(Lang.DE, "m_settings"))
        assertEquals("Beállítások", I18n.t(Lang.HU, "m_settings"))
        assertEquals("Налаштування", I18n.t(Lang.UK, "m_settings"))
        assertEquals("Ajustes", I18n.t(Lang.ES, "m_settings"))
        assertEquals("Réglages", I18n.t(Lang.FR, "m_settings"))
        assertEquals("Impostazioni", I18n.t(Lang.IT, "m_settings"))
    }
}
