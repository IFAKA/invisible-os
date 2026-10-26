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
        Actions.init(applicationContext)
        s = Actions.mode.settings
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dp(16), k.dp(40), k.dp(16), k.dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.page); addView(body) })
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        EventLog.onChange = { render() }
        intent.getStringExtra("page")?.let { n -> Page.values().firstOrNull { it.name.equals(n, true) }?.let { page = it } }   // for screenshots: adb ... --es page menu --es open /Tools
        intent.getStringExtra("open")?.let { openGroups = it.split(',').toSet() }
        render()
        warmTree()   // the Menu page opens at once
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

    private val sp get() = getSharedPreferences("invisible", MODE_PRIVATE)
    private fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun usageOn(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** What a step's button does: open the Settings page, or ask for the runtime permission. */
    private fun doStep(id: String) {
        fun ask(p: String) = requestPermissions(arrayOf(p), 1)
        when (id) {
            "button" -> if (sp.getBoolean("a11y_agreed", false)) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else { disclosure = true; render() }
            "notif" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "assist" -> startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            "post" -> ask(Manifest.permission.POST_NOTIFICATIONS)
            "calendar" -> ask(Manifest.permission.READ_CALENDAR)
            "contacts" -> ask(Manifest.permission.READ_CONTACTS)
            "calls" -> requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS), 1)
            "sms" -> ask(Manifest.permission.SEND_SMS)
            "location" -> ask(Manifest.permission.ACCESS_COARSE_LOCATION)
            "usage" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { render() }

    private var disclosure = false

    /** Shown before the Button access step opens Settings: says in plain words what the accessibility service does. */
    private fun disclosureCard() {
        k.banner(body, "Before you turn on Button access",
            "This app uses an Android accessibility service. It reads only the key presses of your earbud button. " +
            "It does not read what is on your screen, and it reads no messages or passwords. " +
            "The one exception is the Write keyboard: only while you use it, the app looks at the text field you are typing in and the words around it, to help you write, and it types your text there. " +
            "None of this leaves the phone. Nothing is sent to anyone.", Ui.accent) {
            addView(k.bannerBtn("Agree") { sp.edit().putBoolean("a11y_agreed", true).apply(); disclosure = false; render(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            addView(k.bannerBtn("Not now") { disclosure = false; render() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
        }
    }

    private fun home() {
        if (disclosure) { body.addView(k.text("Invisible", 30f, bold = true)); disclosureCard(); return }
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        if (a11y && EventLog.lastGesture != null) sp.edit().putBoolean("button_tested", true).apply()
        val tested = a11y && sp.getBoolean("button_tested", false)
        val steps = Setup.steps(SetupState(a11y, tested, notif, assist, granted(Manifest.permission.POST_NOTIFICATIONS), granted(Manifest.permission.READ_CALENDAR),
            granted(Manifest.permission.ACCESS_COARSE_LOCATION), usageOn(), android.os.Build.VERSION.SDK_INT, sp.getStringSet("setup_skipped", emptySet()) ?: emptySet(),
            contacts = granted(Manifest.permission.READ_CONTACTS), calls = granted(Manifest.permission.CALL_PHONE) && granted(Manifest.permission.ANSWER_PHONE_CALLS), sms = granted(Manifest.permission.SEND_SMS)))
        val next = Setup.current(steps)
        body.addView(k.text("Invisible", 30f, bold = true))
        if (next >= 0) {
            val t = steps[next]
            val head = Setup.label(next, steps.size) + ": " + t.title + if (t.optional) " (optional)" else ""
            val line = if (t.id == "test") (if (a11y) "Press your earbud button once. This screen will say when it hears you." else t.reason) else t.reason + " Turn it on once. Then come back."
            k.banner(body, head, line, Ui.accent) {
                if (t.id != "test") addView(k.bannerBtn(if (t.id in setOf("post", "calendar", "location", "contacts", "calls", "sms")) "Allow" else "Turn on") { doStep(t.id) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
                if (t.optional) addView(k.bannerBtn("Skip") { sp.edit().putStringSet("setup_skipped", (sp.getStringSet("setup_skipped", emptySet()) ?: emptySet()) + t.id).apply(); render() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            }
            body.addView(k.text(steps.withIndex().joinToString("\n") { (i, st) -> "${i + 1}. ${st.title}  " + if (st.done) "- done" else if (st.skipped) "- skipped" else if (i == next) "- now" else "" }, 14f, Ui.soft).apply { setPadding(0, k.dp(8), 0, k.dp(8)); setLineSpacing(0f, 1.2f) })
        } else if (!EventLog.running) {
            k.banner(body, "Stopped", "The app is not listening to the button.", Ui.bad) {
                addView(k.bannerBtn("Start") { startForegroundService(Intent(this@MainActivity, ButtonService::class.java)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            }
        } else {
            k.banner(body, "All set", "Turn the screen off. Hold your earbud button to hear the menu.", Ui.good)
        }
        val n = Actions.tasks().size
        k.navRow(body, "Tasks", if (n == 0) "None yet. Add one." else "$n to do") { open(Page.TASKS) }
        k.navRow(body, "Your menu", "Pin, move or hide what you hear") { open(Page.MENU) }
        k.navRow(body, "Voice and speed", "Voice ${"%.1f".format(s.speechRate)}x, pause ${"%.1f".format(s.itemGapMs / 1000f)} s") { open(Page.VOICE) }
        k.navRow(body, "AI on the phone", if (!s.ai) "Off" else if (ModelDownload.ready(this)) "On, ready" else "On, model not downloaded") { open(Page.AI) }
        k.navRow(body, "Help", "Button gestures and your last presses") { open(Page.HELP) }
        card("Emergency contact", "A phone number that gets a text when you pick Emergency in the Phone list. Leave empty for no text.") {
            val box = k.input("Phone number, e.g. +54 11 5555 1234").apply {
                minLines = 1; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(Actions.emergencyContact())
            }
            val note = k.text("", 14f, Ui.good).apply { setPadding(0, k.dp(6), 0, 0) }
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(k.btn("Save", true) {
                Actions.setEmergencyContact(box.text.toString())
                note.text = if (Actions.emergencyContact().isEmpty()) "Saved: no emergency contact." else "Saved."
            }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(note)
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

    /** The spoken tree, drawn from the same builder the voice uses ([TreeLeaves], [AppTree]). Pin, hide and move are saved in the settings the voice reads. */
    private fun treeCard() {
        // The phone queries (installed apps, 30 days of usage) and the tree search are slow: they run once, off the main thread, and are reused.
        val all = treeAll; val built = treeBuilt
        if (built == null || all == null) { warmTree(); card("What you hear") { addView(k.text("Loading your apps\u2026", 16f, Ui.soft)) }; return }
        if (built.first != treeKey()) warmTree()   // an edit was made: the old tree stays on screen until the new one is ready
        val root = built.second
        card("What you hear", "The tree the voice walks, in its order. Tap a group to open it. Tap an app to pin, move or hide it.") {
            addView(treeSearchBox(root), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(8) })
            fun toggle(set: Set<String>, k: String) = if (k in set) set - k else set + k
            fun actions(a: AppLeaf) {
                val pinned = a.pkg in s.pinned
                val here = AppCategories.main(s.moved[a.pkg] ?: a.category)
                val items = listOf(if (pinned) "Unpin from the first menu" else "Pin to the first menu", "Move to another group", "Hide")
                android.app.AlertDialog.Builder(this@MainActivity).setTitle(a.label).setItems(items.toTypedArray()) { _, i ->
                    when (i) {
                        0 -> change(s.copy(pinned = toggle(s.pinned, a.pkg)))
                        1 -> android.app.AlertDialog.Builder(this@MainActivity).setTitle("Move ${a.label} to").setItems(TreeLeaves.GROUPS.toTypedArray()) { _, g ->
                            change(s.copy(moved = s.moved + (a.pkg to TreeLeaves.GROUPS[g])))
                        }.show()
                        else -> change(s.copy(hidden = s.hidden + a.pkg))
                    }
                }.show()
            }
            fun leafCount(n: TreeNode): Int = if (n.app != null) 1 else n.children.sumOf { leafCount(it) }
            fun node(n: TreeNode, depth: Int, path: String) {
                val a = n.app
                val pad = k.dp(12 * depth)
                if (a == null) {
                    if (n.label.isEmpty()) return
                    val key = "$path/${n.label}"
                    val open = key in openGroups
                    val head = k.row(k.grow(k.text(n.label, 16f, bold = true)), k.text("${leafCount(n)}  " + if (open) "\u25B4" else "\u25BE", 15f, Ui.soft))
                    head.minimumHeight = k.dp(48); head.setPadding(pad, 0, k.dp(4), 0)
                    head.setOnClickListener { openGroups = if (open) openGroups - key else if (depth == 0) setOf(key) else openGroups + key; render() }
                    addView(head)
                    if (open) n.children.forEach { node(it, depth + 1, key) }
                    return
                }
                val tag = listOfNotNull(if (a.pkg in s.pinned) "pinned" else null, if (a.pkg in s.moved) "moved" else null).joinToString(", ")
                val row = k.row(k.grow(k.text(a.label, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }), k.text(tag, 13f, Ui.soft))
                row.minimumHeight = k.dp(48); row.setPadding(pad, 0, k.dp(4), 0)
                row.setOnClickListener { actions(a) }
                addView(row)
            }
            root.children.forEach { node(it, 0, "") }
            val gone = all.filter { it.pkg in s.hidden }
            if (gone.isNotEmpty()) {
                val key = "/hidden"; val open = key in openGroups
                val head = k.row(k.grow(k.text("Hidden", 17f, bold = true)), k.text("${gone.size}  " + if (open) "\u25B4" else "\u25BE", 15f, Ui.soft))
                head.minimumHeight = k.dp(52)
                head.setOnClickListener { openGroups = if (open) openGroups - key else openGroups + key; render() }
                addView(head)
                if (open) gone.forEach { a -> addView(k.row(k.grow(k.text(a.label, 16f)), k.chip("Show") { change(s.copy(hidden = s.hidden - a.pkg)) })) }
            }
        }
    }

    private var openGroups: Set<String> = emptySet()
    @Volatile private var treeFind: NameFinder.Node? = null   // the Find lists, built after the tree (slow on a phone)
    private var treeQuery = ""
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRun: Runnable? = null
    private var searchShow: (() -> Unit)? = null

    /** A name box with the places of the matches below it. The list is redrawn in place (not [render]) so the keyboard stays; it waits 300 ms after the last key. */
    private fun treeSearchBox(root: TreeNode): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val box = k.input("Search an app or option, e.g. volume").apply {
            minLines = 1; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(treeQuery)
        }
        val out = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        fun show() {
            out.removeAllViews()
            if (treeQuery.isBlank()) return
            val hits = TreeSearch.search(root, treeFind, treeQuery)
            if (hits.isEmpty()) { out.addView(k.text("Nothing found", 14f, Ui.soft)); return }
            hits.forEach { h ->
                out.addView(k.text(h.app.label, 16f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
                out.addView(k.text("Click: " + h.tree, 14f, Ui.soft))
                out.addView(k.text("Hold on home: " + (h.find ?: "working it out\u2026"), 14f, Ui.soft))
            }
        }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                treeQuery = e?.toString().orEmpty()
                searchRun?.let { searchHandler.removeCallbacks(it) }
                searchRun = Runnable { show() }.also { searchHandler.postDelayed(it, 300) }
            }
        })
        addView(box); addView(out)
        searchShow = ::show
        show()
    }
    @Volatile private var treeAll: List<AppLeaf>? = null
    @Volatile private var treeBuilt: Pair<List<Any>, TreeNode>? = null
    private val treeBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun treeKey(): List<Any> = listOf(s.pinned, s.hidden, s.moved)

    private fun warmTree() {
        if (!treeBusy.compareAndSet(false, true)) return
        val snap = s; val key = listOf<Any>(snap.pinned, snap.hidden, snap.moved)
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val all = treeAll ?: TreeLeaves.all(this, snap).also { treeAll = it }
                val t1 = System.currentTimeMillis()
                val edited = TreeLeaves.edit(all, snap)
                treeBuilt = key to TreeLeaves.tree(edited)
                android.util.Log.d("TREETIME", "leaves ${t1 - t0} ms (${all.size}), tree ${System.currentTimeMillis() - t1} ms")
            } finally { treeBusy.set(false) }
            runOnUiThread { if (page == Page.MENU && !isDestroyed) render() }
            // Find's plan is slower than the tree: the box shows the tree path first and the Find path when this is done
            try { treeFind = NameFinder.plan(TreeLeaves.edit(treeAll ?: return@Thread, snap)) } catch (e: Throwable) { android.util.Log.e("TREETIME", "find plan", e); return@Thread }
            runOnUiThread { if (page == Page.MENU && !isDestroyed) searchShow?.invoke() }
        }.start()
    }

    private fun menuPage() {
        treeCard()
        card("Order and speed") {
            addView(k.row(k.grow(k.text("Reply options: put the ones you pick most first (automatic)", 14f, Ui.soft)),
                k.toggle("Reply options by use", s.learnOrder) { v -> change(s.copy(learnOrder = v)) }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
            addView(k.row(k.grow(k.text("Speak well-known options as a fast sound (spearcon)", 14f, Ui.soft)),
                k.toggle("Fast sound for known options", s.spearcons) { v -> change(s.copy(spearcons = v)) }),
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
            addView(slider("Pause between options", "%.1f s".format(s.itemGapMs / 1000f), 39, ((s.itemGapMs - 100) / 100).toInt()) {
                s.copy(itemGapMs = 100L + it * 100) })
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
                k.toggle("Use AI for replies and tasks", s.ai) { v -> change(s.copy(ai = v)); if (v) ModelDownload.start(applicationContext) { EventLog.refresh() } }),
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
                com.faka.invisible.voicekit.Voice.GESTURE_HELP.text, 16f).apply { setLineSpacing(0f, 1.25f); setPadding(0, k.dp(6), 0, 0) })
        }

        card("Practice", "A one-minute lesson you hear: click, two clicks, three clicks, close.") {
            addView(k.btn("Replay the practice") { Actions.startPractice() })
        }

        card("Last button presses") {
            addView(k.text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, 15f, Ui.soft).apply { setLineSpacing(0f, 1.2f) })
        }

        body.addView(k.btn("Reset settings") { k.confirm("Reset all settings?", "Reset") { change(LauncherSettings()) } }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(12) })
    }

    /** "25 min", "1 h", "1 h 30". */
    private fun clockText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

}
