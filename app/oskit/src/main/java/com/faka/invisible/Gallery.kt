package com.faka.invisible

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.faka.invisible.voicekit.PressHint

/**
 * The design gallery (dev only: `show GALLERY`): every token and every component once, built only from the kit,
 * so one screenshot judges the whole system. If a part looks wrong here it looks wrong everywhere.
 */
fun Kit.gallery(body: LinearLayout) {
    fun lp(top: Int = 0, w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    body.setPadding(dp(Ui.S2), dp(Ui.S2), dp(Ui.S2), dp(Ui.S4))
    body.addView(header("Design gallery", {}, "Home"))
    body.addView(text("Tokens: ${if (Ui.dark) "dark" else "light"} mode", Type.CAPTION, Ui.soft), lp(Ui.S1))

    card(body, "Colour roles") {
        Palette.all.forEach { r ->
            val sw = swatch(Ui.of(r))
            val label = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(r.name + "  " + (if (Ui.dark) r.dark else r.light)))
                addView(text(r.meaning, Type.CAPTION, Ui.soft))
            }
            addView(row(sw.apply { layoutParams = LinearLayout.LayoutParams(dp(Ui.S6), dp(Ui.S4)).apply { marginEnd = dp(Ui.S2) } }, grow(label)), lp(Ui.S1))
        }
    }

    card(body, "Type scale", "Four steps. Display and title are medium, body and caption regular.") {
        Type.values().forEach { t -> addView(text("${t.name.lowercase()} ${t.size.toInt()}  ${if (t == Type.DISPLAY || t == Type.TITLE) "medium" else "regular"}", t, if (t == Type.DISPLAY) Ui.now else Ui.ink), lp(Ui.S1)) }
        addView(text("Job of display: " + Type.DISPLAY.job, Type.CAPTION, Ui.soft), lp(Ui.S1))
    }

    card(body, "NOW line steps", "Display if 4 lines fit, else title, else body. Never in between.") {
        val a = text("", Type.DISPLAY, Ui.now); nowLine(a, "Reply to Ana", Ui.now)
        val b = text("", Type.DISPLAY, Ui.now); nowLine(b, "Ana says: are you coming to dinner tonight? Bring the bread", Ui.now)
        val c2 = text("", Type.DISPLAY, Ui.now); nowLine(c2, "Ana says: are you coming to dinner tonight, or will you stay late at the office with the others, and should I keep a plate warm for you", Ui.now)
        listOf(a, b, c2).forEach { it.gravity = Gravity.CENTER; addView(it, lp(Ui.S1)) }
        val d = glyphRow(PressHint.click(2)); addView(d, lp(Ui.S1, -1, dp(Ui.S6 + Ui.S2)))
        addView(glyphRow(PressHint.CLICK_HOLD), lp(0, -1, dp(Ui.S6 + Ui.S2)))
    }

    card(body, "Spacing", "S1 8, S2 16, S3 24, S4 32, S6 48 dp") {
        Space.steps.forEach { n ->
            val bar = swatch(Ui.of(Palette.ACCENT_FILL), 2)
            addView(row(bar.apply { layoutParams = LinearLayout.LayoutParams(dp(n * 3), dp(Ui.S1)).apply { marginEnd = dp(Ui.S2) } }, text("$n dp", Type.CAPTION, Ui.soft)), lp(Ui.S1))
        }
    }

    card(body, "Radii and stroke", "R1 12 for rows, buttons, chips. R2 20 for cards. One stroke, one glow (only the NOW line).") {
        val r1 = swatch(Ui.card, Space.R1); val r2 = swatch(Ui.card, Space.R2)
        addView(row(r1.apply { layoutParams = LinearLayout.LayoutParams(0, dp(Ui.S6 + Ui.S2), 1f).apply { marginEnd = dp(Ui.S2) } },
            r2.apply { layoutParams = LinearLayout.LayoutParams(0, dp(Ui.S6 + Ui.S2), 1f) }), lp(Ui.S1))
    }

    body.addView(text("Components", Type.TITLE), lp(Ui.S3))
    header("Header", {}, "Back").let { body.addView(it, lp(Ui.S1)) }
    group(body, "Group header", "Group footer") {
        navRow(this, "Row title (body)", "Row summary (caption)") {}
        dotRow(this, Ui.done, "Row with a dot", "Green means reachable") {}
        choiceRow(this, "Choice, selected", true) {}
        choiceRow(this, "Choice, not selected", false) {}
        toggleRow(this, "Switch row", "Caption under the label", true) {}
    }
    val chips = row()
    listOf("Done" to Ui.done, "Press" to Ui.press, "Problem" to Ui.bad, "Quiet" to Ui.soft).forEach { (l, col) -> chips.addView(statusChip(l, col), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(Ui.S1) }) }
    body.addView(chips, lp(Ui.S3))
    body.addView(primary("Primary button") {}, lp(Ui.S3))
    body.addView(btn("Soft button") {}, lp(Ui.S1))
    body.addView(quiet("Quiet button") {}, lp(Ui.S1, -2))
    val chipRow = row()
    chipRow.addView(chip("Chip", true) {}, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(Ui.S1) })
    chipRow.addView(chip("Chip") {})
    body.addView(chipRow, lp(Ui.S1))
    body.addView(tabs(listOf("One", "Two", "Three"), 1) {}, lp(Ui.S1))
    footer(body, "Footer: a quiet caption under a page.")
}

private fun Kit.glyphRow(h: PressHint): View = pressGlyph(false).also { it.hint = h }
