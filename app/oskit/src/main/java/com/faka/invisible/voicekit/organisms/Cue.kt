package com.faka.invisible.voicekit

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
    fun emit(c: Cue) = ScreenMirror.apply(c)

    /**
     * Spoken cues (LIST_ITEM, LINE) are staged right before the call that speaks them; the next `Narrator.say` takes the
     * staged cue and holds it in a [CueGate] until the engine starts the sound. Nothing else reads this slot.
     */
    private var staged: Cue? = null
    fun stage(c: Cue) { synchronized(this) { staged = c } }
    fun takeStaged(): Cue? = synchronized(this) { staged.also { staged = null } }
    fun stageListItem(items: List<String>, index: Int) = stage(Cue(Cue.Kind.LIST_ITEM, text = items.getOrElse(index) { "" }, index = index, list = items))
    fun stageLine(text: String) = stage(Cue(Cue.Kind.LINE, text))

    fun listItem(items: List<String>, index: Int) = emit(Cue(Cue.Kind.LIST_ITEM, text = items.getOrElse(index) { "" }, index = index, list = items))
    fun line(text: String) = emit(Cue(Cue.Kind.LINE, text))
    fun press(gestureName: String, at: Long) = emit(Cue(Cue.Kind.PRESS, gestureName, onsetMs = at))
    fun sound(name: String) = emit(Cue(Cue.Kind.SOUND, name))
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
