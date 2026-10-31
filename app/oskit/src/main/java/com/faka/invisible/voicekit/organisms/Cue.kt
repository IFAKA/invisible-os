package com.faka.invisible.voicekit

import com.faka.invisible.logickit.squash

/**
 * ORGANISM. One thing the phone says or plays, as the screen must show it: the single event of "what you hear is what you see".
 * The kit emits it ([Cues.emit]); [ScreenMirror] folds the stream into the one [ScreenMirror.Snapshot] that `Kit` draws.
 *
 * [onsetMs] = the time the sound starts, on the clock of the emitter (-1 = not known: "now", the moment of the call; step 2 of
 * CUE-PLAN.md sets it from the audio onset). [chosen] = the item was taken. [index] and the context ([list], [ring]) say where it sits.
 */
data class Cue(
    val kind: Kind,
    val text: String = "",
    val onsetMs: Long = -1,
    val chosen: Boolean = false,
    val index: Int = -1,
    /** LIST_ITEM: the whole list being read. */
    val list: List<String> = emptyList(),
    /** LOOP_ITEM: the items of the round in the order they sound (set when a new round starts, null otherwise). */
    val ring: List<LoopShown>? = null,
    /** LOOP_ITEM: the item itself (what the picture draws). */
    val item: LoopShown? = null,
) {
    enum class Kind {
        /** Item [index] of [list] starts. */
        LIST_ITEM,
        /** Loop item [index] of the ring starts ([chosen] = taken); index -1 = a new round ([ring]) or nothing plays now. */
        LOOP_ITEM,
        /** A line spoken outside a list. */
        LINE,
        /** A sound with no words (an earcon, a game sound). */
        SOUND,
        /** A button gesture; [text] is its plain name. */
        PRESS,
    }
}

/** The one publisher. Everything the kit says or plays goes through [emit]; today the only listener is [ScreenMirror]. */
object Cues {
    /** A test listener that sees every emitted cue (the parity helper). Never set in the app. */
    @Volatile var tap: ((Cue) -> Unit)? = null
    fun emit(c: Cue) { ScreenMirror.apply(c); tap?.invoke(c) }

    /**
     * Spoken cues (LIST_ITEM, LINE) are staged right before the call that speaks them; the next `Narrator.say` takes the
     * staged cue and holds it in a [CueGate] until the engine starts the sound. Nothing else reads this slot.
     */
    private var staged: Cue? = null
    fun stage(c: Cue) { synchronized(this) { staged = c } }
    fun hasStaged(): Boolean = synchronized(this) { staged != null }
    fun takeStaged(): Cue? = synchronized(this) { staged.also { staged = null } }
    private fun norm(t: String) = t.squash().lowercase()
    /** True when [staged] is the cue of exactly this spoken text (same words, case and spacing aside). */
    private fun fits(c: Cue, spoken: String): Boolean { val a = norm(c.text); val b = norm(spoken); return a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a) }
    /** The staged cue if it belongs to the line about to be spoken. A cue staged for something else (its speak call never came) is dropped, so it never shows under a later, unrelated line. */
    fun takeStagedFor(spoken: String): Cue? = takeStaged()?.takeIf { fits(it, spoken) }
    /** A staged cue for exactly [text] is waiting (the caller need not stage it again). */
    fun hasStagedFor(text: String): Boolean = synchronized(this) { staged?.let { fits(it, text) } == true }
    fun clearStaged() { synchronized(this) { staged = null } }
    fun stageListItem(items: List<String>, index: Int) = stage(Cue(Cue.Kind.LIST_ITEM, text = items.getOrElse(index) { "" }, index = index, list = items))
    fun stageLine(text: String) = stage(Cue(Cue.Kind.LINE, text))

    fun listItem(items: List<String>, index: Int) = emit(Cue(Cue.Kind.LIST_ITEM, text = items.getOrElse(index) { "" }, index = index, list = items))
    fun line(text: String) = emit(Cue(Cue.Kind.LINE, text))
    /** The pair keyboard: [header] rows (what is written so far, not heard) above the [heard] rows (the pair now played); the mark is on the first heard row. */
    fun pairScreen(header: List<String>, heard: List<String>) = emit(Cue(Cue.Kind.LIST_ITEM, text = heard.firstOrNull().orEmpty(), index = header.size, list = header + heard))
    fun press(gestureName: String, at: Long) = emit(Cue(Cue.Kind.PRESS, gestureName, onsetMs = at))
    fun sound(name: String, at: Long = -1) = emit(Cue(Cue.Kind.SOUND, name, onsetMs = at))
}

/**
 * ORGANISM. Every sound without words (earcon, screen tone, close sound, beat of a game) tells the picture here when it really starts.
 * [played] is cheap and safe from any thread (the `tones` thread, the audio thread): with no delay it emits at once; with a delay
 * (a sound the mixer will output in [delayMs]) it posts to the main thread, so the mark never shows before the sound.
 * Never do slow work here: sounds start on their own threads and a gesture must stay fast.
 */
object SoundCues {
    /** About the delay between the mixer writing a block and the ear hearing it. */
    const val LATENCY_MS = 60L
    /** The picture keeps this many marks. */
    const val KEEP = 8
    /** The same sound again within this time is merged into one mark (count + 1): a fast tick never floods the strip. */
    const val MERGE_MS = 150L

    @Volatile var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile var post: (Long, () -> Unit) -> Unit = { d, f -> mainHandler.postDelayed(f, d) }

    fun played(name: String, delayMs: Long = 0) {
        if (delayMs <= 0) Cues.sound(name, clock())
        else post(delayMs) { Cues.sound(name, clock()) }
    }
}

/**
 * ORGANISM part. Holds the cues of spoken lines until the speech engine starts the sound, then emits them with the real onset time
 * (what you hear is what you see: the picture changes when the sound does, never before). One gate per [Narrator]; main thread.
 *
 * A non-queued line replaces everything still waiting (those were never heard, so they are dropped, never shown). A queued line
 * waits behind them. [started] emits all waiting cues in order (the engine tells only the newest start). [fail] (speech ended
 * without a start, an error, no voice, or the timeout) emits them anyway with the current time, so the screen never freezes.
 * [cancel] (speech stopped) drops them.
 */
class CueGate(private val emit: (Cue) -> Unit = Cues::emit) {
    private val waiting = ArrayList<Cue>()
    val pending get() = waiting.size

    fun hold(c: Cue, queue: Boolean) { if (!queue) waiting.clear(); waiting += c }
    fun started(nowMs: Long) = release(nowMs)
    fun fail(nowMs: Long) = release(nowMs)
    fun cancel() = waiting.clear()

    private fun release(nowMs: Long) {
        val l = ArrayList(waiting); waiting.clear()
        l.forEach { emit(it.copy(onsetMs = nowMs)) }
    }
}
