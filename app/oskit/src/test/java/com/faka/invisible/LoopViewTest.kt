package com.faka.invisible

import com.faka.invisible.oskit.testing.FakeLoopAudio
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/** What you hear is what you see: the view gets each item at its onset, in the order of the sound, with the same text. */
class LoopViewTest {
    private class Ev(val kind: String, val at: Long, val index: Int, val text: String)
    private val audio = FakeLoopAudio(latencyMs = 90.0)
    private val ev = ArrayList<Ev>()
    private val rounds = ArrayList<List<String>>()
    private val view = object : LoopView {
        override fun round(items: List<LoopShown>) { rounds.add(items.map { it.text }); ev.add(Ev("round", audio.now, -1, "")) }
        override fun shown(index: Int, item: LoopShown) { ev.add(Ev("shown", audio.now, index, item.text)) }
        override fun chosen(index: Int, item: LoopShown) { ev.add(Ev("chosen", audio.now, index, item.text)) }
        override fun idle() { ev.add(Ev("idle", audio.now, -1, "")) }
    }
    private val chosen = ArrayList<LoopChoice<String>>()
    private val params = com.faka.invisible.logickit.ClickModel.Params(mu0 = 0.05, sigma0 = 0.14, learnWrapS = 1.0)
    private fun loop(focus: LoopFocus? = null) = LetterLoop<String>(audio, audio, params = params, onChosen = { chosen.add(it) }, focus = focus).also { it.view = view }
    private fun item(k: String, guess: Boolean = false) = LoopItem(k, Phrase.content(k), 0.1, guess = guess)
    private val letters = ('a'..'f').map { it.toString() }
    private fun shown() = ev.filter { it.kind == "shown" }

    @Test fun eachItemIsShownAtTheMomentItSounds() {
        val l = loop(); l.start(listOf(item("Open Maps", guess = true)) + letters.map { item(it) })
        audio.advance(4000)
        val played = audio.played.sortedBy { it.frame }
        // the cycle order on screen is the order the sounds start
        assertEquals(listOf("Open Maps") + letters.sortedBy { audio.heardAt(it) }, rounds.single())
        val s = shown()
        assertTrue("several cycles shown: ${s.size}", s.size > 7)
        // item for item, the screen's i-th item is the sound's i-th item, at the same moment (1 ms)
        val sounded = played.filter { audio.wallMsOf(it.frame) <= audio.now }
        s.take(sounded.size.coerceAtMost(s.size)).forEachIndexed { i, e ->
            assertEquals("text $i", sounded[i].text, e.text)
            assertEquals("time $i", audio.wallMsOf(sounded[i].frame), e.at.toDouble(), 1.0)
        }
        assertEquals("the guess comes first, then wraps", listOf(0, 1, 2, 3, 4, 5, 6, 0), s.take(8).map { it.index })
    }

    @Test fun aFocusRoundIsANewRoundShownAsItSounds() {
        val l = loop(focus = LoopFocus()); l.start(('a'..'j').map { item(it.toString()) })
        audio.advance(500)
        val t = (audio.heardAt("h", 0) + 150).toLong(); audio.advance(t - audio.now); l.click(t)
        val n = rounds.size
        assertEquals("a new round for the focus round", 2, n)
        val focusRound = rounds.last()
        assertTrue("fewer items: $focusRound", focusRound.size < 10 && "h" in focusRound)
        val before = shown().size
        audio.advance(3000)
        val after = shown().drop(before)
        assertTrue(after.isNotEmpty())
        assertTrue("only focus items shown: ${after.map { it.text }}", after.all { it.text in focusRound })
        assertEquals(focusRound, after.take(focusRound.size).map { it.text })
    }

    @Test fun theChosenItemIsPublishedLast() {
        val l = loop(); l.start(letters.map { item(it) })
        audio.advance(300)
        val t = (audio.heardAt("c", 0)).toLong() + 50
        var cycles = 0
        while (chosen.isEmpty() && cycles < 12) { val tt = t + (cycles * l.cycleMs).toLong(); audio.advance(tt - audio.now); l.click(tt); cycles++ }
        assertTrue(chosen.isNotEmpty())
        assertEquals("chosen", ev.last().kind)
        assertEquals(chosen.single().key, ev.last().text)
        audio.advance(5000)
        assertEquals("nothing more is shown after the pick", "chosen", ev.last().kind)
    }
}
