package com.faka.invisible

/**
 * Design tokens: the only colours, type sizes, spaces and radii of the app. Pure data (no Android), so tests can check it.
 * Screens never use a number or a colour of their own: they use [Kit] parts, which are built from these. See DESIGN.md.
 */
object Palette {
    /** One colour role. [text] roles are drawn as text on the page and must have 4.5:1 contrast on it (checked by DesignTokensTest). */
    class Role(val name: String, val light: String, val dark: String, val text: Boolean, val meaning: String)

    val INK = Role("ink", "#14181C", "#FFFFFF", true, "Main text.")
    val SOFT = Role("soft", "#5F6B76", "#9AA7B5", true, "Quiet text: hints, captions, lines before and after.")
    val NOW = Role("now", "#00677F", "#19E3FF", true, "Cyan. What is being spoken this moment.")
    val PRESS = Role("press", "#A65700", "#FF9A1F", true, "Orange. The press the person must make.")
    val DONE = Role("done", "#17722F", "#32D74B", true, "Green. It worked, it was picked.")
    val BAD = Role("bad", "#D70015", "#FF453A", true, "Red. A problem. Nothing else is red.")
    val PAGE = Role("page", "#EEF3F6", "#05080D", false, "Screen background.")
    val CARD = Role("card", "#FFFFFF", "#0D141C", false, "Grouped surface.")
    val FIELD = Role("field", "#E3EAEF", "#141E29", false, "Input box and row inside a card.")
    val TINT = Role("tint", "#DDF1F6", "#0B2A38", false, "Soft button and chip background.")
    val LINE = Role("line", "#C9D8DF", "#16404D", false, "Hairline (faint cyan in the dark).")
    val EDGE = Role("edge", "#AFC0C9", "#3B5563", false, "Off switch, slider track, chevron.")
    val ACCENT_FILL = Role("accentFill", "#006E8A", "#006E8A", false, "Filled button background (white text, 4.5:1).")

    val all = listOf(INK, SOFT, NOW, PRESS, DONE, BAD, PAGE, CARD, FIELD, TINT, LINE, EDGE, ACCENT_FILL)

    /** WCAG contrast ratio of two #RRGGBB colours. */
    fun contrast(a: String, b: String): Double {
        fun lum(h: String): Double {
            fun ch(i: Int): Double { val v = h.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(0) + 0.7152 * ch(1) + 0.0722 * ch(2)
        }
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}

/**
 * The type scale. Four sizes with fixed jobs, no others. Weight rule: DISPLAY and TITLE are medium, BODY and CAPTION regular.
 * [MONO] is the same size as CAPTION in a fixed-width face (code and terminal text only).
 */
enum class Type(val size: Float, val face: String, val job: String) {
    DISPLAY(40f, "sans-serif-medium", "The NOW line: what is heard this moment. Seen in a glance."),
    TITLE(28f, "sans-serif-medium", "Screen titles."),
    BODY(18f, "sans-serif", "Rows and normal text."),
    CAPTION(14f, "sans-serif", "Hints, footers, quiet lines."),
    MONO(14f, "monospace", "Fixed-width text (terminal, patches).");

    companion object {
        /** The NOW line is shown in one of three discrete steps, never in between: DISPLAY if it fits in 4 lines, else TITLE if it fits in 6, else BODY. */
        const val DISPLAY_LINES = 4
        const val TITLE_LINES = 6
        fun nowStep(linesAt: (Type) -> Int): Type = when {
            linesAt(DISPLAY) <= DISPLAY_LINES -> DISPLAY
            linesAt(TITLE) <= TITLE_LINES -> TITLE
            else -> BODY
        }
    }
}

/** Space (dp, an 8 grid), corner radii, stroke, glow and touch size. The only values screens may use. */
object Space {
    const val S1 = 8
    const val S2 = 16
    const val S3 = 24
    const val S4 = 32
    const val S6 = 48
    val steps = listOf(S1, S2, S3, S4, S6)

    /** Small things: rows, buttons, chips, fields. */
    const val R1 = 12
    /** Big surfaces: cards. */
    const val R2 = 20
    val radii = listOf(R1, R2)

    /** The one line width (dp): hairlines and borders. */
    const val STROKE = 1
    /** Glow radius (dp) of the NOW line. Only the NOW line glows. */
    const val GLOW = 10
    /** Smallest touch target (dp). */
    const val TOUCH = 48
    /** Height of the one filled main button (dp). */
    const val BUTTON = 56
}
