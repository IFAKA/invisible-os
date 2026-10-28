package com.faka.invisible.voicekit

import com.faka.invisible.logickit.ClickModel
import kotlin.math.max

/** One thing the loop speaks: a [key] the caller gets back, the words said ([say]), its chance ([prior]). */
class LoopItem<K>(
    val key: K,
    val say: Phrase,
    val prior: Double,
    /** Choosing it undoes the last choice (the click model drops those clicks, as Nomon's undo key). */
    val undo: Boolean = false,
    /** A costly mistake (Undo, Send): the click model wants a bigger lead before it takes this item. */
    val risky: Boolean = false,
)

/** What was chosen: [rank] = its place in the list the caller gave, [cycles] = whole cycles heard before the choice, [ms] = from the first sound of the round, [clicks] = clicks of the round. */
class LoopChoice<K>(val key: K, val rank: Int, val cycles: Int, val ms: Long, val clicks: Int)

/** What the loop needs to make sound. The Android side is `engines/LoopSound`; tests use a fake one with fake time. All calls on the main thread. */
interface LoopAudio {
    /** Frames per second of [nowFrame] and of the clips. */
    val sampleRate: Int
    /** Make the clips of [texts] (speech, already at the loop speed, mono floats at [sampleRate]); [then] gets the ready ones (a text that could not be made is missing). */
    fun prepare(texts: List<String>, then: (Map<String, FloatArray>) -> Unit)
    /** Frames handed to the output so far. */
    fun nowFrame(): Long
    /** The clock of the button (the time a press carries), in ms. */
    fun nowMs(): Long
    /** The moment [frame] is HEARD, on the clock of the button: it includes what the output needs to play it (buffer, hardware). */
    fun wallMsOf(frame: Long): Double
    /** Play [clip] at the exact [atFrame]; [pan] -1 = the first ear (voice A), +1 = the other ear (voice B). */
    fun play(clip: FloatArray, atFrame: Long, pan: Double)
    /** Drop every sound that is not played yet. */
    fun clear()
    /** The short sound that says an item was taken. */
    fun confirm()
}

/**
 * ORGANISM. The letter loop (layout of Ticker: Nel, Kristensson, MacKay, IEEE TPAMI 2019): the items are spoken in a
 * repeating cycle by two voices, one in each ear, the two streams interleaved by half an item, so the brain can follow one
 * voice. A click is evidence, not a pick: a [ClickModel] (Nomon) turns the clicks and the exact time each item was HEARD
 * into the chance of each item, and an item is taken when it leads by 99 to 1. A late or wrong click is forgiven.
 *
 * Channels: item 0, 2, 4... voice A (left), item 1, 3, 5... voice B (right), so the two best items come first in each ear;
 * the caller puts the controls (Space, Undo, Send) last. Channel B starts half an item later. An item takes [itemMs], or
 * its clip plus a gap if it is longer. The cycle is the longer channel.
 * Onset times: every clip is scheduled at an exact frame ([LoopAudio.play]); the model gets [LoopAudio.wallMsOf] of that
 * frame plus the offset of the item, so it knows when each item was heard on the clock of the button. A fixed delay left
 * (a Bluetooth earbud) is learned by the click density in a few clicks.
 * Rounds: [start] begins one round with new items. When an item is taken the loop stops, plays [LoopAudio.confirm] and tells
 * [onChosen]; the caller then starts the next round (the model carries over what it learned). With no click for
 * [idleCycles] cycles the loop pauses and tells [onIdle]; [resume] starts it again.
 */
