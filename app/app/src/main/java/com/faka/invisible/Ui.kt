package com.faka.invisible

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * UI Kit: every colour and corner size of the screen you can see. Change a value here, the whole app changes. */
object Ui {
    val ink = Color.parseColor("#1B1F23")         // main text
    val soft = Color.parseColor("#5F6872")        // hints
    val accent = Color.parseColor("#2F5BEA")      // buttons, values
    val good = Color.parseColor("#1E8E4E")
    val bad = Color.parseColor("#C5392B")
    val page = Color.parseColor("#F3F4F7")        // screen background, task row
    val tint = Color.parseColor("#E8EDFB")        // soft button, chip
    val edge = Color.parseColor("#B8C0CC")        // outline of the input box
    val card = Color.WHITE
    val onAccent = Color.WHITE
    val clear = Color.TRANSPARENT

    // corner radius in dp
    const val RADIUS_CARD = 14
    const val RADIUS_ROW = 12
    const val RADIUS_CHIP = 16
    const val RADIUS_BUTTON = 24
}

/** UI Kit parts (composable). Screens build only from these and the [Ui] values; a missing part is added here first. */
class Kit(private val c: Context) {
    fun dp(n: Int) = (n * c.resources.displayMetrics.density).toInt()

    fun text(t: String, size: Float = 16f, color: Int = Ui.ink, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    fun btn(label: String, filled: Boolean = false, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        stateListAnimator = null; elevation = 0f
        if (filled) { setTextColor(Ui.onAccent); background = GradientDrawable().apply { setColor(Ui.accent); cornerRadius = dp(Ui.RADIUS_BUTTON).toFloat() } }
        else { setTextColor(Ui.accent); background = GradientDrawable().apply { setColor(Ui.tint); cornerRadius = dp(Ui.RADIUS_BUTTON).toFloat() } }
        setOnClickListener { onClick() }
    }

    fun row(vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    fun spacer() = View(c).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }

    fun grow(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

    /** A white card with a title and optional hint; [fill] adds the content. Added to [parent]. */
    fun card(parent: LinearLayout, title: String, hint: String? = null, fill: LinearLayout.() -> Unit) {
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Ui.card); cornerRadius = dp(Ui.RADIUS_CARD).toFloat() }
        }
        v.addView(text(title, 18f, bold = true))
        if (hint != null) v.addView(text(hint, 14f, Ui.soft).apply { setPadding(0, dp(2), 0, dp(6)) })
        v.fill()
        parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    fun slider(label: String, valueText: String, max: Int, now: Int, onCommit: (Int) -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0)
        addView(row(grow(text(label, 15f)), text(valueText, 15f, Ui.accent, true)))
        addView(SeekBar(c).apply {
            this.max = max; progress = now
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onCommit(progress) }
            })
        })
    }


    /** Native-style wheels for hours and minutes. */
    fun pickTime(now: Int, set: (Int) -> Unit) {
        fun wheel(max: Int, v: Int) = NumberPicker(c).apply { minValue = 0; maxValue = max; value = v; wrapSelectorWheel = true; setFormatter { "%02d".format(it) } }
        val h = wheel(3, now / 60); val m = wheel(59, now % 60)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(h); addView(text("h", 16f, Ui.soft).apply { setPadding(dp(8), 0, dp(24), 0) })
            addView(m); addView(text("min", 16f, Ui.soft).apply { setPadding(dp(8), 0, 0, 0) })
        }
        AlertDialog.Builder(c).setTitle("Timer length").setView(box)
            .setPositiveButton("Set") { _, _ -> set((h.value * 60 + m.value).coerceIn(1, 180)) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Drag [handle] to move its row inside [rows] (equal heights [rowH], draggable ones are 0..[last]). The others slide aside; [onMove] gets the positions when you let go. */
    fun makeDraggable(handle: View, rows: List<View>, last: Int, rowH: Int, onMove: (from: Int, to: Int) -> Unit) {
        val r = handle.parent as View
        var startY = 0f
        handle.setOnTouchListener { v, e ->
            val orig = rows.indexOf(r)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; v.parent.requestDisallowInterceptTouchEvent(true); r.elevation = dp(8).toFloat() }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - startY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
                    r.translationY = dy
                    val target = (orig + Math.round(dy / rowH)).coerceIn(0, last)
                    rows.forEachIndexed { i, o -> if (o !== r && i <= last) o.translationY = when {
                        orig < target && i in (orig + 1)..target -> -rowH.toFloat()
                        orig > target && i in target until orig -> rowH.toFloat()
                        else -> 0f } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val target = (orig + Math.round(r.translationY / rowH)).coerceIn(0, last)
                    rows.forEach { it.translationY = 0f }; r.elevation = 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && target != orig) onMove(orig, target)
                }
            }
            true
        }
    }

    /**
     * Compact reorder list. [keys] = the ON items in order; off items follow. Drag the \u2261 handle: the other rows slide
     * aside while you move, and [onOrder] gets the new ON order when you let go. [locked] items cannot be switched off.
     */
    fun dragList(on: List<String>, names: Map<String, String>, locked: Set<String>, onOrder: (List<String>) -> Unit): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rowH = dp(52)
        val rows = ArrayList<View>()
        val all = on + names.keys.filter { it !in on }
        all.forEach { k ->
            val isOn = k in on
            val handle = TextView(c).apply {
                text = "\u2261"; textSize = 26f; setTextColor(if (isOn) Ui.soft else Ui.clear); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), rowH)
            }
            val r = row(handle, grow(text(names[k]!!, 16f, if (isOn) Ui.ink else Ui.soft)),
                Switch(c).apply { isChecked = isOn; isEnabled = k !in locked; setOnCheckedChangeListener { _, v -> onOrder(if (v) on + k else on - k) } })
            r.tag = k; r.minimumHeight = rowH
            box.addView(r, LinearLayout.LayoutParams(-1, rowH))
            rows.add(r)
            if (isOn) makeDraggable(handle, rows, on.size - 1, rowH) { from, to -> onOrder(on.toMutableList().also { it.add(to, it.removeAt(from)) }) }
        }
        return box
    }
}
