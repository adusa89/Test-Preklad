package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class TextLogicTest {

    private fun line(t: String, top: Int, h: Int = 30) = OcrLine(t, 100, top, 900, top + h)

    // ---------- clean ----------
    @Test fun clean_fixesPipeAndSpaces() {
        assertEquals("I don't know, Dutch.", TextLogic.clean("  |  don’t   know,  Dutch. "))
    }

    @Test fun clean_fixesLowercaseLAsPronoun() {
        assertEquals("I think we should go.", TextLogic.clean("l think we should go."))
        assertEquals("Well, I reckon so.", TextLogic.clean("Well, l reckon so."))
        // nesmí rozbít normální slova
        assertEquals("look at that", TextLogic.clean("look at that"))
        assertEquals("1899 was a year", TextLogic.clean("1899 was a year"))
    }

    // ---------- isUseful ----------
    @Test fun useful_acceptsRealSubtitles() {
        listOf(
            "We need money, Arthur.",
            "Dutch: Have some faith.",
            "Press X to hogtie",
            "Ride to Valentine.",
            "Yes.", "No!", "Hello",
            "Get on your horse."
        ).forEach { assertTrue("mělo projít: $it", TextLogic.isUseful(it)) }
    }

    @Test fun useful_rejectsHudAndNoise() {
        listOf(
            "$12.50", "12/24", "N", "E", "1:45 PM", "x3", "100%", "-", "...", "ii", "6", "|", "~~ ~", "a"
        ).forEach { assertFalse("nemělo projít: $it", TextLogic.isUseful(it)) }
    }

    // ---------- assemble ----------
    @Test fun assemble_joinsLinesOfBlockAndSortsBlocks() {
        val subtitle = OcrBlock(listOf(line("we need to get out of here", 640), line("Dutch: Arthur,", 600)))
        val hint = OcrBlock(listOf(line("Press X to mount", 50)))
        val p = TextLogic.assemble(listOf(subtitle, hint), minLineHeight = 12)
        assertEquals("Press X to mount\nDutch: Arthur, we need to get out of here", p.text)
        assertEquals(3, p.lineCount)
    }

    @Test fun assemble_dropsTinyTextAndHud() {
        val b = OcrBlock(listOf(line("Ride to Valentine.", 600), line("$45.20", 640), line("tiny tiny text", 680, h = 5)))
        assertEquals("Ride to Valentine.", TextLogic.assemble(listOf(b), 12).text)
    }

    @Test fun assemble_emptyWhenNothingUseful() {
        val b = OcrBlock(listOf(line("12", 600), line("N", 640)))
        val p = TextLogic.assemble(listOf(b), 12)
        assertEquals("", p.text)
        assertEquals(0, p.lineCount)
    }

    @Test fun assemble_mergesContinuationOfSpeakerLine() {
        val a = OcrBlock(listOf(line("Hosea: If we keep going like this", 600)))
        val b = OcrBlock(listOf(line("we will all hang.", 640)))
        assertEquals("Hosea: If we keep going like this we will all hang.", TextLogic.assemble(listOf(a, b), 12).text)
    }

    // ---------- mluvčí ----------
    @Test fun speaker_detected() {
        assertEquals(Segment("Dutch", "We need money."), TextLogic.splitSpeaker("Dutch: We need money."))
        assertEquals(Segment("Uncle", "I got lumbago."), TextLogic.splitSpeaker("Uncle: I got lumbago."))
        assertEquals(Segment("Mary-Beth", "Hello."), TextLogic.splitSpeaker("Mary-Beth: Hello."))
        assertEquals(Segment("Sean Macguire", "Ha!"), TextLogic.splitSpeaker("Sean Macguire: Ha!"))
    }

    @Test fun speaker_notDetectedForHintsAndPlainText() {
        assertNull(TextLogic.splitSpeaker("Objective: Ride to camp.").speaker)
        assertNull(TextLogic.splitSpeaker("We ride at dawn.").speaker)
        assertNull(TextLogic.splitSpeaker("time: 10:45").speaker)
        assertNull(TextLogic.splitSpeaker("Tip: Press X").speaker)
    }

    @Test fun segments_multiLine() {
        val s = TextLogic.segments("Press X to mount\nDutch: Go!")
        assertEquals(2, s.size)
        assertNull(s[0].speaker)
        assertEquals("Dutch", s[1].speaker)
        assertEquals("Go!", s[1].body)
    }

    // ---------- podobnost ----------
    @Test fun similar_toleratesOcrJitter() {
        assertTrue(TextLogic.similar("We need money, Arthur.", "We need rnoney, Arthur."))
        assertTrue(TextLogic.similar("We need money, Arthur.", "We need money, Arthur"))
        assertTrue(TextLogic.similar("", ""))
    }

    @Test fun similar_distinguishesDifferentLines() {
        assertFalse(TextLogic.similar("We need money, Arthur.", "Get on your horse."))
        assertFalse(TextLogic.similar("Yes.", "No."))
        assertFalse(TextLogic.similar("", "Hello"))
        assertFalse(TextLogic.similar("Hello there", ""))
    }

    @Test fun levenshtein_basic() {
        assertEquals(3, TextLogic.levenshtein("kitten", "sitting"))
        assertEquals(0, TextLogic.levenshtein("abc", "abc"))
        assertEquals(3, TextLogic.levenshtein("", "abc"))
    }
}