class LetterLoop<K>(
    private val audio: LoopAudio,
    private val timers: Timers,
    private val itemMs: Int = Pacing.LOOP_ITEM_MS,
    private val idleCycles: Int = Pacing.LOOP_IDLE_CYCLES,
    private val gapMs: Int = Pacing.LOOP_GAP_MS,
    private val leadMs: Int = Pacing.LOOP_LEAD_MS,
    /** What the person learned before (see [offsets]): click offsets in seconds, newest first. */
    seed: List<Double> = emptyList(),
    private val log: (event: String, fields: List<Any?>) -> Unit = { _, _ -> },
    private val onChosen: (LoopChoice<K>) -> Unit,
    private val onIdle: () -> Unit = {},
) {
    private enum class State { OFF, PREPARING, RUNNING, PAUSED }
    private var state = State.OFF
    private var gen = 0
    private var items: List<LoopItem<K>> = emptyList()
    private var model: ClickModel<K>? = null
    private var prev: ClickModel<K>? = null
    private var seedOffsets = seed
    private var startWall = 0.0
    private var periodMs = 0.0
    private var cycleStartFrame = 0L
    private var lastActiveMs = 0L
    private var clicks = 0
    private var cycleFrames = 0L
    private var plays: List<Triple<FloatArray, Long, Double>> = emptyList()   // clip, frame offset in the cycle, pan
    /** Times of the items in the cycle, ms after the cycle start (key to time); what the model got. Read by tests and traces. */
    var onsets: Map<K, Double> = emptyMap(); private set
    /** The cycle length in ms of the round now. */
    val cycleMs get() = periodMs
    val running get() = state == State.RUNNING

    /** What to keep between sessions: the click offsets learned so far. */
    fun offsets(): List<Double> = (model ?: prev)?.offsets() ?: seedOffsets

    /** Begin a round with [list] (in order of chance, controls last). */
    fun start(list: List<LoopItem<K>>) {
        halt(); items = list
        if (list.isEmpty()) return
        state = State.PREPARING
        val g = ++gen
        audio.prepare(list.map { it.say.text }.distinct()) { clips -> if (g == gen && state == State.PREPARING) begin(clips) }
    }

    /** Start the same round again after [onIdle]. */
    fun resume() { if (state == State.PAUSED) start(items) }

    /** Stop for good: no sound, no timers. */
    fun stop() { halt(); state = State.OFF }

    private fun halt() { gen++; timers.clear(TIMER); audio.clear(); if (state != State.OFF) state = State.PAUSED }

    private fun begin(clips: Map<String, FloatArray>) {
        val live = items.filter { clips[it.say.text] != null }
        if (live.isEmpty()) { state = State.PAUSED; log("loop-empty", listOf(items.size)); return }
        val sr = audio.sampleRate
        val half = itemMs / 2.0
        val at = HashMap<K, Double>(); val ps = ArrayList<Triple<FloatArray, Long, Double>>()
        val end = doubleArrayOf(0.0, half)   // where the next item of channel A and of channel B starts
        var longest = 0.0
        live.forEachIndexed { i, item ->
            val ch = i % 2
            val clip = clips.getValue(item.say.text)
            val ms = clip.size * 1000.0 / sr
            at[item.key] = end[ch]
            ps.add(Triple(clip, Math.round(end[ch] * sr / 1000.0), if (ch == 0) -1.0 else 1.0))
            end[ch] += max(itemMs.toDouble(), ms + gapMs)
            longest = max(longest, end[ch] - (if (ch == 1) half else 0.0))
        }
        periodMs = longest
        cycleFrames = Math.round(periodMs * sr / 1000.0)
        plays = ps; onsets = at
        cycleStartFrame = audio.nowFrame() + sr * leadMs / 1000
        startWall = audio.wallMsOf(cycleStartFrame)
        val m = ClickModel<K>(periodMs)
        val before = prev
        if (before != null) m.inherit(before) else m.seed(seedOffsets)
        m.start(
            live.associate { it.key to it.prior.coerceAtLeast(1e-9) }, at, startWall,
            live.filter { it.risky }.associate { it.key to m.params.winDiffHigh },
        )
        model = m; clicks = 0; lastActiveMs = startWall.toLong()
        state = State.RUNNING
        schedule(0); schedule(1)
        log("loop-start", listOf(live.size, periodMs.toLong(), startWall.toLong()))
        next(0)
    }

    private fun schedule(k: Int) {
        val base = cycleStartFrame + k * cycleFrames
        for ((clip, off, pan) in plays) audio.play(clip, base + off, pan)
    }

    /** At the start of cycle [k]: put cycle k+2 on the audio clock, look for idleness. */
    private fun next(k: Int) {
        val g = gen
        val due = startWall + k * periodMs
        timers.set(TIMER, max(0L, Math.round(due - audio.nowMs()))) {
            if (g != gen || state != State.RUNNING) return@set
            if (audio.nowMs() - lastActiveMs >= idleCycles * periodMs - periodMs / 2) { pause(); return@set }
            schedule(k + 2); next(k + 1)
        }
    }

    private fun pause() {
        halt(); state = State.PAUSED
        log("loop-idle", listOf(items.size))
        onIdle()
    }

    /** A click of the button at [timeMs] (the press time, same clock as [LoopAudio.nowMs]). */
    fun click(timeMs: Long) {
        val m = model ?: return
        if (state != State.RUNNING) return
        clicks++; lastActiveMs = timeMs
        val won = m.click(timeMs.toDouble())
        val best = m.best()
        log("loop-click", listOf(timeMs, ((timeMs - startWall) % periodMs).toLong(), best, "%.2f".format(m.posteriors()[best] ?: 0.0)))
        if (won) finish(m, m.chosen() ?: return, timeMs)
    }

    private fun finish(m: ClickModel<K>, key: K, timeMs: Long) {
        val rank = items.indexOfFirst { it.key == key }
        val item = items[rank]
        val c = LoopChoice(key, rank, ((timeMs - startWall) / periodMs).toInt(), (timeMs - startWall).toLong(), clicks)
        halt(); state = State.PAUSED
        audio.confirm()
        if (item.undo) m.undo() else m.learn(key)
        prev = m; model = null
        log("loop-choice", listOf(key, c.rank, c.cycles, c.ms, c.clicks))
        onChosen(c)
    }

    private companion object { const val TIMER = "loop:cycle" }
}
