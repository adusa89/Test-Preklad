package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class GlossaryTest {
    @Test fun namesKeptTermsTranslated() {
        val g = Glossary(names = listOf("Kratos", "Atreus"), terms = mapOf("bounty" to "odměna"))
        val p = g.prepare("Kratos, the Bounty is for Atreus.")
        assertEquals("[1], the [2] is for [3].", p.protectedText)
        assertEquals("Kratos, ta odměna je pro Atreus.", OfflinePrep.finish(p, "[1], ta [2] je pro [3]."))
    }

    @Test fun termsMatchWholeWordsOnly() {
        val g = Glossary(terms = mapOf("posse" to "tlupa"))
        assertEquals("He is possessed.", g.prepare("He is possessed.").protectedText)
        assertEquals("That [1] is fast.", g.prepare("That Posse is fast.").protectedText)
    }

    @Test fun sentenceOverrideIgnoresCaseAndPunctuation() {
        val g = Glossary(sentences = mapOf("Get on your horse." to "Nasedni na koně."))
        assertEquals("Nasedni na koně.", g.sentence("get on your horse"))
        assertEquals("Nasedni na koně.", g.translate("Get on your horse!") { error("nemá se volat překladač") })
        assertNull(g.sentence("Get off your horse."))
    }

    @Test fun pipelineFallsBackWhenTokensBroken() {
        val g = Glossary(names = listOf("Geralt"))
        val out = g.translate("Geralt is here.") { s -> if (s.contains("[")) "Někdo je tady." else "Geralt je tady." }
        assertEquals("Geralt je tady.", out)
    }

    @Test fun emptyGlossaryStillStandardizesSlang() {
        val g = Glossary()
        var sent = ""
        g.translate("We ain't goin' nowhere.") { sent = it; "x" }
        assertEquals("We are not going nowhere.", sent)
        assertEquals(0, g.size)
    }

    @Test fun userDictionaryRoundTrip() {
        val m = linkedMapOf("bounty" to "odměna", "Get lost!" to "Zmiz!")
        val back = Glossary.parseUser(Glossary.serializeUser(m))
        assertEquals(m, back)
        assertTrue(Glossary.parseUser(null).isEmpty())
        assertTrue(Glossary.parseUser("bez tabulatoru\n\tprazdne\nx\t").isEmpty())
        assertTrue(Glossary.isSentence("Get lost!"))
        assertTrue(Glossary.isSentence("we ride at dawn boys"))
        assertFalse(Glossary.isSentence("bounty"))
        assertFalse(Glossary.isSentence("bounty hunter"))
    }
}

class GameProfilesTest {
    @Test fun defaultIsUniversalAndCyclesThroughAll() {
        assertEquals("all", GameProfiles.of(null).id)
        assertEquals("all", GameProfiles.of("neexistuje").id)
        var p = GameProfiles.of(null)
        val seen = HashSet<String>()
        repeat(GameProfiles.ALL.size) { seen += p.id; p = GameProfiles.next(p.id) }
        assertEquals(GameProfiles.ALL.size, seen.size)
        assertEquals("all", p.id)
    }

    @Test fun universalHasNamesFromManyGamesButNoCommonWords() {
        val u = GameProfiles.UNIVERSAL.names
        assertTrue(u.containsAll(listOf("Dutch", "Kratos", "Aloy", "Geralt", "Los Santos", "Ellie")))
        listOf("Bill", "Gray", "Jack", "Sam", "Wolves", "Peter").forEach { assertFalse(it, it in u) }
    }

    @Test fun universalDoesNotBreakOrdinaryEnglish() {
        val g = Glossary(names = GameProfiles.UNIVERSAL.names)
        listOf(
            "Pay the bill and ring the bell.",
            "The gray wolves ran across the badlands.",
            "Jack up the car, uncle.",
            "A rogue wave hit the ship.",
        ).forEach { assertEquals(it, g.prepare(it).protectedText) }
    }

    @Test fun gameProfileKeepsItsNames() {
        val g = Glossary(names = GameProfiles.of("gow").names)
        assertEquals("[1] and [2] reach [3].", g.prepare("Kratos and Atreus reach Midgard.").protectedText)
        assertEquals("[1], pay the [2].", Glossary(GameProfiles.of("rdr2").names).prepare("Dutch, pay the Bill.").protectedText)
    }

    @Test fun builtinPhrasesForCzechAndSlovakOnly() {
        assertEquals("Nasedni na koně.", Glossary(sentences = BuiltinPhrases.forLang(Lang.CS)).sentence("Get on your horse."))
        assertEquals("Nasadni na koňa.", Glossary(sentences = BuiltinPhrases.forLang(Lang.SK)).sentence("Get on your horse."))
        assertTrue(BuiltinPhrases.forLang(Lang.DE).isEmpty())
        assertEquals(BuiltinPhrases.forLang(Lang.CS).keys, BuiltinPhrases.forLang(Lang.SK).keys)
    }
}

class SupportTest {
    @Test fun diagLogKeepsLastLines() {
        DiagLog.clear()
        repeat(500) { DiagLog.add("radek $it", 1000L) }
        assertEquals(300, DiagLog.size())
        assertTrue(DiagLog.dump().endsWith("radek 499"))
        assertFalse(DiagLog.dump().contains("radek 100\n"))
        DiagLog.clear()
    }

    @Test fun updateCheck() {
        assertEquals(UpdateCheck.Remote(12, "0.12"), UpdateCheck.parse("12 0.12\n"))
        assertEquals(UpdateCheck.Remote(7, "7"), UpdateCheck.parse("7"))
        assertNull(UpdateCheck.parse("<html>Not Found</html>"))
        assertNull(UpdateCheck.parse(null)); assertNull(UpdateCheck.parse("-3 x")); assertNull(UpdateCheck.parse(""))
        assertTrue(UpdateCheck.isNewer(UpdateCheck.parse("12 0.12"), 10))
        assertFalse(UpdateCheck.isNewer(UpdateCheck.parse("10 0.10"), 10))
        assertFalse(UpdateCheck.isNewer(null, 10))
    }
}
