package com.faka.invisible

import com.faka.invisible.claude.PushItem
import com.faka.invisible.voicekit.Gesture

/**
 * The rules of the push line (DESIGN-LAYER.md), pure so a unit test can run them: when a line is spoken or only queued,
 * which press means which answer, and in which order the queue is read.
 */
object LineRules {
    const val GLOBAL_GAP_MS = 20_000L   // at least this long since the last pushed line
    const val ROOM_GAP_MS = 60_000L     // and this long since the last line of the same room / ask
    const val WINDOW_MS = 8_000L        // the answer window after the hint
    const val DONE_MIN_WORKED_S = 30    // a finished window is said only if it worked this long (unknown = say it)

    enum class Level { SPOKEN, QUEUED, TONE }
    class Decision(val level: Level, val why: String)
    /** What is true on the phone now. */
    data class Ctx(val menuOpen: Boolean, val programRuns: Boolean, val inCall: Boolean, val muted: Boolean, val busy: Boolean = false)

    /** Speak the line, only queue it, or (the menu is open) make the tone and queue it. [lastAnyMs] / [lastKeyMs]: when a line was last spoken (null = never). */
    fun decide(item: PushItem, ctx: Ctx, nowMs: Long, lastAnyMs: Long?, lastKeyMs: Long?, replay: Boolean = false): Decision = when {
        replay -> Decision(Level.QUEUED, "startup")
        item.laptopActive == true -> Decision(Level.QUEUED, "laptop_active")
        ctx.inCall -> Decision(Level.QUEUED, "call")
        ctx.programRuns -> Decision(Level.QUEUED, "program")
        ctx.muted -> Decision(Level.QUEUED, "muted")
        ctx.menuOpen -> Decision(Level.TONE, "menu_open")
        ctx.busy -> Decision(Level.QUEUED, "busy")
        lastAnyMs != null && nowMs - lastAnyMs < GLOBAL_GAP_MS -> Decision(Level.QUEUED, "rate_global")
        lastKeyMs != null && nowMs - lastKeyMs < ROOM_GAP_MS -> Decision(Level.QUEUED, "rate_room")
        else -> Decision(Level.SPOKEN, "needs_you")
    }

    /** A finished window is said when it worked long enough (or the laptop did not say how long). */
    fun doneSpoken(workedS: Double?) = workedS == null || workedS >= DONE_MIN_WORKED_S

    enum class Move { A, B, NO, MORE, LATER, NONE }

    /** The button during the answer window. Normal: click = a, two = b, hold = more, three = later. A risky a: click = no, hold = a, two = more. */
    fun move(item: PushItem, g: Gesture): Move {
        val a = item.a ?: return Move.NONE
        if (g == Gesture.TRIPLE) return Move.LATER
        return if (a.risky) when (g) {
            Gesture.SINGLE -> Move.NO
            Gesture.HOLD, Gesture.CLICK_HOLD -> Move.A
            Gesture.DOUBLE -> Move.MORE
            else -> Move.NONE
        } else when (g) {
            Gesture.SINGLE -> Move.A
            Gesture.DOUBLE -> if (item.b != null) Move.B else Move.NONE
            Gesture.HOLD, Gesture.CLICK_HOLD -> Move.MORE
            else -> Move.NONE
        }
    }
}

/** What waits for the owner: one item per key (a newer one replaces the older), read asks first, then room needs, then lines; newest first. Main thread only. */
class LineQueue {
    private class Slot(val item: PushItem, val seq: Long, val atMs: Long)
    private val slots = LinkedHashMap<String, Slot>()
    private var seq = 0L
    private val lastByKey = HashMap<String, Long>()
    var lastSpokenMs: Long? = null; private set
    /** The last item that was pushed (spoken) by itself, for the four-click replay. */
    var lastPushed: PushItem? = null; private set

    val size get() = slots.size
    fun isEmpty() = slots.isEmpty()
    fun contains(key: String) = slots.containsKey(key)

    /** Adds [item]. An older item with the same key, or the same room and words (an ask also shown as its room), is replaced. */
    fun offer(item: PushItem, nowMs: Long = 0L) {
        slots.values.filter { it.item.key == item.key || (it.item.who == item.who && it.item.line == item.line) }.map { it.item.key }.forEach { slots.remove(it) }
        slots[item.key] = Slot(item, ++seq, nowMs)
    }
    fun remove(key: String) { slots.remove(key) }
    fun clear() { slots.clear() }
    /** Drops what waited longer than [ageMs]: the laptop may have moved on without us. */
    fun dropOlder(nowMs: Long, ageMs: Long) { slots.values.filter { nowMs - it.atMs > ageMs }.map { it.item.key }.forEach { slots.remove(it) } }

    /** The reading order. */
    fun ordered(): List<PushItem> = slots.values.sortedWith(compareBy<Slot> { rank(it.item.kind) }.thenByDescending { it.seq }).map { it.item }
    fun top(skip: Set<String> = emptySet()): PushItem? = ordered().firstOrNull { it.key !in skip }
    private fun rank(kind: String) = when (kind) { PushItem.ASK -> 0; PushItem.ROOM -> 1; else -> 2 }

    fun lastAny(): Long? = lastSpokenMs
    fun lastFor(key: String): Long? = lastByKey[key]
    /** A line was spoken by itself at [nowMs]: the rate limits start. */
    fun noteSpoken(item: PushItem, nowMs: Long) { lastSpokenMs = nowMs; lastByKey[item.key] = nowMs; lastPushed = item }
}
