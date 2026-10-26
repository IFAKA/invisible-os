@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * ORGANISM. The one button. Takes every key from the engines (media session or accessibility), drops duplicates, hands the
 * raw button to a running program, answers each press with a tap tone, and turns presses into gestures for the menu.
 * Hold 3 s to leave is handled by the programs themselves; this class never lets a hold get lost.
 */
class ButtonInput(private val handler: Handler, private val narrator: Narrator, private val target: ButtonTarget) : ButtonPort {
    companion object {
        private const val TAG = "Invisible"
        /** The system's long-press signal only counts when the key path has seen no key for this long (then it is the only source). */
        private const val ASSIST_QUIET_MS = 3000L
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
        target.raw()?.let { r -> if (isDown) r.down(downTime) else r.up(time); return }   // a program owns the raw button
        if (isDown && target.menuActive) narrator.beep(Tone.TAP)   // blind: every press is answered at once
        if (isDown) detector.down(time) else detector.up(time)
    }

    /** Long press launched the assistant role / hands-free: act as HOLD unless a HOLD just fired from the key path. */
    fun assistHold() {
        if (target.raw() != null) return
        if (SystemClock.uptimeMillis() - lastKeyAt < ASSIST_QUIET_MS) { Log.d(TAG, "ASSIST ignored, the key path is seeing this press"); return }   // a hold that woke the OS and goes on must not read as exit
        if (clickHolding || SystemClock.uptimeMillis() - lastHoldAt < 1500) { Log.d(TAG, "ASSIST ignored, hold just fired"); return }
        fire(Gesture.HOLD, 0)
    }

    private fun fire(g: Gesture, dur: Long) {
        if (g == Gesture.HOLD || g == Gesture.CLICK_HOLD) lastHoldAt = SystemClock.uptimeMillis()
        if (g == Gesture.CLICK_HOLD) clickHolding = true else if (g == Gesture.CLICK_HOLD_END) clickHolding = false
        Log.d(TAG, "GESTURE ${g.name.lowercase()} dur=$dur")
        target.gesture(g, dur)
    }
}
