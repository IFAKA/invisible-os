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
    private fun loop(seed: List<Double> = emptyList(), focus: LoopFocus? = null, params: com.faka.invisible.logickit.ClickModel.Params = com.faka.invisible.logickit.ClickModel.Params(mu0 = 0.05, sigma0 = 0.14, learnWrapS = 1.0)) = LetterLoop<String>(audio, audio, seed = seed, params = params, onChosen = { chosen.add(it) }, onIdle = { idle++ }, focus = focus, log = { e, f -> events.add(e + f.joinToString(" ", " ")) })
    private val events = ArrayList<String>()
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

    // ---- the focus round ----
    private val ten = ('a'..'j').map { it.toString() }
    private val texts get() = audio.played.map { it.text }.toSet()
    /** The next time (not in the past) the item [k] is heard. */
    private fun nextHeard(k: String) = audio.played.filter { it.text == k }.map { audio.wallMsOf(it.frame) }.filter { it >= audio.now }.min()

    @Test fun aClickNearHStartsAFocusRoundOfHAndItsNeighbours() {
        val l = loop(focus = LoopFocus()); l.start(ten.map { item(it) })
        audio.advance(500)
        val t = (audio.heardAt("h", 0) + 150).toLong(); audio.advance(t - audio.now); l.click(t)
        assertTrue("no pick yet", chosen.isEmpty())
        assertTrue("focus event in $events", events.any { it.startsWith("focus n=") && it.endsWith("ms=${Pacing.LOOP_FOCUS_MS}") })
        val set = texts
        assertTrue("h in $set", "h" in set)
        assertTrue("at most 6 in $set", set.size in 2..6)
        assertFalse("a is not played in the focus round", "a" in set)
        assertTrue("slower items", l.cycleMs >= Math.ceil(set.size / 2.0) * Pacing.LOOP_FOCUS_MS - 1)
    }

    @Test fun aSecondClickPicksHInTheFocusRound() {
        val l = loop(focus = LoopFocus(), params = com.faka.invisible.logickit.ClickModel.Params(mu0 = 0.15, sigma0 = 0.04)); l.start(ten.map { item(it) })
        audio.advance(500)
        var t = (audio.heardAt("h", 0) + 150).toLong(); audio.advance(t - audio.now); l.click(t)
        var n = 0
        while (chosen.isEmpty() && n++ < 12) {
            if ("h" !in texts) break
            t = (nextHeard("h") + 150).toLong(); audio.advance(t - audio.now); l.click(t)
        }
        assertEquals("events $events", 1, chosen.size)
        assertEquals("h", chosen.single().key)
        assertTrue("took $n more clicks", n <= 4)
    }

    @Test fun noClickInTheFocusRoundReturnsToTheFullLoop() {
        val l = loop(focus = LoopFocus()); l.start(ten.map { item(it) })
        audio.advance(500)
        val t = (audio.heardAt("h", 0) + 150).toLong(); audio.advance(t - audio.now); l.click(t)
        assertFalse("a" in texts)
        audio.advance((3 * l.cycleMs).toLong() + 600)
        assertTrue("whole loop again: $texts", texts.size == 10)
        assertTrue(l.running)
        // the evidence is kept: the click on h still counts, so one more click on h is enough sooner than from scratch
        assertTrue(events.any { it.startsWith("focus-end") })
    }

    @Test fun focusPicksTheLikelyItemsAndUndoLast() {
        val items = ten.map { item(it, prior = 0.1, undo = it == "j") }
        val chance = ten.associateWith { if (it == "c") 0.5 else if (it == "d") 0.3 else if (it == "e") 0.15 else 0.01 }
        val f = LoopFocus().pick(items, chance)
        assertEquals("j", f.last().key)
        assertTrue(f.size <= 7)
        assertEquals(listOf("c", "d", "e"), f.take(3).map { it.key })
    }

    // ---- the guess slot and the pace ----
    private fun guess(k: String, prior: Double = 0.2) = LoopItem(k, Phrase.content(k), prior, guess = true)
    private fun loopAt(pace: LoopPace) = LetterLoop<String>(audio, audio, pace = pace, onChosen = { chosen.add(it) }, onIdle = { idle++ }, focus = null, log = { _, _ -> })
    private fun startMs(l: LetterLoop<String>, k: String) = l.onsets.getValue(k)

    @Test fun guessesAreReadAloneInTheMiddleAtNormalSpeechBeforeTheLetters() {
        audio.lengthMs = { if (it.startsWith("Play")) 1200 else 200 }
        val l = loop(); l.start(listOf(guess("Play La Maza"), guess("Open WhatsApp")) + six.map { item(it) })
        audio.advance(100)
        // each guess alone: the first at 0, the second after the first plus its quiet; both in the middle (pan 0)
        assertEquals(0.0, startMs(l, "Play La Maza"), 0.01)
        assertEquals(1200.0 + Pacing.LOOP_GUESS_GAP_MS, startMs(l, "Open WhatsApp"), 0.5)
        val lettersStart = 1200.0 + Pacing.LOOP_GUESS_GAP_MS + 200 + Pacing.LOOP_GUESS_GAP_MS
        assertEquals(lettersStart, startMs(l, "a"), 0.5)
        assertEquals(lettersStart + 175.0, startMs(l, "b"), 0.5)   // two ears: the letters keep the old layout after the slot
        assertTrue(audio.played.filter { it.text.startsWith("Play") || it.text.startsWith("Open") }.all { it.pan == 0.0 })
        assertEquals(1.0, audio.speeds.getValue("Play La Maza"), 0.0)
        assertEquals(Pacing.LOOP_SPEED, audio.speeds.getValue("a"), 0.0)
        assertEquals(lettersStart + 1050.0, l.cycleMs, 0.5)
    }

    @Test fun aClickOnAGuessPicksItAtItsRealOnset() {
        loopRef = loop(); loopRef.start(listOf(guess("Play La Maza", 0.6), guess("Open WhatsApp", 0.1)) + six.map { item(it, prior = 0.05) })
        val n = clickOn("Play La Maza", 60)
        assertEquals("Play La Maza", chosen.single().key); assertEquals(0, chosen.single().rank)
        assertTrue("took $n clicks", n <= 5)
    }

    @Test fun oneEarMeansOneChannelInTheMiddleAtTheLevelPace() {
        val l = loopAt(LoopPace(itemMs = 600, speed = 1.0, ears = 1)); l.start(six.map { item(it) })
        audio.advance(100)
        assertEquals(0.0, startMs(l, "a"), 0.01); assertEquals(600.0, startMs(l, "b"), 0.01); assertEquals(3000.0, startMs(l, "f"), 0.01)
        assertEquals(3600.0, l.cycleMs, 0.01)
        assertTrue(audio.played.all { it.pan == 0.0 })
        assertEquals(1.0, audio.speeds.getValue("a"), 0.0)
    }

    @Test fun theFocusRoundIsAtLeast150msSlowerThanTheItemsOfTheLoop() {
        val l = LetterLoop<String>(audio, audio, pace = LoopPace(itemMs = 600, speed = 1.0, ears = 1), params = com.faka.invisible.logickit.ClickModel.Params(mu0 = 0.05, sigma0 = 0.14, learnWrapS = 1.0), onChosen = { chosen.add(it) }, focus = LoopFocus(), log = { e, f -> events.add(e + f.joinToString(" ", " ")) })
        l.start(six.map { item(it, prior = 0.1) })
        audio.advance(100)
        l.click(audio.heardAt("d").toLong() + 300)   // a click that does not choose
        assertTrue(events.toString(), events.any { it.startsWith("focus") && it.contains("ms=750") })
    }
}
