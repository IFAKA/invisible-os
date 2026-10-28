package com.faka.invisible.voicekit

import com.faka.invisible.logickit.ClickModel
import kotlin.math.max
import kotlin.math.min

/** One thing the loop speaks: a [key] the caller gets back, the words said ([say]), its chance ([prior]). */
class LoopItem<K>(
    val key: K,
    val say: Phrase,
    val prior: Double,
    /** Choosing it undoes the last choice (the click model drops those clicks, as Nomon's undo key). */
    val undo: Boolean = false,
    /** A costly mistake (Undo, Send): the click model wants a bigger lead before it takes this item. */
    val risky: Boolean = false,
    /** A whole guess (an action, a word, a text): it is read ALONE, one voice, normal speech, before the short items of the cycle (the guess slot). */
    val guess: Boolean = false,
)

/** How fast one cycle runs: [itemMs] per short item, speech [speed] (1.0 = normal), [ears] 1 = one channel in both ears, 2 = two voices (Ticker). */
data class LoopPace(val itemMs: Int = Pacing.LOOP_ITEM_MS, val speed: Double = Pacing.LOOP_SPEED, val ears: Int = 2)

/** What was chosen: [rank] = its place in the list the caller gave, [cycles] = whole cycles heard before the choice, [ms] = from the first sound of the round, [clicks] = clicks of the round. */
class LoopChoice<K>(val key: K, val rank: Int, val cycles: Int, val ms: Long, val clicks: Int)

/**
 * The focus round (Huffman / probabilistic scanning with noisy switches: Roark, Fried-Oken, Gibbons et al., Computer Speech & Language 2013):
 * a click that does not choose stops the loop; the next round plays only the most likely items ([min] to [max], together about [cover] of the chance)
 * plus Undo, [itemMs] per item, most likely first. The model keeps its scores. With no click for [passes] passes the whole loop starts again with the evidence kept.
 */
class LoopFocus(
    val itemMs: Int = Pacing.LOOP_FOCUS_MS,
    val cover: Double = Pacing.LOOP_FOCUS_COVER,
    val min: Int = 2,
    val max: Int = 6,
    val passes: Int = 2,
    val pauseMs: Int = Pacing.LOOP_FOCUS_PAUSE_MS,
) {
    /** The items of the focus round: most likely first until [cover] (at least [min], at most [max]), then every Undo item. [chance] = the chance of each item now. */
    fun <K> pick(items: List<LoopItem<K>>, chance: Map<K, Double>): List<LoopItem<K>> {
        val likely = items.filter { !it.undo }.sortedByDescending { chance[it.key] ?: 0.0 }
        var sum = 0.0
        val out = ArrayList<LoopItem<K>>()
        for (it in likely) {
            if (out.size >= max || (sum >= cover && out.size >= min)) break
            out.add(it); sum += chance[it.key] ?: 0.0
        }
        out.addAll(items.filter { it.undo })
        return out
    }
}

