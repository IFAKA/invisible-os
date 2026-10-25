package com.faka.invisible

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.NumberPicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Setup checklist, then the settings of the launcher (the menu you hear). Plain views, rebuilt on every change. */
class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private var s = LauncherSettings()
    private var taskNote = ""        // result of the last Add, shown under the button
    private var taskNoteBad = false


    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Actions.init(applicationContext)
        s = Actions.mode.settings
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.page); addView(body) })
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        EventLog.onChange = { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        render()
    }

    override fun onDestroy() { EventLog.onChange = null; super.onDestroy() }

    private fun change(n: LauncherSettings) { s = n; Actions.saveSettings(n); render() }

    // ---- small view helpers ----
    private fun text(t: String, size: Float = 16f, color: Int = Ui.ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun card(title: String, hint: String? = null, fill: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Ui.card); cornerRadius = dp(Ui.RADIUS_CARD).toFloat() }
        }
        c.addView(text(title, 18f, bold = true))
        if (hint != null) c.addView(text(hint, 14f, Ui.soft).apply { setPadding(0, dp(2), 0, dp(6)) })
        c.fill()
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        stateListAnimator = null; elevation = 0f
        if (filled) { setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Ui.accent); cornerRadius = dp(Ui.RADIUS_BUTTON).toFloat() } }
        else { setTextColor(Ui.accent); background = GradientDrawable().apply { setColor(Ui.tint); cornerRadius = dp(Ui.RADIUS_BUTTON).toFloat() } }
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }

    private fun grow(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

    private fun slider(label: String, valueText: String, max: Int, now: Int, apply: (Int) -> LauncherSettings): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0)
        addView(row(grow(text(label, 15f)), text(valueText, 15f, Ui.accent, true)))
        addView(SeekBar(this@MainActivity).apply {
            this.max = max; progress = now
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { change(apply(progress)) }
            })
        })
    }

    // ---- screen ----
    private fun render() {
        body.removeAllViews()
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        val missing = listOf(a11y, notif, assist).count { !it }

        body.addView(text("Invisible", 30f, bold = true))
        body.addView(text("Control your phone with one earbud button. No screen needed.", 15f, Ui.soft))

        card(if (missing == 0) "Ready" else "$missing step${if (missing > 1) "s" else ""} left to set up",
            "Turn each one on once. Then this app works with the screen off.") {
            fun step(name: String, why: String, ok: Boolean, open: String) {
                addView(row(
                    grow(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text((if (ok) "✓  " else "✗  ") + name, 16f, if (ok) Ui.good else Ui.bad, true))
                        addView(text(why, 13f, Ui.soft).apply { setPadding(dp(24), 0, 0, 0) })
                    }),
                    if (ok) spacer() else btn("Turn on", true) { startActivity(Intent(open)) }))
            }
            step("Button access", "Hears your earbud button.", a11y, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            step("Notification access", "Reads your new messages.", notif, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            step("Assistant", "A long press works on the lock screen.", assist, Settings.ACTION_VOICE_INPUT_SETTINGS)
            addView(row(grow(text(if (EventLog.running) "Listening for the button" else "Stopped", 15f, Ui.soft)),
                btn(if (EventLog.running) "Stop" else "Start") {
                    val svc = Intent(this@MainActivity, ButtonService::class.java)
                    if (EventLog.running) stopService(svc) else startForegroundService(svc)
                }))
        }

        card("Tasks" + Actions.tasks().size.let { if (it > 0) " ($it)" else "" }, "Hold the button, then click \"Tasks\" to hear them. Click Done to finish one, Next to skip it.") {
            val input = EditText(this@MainActivity).apply {
                hint = "Write a task, or paste a checklist"; setHintTextColor(Ui.soft); setTextColor(Ui.ink); textSize = 16f
                minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply { setColor(Ui.card); setStroke(dp(2), Ui.edge); cornerRadius = dp(Ui.RADIUS_ROW).toFloat() }
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val note = text(taskNote, 14f, if (taskNoteBad) Ui.bad else Ui.good).apply { setPadding(0, dp(6), 0, 0) }
            fun add() {
                val t = input.text.toString()
                if (t.isBlank()) { taskNote = "Write a task in the box first."; taskNoteBad = true; render(); return }
                Actions.addTasks(t) { n ->
                    taskNoteBad = n == 0
                    taskNote = when (n) { 0 -> "Nothing new: already in the list, or all items were checked."; 1 -> "Added 1 task."; else -> "Added $n tasks." }
                    render()
                }
            }
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(btn("Add task", true) { add() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
            if (taskNote.isNotEmpty()) addView(note)
            val ts = Actions.tasks()
            if (ts.isEmpty()) addView(text("No tasks yet.\nIn Google Keep: open a note, tap Share, choose \"Invisible task\". Only unchecked items are added.", 14f, Ui.soft).apply { setPadding(0, dp(12), 0, 0) })
            val taskRows = ArrayList<View>()
            val th = dp(68)
            ts.forEachIndexed { i, t ->
                val handle = TextView(this@MainActivity).apply { text = "\u2261"; textSize = 26f; setTextColor(Ui.soft); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(40), -1) }
                val item = row(handle, grow(text(t.text, 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }),
                    btn("Done") { Actions.completeTask(t.id); taskNote = ""; render() }.apply { minWidth = dp(84) })
                item.setPadding(dp(4), 0, dp(8), 0)
                item.background = GradientDrawable().apply { setColor(Ui.page); cornerRadius = dp(Ui.RADIUS_ROW).toFloat() }
                taskRows.add(item)
                if (ts.size > 1) makeDraggable(handle, taskRows, ts.size - 1, th + dp(8)) { from, to -> Actions.moveTask(from, to); render() } else handle.setTextColor(Color.TRANSPARENT)
                addView(item, LinearLayout.LayoutParams(-1, th).apply { topMargin = dp(if (i == 0) 14 else 8) })
            }
        }

        card("Your menu", "Hold \u2261 and drag to reorder. The switch turns an item off.") {
            addView(dragList(s.hub, LauncherSettings.HUB_ITEMS, setOf("more")) { order -> change(s.copy(hub = LauncherSettings.cleanHub(order))) })
            addView(row(grow(text("Reply options: put the ones you pick most first (automatic)", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.learnOrder; setOnCheckedChangeListener { _, v -> change(s.copy(learnOrder = v)) } }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        card("Inside \"More\"", "What you hear after you click More. Same drag and switch.") {
            addView(dragList(s.more, LauncherSettings.MORE_ITEMS, setOf("exit")) { order -> change(s.copy(more = LauncherSettings.cleanMore(order))) })
        }

        card("Timer", "Tap a time to change it, like the clock app. These are the choices you hear. \"Last used\" is always added.") {
            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            s.timers.forEachIndexed { i, m ->
                chips.addView(Button(this@MainActivity).apply {
                    text = clockText(m); isAllCaps = false; textSize = 18f; setTextColor(Ui.accent); stateListAnimator = null; elevation = 0f
                    background = GradientDrawable().apply { setColor(Ui.tint); cornerRadius = dp(Ui.RADIUS_CHIP).toFloat() }
                    setOnClickListener { pickTime(m) { v -> change(s.copy(timers = LauncherSettings.cleanTimers(s.timers.toMutableList().also { it[i] = v }))) } }
                }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            addView(chips, LinearLayout.LayoutParams(-1, -2))
        }

        card("Speed", "Find what feels right. You can change it any time.") {
            addView(slider("Pause between options", "%.1f s".format(s.itemGapMs / 1000f), 34, ((s.itemGapMs - 600) / 100).toInt()) {
                s.copy(itemGapMs = 600L + it * 100) })
            addView(slider("Voice speed", "%.1f x".format(s.speechRate), 12, ((s.speechRate - 0.6f) * 10).toInt()) {
                s.copy(speechRate = 0.6f + it / 10f) })
            addView(slider("Time to cancel a send", "%.1f s".format(s.undoMs / 1000f), 13, ((s.undoMs - 1500) / 500).toInt()) {
                s.copy(undoMs = 1500L + it * 500) })
            addView(slider("Rounds before it goes quiet", "${s.silentLoops}", 4, s.silentLoops - 1) { s.copy(silentLoops = it + 1) })
        }

        card("How to use it") {
            addView(text("Music playing or phone idle: click = play/pause, 2 = next, 3 = previous.\n" +
                "Hold: wake the menu.\n" +
                "In the menu: click when you hear the option. 2 = start over, 3 = back, 4 = leave. Hold = exit.", 14f, Ui.soft))
        }

        card("Last gestures") {
            addView(text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, 14f, Ui.soft))
        }

        body.addView(btn("Reset settings") { change(LauncherSettings()) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    }

    /** "25 min", "1 h", "1 h 30". */
    private fun clockText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

    /** Native-style wheels for hours and minutes. */
    private fun pickTime(now: Int, set: (Int) -> Unit) {
        fun wheel(max: Int, v: Int) = NumberPicker(this).apply { minValue = 0; maxValue = max; value = v; wrapSelectorWheel = true; setFormatter { "%02d".format(it) } }
        val h = wheel(3, now / 60); val m = wheel(59, now % 60)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(h); addView(text("h", 16f, Ui.soft).apply { setPadding(dp(8), 0, dp(24), 0) })
            addView(m); addView(text("min", 16f, Ui.soft).apply { setPadding(dp(8), 0, 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("Timer length").setView(box)
            .setPositiveButton("Set") { _, _ -> set((h.value * 60 + m.value).coerceIn(1, 180)) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Drag [handle] to move its row inside [rows] (equal heights [rowH], draggable ones are 0..[last]). The others slide aside; [onMove] gets the positions when you let go. */
    private fun makeDraggable(handle: View, rows: List<View>, last: Int, rowH: Int, onMove: (from: Int, to: Int) -> Unit) {
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
    private fun dragList(on: List<String>, names: Map<String, String>, locked: Set<String>, onOrder: (List<String>) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowH = dp(52)
        val rows = ArrayList<View>()
        val all = on + names.keys.filter { it !in on }
        all.forEach { k ->
            val isOn = k in on
            val handle = TextView(this).apply {
                text = "\u2261"; textSize = 26f; setTextColor(if (isOn) Ui.soft else Color.TRANSPARENT); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), rowH)
            }
            val r = row(handle, grow(text(names[k]!!, 16f, if (isOn) Ui.ink else Ui.soft)),
                Switch(this).apply { isChecked = isOn; isEnabled = k !in locked; setOnCheckedChangeListener { _, v -> onOrder(if (v) on + k else on - k) } })
            r.tag = k; r.minimumHeight = rowH
            box.addView(r, LinearLayout.LayoutParams(-1, rowH))
            rows.add(r)
            if (isOn) makeDraggable(handle, rows, on.size - 1, rowH) { from, to -> onOrder(on.toMutableList().also { it.add(to, it.removeAt(from)) }) }
        }
        return box
    }
}
