package com.faka.invisible

import com.faka.invisible.logickit.*
import com.faka.invisible.practice.PracticeProgram
import com.faka.invisible.voicekit.*
import com.faka.invisible.write.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The button path with the real [com.faka.invisible.voicekit.ButtonInput] on a real (paused) Android looper: programs that own the raw button. See the table in FlowTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowButtonTest {
    // ---- practice_end_to_end ----
    @Test fun practice_end_to_end() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.need("click once now")
        rig.press("120");                 rig.need("click twice")
        rig.press("120,150,120");         rig.need("click three times")
        rig.press("100,150,100,150,100"); rig.need("click, then hold")
        assertEquals(0, host.ended)
        rig.press("120,150,900")          // click, then hold: the OS (ButtonInput) catches it and closes the practice
        rig.advance(Pacing.GAP_MS)
        assertEquals("the practice did not end", 1, host.ended)
        assertEquals(1, rig.exits)
        assertTrue(host.prefs.getBoolean(PracticeProgram.DONE_KEY, false))   // it ran once: never again by itself
    }

    // ---- click then hold leaves any program (also the tail of screen_tap_picks) ----
    @Test fun click_then_hold_leaves_a_program_but_a_plain_hold_does_not() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.press("900")   // a plain hold (under 3 s) is not an exit
        rig.advance(Pacing.GAP_MS)
        assertEquals(0, rig.exits); assertEquals(0, host.ended)
        rig.press("120,150,900")
        rig.advance(Pacing.GAP_MS)
        assertEquals(1, rig.exits); assertEquals(1, host.ended)
    }

    // ---- hold 3 s always leaves ----
    @Test fun hold_three_seconds_leaves() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram()
        rig.running = p; p.start(host)
        rig.press("3200"); rig.advance(Pacing.GAP_MS)
        assertEquals(1, host.ended)
    }

    /** The loop's sound on the rig's clock: records what is scheduled (text, frame), no real output. */
    private class RigLoopAudio(val rig: RoboRig) : LoopAudio, Timers {
        override val sampleRate = 24000
        val played = ArrayList<Pair<String, Long>>()
        private val textOf = java.util.IdentityHashMap<FloatArray, String>()
        private val tasks = HashMap<String, Runnable>()
        var confirms = 0
        override fun prepare(texts: List<String>, speed: Double, then: (Map<String, FloatArray>) -> Unit) {
            then(texts.associateWith { t -> FloatArray(200 * sampleRate / 1000).also { textOf[it] = t } })
        }
        override fun nowFrame() = rig.now() * sampleRate / 1000
        override fun nowMs() = rig.now()
        override fun wallMsOf(frame: Long) = frame * 1000.0 / sampleRate + 40.0   // 40 ms of output delay
        override fun play(clip: FloatArray, atFrame: Long, pan: Double) { played.add((textOf[clip] ?: "?") to atFrame) }
        override fun clear() { played.clear() }
        override fun confirm() { confirms++ }
        override fun set(key: String, delayMs: Long, task: () -> Unit) {
            tasks.remove(key)?.let { rig.handler.removeCallbacks(it) }
            val r = Runnable { tasks.remove(key); task() }; tasks[key] = r; rig.handler.postDelayed(r, delayMs)
        }
        override fun clear(key: String) { tasks.remove(key)?.let { rig.handler.removeCallbacks(it) } }
        fun heard(text: String) = played.filter { it.first == text }.map { wallMsOf(it.second) }.sorted()
    }

    // ---- write_loop_picks: the new Write on the letter loop (real button path; the phone-only part is e2e "not built yet") ----
    @Test fun write_loop_picks() {
        val rig = RoboRig(); val host = rig.Host(); val audio = RigLoopAudio(rig)
        var sent: String? = null; val reads = ArrayList<String>()
        val lm = object : Ranker { override fun ranked(context: List<String>) = listOf("hello" to -2.0, "hi" to -3.0, "yes" to -4.0, "no" to -5.0) }
        val out = object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) { rig.said.add(text.text) }
            override fun cue(c: TypeCue) {}
            override fun readBack(text: String) { reads.add(text); rig.said.add(text) }
            override fun finished(text: String) { sent = text }
        }
        val p = LoopProgram(lm, out, audio, audio, Frame(), object : LoopStore { override fun load() = emptyList<Double>(); override fun level() = com.faka.invisible.logickit.LoopLevel.TOP; override fun save(offsets: List<Double>) {} }) { host.ended() }
        rig.rawOwner = p; p.start()
        var guard = 0
        fun clickOn(text: String, done: () -> Boolean) {
            while (audio.played.isEmpty() && guard++ < 2000) rig.advance(50)
            var n = 0
            while (!done() && n++ < 12) {
                var wait = 0; while (audio.heard(text).size < 2 && wait++ < 2000) rig.advance(50)   // letters sit in fixed places: wait for a full round
                val at = audio.heard(text)
                val cyc = if (at.size > 1) at[1] - at[0] else 1000.0
                var t = at.first() + 300; while (t < rig.now()) t += cyc
                while (rig.now() < t.toLong()) rig.advance(5)
                rig.press("80"); rig.advance(60)
            }
            assertTrue("never picked $text; heard ${rig.said.takeLast(6)}", done())
        }
        clickOn("hello") { p.text() == "Hello" }
        clickOn("Send") { reads.isNotEmpty() }
        assertEquals("Hello", reads.last()); assertNull(sent)
        rig.press("100"); rig.advance(200)   // a click sends it
        assertEquals("Hello", sent); assertEquals(1, host.ended)
        assertTrue(audio.confirms >= 2)
    }

    // ---- home_letters_open: a hold on home starts the letter loop; click on "w", then on "Open WhatsApp" (real button path, fake sound clock) ----
    @Test fun home_letters_open() {
        val rig = RoboRig(); val host = rig.Host(); val audio = RigLoopAudio(rig)
        val apps = object : ActionSource {
            override val id = "apps"
            override fun actions() = listOf(
                HomeAction(ActionKind.OPEN, "WhatsApp", "com.whatsapp", 30.0), HomeAction(ActionKind.OPEN, "Weather", "com.weather", 3.0),
                HomeAction(ActionKind.OPEN, "Maps", "com.maps", 20.0), HomeAction(ActionKind.START, "Timer", "hub:timer", 5.0))
        }
        val store = object : com.faka.invisible.write.LoopStore { override fun load() = emptyList<Double>(); override fun level() = com.faka.invisible.logickit.LoopLevel.TOP; override fun save(offsets: List<Double>) {} }
        var hint = true
        val p = HomeLetters(listOf(apps), ActionRanker(), audio, audio, store, { hint }, { hint = true })
        rig.running = p; p.start(host)
        fun clickOn(text: String, done: () -> Boolean) {
            var guard = 0; val before = audio.played.size
            while ((audio.played.isEmpty() || audio.heard(text).isEmpty()) && guard++ < 2000) rig.advance(50)
            var n = 0
            while (!done() && n++ < 12) {
                var wait = 0; while (audio.heard(text).size < 2 && wait++ < 2000) rig.advance(50)   // letters sit in fixed places: wait for a full round
                val at = audio.heard(text)
                val cyc = if (at.size > 1) at[1] - at[0] else 1000.0
                var t = at.first() + 300; while (t < rig.now()) t += cyc
                while (rig.now() < t.toLong() && !done()) rig.advance(5)
                if (done()) break
                rig.press("80"); rig.advance(60)
            }
            assertTrue("never picked $text; heard ${rig.said.takeLast(6)}", done())
        }
        clickOn("w") { p.letters() == "w" || host.ended > 0 }   // the letter, at its onset
        assertEquals("w", p.letters())
        rig.advance(1500)   // its name is read, then the loop starts again
        clickOn("Open WhatsApp") { host.ended > 0 }
        val r = p.result as HomeResult.Act
        assertEquals("com.whatsapp", r.action.target)
        assertEquals(1, host.ended)
    }

    @Test fun home_letters_hold_three_seconds_leaves_and_click_hold_is_only_clicking() {
        fun make(): Triple<RoboRig, RoboRig.Host, HomeLetters> {
            val rig = RoboRig(); val host = rig.Host(); val audio = RigLoopAudio(rig)
            val store = object : com.faka.invisible.write.LoopStore { override fun load() = emptyList<Double>(); override fun level() = com.faka.invisible.logickit.LoopLevel.TOP; override fun save(offsets: List<Double>) {} }
            val p = HomeLetters(emptyList(), ActionRanker(), audio, audio, store, { true }, {})
            rig.running = p; p.start(host); return Triple(rig, host, p)
        }
        val (rig, host, p) = make()
        rig.press("3200"); rig.advance(Pacing.GAP_MS)
        assertEquals(1, host.ended); assertTrue(p.result is HomeResult.Leave)
        // click then hold: the loop keeps running (Write works the same); nothing closes the OS
        val (rig2, host2, p2) = make()
        rig2.advance(1000)
        rig2.press("120,150,900"); rig2.advance(Pacing.GAP_MS + 2000)
        assertEquals(0, rig2.exits); assertEquals(0, host2.ended); assertTrue(p2.active)
    }

    /** The real phone (trace 2026-10-28 21:47): one click reached the loop, then ~600 ms later the system's assistant signal arrived and was read as click-then-hold = exit. */
    @Test fun home_letters_one_click_then_the_assistant_signal_does_not_end_it() {
        val rig = RoboRig(); val host = rig.Host(); val audio = RigLoopAudio(rig)
        val apps = object : ActionSource { override val id = "apps"; override fun actions() = listOf(HomeAction(ActionKind.OPEN, "WhatsApp", "com.whatsapp", 30.0)) }
        val store = object : com.faka.invisible.write.LoopStore { override fun load() = emptyList<Double>(); override fun level() = com.faka.invisible.logickit.LoopLevel.TOP; override fun save(offsets: List<Double>) {} }
        val p = HomeLetters(listOf(apps), ActionRanker(), audio, audio, store, { true }, {})
        rig.running = p; p.start(host)
        rig.advance(1500)
        rig.press("2")                       // the 2 ms click of the trace
        rig.advance(614); rig.buttons.assistHold()   // the signal that followed it
        rig.advance(2000)
        assertTrue("the loop ended", p.active); assertEquals(0, host.ended); assertEquals(0, rig.exits)
        assertEquals("the click did not reach the loop", 1, p.clicksSeen)
        rig.press("3200"); rig.advance(Pacing.GAP_MS)
        assertEquals(1, host.ended); assertTrue(p.result is HomeResult.Leave)   // hold 3 s still leaves
    }

    /** Write owns every press too: a click and the assistant signal after it do not leave Write; only hold 3 s does. */
    @Test fun write_loop_one_click_then_the_assistant_signal_does_not_end_it() {
        val rig = RoboRig(); val host = rig.Host(); val audio = RigLoopAudio(rig)
        val lm = object : Ranker { override fun ranked(context: List<String>) = listOf("hello" to -2.0) }
        val out = object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) { rig.said.add(text.text) }
            override fun cue(c: TypeCue) {}
            override fun readBack(text: String) {}
            override fun finished(text: String) {}
        }
        var ended = 0
        val p = LoopProgram(lm, out, audio, audio, Frame(), object : LoopStore { override fun load() = emptyList<Double>(); override fun level() = com.faka.invisible.logickit.LoopLevel.TOP; override fun save(offsets: List<Double>) {} }) { ended++ }
        rig.rawOwner = p; p.start()
        rig.advance(6000)
        rig.press("2"); rig.advance(614); rig.buttons.assistHold(); rig.advance(2300)   // the second click must not land on the Send of the two-item focus round
        assertTrue(p.active); assertEquals(0, ended); assertEquals(0, rig.exits)
        rig.press("120,150,900"); rig.advance(Pacing.GAP_MS + 1000)
        assertTrue("click then hold must not leave Write ended=$ended", p.active); assertEquals(0, rig.exits)
    }
}
