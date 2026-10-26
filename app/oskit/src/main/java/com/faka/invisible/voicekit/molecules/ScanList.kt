package com.faka.invisible.voicekit

/**
 * The one list of the OS: items read one at a time, a current index, next / again, wrap-around and a loop count.
 * It holds no timers and no speech: the owner (ControllerMode, the Write menu) reads [current], waits, and asks for the next.
 * Every list in the app is a ScanList (enforced by KitRulesTest).
 */
class ScanList<T>(val items: List<T>) {
    var index = 0
        private set
    /** Full passes over the list that ended without a choice. */
    var loops = 0
        private set

    val size get() = items.size
    val isEmpty get() = items.isEmpty()
    val current: T get() = items[index]
    val isLast get() = index >= items.size - 1

    /** The index after the current one; after the last, the first. */
    fun nextIndex() = if (index + 1 < items.size) index + 1 else 0

    fun moveTo(i: Int) { index = i }
    fun resetLoops() { loops = 0 }

    /** The list ended: count one loop. */
    fun endLoop(): Int { loops++; return loops }

    // ---- fast scroll (click-then-hold) ----
    /** One fast step: [index] moves to the next item, wrapping at the end. [wrapped] = it went back to the first. */
    class Step(val index: Int, val wrapped: Boolean, val text: String)

    /** Moves on by one and returns what to say: only the first word of the label. */
    fun fastStep(label: (T) -> String): Step {
        val wrapped = index + 1 >= items.size
        index = nextIndex()
        return Step(index, wrapped, firstWord(label(items[index])))
    }

    companion object {
        /** About 4 steps per second while the button is held. */
        const val FAST_MS = Pacing.FAST_MS

        fun firstWord(label: String): String = label.trim().split(Regex("\\s+"), limit = 2).first().trimEnd(',', '.', ':', ';')
    }
}
