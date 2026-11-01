package com.faka.invisible.voicekit

/**
 * ATOM. The press a spoken line asks for, as a picture: one source for the voice and the screen. A line made with
 * [Phrase.asks] carries it; the kit publishes it with the line ([Cue.press]) and `Kit` draws it as [glyphs]
 * (orange: click = a dot, hold = a long bar). Pure, no Android.
 */
data class PressHint private constructor(val clicks: Int, val hold: Boolean) {
    enum class Glyph { DOT, BAR }

    /** What to draw, left to right: a dot per click, then a bar for a hold. */
    val glyphs: List<Glyph> get() = List(clicks) { Glyph.DOT } + (if (hold) listOf(Glyph.BAR) else emptyList())

    companion object {
        /** [n] clicks (1 to 4). */
        fun click(n: Int = 1) = PressHint(n.coerceIn(1, 4), false)
        val HOLD = PressHint(0, true)
        val CLICK_HOLD = PressHint(1, true)

        /** The hint for the plain gesture name the screen keeps ([ScreenMirror.gestureText]); null when it is not a press. */
        fun fromGestureText(t: String?): PressHint? = when (t) {
            "click" -> click(1)
            "2 clicks" -> click(2)
            "3 clicks" -> click(3)
            "4 clicks" -> click(4)
            "hold" -> HOLD
            "click, then hold" -> CLICK_HOLD
            else -> null
        }
    }
}
