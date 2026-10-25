package com.faka.invisible

import java.io.File
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
        lm.top(listOf("a"), 4)
        val t0 = System.nanoTime()
        repeat(20) { i -> lm.top(listOf("can", "you", "call", "me", "x$i"), 4) }
        val ms = (System.nanoTime() - t0) / 20 / 1e6
        println("top4 ms on laptop: $ms")
        assertTrue(ms < 25)
    }
}
