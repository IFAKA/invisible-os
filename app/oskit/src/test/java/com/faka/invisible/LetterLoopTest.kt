package com.faka.invisible

import com.faka.invisible.oskit.testing.FakeLoopAudio
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

class LetterLoopTest {
    private val chosen = ArrayList<LoopChoice<String>>()
    private var idle = 0
    private val audio = FakeLoopAudio(latencyMs = 90.0)
    private fun item(k: String, prior: Double = 0.1, undo: Boolean = false) = LoopItem(k, Phrase.content(k), prior, undo)
    private fun loop(seed: List<Double> = emptyList()) = LetterLoop<String>(audio, audio, seed = seed, onChosen = { chosen.add(it) }, onIdle = { idle++ })
    private val six = listOf("a", "b", "c", "d", "e", "f")

    @Test fun twoChannelsHalfAnItemApartAndTheyRepeat() {
        val l = loop(); l.start(six.map { item(it) })
        audio.advance(3000)
        val at = { t: String, c: Int -> audio.played.filter { it.text == t }.sortedBy { it.frame }[c].frame * 1000.0 / audio.sampleRate }
        val start = at("a", 0)
        // voice A (left): a, c, e one item apart; voice B (right): b, d, f, half an item later
        assertEquals(0.0, at("a", 0) - start, 0.1); assertEquals(350.0, at("c", 0) - start, 0.1); assertEquals(700.0, at("e", 0) - start, 0.1)
        assertEquals(175.0, at("b", 0) - start, 0.1); assertEquals(525.0, at("d", 0) - start, 0.1); assertEquals(875.0, at("f", 0) - start, 0.1)
        assertEquals(listOf(-1.0, 1.0), audio.played.filter { it.text == "a" || it.text == "b" }.take(2).map { it.pan }.sorted().let { it })
        assertTrue(audio.played.filter { it.text in listOf("a", "c", "e") }.all { it.pan == -1.0 })
        assertTrue(audio.played.filter { it.text in listOf("b", "d", "f") }.all { it.pan == 1.0 })
        // the cycle is the longer channel (3 items = 1050 ms) and repeats
        assertEquals(1050.0, l.cycleMs, 0.01)
        assertEquals(1050.0, at("a", 1) - start, 0.1); assertEquals(2100.0, at("a", 2) - start, 0.1)
    }

    @Test fun theModelGetsTheTimeEachItemIsHeard() {
        val l = loop(); l.start(six.map { item(it) })
        // the first sound is scheduled leadMs after now and heard latencyMs later
        assertEquals(audio.heardAt("a"), audio.heardAt("a", 0), 0.0)
        assertEquals(0.0, l.onsets.getValue("a"), 0.01); assertEquals(525.0, l.onsets.getValue("d"), 0.01)
        assertEquals(Pacing.LOOP_LEAD_MS + 90.0, audio.heardAt("a"), 0.1)
    }

    @Test fun aLongClipGetsALongerSlot() {
        audio.lengthMs = { if (it == "c") 900 else 200 }
        val l = loop(); l.start(six.map { item(it) })
        assertEquals(350.0 + 940.0, l.onsets.getValue("e"), 0.5)   // after a (350) and c (900 + 40 gap)
    }

    /** Click once per cycle [delay] ms after the item sounds, until the loop chooses; returns the cycles it took. */
    private fun clickOn(key: String, delay: Long): Int {
        var cycles = 0
        while (chosen.isEmpty() && cycles < 12) {
            val t = (audio.heardAt(key, 0) + cycles * loopRef.cycleMs).toLong() + delay
            audio.advance(t - audio.now)
            // a real click is the press time; here the clock is the press clock
            loopRef.click(t)
            cycles++
        }
        return cycles
    }
    private lateinit var loopRef: LetterLoop<String>

    @Test fun aClickAfterAnItemPicksItWhenItIsLikely() {
        loopRef = loop(); loopRef.start(six.map { item(it, prior = if (it == "c") 0.6 else 0.08) })
        val n = clickOn("c", 50)
        assertEquals("c", chosen.single().key); assertEquals(2, chosen.single().rank)
        assertTrue("took $n clicks", n <= 5)
        assertEquals(1, audio.confirms)
    }

    @Test fun clicksOnTheSameItemEachCycleAddUp() {
        loopRef = loop(); loopRef.start(six.map { item(it) })
        val n = clickOn("d", 50)
        assertEquals("d", chosen.single().key)
        assertTrue("took $n cycles", n in 2..10)
        assertEquals(n, chosen.single().clicks)
    }

    @Test fun aPersonWhoIsAlwaysLateIsLearnedAndNeedsFewerClicks() {
        // 30 clicks 250 ms after the item were learned: one click at that delay is now enough for a likely item
        loopRef = loop(seed = List(30) { 0.25 }); loopRef.start(six.map { item(it, prior = if (it == "c") 0.6 else 0.08) })
        assertEquals(1, clickOn("c", 250))
        assertEquals("c", chosen.single().key)
    }

    @Test fun aClick150MsAfterAnItemPicksItOnceTheDelayIsLearned() {
        loopRef = loop(seed = List(30) { 0.15 }); loopRef.start(six.map { item(it, prior = if (it == "d") 0.6 else 0.08) })
        assertEquals(1, clickOn("d", 150))
        assertEquals("d", chosen.single().key)
    }

    @Test fun aWrongClickThenUndo() {
        loopRef = loop()
        val items = six.map { item(it, prior = if (it == "c") 0.6 else 0.08) } + item("undo", prior = 0.05, undo = true)
        loopRef.start(items)
        clickOn("c", 50)                       // meant d, the loop took c
        assertEquals("c", chosen.last().key)
        chosen.clear(); audio.confirms = 0
        loopRef.start(items.map { item(it.key, if (it.key == "undo") 0.6 else 0.05, it.key == "undo") })
        val n = clickOn("undo", 50)
        assertEquals("undo", chosen.single().key); assertTrue("undo took $n", n <= 6)
        // the next round starts clean: the old clicks of c are not in it
        chosen.clear()
        loopRef.start(items.map { item(it.key, if (it.key == "d") 0.6 else 0.05) })
        clickOn("d", 50)
        assertEquals("d", chosen.single().key)
    }

    @Test fun noClickForFourCyclesReportsIdleAndResumeAgain() {
        val l = loop(); l.start(six.map { item(it) })
        audio.advance(1050L * 3)
        assertEquals(0, idle)
        audio.advance(1050L * 2)
        assertEquals(1, idle)
        assertFalse(l.running)
        assertTrue(audio.played.isEmpty())
        audio.advance(5000); assertTrue(audio.played.isEmpty())   // paused: nothing more is scheduled
        l.resume(); audio.advance(100)
        assertTrue(l.running); assertTrue(audio.played.isNotEmpty())
    }

    @Test fun anItemTheVoiceCannotMakeIsLeftOut() {
        audio.missing.add("b")
        val l = loop(); l.start(six.map { item(it) })
        assertFalse(l.onsets.containsKey("b")); assertEquals(5, l.onsets.size)
    }

    @Test fun aClickBeforeTheLoopIsRunningDoesNothing() {
        val l = loop(); l.click(100); assertTrue(chosen.isEmpty())
    }
}
