package cz.testpreklad.core

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
        assertEquals(Perf(1280, 250, true), Perf.forDevice(1024, false))
        assertEquals(Perf(1280, 250, true), Perf.forDevice(4096, true))      // systém hlásí "low RAM"
        assertEquals(Perf(1280, 150, true), Perf.forDevice(2048, false))
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


class SlowDeviceTest {
    @Test fun completeSentenceShownAfterOneReadOnSlowDevice() {
        val t = SubtitleTracker()
        assertTrue(t.onOcr("Dutch: We need money, Arthur.", slowDevice = true) is SubtitleTracker.Action.Show)
    }

    @Test fun incompleteTextStillWaitsOnSlowDevice() {
        val t = SubtitleTracker()
        assertEquals(SubtitleTracker.Action.None, t.onOcr("Arthur: L roakon IAIO oint ann find noth", slowDevice = true))
        assertEquals(SubtitleTracker.Action.None, t.onOcr("Dut", slowDevice = true))
    }

    @Test fun slowDeviceHidesAfterOneEmptyRead() {
        val t = SubtitleTracker()
        val a = t.onOcr("Ride on, boys.", slowDevice = true) as SubtitleTracker.Action.Show
        t.confirmShown(a.text)
        assertEquals(SubtitleTracker.Action.Hide, t.onOcr("", slowDevice = true))
    }

    @Test fun fastDeviceUnchanged() {
        val t = SubtitleTracker()
        assertEquals(SubtitleTracker.Action.None, t.onOcr("Dutch: We need money, Arthur."))
        assertTrue(t.onOcr("Dutch: We need money, Arthur.") is SubtitleTracker.Action.Show)
    }

    @Test fun looksComplete() {
        assertTrue(TextLogic.looksComplete("We ride at dawn."))
        assertTrue(TextLogic.looksComplete("Where is he?"))
        assertFalse(TextLogic.looksComplete("We ride at"))
        assertFalse(TextLogic.looksComplete("No."))
    }
}


class FeedbackLoopTest {
    @Test fun ownTranslationIsNeverEnglish() {
        assertTrue(TextLogic.looksForeign("Dutch: Potřebujeme peníze, Arthur."))
        assertTrue(TextLogic.looksForeign("Arthur: Myslím, že nebudeme nic najít v Valentine."))
        assertTrue(TextLogic.looksForeign("Нам потрібні гроші"))
        assertTrue(TextLogic.looksForeign("Wir brauchen Geld, und zwar schön schnell für später."))
    }

    @Test fun englishSubtitlesAreNotForeign() {
        listOf(
            "Dutch: We need money, Arthur.",
            "Arthur: I reckon we ain't gonna find nothin' in Valentine.",
            "Meet me at the café.",          // jedno písmeno s čárkou nevadí
            "Press X to mount", "",
        ).forEach { assertFalse(it, TextLogic.looksForeign(it)) }
    }

}
