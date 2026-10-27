package com.faka.invisible.voicekit

/**
 * ORGANISM. What the OS is doing right now, for the app screen: the list being read, the item being read and the last button
 * gesture, so a sighted person can follow. Written by [Scanner] and the button, read by the app screen. Nothing here is spoken.
 */
object ScreenMirror {
    /** One immutable picture of the OS. [pressAt] is the clock time of [lastPress] (0 = none). */
    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val items: List<String> = emptyList(),
        val index: Int = 0,
        val lastPress: String? = null,
        val pressAt: Long = 0,
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
    fun show(items: List<String>, index: Int) = update { it.copy(active = true, items = items, index = index.coerceIn(0, maxOf(0, items.size - 1))) }

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
        "HOLD" -> "hold"
        "CLICK_HOLD" -> "click, then hold"
        "CLICK_HOLD_END" -> "release"
        else -> name.lowercase().replace('_', ' ')
    }
}