/** What the loop needs to make sound. The Android side is `engines/LoopSound`; tests use a fake one with fake time. All calls on the main thread. */
interface LoopAudio {
    /** Frames per second of [nowFrame] and of the clips. */
    val sampleRate: Int
    /** Make the clips of [texts] (speech, already at [speed] times normal speech, mono floats at [sampleRate]); [then] gets the ready ones (a text that could not be made is missing). */
    fun prepare(texts: List<String>, speed: Double, then: (Map<String, FloatArray>) -> Unit)
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
 * The guess slot: items with [LoopItem.guess] (a whole action, word or text) are NOT in the fast channels. Each is read alone in the middle, at
 * normal speech, with [Pacing.LOOP_GUESS_GAP_MS] of quiet after it, before the short items of the cycle; each has its own onset for the click model.
 * Pace ([LoopPace]): at the beginner levels one channel in both ears, slower items and speech; two ears only at the top level.
 * Channels (two ears): item 0, 2, 4... voice A (left), item 1, 3, 5... voice B (right), so the two best items come first in each ear;
 * the caller puts the controls (Space, Undo, Send) last. Channel B starts half an item later. An item takes [LoopPace.itemMs], or
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
    /** The pace of the short items; the caller may change it between rounds (the beginner levels, [com.faka.invisible.logickit.LoopLevel]). */
    var pace: LoopPace = LoopPace(),
    private val idleCycles: Int = Pacing.LOOP_IDLE_CYCLES,
    private val gapMs: Int = Pacing.LOOP_GAP_MS,
    private val leadMs: Int = Pacing.LOOP_LEAD_MS,
    /** What the person learned before (see [offsets]): click offsets in seconds, newest first. */
    seed: List<Double> = emptyList(),
    /** The model's start values (from the Click test when the person has no learned clicks yet). */
    private val params: ClickModel.Params = ClickModel.Params(),
    private val log: (event: String, fields: List<Any?>) -> Unit = { _, _ -> },
    private val onChosen: (LoopChoice<K>) -> Unit,
    private val onIdle: () -> Unit = {},
    /** The focus round after a click that does not choose; null = the whole loop plays on. */
    private val focus: LoopFocus? = LoopFocus(),
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
    private var clips: Map<String, FloatArray> = emptyMap()
    private var fullLive: List<LoopItem<K>> = emptyList()   // the items of the whole loop that have a clip
    private var focused = false
    private var fullScores: Map<K, Double> = emptyMap()     // what the model thought of every item when the focus round began
    private var roundWall = 0.0                             // first sound of the round (the whole loop's)
    private var roundPeriod = 0.0
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
        halt(); items = list; focused = false
        if (list.isEmpty()) return
        state = State.PREPARING
        val g = ++gen
        val guessTexts = list.filter { it.guess }.map { it.say.text }.distinct()
        val shortTexts = list.filter { !it.guess }.map { it.say.text }.distinct().filter { it !in guessTexts }
        // guesses are read at normal speech, the short items at the pace of the level
        audio.prepare(guessTexts, 1.0) { a -> if (g == gen && state == State.PREPARING) audio.prepare(shortTexts, pace.speed) { b -> if (g == gen && state == State.PREPARING) begin(a + b) } }
    }

    /** Ms until the cycle now playing ends and the next one starts (0 when the loop is not running): the moment a new item list may take over without changing a cycle being heard. */
    fun msToNextCycle(): Long = if (state != State.RUNNING || periodMs <= 0.0) 0L else Math.round(periodMs - ((audio.nowMs() - startWall).coerceAtLeast(0.0) % periodMs))

    /** Start the same round again after [onIdle]. */
    fun resume() { if (state == State.PAUSED) start(items) }

    /** Stop for good: no sound, no timers. */
    fun stop() { halt(); state = State.OFF }

    private fun halt() {
        gen++; timers.clear(TIMER)
        if (state == State.RUNNING || state == State.PREPARING) audio.clear()   // a sound that is not the loop's (the confirm) is never cut
        if (state != State.OFF) state = State.PAUSED
    }

    private fun begin(made: Map<String, FloatArray>) {
        clips = made
        val live = items.filter { made[it.say.text] != null }
        if (live.isEmpty()) { state = State.PAUSED; log("loop-empty", listOf(items.size)); return }
        fullLive = live; focused = false
        play(live, pace.itemMs, null, 0)
        roundWall = startWall; roundPeriod = periodMs
        clicks = 0
    }

    /**
     * Play [live] as one cycle, [ms] per item, [pauseMs] after the lead. [carry] = the scores of a round that goes on (a focus round or the whole loop after it): the model
     * keeps them and what it learned; null = a new round (priors, the leader's start handicap).
     */
    private fun play(live: List<LoopItem<K>>, ms: Int, carry: Map<K, Double>?, pauseMs: Int) {
        val sr = audio.sampleRate
        val twoEars = pace.ears >= 2
        val half = if (twoEars) ms / 2.0 else 0.0
        val at = HashMap<K, Double>(); val ps = ArrayList<Triple<FloatArray, Long, Double>>()
        // the guess slot: each guess alone, in the middle, at normal speech, with quiet after it
        var t = 0.0
        for (item in live.filter { it.guess }) {
            val clip = clips.getValue(item.say.text)
            at[item.key] = t
            ps.add(Triple(clip, Math.round(t * sr / 1000.0), 0.0))
            t += clip.size * 1000.0 / sr + Pacing.LOOP_GUESS_GAP_MS
        }
        // the short items: one channel (both ears the same), or two voices half an item apart
        val end = doubleArrayOf(t, t + half)   // where the next item of channel A and of channel B starts
        var longest = t
        live.filter { !it.guess }.forEachIndexed { i, item ->
            val ch = if (twoEars) i % 2 else 0
            val clip = clips.getValue(item.say.text)
            val len = clip.size * 1000.0 / sr
            at[item.key] = end[ch]
            ps.add(Triple(clip, Math.round(end[ch] * sr / 1000.0), if (!twoEars) 0.0 else if (ch == 0) -1.0 else 1.0))
            end[ch] += max(ms.toDouble(), len + gapMs)
            longest = max(longest, end[ch] - (if (ch == 1) half else 0.0))
        }
        periodMs = longest
        cycleFrames = Math.round(periodMs * sr / 1000.0)
        plays = ps; onsets = at
        cycleStartFrame = audio.nowFrame() + sr * (leadMs + pauseMs) / 1000
        startWall = audio.wallMsOf(cycleStartFrame)
        val m = ClickModel<K>(periodMs, params)
        val risky = live.filter { it.risky }.associate { it.key to m.params.winDiffHigh }
        if (carry != null) {
            (model ?: prev)?.let { m.inherit(it) }
            m.restart(carry, at, startWall, risky)
        } else {
            val before = prev
            if (before != null) m.inherit(before) else m.seed(seedOffsets)
            m.start(live.associate { it.key to it.prior.coerceAtLeast(1e-9) }, at, startWall, risky)
        }
        model = m; lastActiveMs = startWall.toLong()
        state = State.RUNNING
        schedule(0); schedule(1)
        log("loop-start", listOf(live.size, periodMs.toLong(), startWall.toLong()))
        next(0)
    }

    /** A click that did not choose: play only the likely items for a while. */
    private fun refocus(m: ClickModel<K>) {
        val f = focus ?: return
        val set = f.pick(fullLive, m.posteriors())
        val focusMs = max(f.itemMs, pace.itemMs + Pacing.LOOP_FOCUS_EXTRA_MS)
        if (set.size >= fullLive.size) return   // nothing would be left out
        val now = m.scoresMap()
        fullScores = if (focused) fullScores + now else now
        halt()
        play(set, focusMs, now.filterKeys { k -> set.any { it.key == k } }, f.pauseMs)
        focused = true
        log("focus", listOf("n=${set.size}", "ms=$focusMs"))
    }

    /** No click in the focus round: the whole loop again, with what the clicks said. */
    private fun unfocus() {
        val m = model ?: return
        val carry = fullScores + m.scoresMap()
        halt()
        play(fullLive, pace.itemMs, fullLive.associate { it.key to (carry[it.key] ?: 0.0) }, 0)
        focused = false
        log("focus-end", listOf(fullLive.size))
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
            if (focused && focus != null && audio.nowMs() - lastActiveMs >= focus.passes * periodMs - periodMs / 2) { unfocus(); return@set }
            if (audio.nowMs() - lastActiveMs >= min(idleCycles * periodMs, max(Pacing.LOOP_IDLE_MAX_MS.toDouble(), 1.5 * periodMs)) - periodMs / 2) { pause(); return@set }
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
        if (won) finish(m, m.chosen() ?: return, timeMs) else refocus(m)
    }

    private fun finish(m: ClickModel<K>, key: K, timeMs: Long) {
        val rank = items.indexOfFirst { it.key == key }
        val item = items[rank]
        val c = LoopChoice(key, rank, ((timeMs - roundWall) / roundPeriod).toInt(), (timeMs - roundWall).toLong(), clicks)
        halt(); state = State.PAUSED; focused = false
        audio.confirm()
        if (item.undo) m.undo() else m.learn(key)
        prev = m; model = null
        log("loop-choice", listOf(key, c.rank, c.cycles, c.ms, c.clicks))
        onChosen(c)
    }

    private companion object { const val TIMER = "loop:cycle" }
}
