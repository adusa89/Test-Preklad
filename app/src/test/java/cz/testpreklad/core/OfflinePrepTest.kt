package cz.testpreklad.core

import org.junit.Assert.*
import org.junit.Test

class OfflinePrepTest {

    @Test fun slangBecomesStandardEnglish() {
        assertEquals("Well, I think we are not going to make it.", OfflinePrep.normalize("Well, I reckon we ain't gonna make it."))
        assertEquals("I am not scared. He is not either. You all are not.", OfflinePrep.normalize("I ain't scared. He ain't either. Y'all ain't."))
        assertEquals("Ain't" .let { OfflinePrep.normalize("$it it grand?") }, "Is not it grand?")
        assertEquals("You all better get riding.", OfflinePrep.normalize("Y'all better get ridin'."))
        assertEquals("Let me tell you something, man.", OfflinePrep.normalize("Lemme tell ya somethin', fella."))
        assertEquals("Get out of here, old man!", OfflinePrep.normalize("Get outta here, ol' man!"))
        assertEquals("Shoot them, because I said so.", OfflinePrep.normalize("Shoot 'em, 'cause I said so."))
        assertEquals("Yes.", OfflinePrep.normalize("Yep."))
    }

    @Test fun normalizeDoesNotBreakNormalWords() {
        listOf(
            "We need money, Arthur.",
            "Galloping across the valley.",       // "gal" uvnitř slova
            "The yacht is gone.",                 // "ya" uvnitř slova
            "Reckoning day is coming.",            // "reckon" uvnitř slova
            "I'm going in.",                       // "in" bez apostrofu
            "Rock 'n' roll",
        ).forEach { assertEquals(it, OfflinePrep.normalize(it)) }
    }

    @Test fun capitalizationKept() {
        assertEquals("Hello, partner.", OfflinePrep.normalize("Howdy, partner."))
        assertEquals("hello, partner.", OfflinePrep.normalize("howdy, partner."))
    }

    @Test fun namesProtectedWithTokens() {
        val p = OfflinePrep.prepare("Dutch wants us in Strawberry, then Valentine. Dutch is sure.")
        assertEquals("[1] wants us in [2], then [3]. [1] is sure.", p.protectedText)
        assertEquals(listOf("Dutch", "Strawberry", "Valentine"), p.names)
    }

    @Test fun multiWordNamesBeforeShortOnes() {
        val p = OfflinePrep.prepare("Go to Saint Denis and find Van der Linde.")
        assertEquals("Go to [1] and find [2].", p.protectedText)
        assertEquals(listOf("Saint Denis", "Van der Linde"), p.names)
    }

    @Test fun namesInsideOtherWordsUntouched() {
        val p = OfflinePrep.prepare("Billy and Johnny rode to Rhodes Island.")
        assertEquals(listOf("Rhodes"), p.names)
        assertTrue(p.protectedText.startsWith("Billy and Johnny"))
    }

    @Test fun finishRestoresNames() {
        val p = OfflinePrep.prepare("Dutch wants us in Strawberry.")
        assertEquals("Dutch nás chce v Strawberry.", OfflinePrep.finish(p, "[1] nás chce v [2]."))
        assertEquals("Dutch nás chce v Strawberry.", OfflinePrep.finish(p, "[ 1 ] nás chce v [2 ]."))   // mezery navíc
    }

    @Test fun finishFailsWhenTokensBroken() {
        val p = OfflinePrep.prepare("Dutch wants us in Strawberry.")
        assertNull(OfflinePrep.finish(p, "1 nás chce v [2]."))       // chybí značka
        assertNull(OfflinePrep.finish(p, "[1] nás chce v [3]."))      // špatná značka
    }

    @Test fun noNamesMeansNoTokens() {
        val p = OfflinePrep.prepare("Get on your horse.")
        assertEquals(p.plain, p.protectedText)
        assertEquals("Nasedni.", OfflinePrep.finish(p, "Nasedni."))
    }

    @Test fun pipelineUsesTokensWhenTheySurvive() {
        val calls = ArrayList<String>()
        val out = OfflinePrep.translate("Dutch ain't happy.") { s -> calls += s; s.replace("is not happy", "není šťastný") }
        assertEquals("Dutch není šťastný.", out)
        assertEquals(listOf("[1] is not happy."), calls)
    }

    @Test fun themBeforeNounBecomesThose() {
        val p = OfflinePrep.prepare("Before them Pinkertons and them boys show up, shoot them.")
        assertEquals("Before those [1] and those boys show up, shoot them.", p.protectedText)
        assertEquals("Before those Pinkertons and those boys show up, shoot them.", p.plain)
        assertEquals("Tell them we ride.", OfflinePrep.prepare("Tell them we ride.").plain)   // běžné "them" zůstane
        assertEquals("Tell them Dutch said so.", OfflinePrep.prepare("Tell them Dutch said so.").plain)  // jméno, ne množné číslo
        assertEquals("Kill those O'Driscolls!", OfflinePrep.prepare("Kill them O'Driscolls!").plain)
    }

    @Test fun pipelineFallsBackWhenTokensDestroyed() {
        val calls = ArrayList<String>()
        val out = OfflinePrep.translate("Dutch ain't happy.") { s ->
            calls += s
            if (s.contains("[")) "Někdo není šťastný." else "Holanďan není šťastný."
        }
        assertEquals("Holanďan není šťastný.", out)          // záloha: nic se neztratí
        assertEquals(2, calls.size)
        assertEquals("Dutch is not happy.", calls[1])
    }

    @Test fun pipelineHandlesTranslatorFailure() {
        assertNull(OfflinePrep.translate("Dutch.") { null })
        assertEquals("Ahoj.", OfflinePrep.translate("Hello.") { "Ahoj." })
    }
}
