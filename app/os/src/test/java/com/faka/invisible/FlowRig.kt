package com.faka.invisible

import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.GestureDetector
import com.faka.invisible.voicekit.ScreenMirror

/**
 * The laptop twin of `tools/e2e.py`: a person's presses go through the REAL gesture detector (350 ms gap, 500 ms hold, 1300 ms
 * click-then-hold; nothing is shortened) into the real [ControllerMode], on a fake clock. Speech is the Rig's fake voice
 * (starts 50 ms after the call, ends 500 ms after it). The press strings are the ones the phone script sends: "120,150,900" =
 * press 120 ms, wait 150 ms, press 900 ms.
 */
internal class Flow(val r: ModeTest.Rig = ModeTest.Rig(gap = null)) {
    val m get() = r.m
    /** The practice has run (the first wake runs it once; the phone script's `reset` marks it done). */
    var practiceDone = true
    /** Called instead of the menu when a hold comes before the practice ran (the OS starts the practice then). */
    var onPractice: () -> Unit = {}

    private var task: (() -> Unit)? = null
    private var taskAt = 0L
    private val detector = GestureDetector(
        schedule = { delay, t -> task = t; taskAt = r.now + delay },
        cancel = { task = null },
        onGesture = { g, dur -> gesture(g, dur) },
    )

    init { ScreenMirror.clear(); ScreenMirror.onChange = null; ScreenMirror.now = { r.now } }

    /** What `Actions.buttonTarget.gesture` does with a finished gesture (its tones are not modelled). */
    private fun gesture(g: Gesture, dur: Long) {
        EventLog.pressed(g)
        if ((g == Gesture.HOLD || g == Gesture.CLICK_HOLD) && !m.active && !practiceDone) { onPractice(); return }
        m.onGesture(g, dur)
    }

    /** Moves the clock; the gesture detector's timer and every mode timer fire at their own time, in order. */
    fun advance(ms: Long) {
        val end = r.now + ms
        while (true) {
            val t = task
            if (t == null || taskAt > end) break
            task = null
            r.advance(maxOf(0L, taskAt - r.now)); t()
        }
        r.advance(end - r.now)
    }

    /** The phone script's `press`: "down,gap,down,..." in ms. Returns when the last release happened (the gesture is detected later). */
    fun press(spec: String) {
        spec.split(",").map { it.trim().toLong() }.forEachIndexed { i, d ->
            if (i % 2 == 0) { detector.down(r.now); advance(d); detector.up(r.now) } else advance(d)
        }
    }

    // ---- what a person hears ----
    fun mark() = r.log.size
    private val sayPrefix = Regex("^say(\\[..\\])? ")
    fun heard(i: Int = 0): List<String> = r.log.drop(i).filter { it.startsWith("say") }.map { sayPrefix.replace(it, "") }
    fun said(text: String, i: Int = 0) = heard(i).any { it.contains(text, ignoreCase = true) }

    /** Waits (fake time) until [text] is spoken after [i]; fails with what was heard. */
    fun need(text: String, i: Int = 0, withinMs: Long = 15_000) {
        var t = 0L
        while (!said(text, i) && t < withinMs) { advance(10); t += 10 }
        if (!said(text, i)) throw AssertionError("never heard \"$text\"; heard: " + heard(i).take(8).joinToString(" | "))
    }

    /** Like the script's heard_start: the line is spoken AND the voice has started it (a click before that does not pick it). */
    fun heardStart(text: String, i: Int = 0, withinMs: Long = 15_000) { need(text, i, withinMs); advance(60) }

    fun screen() = m.screen
    fun active() = m.active
}
