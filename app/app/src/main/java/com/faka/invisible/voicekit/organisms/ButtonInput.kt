@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * ORGANISM. The one button. Takes every key from the engines (media session or accessibility), drops duplicates, hands the
 * raw button to a running program, answers each press with a tap tone, and turns presses into gestures for the menu.
 * Hold 3 s to leave is handled by the programs themselves. Click then hold closes the OS everywhere: in the menu it is the
 * HOLD gesture, and while a program owns the raw button this class watches for it and calls [ButtonTarget.exitProgram].
 * This class never lets a hold get lost.
 */
class ButtonInput(private val handler: Handler, private val narrator: Narrator, private val target: ButtonTarget) : ButtonPort {
    companion object {
        private const val TAG = "Invisible"
        /** A click this recently before the system's long-press signal means click-then-hold (the signal comes ~0.7 s after the press starts). */
        private const val CLICK_THEN_HOLD_MS = 1300L
        /** The assistant signal has no release: a fast scroll started by it runs this long. */
        private const val ASSIST_FAST_MS = 2500L
        /** Only the earbud button counts. */
        fun isButton(e: KeyEvent) = e.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }

    /** For the accessibility key path: true = the key was taken. */
    fun keyEvent(source: String, e: KeyEvent): Boolean {
        if (!isButton(e)) return false
        Log.d(TAG, "AKEY action=${e.action} code=${e.keyCode} repeat=${e.repeatCount} down=${e.downTime} t=${e.eventTime}")
        key(source, e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.downTime, e.eventTime)
        return true
    }

    private val deduper = Deduper()
    private var lastHoldAt = 0L
    private var clickHolding = false   // a click-then-hold is down (fast scroll): the system's long press must not also fire
    private var lastKeyAt = 0L          // the last key event the key path saw (down or up)
    private var gestureTimer: Runnable? = null
    private var rawDownAt = -1L                // a press is down in a program (key time)
    private var rawUpAt = -1L                  // when the last press in a program ended
    private var rawLastLen = Long.MAX_VALUE    // how long that press was: only a click counts before the hold
    private var rawUpWall = 0L                 // rawUpAt on the uptime clock, for the assistant signal (it has no key times)
    private val rawExit = Runnable { if (rawDownAt >= 0) { rawDownAt = -1; Log.d(TAG, "GESTURE click_hold in program: exit"); target.exitProgram() } }
    private val detector = GestureDetector(
        schedule = { delay, task -> val r = Runnable(task); gestureTimer = r; handler.postDelayed(r, delay) },
        cancel = { gestureTimer?.let { handler.removeCallbacks(it) }; gestureTimer = null },
        onGesture = { g, dur -> fire(g, dur) },
    )

    /** Call on the main thread. repeatCount > 0 is ignored (the hold timer handles holds). */
    override fun key(source: String, isDown: Boolean, repeat: Int, downTime: Long, time: Long) {
        if (repeat > 0) return
        lastKeyAt = SystemClock.uptimeMillis()
        if (!deduper.accept(source, isDown, downTime)) { Log.d(TAG, "DEDUPE dropped $source down=$downTime"); return }
        target.raw()?.let { r -> if (isDown) rawDown(r, time) else rawUp(r, time); return }   // a program owns the raw button
        if (isDown && target.menuActive) narrator.beep(Tone.TAP)   // blind: every press is answered at once
        if (isDown) detector.down(time) else detector.up(time)
    }

    /** A press in a program. If a click ended less than [Pacing.GAP_MS] ago and this press reaches [Pacing.HOLD_MS], it is click then hold: exit. */
    private fun rawDown(r: RawButton, time: Long) {
        val afterClick = rawUpAt >= 0 && time - rawUpAt < Pacing.GAP_MS && rawLastLen < Pacing.HOLD_MS
        rawDownAt = time
        handler.removeCallbacks(rawExit)
        if (afterClick) handler.postDelayed(rawExit, Pacing.HOLD_MS)
        r.down(time)
    }

    private fun rawUp(r: RawButton, time: Long) {
        handler.removeCallbacks(rawExit)
        if (rawDownAt < 0) return   // the press that closed the program, or a stray release
        rawLastLen = time - rawDownAt; rawDownAt = -1; rawUpAt = time; rawUpWall = SystemClock.uptimeMillis()
        r.up(time)
    }

    /**
     * The earbud's long press often reaches us only as the system's assistant signal, with no key events (the log shows hold dur=0 and no AKEY).
     * Idle: it is a hold (wake). In the menu a plain hold is the fast-scroll / Find gesture, and a click then a hold is exit, so the
     * signal is exit only when a click was seen just before it. There is no release event, so a fast scroll ends by a timer.
     */
    fun assistHold() {
        if (target.raw() != null) {   // a program: the signal without key events after a click is click then hold
            if (rawDownAt < 0 && rawLastLen < Pacing.HOLD_MS && SystemClock.uptimeMillis() - rawUpWall < CLICK_THEN_HOLD_MS) { Log.d(TAG, "ASSIST click_hold in program: exit"); target.exitProgram() }
            return
        }
        if (clickHolding || SystemClock.uptimeMillis() - lastHoldAt < 1500) { Log.d(TAG, "ASSIST ignored, hold just fired"); return }
        if (!target.menuActive) { fire(Gesture.HOLD, 0); return }
        if (SystemClock.uptimeMillis() - lastKeyAt < CLICK_THEN_HOLD_MS) { fire(Gesture.HOLD, 0); return }
        fire(Gesture.CLICK_HOLD, 0)
        handler.postDelayed({ if (clickHolding) fire(Gesture.CLICK_HOLD_END, ASSIST_FAST_MS) }, ASSIST_FAST_MS)
    }

    private fun fire(g: Gesture, dur: Long) {
        if (g == Gesture.HOLD || g == Gesture.CLICK_HOLD) lastHoldAt = SystemClock.uptimeMillis()
        if (g == Gesture.CLICK_HOLD) clickHolding = true else if (g == Gesture.CLICK_HOLD_END) clickHolding = false
        Log.d(TAG, "GESTURE ${g.name.lowercase()} dur=$dur")
        target.gesture(g, dur)
    }
}
