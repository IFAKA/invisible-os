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

    /** One immutable picture of the OS. [pressAt] is the clock time of [lastPress] (0 = none). */
    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val items: List<String> = emptyList(),
        val index: Int = 0,
        val lastPress: String? = null,
        val pressAt: Long = 0,
        val loop: LoopSnap? = null,
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

    /** A list item starts: the whole list and the item being read. */
    fun show(items: List<String>, index: Int) = update { it.copy(active = true, items = items, index = index.coerceIn(0, maxOf(0, items.size - 1)), loop = null) }

    /** The loop screen with [header] rows above; the items come from [loopView]. A new round starts with an empty ring. */
    fun loopHeader(header: List<String>) = update { it.copy(active = true, items = emptyList(), index = 0, loop = LoopSnap(header)) }

    /** The loop's items, drawn as they sound. Give it to `LetterLoop.view`. */
    val loopView = object : LoopView {
        override fun round(items: List<LoopShown>) = updateLoop { it.copy(ring = items, at = -1, chosen = null) }
        override fun shown(index: Int, item: LoopShown) = updateLoop { it.copy(at = index, chosen = null) }
        override fun chosen(index: Int, item: LoopShown) = updateLoop { it.copy(at = index, chosen = item) }
        override fun idle() = updateLoop { it.copy(at = -1) }
    }
    private fun updateLoop(f: (LoopSnap) -> LoopSnap) { if (state.loop != null) update { st -> st.loop?.let { st.copy(loop = f(it)) } ?: st } }

    /** Move the mark to item [index] of the list on screen (the one a click would choose now). */
    fun point(index: Int) = update { it.copy(index = index.coerceIn(0, maxOf(0, it.items.size - 1))) }

    /** A program starts: its name, and no list (the menu list it was picked from is no longer being read). */
    fun program(title: String) = update { it.copy(active = true, title = title, items = emptyList(), index = 0, loop = null) }

    /** A line spoken outside a list (a message, a call, a level, a hint): the last few lines, the newest marked, so the screen shows what is said. */
    fun said(line: String) = update { val l = (it.items + line).takeLast(4); it.copy(active = true, items = l, index = l.size - 1, loop = null) }

    fun setTitle(title: String) = update { it.copy(active = true, title = title) }

    /** A button gesture, by its plain name ("click", "2 clicks", "hold"). */
    fun pressed(gestureName: String) = update { it.copy(lastPress = gestureName, pressAt = now()) }

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
