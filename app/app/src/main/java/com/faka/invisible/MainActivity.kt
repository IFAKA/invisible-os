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


    private val k by lazy { Kit(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Actions.init(applicationContext)
        s = Actions.mode.settings
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dp(16), k.dp(40), k.dp(16), k.dp(32)) }
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

    // ---- small view helpers: the parts live in Kit (Ui.kt) ----
    private fun card(title: String, hint: String? = null, fill: LinearLayout.() -> Unit) = k.card(body, title, hint, fill)
    private fun slider(label: String, valueText: String, max: Int, now: Int, apply: (Int) -> LauncherSettings) = k.slider(label, valueText, max, now) { change(apply(it)) }

    // ---- screen ----
    private fun render() {
        body.removeAllViews()
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        val missing = listOf(a11y, notif, assist).count { !it }

        body.addView(k.text("Invisible", 30f, bold = true))
        body.addView(k.text("Control your phone with one earbud button. No screen needed.", 15f, Ui.soft))

        card(if (missing == 0) "Ready" else "$missing step${if (missing > 1) "s" else ""} left to set up",
            "Turn each one on once. Then this app works with the screen off.") {
            fun step(name: String, why: String, ok: Boolean, open: String) {
                addView(k.row(
                    k.grow(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(k.text((if (ok) "✓  " else "✗  ") + name, 16f, if (ok) Ui.good else Ui.bad, true))
                        addView(k.text(why, 13f, Ui.soft).apply { setPadding(k.dp(24), 0, 0, 0) })
                    }),
                    if (ok) k.spacer() else k.btn("Turn on", true) { startActivity(Intent(open)) }))
            }
            step("Button access", "Hears your earbud button.", a11y, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            step("Notification access", "Reads your new messages.", notif, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            step("Assistant", "A long press works on the lock screen.", assist, Settings.ACTION_VOICE_INPUT_SETTINGS)
            addView(k.row(k.grow(k.text(if (EventLog.running) "Listening for the button" else "Stopped", 15f, Ui.soft)),
                k.btn(if (EventLog.running) "Stop" else "Start") {
                    val svc = Intent(this@MainActivity, ButtonService::class.java)
                    if (EventLog.running) stopService(svc) else startForegroundService(svc)
                }))
        }

        card("Tasks" + Actions.tasks().size.let { if (it > 0) " ($it)" else "" }, "Hold the button, then click \"Tasks\" to hear them. Click Done to finish one, Next to skip it.") {
            val input = k.input("Write a task, or paste a checklist")
            val note = k.text(taskNote, 14f, if (taskNoteBad) Ui.bad else Ui.good).apply { setPadding(0, k.dp(6), 0, 0) }
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
            addView(k.btn("Add task", true) { add() }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            if (taskNote.isNotEmpty()) addView(note)
            val ts = Actions.tasks()
            if (ts.isEmpty()) addView(k.text("No tasks yet.\nIn Google Keep: open a note, tap Share, choose \"Invisible task\". Only unchecked items are added.", 14f, Ui.soft).apply { setPadding(0, k.dp(12), 0, 0) })
            val taskRows = ArrayList<View>()
            val th = k.dp(68)
            ts.forEachIndexed { i, t ->
                val handle = k.handle(40, visible = ts.size > 1)
                val item = k.row(handle, k.grow(k.text(t.text, 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }),
                    k.btn("Done") { Actions.completeTask(t.id); taskNote = ""; render() }.apply { minWidth = k.dp(84) })
                item.setPadding(k.dp(4), 0, k.dp(8), 0)
                k.surface(item)
                taskRows.add(item)
                if (ts.size > 1) k.makeDraggable(handle, taskRows, ts.size - 1, th + k.dp(8)) { from, to -> Actions.moveTask(from, to); render() }
                addView(item, LinearLayout.LayoutParams(-1, th).apply { topMargin = k.dp(if (i == 0) 14 else 8) })
            }
        }

        card("Your menu", "Hold \u2261 and drag to reorder. The switch turns an item off.") {
            addView(k.dragList(s.hub, LauncherSettings.HUB_ITEMS, setOf("more")) { order -> change(s.copy(hub = LauncherSettings.cleanHub(order))) })
            addView(k.row(k.grow(k.text("Reply options: put the ones you pick most first (automatic)", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.learnOrder; setOnCheckedChangeListener { _, v -> change(s.copy(learnOrder = v)) } }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
            addView(k.row(k.grow(k.text("Speak well-known options as a fast sound (spearcon)", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.spearcons; setOnCheckedChangeListener { _, v -> change(s.copy(spearcons = v)) } }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        }

        card("Inside \"More\"", "What you hear after you click More. Same drag and switch.") {
            addView(k.dragList(s.more, LauncherSettings.MORE_ITEMS, setOf("exit")) { order -> change(s.copy(more = LauncherSettings.cleanMore(order))) })
        }

        card("Timer", "Tap a time to change it, like the clock app. These are the choices you hear. \"Last used\" is always added.") {
            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            s.timers.forEachIndexed { i, m ->
                chips.addView(k.chip(clockText(m)) { k.pickTime(m) { v -> change(s.copy(timers = LauncherSettings.cleanTimers(s.timers.toMutableList().also { it[i] = v }))) } }, LinearLayout.LayoutParams(0, k.dp(64), 1f).apply { if (i > 0) marginStart = k.dp(8) })
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
            addView(k.text("Music playing or phone idle: click = play/pause, 2 = next, 3 = previous.\n" +
                "Hold: wake the menu.\n" +
                Voice.GESTURE_HELP, 14f, Ui.soft))
        }

        card("Last gestures") {
            addView(k.text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, 14f, Ui.soft))
        }

        body.addView(k.btn("Reset settings") { change(LauncherSettings()) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(12) })
    }

    /** "25 min", "1 h", "1 h 30". */
    private fun clockText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

}
