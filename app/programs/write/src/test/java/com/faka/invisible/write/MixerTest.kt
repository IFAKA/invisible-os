package com.faka.invisible.write

import org.junit.Assert.*
import org.junit.Test

class MixerTest {
    private val req = Request(Frame(), emptyList(), "")
    private val lm = listOf("hello" to Math.log(0.6), "hi" to Math.log(0.3), "home" to Math.log(0.1))

    @Test fun noAnswerLeavesTheWordModelAsIs() {
        val m = Mixer().mix(req, lm)
        assertEquals(lm, m.words); assertTrue(m.phrases.isEmpty())
    }

    @Test fun sharesStartEqualAndTheMixIsTheSumOfShareTimesChance() {
        val x = Mixer()
        x.set("a", "", listOf(Guess("how are you", 3.0), Guess("see you", 1.0)))
        x.set("b", "", listOf(Guess("see you", 1.0)))
        val p = x.mix(req, lm).phrases.toMap()
        // a: how 0.75, see 0.25; b: see 1.0; shares 0.5 each
        assertEquals(0.375, p.getValue("how are you"), 1e-9)
        assertEquals(0.625, p.getValue("see you"), 1e-9)
        assertEquals(listOf("see you", "how are you"), x.mix(req, lm).phrases.map { it.first })
    }

    @Test fun wordsFromASourceJoinTheWordModelByShare() {
        val x = Mixer()
        x.set("lists", "h", listOf(Guess("hugo", 1.0), Guess("anna", 1.0)))   // "anna" does not fit the prefix h
        val r = x.mix(Request(Frame(), emptyList(), "h"), lm)
        val p = r.words.filter { it.first.startsWith("h") }.associate { it.first to Math.exp(it.second) }
        assertEquals(0.5 * 0.6 + 0, p.getValue("hello"), 1e-9)
        assertEquals(0.5, p.getValue("hugo"), 1e-9)
        assertEquals(1.0, p.values.sum(), 1e-9)
        assertFalse(r.words.any { it.first == "anna" })
    }

    @Test fun anAnswerForAnotherTextIsNotUsed() {
        val x = Mixer(); x.set("complete", "hay", listOf(Guess("how are you")))
        assertTrue(x.mix(Request(Frame(), emptyList(), "ha"), lm).phrases.isEmpty())
        assertEquals(listOf("how are you"), x.mix(Request(Frame(), emptyList(), "hay"), lm).phrases.map { it.first })
    }

    @Test fun picksMoveTrustToTheSourcesThatRankedTheItem() {
        val store = BucketStore(emptyList())
        val x = Mixer(store::trust, store::scored)
        repeat(6) {
            x.set("good", "", listOf(Guess("pick me"), Guess("other")))
            x.set("bad", "", listOf(Guess("no way"), Guess("not now"), Guess("zero two"), Guess("pick me")))   // rank 3: outside the top 3
            x.mix(req, lm)
            assertEquals("good" to 0, x.picked("pick me", false))
        }
        assertTrue(store.trust("src:good") > 0.8); assertTrue(store.trust("src:bad") < 0.2)
        x.set("good", "", listOf(Guess("pick me"))); x.set("bad", "", listOf(Guess("no way")))
        val p = x.mix(req, lm).phrases.toMap()
        assertTrue(p.getValue("pick me") > 4 * p.getValue("no way"))
        assertEquals(null, x.picked("never offered", false))
    }

    @Test fun hayFitsTheFirstLettersOfHowAreYou() {
        val x = Mixer()
        x.set("complete", "hay", listOf(Guess("how are you", 5.0), Guess("hayden", 1.0), Guess("what is up", 1.0)))
        val r = x.mix(Request(Frame(), emptyList(), "hay"), lm)
        assertEquals(listOf("how are you"), r.phrases.map { it.first })   // "what is up" does not fit
        assertTrue(r.words.any { it.first == "hayden" })
    }

    @Test fun spelledWordsAreKeptAndOfferedByTheirFirstLetters() {
        val s = BucketStore(emptyList())
        s.learnSpelled(Frame(), listOf("Xavi", "xavier", "xavi")); s.learnSpelled(Frame(kind = FieldKind.PASSWORD), listOf("secret"))
        assertEquals(listOf("xavi", "xavier"), s.spelled("xa")); assertTrue(s.spelled("se").isEmpty())
        var saved = emptyList<String>()
        BucketStore(emptyList()) { saved = it }.learnSpelled(Frame(), listOf("xavi", "xavi", "xavier"))
        assertEquals(listOf("xavi", "xavier"), BucketStore(saved).spelled("x"))   // kept between runs
    }
}
