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
import com.faka.invisible.voicekit.MenuLanguage
import com.faka.invisible.voicekit.ScreenMirror
import com.faka.invisible.voicekit.Translations
import com.faka.invisible.voicekit.Voice


/** Setup checklist, then the settings of the launcher (the menu you hear). Plain views, rebuilt on every change. */
class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var root: android.widget.FrameLayout   // holds the scrolling pages, or the fixed mirror of the OS
    private lateinit var scroll: ScrollView
    private var mirror: Kit.MirrorView? = null; private var mirrorKey = ""; private var mirrorHost: View? = null
    private lateinit var scrollBody: LinearLayout   // the body of the scrolling pages
    private var pageTop: LinearLayout? = null         // fixed pages: the part above the scrolling list
    private var settingsOpen = false   // the person left the mirror to use the settings
    private var s = LauncherSettings()
    private var taskNote = ""        // result of the last Add, shown under the button
    private var taskNoteBad = false


    private val k by lazy { Kit(this) }
    /** A screen label in the menu language. */
    private fun tr(text: String) = Translations.label(text)
    private var announceStep = true      // say the setup step when the screen opens and when we come back from Settings
    private var lostNote = false         // Button access was turned off since last time: say it once

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Actions.init(applicationContext)
        s = Actions.mode.settings
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(k.dp(Ui.S2), k.dp(Ui.S2), k.dp(Ui.S2), k.dp(Ui.S4)) }
        scrollBody = body
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { if (!goBack()) finish() }   // Android 16 no longer calls onBackPressed
        scroll = k.screen(this, body)
        root = android.widget.FrameLayout(this).apply { addView(scroll) }
        setContentView(root)
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        EventLog.onChange = { renderSoon() }
        ScreenMirror.onChange = { if (page == Page.HOME) EventLog.refresh() }
        intent.getStringExtra("page")?.let { n -> Page.values().firstOrNull { it.name.equals(n, true) }?.let { page = it } }   // for screenshots: adb ... --es page menu --es open /Tools
        if (page == Page.LAPTOP) laptop.start()
        if (page == Page.COMPUTERS) computersPage.start()
        previewReady = intent.getBooleanExtra("ready", false)
        if (intent.getBooleanExtra("disclosure", false)) disclosure = true   // for screenshots
        intent.getStringExtra("open")?.let { openGroups = it.split(',').toSet() }
        lostNote = ButtonAccess.check(this)
        render()
        warmTree()   // the Menu page opens at once
    }

    override fun onNewIntent(i: Intent) {   // for screenshots: --es page voice on the running app
        super.onNewIntent(i); setIntent(i)
        i.getStringExtra("page")?.let { n -> Page.values().firstOrNull { it.name.equals(n, true) }?.let { open(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        announceStep = true
        if (!lostNote) lostNote = ButtonAccess.check(this)
        render()
    }

    override fun onDestroy() { EventLog.onChange = null; ScreenMirror.onChange = null; super.onDestroy() }

    private fun change(n: LauncherSettings) { s = n; Actions.saveSettings(n); render() }

    // ---- small view helpers: the parts live in Kit (Ui.kt) ----
    private var cardParent: LinearLayout? = null   // where card() puts the next card; the body unless a fixed page set it
    private fun card(title: String, hint: String? = null, fill: LinearLayout.() -> Unit) = k.card(cardParent ?: body, title, hint, true, fill)
    private fun slider(label: String, valueText: String, max: Int, now: Int, apply: (Int) -> LauncherSettings) = k.slider(label, valueText, max, now) { change(apply(it)) }

    // ---- screens: Home shows one thing to do and a few doors; each door opens one small page ----
    private enum class Page(val title: String) { HOME("Invisible OS"), TASKS("Tasks"), MENU("Your menu"), MENUMORE("Order, Today and Phone"), VOICE("Voice and speed"), EMERGENCY("Emergency contact"), COMPUTERS("Computers"), LAPTOP("Laptop"), AI("AI on the phone"), HELP("Help") }
    private var page = Page.HOME

    private val laptop by lazy { LaptopPage(this, k, body) { render() } }
    private val computersPage by lazy { ComputersPage(this, k, body, { render() }) { name -> page = Page.LAPTOP; laptop.startAt(name); render() } }

    private fun open(p: Page) { page = p; if (p == Page.LAPTOP) laptop.start(); if (p == Page.COMPUTERS) computersPage.start(); render(); (body.parent as? ScrollView)?.scrollTo(0, 0); k.enter(body) }

    /** Back goes up one step: inside the Computers page, a sub-page to its page, a page to Home, the settings to the mirror; from Home it leaves. */
    private fun goBack(): Boolean = when {
        page == Page.COMPUTERS && computersPage.inside -> { computersPage.back(); true }
        page == Page.MENUMORE -> { open(Page.MENU); true }
        page != Page.HOME -> { open(Page.HOME); true }
        settingsOpen -> { settingsOpen = false; render(); true }
        else -> false
    }
    @Deprecated("Back goes up one step, then leaves")
    override fun onBackPressed() { if (!goBack()) super.onBackPressed() }

    private var lastDraw = 0L
    private var drawQueued = false
    /** Redraw, at most about 5 times a second (the mirror changes with every item). */
    private fun renderSoon() {
        val wait = 200 - (android.os.SystemClock.uptimeMillis() - lastDraw)
        if (wait <= 0) { lastDraw = android.os.SystemClock.uptimeMillis(); render() }
        else if (!drawQueued) { drawQueued = true; body.postDelayed({ drawQueued = false; lastDraw = android.os.SystemClock.uptimeMillis(); render() }, wait) }
    }

    /** Pages whose top (back, title) stays; only the list under it scrolls. The others scroll as one (long text, the laptop rooms). */
    private val fixedPages = setOf(Page.TASKS, Page.MENU, Page.MENUMORE, Page.VOICE, Page.EMERGENCY, Page.AI)

    private fun render() {
        val keep = page == Page.HOME && ScreenMirror.active && !settingsOpen && root.getChildAt(0) === mirrorHost && mirrorHost != null
        body = scrollBody; pageTop = null
        if (page in fixedPages) {
            val f = k.fixedScreen(this)
            f.addView(k.header(tr(page.title), { open(if (page == Page.MENUMORE) Page.MENU else Page.HOME) }, if (page == Page.MENUMORE) "Your menu" else "Home"))
            val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            f.addView(top)
            f.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            body = content; pageTop = top
            root.removeAllViews(); root.addView(f)
        } else if (!keep && root.getChildAt(0) !== scroll) { root.removeAllViews(); root.addView(scroll) }
        body.removeAllViews(); cardParent = null
        if (page in fixedPages) { /* header is already in place */ }
        else if (page == Page.LAPTOP && laptop.inRoom) body.addView(k.header(laptop.title(), { laptop.leaveRoom() }, "Rooms"))
        else if (page != Page.HOME) body.addView(k.header(tr(page.title), { open(Page.HOME) }))
        when (page) {
            Page.HOME -> home()
            Page.TASKS -> tasksPage()
            Page.MENU -> menuPage()
            Page.MENUMORE -> menuMorePage()
            Page.VOICE -> voicePage()
            Page.EMERGENCY -> emergencyPage()
            Page.COMPUTERS -> computersPage.draw()
            Page.LAPTOP -> laptop.draw()
            Page.AI -> aiPage()
            Page.HELP -> helpPage()
        }
        if (page !in fixedPages) body.addView(k.text(tr("Version {0}").replace("{0}", BuildConfig.VERSION_NAME) + if (page == Page.HELP) " (${BuildConfig.BUILD_STAMP})" else "", Type.CAPTION, Ui.soft).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S4) })
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
            "calllog" -> ask(Manifest.permission.READ_CALL_LOG)
            "sms" -> ask(Manifest.permission.SEND_SMS)
            "location" -> ask(Manifest.permission.ACCESS_COARSE_LOCATION)
            "usage" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { render() }

    private var disclosure = false

    /** Shown before the Button access step opens Settings: says in plain words what the accessibility service does. */
    private fun disclosureCard() {
        k.onboarding(body, 0.04f, "Before you start", "Button access",
            "This app uses an Android accessibility service. It reads only the key presses of your earbud button. " +
            "It does not read what is on your screen, and it reads no messages or passwords.\n\n" +
            "The one exception is the Write keyboard. Only while you use it, the app looks at the text field you are typing in, to help you write.\n\n" +
            "None of this leaves the phone.",
            "Agree", { sp.edit().putBoolean("a11y_agreed", true).apply(); disclosure = false; render(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "Not now", { disclosure = false; render() })
    }

    private var lastStep = ""
    private var previewReady = false   // for screenshots: --ez ready true shows the home after setup

    private fun home() {
        if (disclosure) { disclosureCard(); return }
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        if (a11y && EventLog.lastGesture != null) sp.edit().putBoolean("button_tested", true).apply()
        val tested = a11y && sp.getBoolean("button_tested", false)
        val steps = Setup.steps(SetupState(a11y, tested, notif, assist, granted(Manifest.permission.POST_NOTIFICATIONS), granted(Manifest.permission.READ_CALENDAR),
            granted(Manifest.permission.ACCESS_COARSE_LOCATION), usageOn(), android.os.Build.VERSION.SDK_INT, sp.getStringSet("setup_skipped", emptySet()) ?: emptySet(),
            contacts = granted(Manifest.permission.READ_CONTACTS), calls = granted(Manifest.permission.CALL_PHONE) && granted(Manifest.permission.ANSWER_PHONE_CALLS), sms = granted(Manifest.permission.SEND_SMS), callLog = granted(Manifest.permission.READ_CALL_LOG)))
        val next = if (previewReady) -1 else Setup.current(steps)
        val stepKey = if (next >= 0) steps[next].id else if (!EventLog.running) "stopped" else "ready"
        val changed = stepKey != lastStep
        if (changed) { lastStep = stepKey; body.post { k.enter(body) } }
        if (next >= 0) {
            val t = steps[next]
            val perm = t.id in Setup.PERMISSION_STEPS
            val action = if (t.id == "test") null else if (perm) "Allow" else "Turn on"
            val line = if (t.id == "test") tr(if (a11y) "Press your earbud button once. This screen will say when it hears you." else t.reason) else tr(t.reason) + tr(" You turn it on once.")
            if ((changed || announceStep) && !previewReady) {   // the app's own voice, for a person who cannot see the screen
                if (lostNote) { Actions.announce(Voice.BUTTON_ACCESS_OFF, false); ButtonAccess.told(this); lostNote = false }
                Actions.announce(Voice.setupStep(next + 1, steps.size, t.title, action), true)
            }
            announceStep = false
            k.onboarding(body, (next + 1f) / steps.size, tr(Setup.label(next, steps.size)) + if (t.optional) " \u00B7 " + tr("Optional") else "", tr(t.title), line,
                action?.let { tr(it) }, { doStep(t.id) },
                if (t.optional) tr("Skip") else null, { sp.edit().putStringSet("setup_skipped", (sp.getStringSet("setup_skipped", emptySet()) ?: emptySet()) + t.id).apply(); render() },
                focusFirst = changed)
            val before = steps.take(next).filter { it.passed }
            if (before.isNotEmpty()) k.stepList(body, before.map { (if (it.skipped) "\u2013" else "\u2713") to tr(it.title) })
            return
        }
        announceStep = false
        val mir = ScreenMirror.state
        if (!mir.active) settingsOpen = false
        val fixed = k.fixedScreen(this)   // the home never scrolls as a whole: the status is fixed, the doors are the list
        if (mir.active && EventLog.running && !settingsOpen) {   // the OS is in use: the app is its picture, and a second way to drive it
            val key = mir.title + "\u0000" + mir.items.joinToString("\u0001")
            if (mirrorKey == key && root.getChildAt(0) === mirrorHost) { mirror?.update(mir.index, mir.lastPress); return }   // same list: only the mark moves
            k.mirrorScreen(fixed, mir.title.ifEmpty { tr("Home") }, mir.items, mir.index, mir.lastPress,
                { i, t -> Actions.tapItem(i, t) },
                tr("Back"), { Actions.screenPress(com.faka.invisible.voicekit.Gesture.TRIPLE) },
                tr("Close"), { Actions.screenPress(com.faka.invisible.voicekit.Gesture.HOLD) },
                tr("Settings"), { settingsOpen = true; render() }).also { mirror = it; mirrorKey = key; mirrorHost = fixed }
            root.removeAllViews(); root.addView(fixed)
            return
        } else if (!EventLog.running) {
            k.status(fixed, tr("Stopped"), tr("The app is not listening to the button."), Ui.bad) {
                addView(k.primary(tr("Start")) { startForegroundService(Intent(this@MainActivity, ButtonService::class.java)) })
            }
        } else {
            k.status(fixed, tr("Ready"), tr("Turn the screen off. Hold your earbud button to hear the menu."), Ui.good) {
                addView(k.primary(tr("Wake the menu")) { Actions.screenPress(com.faka.invisible.voicekit.Gesture.HOLD) })
            }
        }
        if (mir.active && settingsOpen) fixed.addView(k.quiet("\u2039 " + tr("Back to the menu")) { settingsOpen = false; render() })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fixed.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = k.dp(Ui.S2) })
        root.removeAllViews(); root.addView(fixed); cardParent = list
        val n = Actions.tasks().size
        k.group(list) {
            k.navRow(this, tr("Tasks"), if (n == 0) tr("None yet") else tr("{0} to do").replace("{0}", "$n")) { open(Page.TASKS) }
            k.navRow(this, tr("Your menu"), tr("Pin, move or hide what you hear")) { open(Page.MENU) }
            k.navRow(this, tr("Voice and speed"), tr("Language") + " " + MenuLanguage.names[MenuLanguage.code] + ", " + tr("Voice").lowercase() + " ${"%.1f".format(s.speechRate)}x") { open(Page.VOICE) }
            k.navRow(this, tr("Emergency contact"), Actions.emergencyContact().ifEmpty { tr("Not set") }) { open(Page.EMERGENCY) }
            val pcs = Actions.computers()
            k.navRow(this, tr("Computers"), if (pcs.isEmpty()) tr("Add a computer") else pcs.joinToString(", ") { it.name }) { open(Page.COMPUTERS) }
            if (pcs.isNotEmpty()) k.navRow(this, tr("Laptop"), tr("Rooms of your laptop: read, answer")) { open(Page.LAPTOP) }
            k.navRow(this, tr("AI on the phone"), if (!s.ai) "Off" else if (ModelDownload.ready(this@MainActivity)) "On" else "On, model not downloaded") { open(Page.AI) }
            k.navRow(this, tr("Help"), tr("Button gestures and practice")) { open(Page.HELP) }
        }
        if (EventLog.running) list.addView(k.quiet(tr("Stop listening"), Ui.bad) { stopService(Intent(this, ButtonService::class.java)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
    }

    /** The emergency contact: its own small page. */
    private fun emergencyPage() {
        card(tr("Phone number"), tr("Gets a text when you pick Emergency in Phone. Leave empty for no text.")) {
            val box = k.input(tr("Phone number")).apply {
                minLines = 1; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(Actions.emergencyContact())
            }
            val note = k.text("", Type.CAPTION, Ui.good).apply { setPadding(0, k.dp(Ui.S1), 0, 0); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(k.btn(tr("Save")) {
                Actions.setEmergencyContact(box.text.toString())
                note.visibility = View.VISIBLE; note.text = if (Actions.emergencyContact().isEmpty()) tr("Saved. No emergency contact.") else tr("Saved.")
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
            addView(note)
        }
    }

    private fun tasksPage() {
        k.card(pageTop!!, "Tasks" + Actions.tasks().size.let { if (it > 0) " ($it)" else "" }, "Add one here, or share from Google Keep. Hold the button and pick \"Tasks\" to hear them.", true) {
            val input = k.input("Write a task, or paste a checklist")
            val note = k.text(taskNote, Type.CAPTION, if (taskNoteBad) Ui.bad else Ui.good).apply { setPadding(0, k.dp(6), 0, 0) }
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
            addView(k.btn("Add task", true) { add() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
            if (taskNote.isNotEmpty()) addView(note)
        }
        run {
            val ts = Actions.tasks()
            if (ts.isEmpty()) body.addView(k.text("No tasks yet.\nIn Google Keep: open a note, tap Share, choose \"Invisible task\". Only unchecked items are added.", Type.CAPTION, Ui.soft).apply { setPadding(0, k.dp(12), 0, 0) })
            val taskRows = ArrayList<View>()
            val th = k.dp(68)
            ts.forEachIndexed { i, t ->
                val handle = k.handle(40, visible = ts.size > 1)
                val item = k.row(handle, k.grow(k.text(t.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }),
                    k.btn("Done") { Actions.completeTask(t.id); taskNote = ""; render() }.apply { minWidth = k.dp(84) })
                item.setPadding(k.dp(4), 0, k.dp(8), 0)
                k.surface(item)
                taskRows.add(item)
                if (ts.size > 1) k.makeDraggable(handle, taskRows, ts.size - 1, th + k.dp(8)) { from, to -> Actions.moveTask(from, to); render() }
                body.addView(item, LinearLayout.LayoutParams(-1, th).apply { topMargin = k.dp(if (i == 0) 14 else 8) })
            }
        }

    }

    /** The spoken tree, drawn from the same builder the voice uses ([TreeLeaves], [AppTree]). Pin, hide and move are saved in the settings the voice reads. */
    private fun treeCard() {
        // The phone queries (installed apps, 30 days of usage) and the tree search are slow: they run once, off the main thread, and are reused.
        val all = treeAll; val built = treeBuilt
        if (built == null || all == null) { warmTree(); card("What you hear") { addView(k.text("Loading your apps\u2026", Type.BODY, Ui.soft)) }; return }
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
                    val head = k.row(k.grow(k.text(n.label, Type.HEAD)), k.text("${leafCount(n)}  " + if (open) "\u25B4" else "\u25BE", Type.SMALL, Ui.soft))
                    head.minimumHeight = k.dp(48); head.setPadding(pad, 0, k.dp(4), 0)
                    head.setOnClickListener { openGroups = if (open) openGroups - key else if (depth == 0) setOf(key) else openGroups + key; render() }
                    addView(head)
                    if (open) n.children.forEach { node(it, depth + 1, key) }
                    return
                }
                val tag = listOfNotNull(if (a.pkg in s.pinned) "pinned" else null, if (a.pkg in s.moved) "moved" else null).joinToString(", ")
                val row = k.row(k.grow(k.text(a.label).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }), k.text(tag, Type.CAPTION, Ui.soft))
                row.minimumHeight = k.dp(48); row.setPadding(pad, 0, k.dp(4), 0)
                row.setOnClickListener { actions(a) }
                addView(row)
            }
            root.children.forEach { node(it, 0, "") }
            val gone = all.filter { it.pkg in s.hidden }
            if (gone.isNotEmpty()) {
                val key = "/hidden"; val open = key in openGroups
                val head = k.row(k.grow(k.text("Hidden", Type.HEAD)), k.text("${gone.size}  " + if (open) "\u25B4" else "\u25BE", Type.SMALL, Ui.soft))
                head.minimumHeight = k.dp(52)
                head.setOnClickListener { openGroups = if (open) openGroups - key else openGroups + key; render() }
                addView(head)
                if (open) gone.forEach { a -> addView(k.row(k.grow(k.text(a.label)), k.chip("Show") { change(s.copy(hidden = s.hidden - a.pkg)) })) }
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
            if (hits.isEmpty()) { out.addView(k.text("Nothing found", Type.CAPTION, Ui.soft)); return }
            hits.forEach { h ->
                out.addView(k.text(h.app.label, Type.HEAD), LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
                out.addView(k.text("Click: " + h.tree, Type.CAPTION, Ui.soft))
                out.addView(k.text("Hold on home: " + (h.find ?: "working it out\u2026"), Type.CAPTION, Ui.soft))
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
        k.group(body) { k.navRow(this, tr("Order, Today and Phone"), tr("Order, and what Today and Phone say")) { open(Page.MENUMORE) } }
        treeCard()
    }

    private fun menuMorePage() {
        k.group(body, "Order and speed") {
            k.toggleRow(this, "Reply options by use", "Put the ones you pick most first.", s.learnOrder) { v -> change(s.copy(learnOrder = v)) }
            k.toggleRow(this, "Fast sound for known options", "Well-known options are spoken as a short sound.", s.spearcons) { v -> change(s.copy(spearcons = v)) }
        }

        k.card(body, "Inside Today", "What you hear after you pick Today. Drag to reorder.", pad = false) {
            addView(k.dragList(s.info, LauncherSettings.INFO_ITEMS, emptySet()) { order -> change(s.copy(info = LauncherSettings.cleanInfo(order))) })
        }

        k.card(body, "Inside Phone", "What you hear after you pick Phone. Drag to reorder.", pad = false) {
            addView(k.dragList(s.phone, LauncherSettings.PHONE_ITEMS, emptySet()) { order -> change(s.copy(phone = LauncherSettings.cleanPhone(order))) })
        }

    }

    private fun voicePage() {
        k.group(body) {
            val codes = listOf(MenuLanguage.AUTO) + MenuLanguage.supported
            fun name(c: String) = if (c == MenuLanguage.AUTO) tr("Automatic") else MenuLanguage.names[c] ?: c
            k.navRow(this, tr("Menu language"), name(s.menuLanguage)) {
                AlertDialog.Builder(this@MainActivity).setTitle(tr("Menu language")).setSingleChoiceItems(codes.map { name(it) }.toTypedArray(), codes.indexOf(s.menuLanguage).coerceAtLeast(0)) { d, i ->
                    d.dismiss(); change(s.copy(menuLanguage = codes[i]))
                }.show()
            }
        }
        card(tr("Speed"), tr("Find what feels right. You can change it any time.")) {
            addView(slider(tr("Pause between options"), "%.1f s".format(s.itemGapMs / 1000f), 39, ((s.itemGapMs - 100) / 100).toInt()) {
                s.copy(itemGapMs = 100L + it * 100) })
            addView(slider(tr("Voice speed"), "%.1f x".format(s.speechRate), 12, ((s.speechRate - 0.6f) * 10).toInt()) {
                s.copy(speechRate = 0.6f + it / 10f) })
            addView(slider(tr("Time to cancel a send"), "%.1f s".format(s.undoMs / 1000f), 13, ((s.undoMs - 1500) / 500).toInt()) {
                s.copy(undoMs = 1500L + it * 500) })
            addView(slider(tr("Rounds before it goes quiet"), "${s.silentLoops}", 4, s.silentLoops - 1) { s.copy(silentLoops = it + 1) })
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
        card("AI on the phone", "Writes replies and tidy tasks. It runs on the phone, uses no network after the download, and never listens.") {
            k.toggleRow(this, "Use AI for replies and tasks", null, s.ai) { v -> change(s.copy(ai = v)); if (v) ModelDownload.start(applicationContext) { EventLog.refresh() } }
            if (s.ai) {
                val ready = ModelDownload.ready(this@MainActivity)
                addView(k.text(if (ready) "Ready. The model is on this phone." else ModelDownload.status.ifEmpty { "The model (2.6 GB) is not on this phone yet." }, Type.CAPTION, Ui.soft),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
                if (!ready) addView(k.btn("Download model (Wi-Fi only)", true) { ModelDownload.start(applicationContext) { EventLog.refresh() }; render() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
            }
        }

    }

    private fun helpPage() {
        card("How to use it") {
            addView(k.text("Music or idle phone: click plays or pauses. 2 clicks: next. 3 clicks: previous.\n\n" +
                "Hold: wake the menu.\n\n" +
                com.faka.invisible.voicekit.Voice.GESTURE_HELP.text).apply { setLineSpacing(0f, 1.1f) })
        }

        card("Practice", "A one-minute lesson you hear: click, two clicks, three clicks, close.") {
            addView(k.btn("Replay the practice") { Actions.startPractice() })
        }

        card("Recent button presses") {
            addView(k.text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, Type.SMALL, Ui.soft).apply { setLineSpacing(0f, 1.2f) })
        }

        body.addView(k.quiet("Reset settings", Ui.bad) { k.confirm("Reset all settings?", "Reset") { change(LauncherSettings()) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
    }

    /** "25 min", "1 h", "1 h 30". */
    private fun clockText(min: Int) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

}
