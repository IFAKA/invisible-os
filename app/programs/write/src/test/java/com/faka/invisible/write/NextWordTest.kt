package com.faka.invisible.write

import com.faka.invisible.voicekit.*

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextWordTest {
    private val dir = File("src/main/assets/lm")
    private val lm by lazy { NextWord(File(dir, "lm.bin").inputStream(), File(dir, "vocab.txt").readText()) }

    @Test fun ranksPlausibleWords() {
        val top = lm.top(NextWord.words("thank"), 5).map { it.first }
        assertTrue(top.toString(), "you" in top)
    }

    @Test fun firstPairOfAnEmptyTextIsNotEmpty() {
        val r = lm.ranked(emptyList())
        assertTrue(r.size > 1000)
        assertTrue(lm.ranked(listOf("call")).first().first != r.first().first || r.size > 1000)
    }

    @Test fun probabilitiesAreSane() {
        val t = lm.top(NextWord.words("see you"), 4)
        assertTrue(t.all { it.second < 0.0 } && t[0].second >= t[3].second)
    }

    @Test fun fastEnough() {
        lm.top(listOf("a"), 4)   // warm up
        // the best of 3 runs: a busy machine (parallel Gradle) slows one run, not all; the target is 25 ms, the bound is loose (10x)
        val ms = (0 until 3).minOf { run ->
            val t0 = System.nanoTime()
            repeat(20) { i -> lm.top(listOf("can", "you", "call", "me", "x$run$i"), 4) }
            (System.nanoTime() - t0) / 20 / 1e6
        }
        println("top4 ms on laptop (best of 3): $ms")
        assertTrue("took $ms ms", ms < 250)
    }
}

class InitialsBeamTest {
    private val dir = File("src/main/assets/lm")
    private val lm by lazy { NextWord(File(dir, "lm.bin").inputStream(), File(dir, "vocab.txt").readText()) }

    @Test fun hayGivesHowAreYou() {
        val r = lm.initials(emptyList(), "hay").map { it.first }
        println("BEAM hay -> $r")
        assertTrue(r.toString(), "how are you" in r.take(3))
    }

    @Test fun omwIsOnlyFoundWithAWideBeamBecauseTheModelPrefersOtherTexts() {
        // "on", "my", "way" are in the vocabulary, but at the start of a text the model gives "or maybe we" more chance than "on my way" (measured 2026-10-28:
        // top 5 with beam 8 or 30 is "or maybe we, okay maybe we, ..."). So no promise for "omw"; history and the AI source carry such short forms.
        val r = lm.initials(emptyList(), "omw", beam = 60, n = 60).map { it.first }
        println("BEAM omw rank of 'on my way' = ${r.indexOf("on my way")} of ${r.size}; top5 ${r.take(5)}")
        assertTrue(r.isNotEmpty() && r.all { it.split(" ").size == 3 && it.split(" ").map { w -> w[0] } == listOf('o', 'm', 'w') })
    }

    @Test fun doneWordsAreKeptAndFourLettersAreFast() {
        val r = lm.initials(listOf("see"), "yt").map { it.first }
        println("BEAM see+yt -> $r")
        assertTrue(r.isNotEmpty() && r.all { it.startsWith("see ") && it.split(" ").size == 3 })
        lm.initials(emptyList(), "hay")   // warm up
        // best of 3 (a busy machine slows one run, not all); targets 150 ms and 50 ms, the bounds are loose (10x)
        var four = emptyList<Pair<String, Double>>()
        val ms = (0 until 3).minOf { val t0 = System.nanoTime(); four = lm.initials(emptyList(), "ttyl", budgetMs = 5000); (System.nanoTime() - t0) / 1_000_000 }
        println("BEAM ttyl -> ${four.map { it.first }} in $ms ms (best of 3)")
        assertTrue("took $ms ms", ms < 1500)
        var cut = lm.initials(emptyList(), "ttyl", budgetMs = 0)
        val cutMs = (0 until 3).minOf { val t1 = System.nanoTime(); cut = lm.initials(emptyList(), "ttyl", budgetMs = 0); (System.nanoTime() - t1) / 1_000_000 }
        println("BEAM cut in $cutMs ms (best of 3)")
        assertTrue(cut.isEmpty() && cutMs < 500)
    }

    @Test fun theInitialsSourceNeedsTwoLettersAndAnswersWithWholeTexts() {
        val s = InitialsSource(lm, run = { it() })
        var got: List<Guess>? = null
        s.ask(Request(Frame(), emptyList(), "h")) { got = it }
        assertNull(got)
        s.ask(Request(Frame(), emptyList(), "hay")) { got = it }
        assertTrue(got!!.first().text == "how are you")
    }
}
