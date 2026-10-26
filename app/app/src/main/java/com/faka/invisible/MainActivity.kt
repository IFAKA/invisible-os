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
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CALENDAR), 1)   // location: Weather; calendar: Next event
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

    // ---- screens: Home shows one thing to do and a few doors; each door opens one small page ----
    private enum class Page(val title: String) { HOME("Invisible"), TASKS("Tasks"), MENU("Your menu"), VOICE("Voice and speed"), AI("AI on the phone"), HELP("Help") }
    private var page = Page.HOME

    private fun open(p: Page) { page = p; render(); (body.parent as? ScrollView)?.scrollTo(0, 0) }

    @Deprecated("Back goes to Home first, then leaves")
    override fun onBackPressed() { if (page != Page.HOME) open(Page.HOME) else super.onBackPressed() }

    private fun render() {
        body.removeAllViews()
        if (page != Page.HOME) body.addView(k.header(page.title) { open(Page.HOME) })
        when (page) {
            Page.HOME -> home()
            Page.TASKS -> tasksPage()
            Page.MENU -> menuPage()
            Page.VOICE -> voicePage()
            Page.AI -> aiPage()
            Page.HELP -> helpPage()
        }
        body.addView(k.text("Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_STAMP}", 12f, Ui.soft), LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(24) })
    }

    private fun home() {
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        // one step at a time: the first missing permission is the only thing asked
        val steps = listOf(
            Triple("Button access", "So the app can hear your earbud button.", a11y) to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Triple("Notification access", "So it can read your new messages to you.", notif) to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            Triple("Assistant", "So a long press works on the lock screen.", assist) to Settings.ACTION_VOICE_INPUT_SETTINGS)
        val next = steps.indexOfFirst { !it.first.third }
        body.addView(k.text("Invisible", 30f, bold = true))
        if (next >= 0) {
            val (t, open) = steps[next]
            k.banner(body, "Step ${next + 1} of ${steps.size}: ${t.first}", t.second + " Turn it on once. Then come back.", Ui.accent) {
                addView(k.bannerBtn("Turn on") { startActivity(Intent(open)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            }
        } else if (!EventLog.running) {
            k.banner(body, "Stopped", "The app is not listening to the button.", Ui.bad) {
                addView(k.bannerBtn("Start") { startForegroundService(Intent(this@MainActivity, ButtonService::class.java)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            }
        } else {
            k.banner(body, "Ready", "Turn the screen off. Hold your earbud button to hear the menu.", Ui.good)
        }
        val n = Actions.tasks().size
        k.navRow(body, "Tasks", if (n == 0) "None yet. Add one." else "$n to do") { open(Page.TASKS) }
        k.navRow(body, "Your menu", "${s.hub.size} items you hear, in your order") { open(Page.MENU) }
        k.navRow(body, "Voice and speed", "Voice ${"%.1f".format(s.speechRate)}x, pause ${"%.1f".format(s.itemGapMs / 1000f)} s") { open(Page.VOICE) }
        k.navRow(body, "AI on the phone", if (!s.ai) "Off" else if (ModelDownload.ready(this)) "On, ready" else "On, model not downloaded") { open(Page.AI) }
        k.navRow(body, "Help", "Button gestures and your last presses") { open(Page.HELP) }
        if (next >= 0) {
            val left = steps.drop(next + 1).filter { !it.first.third }
            if (left.isNotEmpty()) body.addView(k.text("After this: " + left.joinToString(", ") { it.first.first }, 14f, Ui.soft).apply { setPadding(0, k.dp(12), 0, 0) })
        }
        if (next < 0 && EventLog.running) body.addView(k.btn("Stop listening") { stopService(Intent(this, ButtonService::class.java)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(16) })
    }

    private fun tasksPage() {
        card("Tasks" + Actions.tasks().size.let { if (it > 0) " ($it)" else "" }, "Add one here, or share from Google Keep. Hold the button and pick \"Tasks\" to hear them.") {
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

    }

    private fun menuPage() {
        card("What you hear first", "Hold \u2261 and drag to reorder. The switch turns an item off.") {
            addView(k.dragList(s.hub, LauncherSettings.HUB_ITEMS, emptySet()) { order -> change(s.copy(hub = LauncherSettings.cleanHub(order))) })
            addView(k.row(k.grow(k.text("Reply options: put the ones you pick most first (automatic)", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.learnOrder; setOnCheckedChangeListener { _, v -> change(s.copy(learnOrder = v)) } }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
            addView(k.row(k.grow(k.text("Speak well-known options as a fast sound (spearcon)", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.spearcons; setOnCheckedChangeListener { _, v -> change(s.copy(spearcons = v)) } }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        }

        card("Inside \"Today\"", "What you hear after you click Today. Same drag and switch.") {
            addView(k.dragList(s.info, LauncherSettings.INFO_ITEMS, emptySet()) { order -> change(s.copy(info = LauncherSettings.cleanInfo(order))) })
        }

        card("Inside \"Phone\"", "What you hear after you click Phone. Same drag and switch.") {
            addView(k.dragList(s.phone, LauncherSettings.PHONE_ITEMS, emptySet()) { order -> change(s.copy(phone = LauncherSettings.cleanPhone(order))) })
        }

    }

    private fun voicePage() {
        card("Speed", "Find what feels right. You can change it any time.") {
            addView(slider("Pause between options", "%.1f s".format(s.itemGapMs / 1000f), 34, ((s.itemGapMs - 600) / 100).toInt()) {
                s.copy(itemGapMs = 600L + it * 100) })
            addView(slider("Voice speed", "%.1f x".format(s.speechRate), 12, ((s.speechRate - 0.6f) * 10).toInt()) {
                s.copy(speechRate = 0.6f + it / 10f) })
            addView(slider("Time to cancel a send", "%.1f s".format(s.undoMs / 1000f), 13, ((s.undoMs - 1500) / 500).toInt()) {
                s.copy(undoMs = 1500L + it * 500) })
            addView(slider("Rounds before it goes quiet", "${s.silentLoops}", 4, s.silentLoops - 1) { s.copy(silentLoops = it + 1) })
        }

        card("Timer", "Tap a time to change it, like the clock app. These are the choices you hear. \"Last used\" is always added.") {
            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            s.timers.forEachIndexed { i, m ->
                chips.addView(k.chip(clockText(m)) { k.pickTime(m) { v -> change(s.copy(timers = LauncherSettings.cleanTimers(s.timers.toMutableList().also { it[i] = v }))) } }, LinearLayout.LayoutParams(0, k.dp(64), 1f).apply { if (i > 0) marginStart = k.dp(8) })
            }
            addView(chips, LinearLayout.LayoutParams(-1, -2))
        }

    }

    private fun aiPage() {
        card("AI on the phone", "Writes reply sentences and clean tasks from a message. It runs on the phone, no network use after the download, and it never listens.") {
            addView(k.row(k.grow(k.text("Use AI for replies and tasks", 14f, Ui.soft)),
                Switch(this@MainActivity).apply { isChecked = s.ai; setOnCheckedChangeListener { _, v -> change(s.copy(ai = v)); if (v) ModelDownload.start(applicationContext) { EventLog.refresh() } } }),
                LinearLayout.LayoutParams(-1, -2))
            if (s.ai) {
                val ready = ModelDownload.ready(this@MainActivity)
                addView(k.text(if (ready) "Ready. The model is on this phone." else ModelDownload.status.ifEmpty { "The model (2.6 GB) is not on this phone yet." }, 14f, Ui.soft),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
                if (!ready) addView(k.btn("Download model (Wi-Fi only)", true) { ModelDownload.start(applicationContext) { EventLog.refresh() }; render() },
                    LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(8) })
            }
        }

    }

    private fun helpPage() {
        card("How to use it") {
            addView(k.text("Music playing or phone idle: click = play/pause, 2 = next, 3 = previous.\n" +
                "Hold: wake the menu.\n" +
                com.faka.invisible.voicekit.Voice.GESTURE_HELP.text, 14f, Ui.soft))
        }

        card("Last button presses") {
            addView(k.text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, 14f, Ui.soft))
        }

        body.addView(k.btn("Reset settings") { change(LauncherSettings()) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(12) })
    }

    /** "25 min", "1 h", "1 h 30". */
    private fun clockText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

}
