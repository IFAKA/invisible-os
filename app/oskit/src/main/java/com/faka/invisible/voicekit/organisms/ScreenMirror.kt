package com.faka.invisible.voicekit

/**
 * ORGANISM. What the OS is doing right now, for the app screen: the list being read, the item being read and the last button
 * gesture, so a sighted person can follow. Written by [Scanner] and the button, read by the app screen. Nothing here is spoken.
 */
object ScreenMirror {
    /**
     * The letter loop on screen (what you hear is what you see): [header] = the rows above (typed text, status), [ring] = the items of the cycle
     * in the order they sound, [at] = the one sounding now (-1 = none), [chosen] = the item just taken (shown until the next round).
     */
    data class LoopSnap(val header: List<String> = emptyList(), val ring: List<LoopShown> = emptyList(), val at: Int = -1, val chosen: LoopShown? = null)

    /** One sound that played: its short [name], when it started, and how many identical ones were merged into it. */
    data class SoundMark(val name: String, val onsetMs: Long, val count: Int = 1)

    /** One immutable picture of the OS. [pressAt] is the clock time of [lastPress] (0 = none). */
    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val items: List<String> = emptyList(),
        val index: Int = 0,
        val lastPress: String? = null,
        val pressAt: Long = 0,
        val loop: LoopSnap? = null,
        /** The last [SoundCues.KEEP] sounds without words, oldest first (the beat strip). */
        val sounds: List<SoundMark> = emptyList(),
    )

    @Volatile var state = Snapshot(); private set
    @Volatile var onChange: (() -> Unit)? = null
    /** Clock for press times; tests replace it. */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }

    val active get() = state.active
    val title get() = state.title
    val items get() = state.items
    val index get() = state.index
    val lastPress get() = state.lastPress

    private inline fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { state = f(state) }
        onChange?.invoke()
    }

    /** Fold one [Cue] into the picture. The only way list items, lines, loop items and presses reach the screen. */
    fun apply(c: Cue) = update { reduce(it, c, now) }

    /** Pure: the picture after [c]. */
    fun reduce(s: Snapshot, c: Cue, now: () -> Long = { System.currentTimeMillis() }): Snapshot = when (c.kind) {
        Cue.Kind.LIST_ITEM -> s.copy(active = true, items = c.list, index = c.index.coerceIn(0, maxOf(0, c.list.size - 1)), loop = null)
        Cue.Kind.LINE -> if (c.text.isBlank()) s else { val l = (s.items + c.text).takeLast(4); s.copy(active = true, items = l, index = l.size - 1, loop = null) }
        Cue.Kind.PRESS -> s.copy(lastPress = c.text, pressAt = if (c.onsetMs >= 0) c.onsetMs else now())
        Cue.Kind.SOUND -> if (c.text.isBlank()) s else {
            val at = if (c.onsetMs >= 0) c.onsetMs else now()
            val last = s.sounds.lastOrNull()
            s.copy(sounds = if (last != null && last.name == c.text && at - last.onsetMs < SoundCues.MERGE_MS) s.sounds.dropLast(1) + last.copy(onsetMs = at, count = last.count + 1)
                else (s.sounds + SoundMark(c.text, at)).takeLast(SoundCues.KEEP))
        }
        Cue.Kind.LOOP_ITEM -> s.loop?.let { lp ->
            s.copy(loop = when {
                c.ring != null -> lp.copy(ring = c.ring, at = -1, chosen = null)
                c.index < 0 -> lp.copy(at = -1)
                c.chosen -> lp.copy(at = c.index, chosen = c.item ?: LoopShown(null, c.text, false))
                else -> lp.copy(at = c.index, chosen = null)
            })
        } ?: s
    }

    /** The loop screen with [header] rows above; the items come from the loop's LOOP_ITEM cues. A new round starts with an empty ring. */
    fun loopHeader(header: List<String>) = update { it.copy(active = true, items = emptyList(), index = 0, loop = LoopSnap(header)) }


    /** Move the mark to item [index] of the list on screen (the one a click would choose now). */
    fun point(index: Int) = update { it.copy(index = index.coerceIn(0, maxOf(0, it.items.size - 1))) }

    /** A program starts: its name, and no list (the menu list it was picked from is no longer being read). */
    fun program(title: String) = update { it.copy(active = true, title = title, items = emptyList(), index = 0, loop = null) }

    fun setTitle(title: String) = update { it.copy(active = true, title = title) }

    /** A button gesture, by its plain name ("click", "2 clicks", "hold"). */
    fun pressed(gestureName: String) = Cues.press(gestureName, now())

    /** The OS closed: back to the plain screen. */
    fun clear() = update { Snapshot() }

    /** Plain name of a gesture for the screen. */
    fun gestureText(name: String): String = when (name.uppercase()) {
        "SINGLE" -> "click"
        "DOUBLE" -> "2 clicks"
        "TRIPLE" -> "3 clicks"
        "QUAD" -> "4 clicks"
        "CLICK_HOLD" -> "hold"           // the detector names a plain hold CLICK_HOLD and click-then-hold HOLD
        "HOLD" -> "click, then hold"
        else -> name.lowercase().replace('_', ' ')
    }
}
