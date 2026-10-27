package com.faka.invisible

import android.app.Activity
import android.text.InputType
import android.widget.LinearLayout
import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.Computers
import com.faka.invisible.voicekit.Translations
import java.io.IOException

/**
 * The "Computers" page of the app (phone unlocked): every computer that runs its own bridge/server.py, with a dot (green = it answered
 * a quick GET /rooms with the token, grey = not), the number of windows, and Add / Rename / Remove. Network work runs on a background
 * thread. Only UI Kit parts.
 */
class ComputersPage(private val a: Activity, private val k: Kit, private val body: LinearLayout, private val redraw: () -> Unit, private val openRooms: (String) -> Unit) {
    private enum class Mode { LIST, ADD, NAME, HAND, ONE, RENAME }
    private class Status(val ok: Boolean, val windows: Int)

    private var mode = Mode.LIST
    private var selected: String? = null
    private var status = HashMap<String, Status>()
    private var checking = false
    private var error = ""
    private var paired: Computer? = null
    private var gen = 0
    private fun tr(t: String) = Translations.label(t)

    /** True when the back link steps up to the list instead of leaving the page. */
    val inside get() = mode != Mode.LIST
    fun back() { mode = Mode.LIST; error = ""; redraw() }

    /** Opening the page: show the list and ask every computer once. */
    fun start() { mode = Mode.LIST; error = ""; check() }

    private fun check() {
        val list = Actions.computers(); val g = ++gen
        status = HashMap(); checking = list.isNotEmpty()
        for (c in list) Thread({
            val st = try { Status(true, BridgeClient(c.url, c.token).rooms().size) } catch (_: IOException) { Status(false, 0) } catch (_: RuntimeException) { Status(false, 0) }
            a.runOnUiThread { if (g == gen) { status[c.name] = st; checking = status.size < list.size; redraw() } }
        }, "computers-check").apply { isDaemon = true; start() }
        redraw()
    }

    private fun summary(c: Computer): String {
        val st = status[c.name]
        val state = when {
            st == null -> if (checking) tr("Checking...") else ""
            !st.ok -> tr("Not reachable")
            st.windows == 1 -> tr("1 window")
            else -> tr("{0} windows").replace("{0}", "${st.windows}")
        }
        val listening = if (isActive(c)) tr("Listening") + " · " else ""
        return listening + c.url + if (state.isEmpty()) "" else " · $state"
    }
    private fun isActive(c: Computer) = Actions.activeComputer()?.name.equals(c.name, true)
    private fun dot(c: Computer) = when (status[c.name]?.ok) { true -> Ui.good; false -> Ui.edge; null -> Ui.edge }

    fun draw() {
        when (mode) {
            Mode.LIST -> drawList()
            Mode.ADD -> drawPaste()
            Mode.NAME -> drawName()
            Mode.HAND -> drawAdd()
            Mode.ONE -> Computers.find(Actions.computers(), selected.orEmpty())?.let { drawOne(it) } ?: back()
            Mode.RENAME -> drawRename()
        }
    }

    private fun drawList() {
        val list = Actions.computers()
        if (list.isEmpty()) body.addView(k.text(tr("No computer yet. Run bridge/server.py on your computer, copy the pair code line it prints, then add it here."), Type.BODY, Ui.soft))
        else {
            k.group(body, null, tr("The phone listens to one computer at a time. Tap a computer to listen to it. Two Macs or two users: each runs its own bridge (the second with --port 8766).")) {
                list.forEach { c -> k.dotRow(this, dot(c), if (isActive(c)) c.name + " · " + tr("Listening") else c.name, summary(c)) { Actions.setActive(c.name); redraw() } }
            }
            list.forEach { c -> body.addView(k.quiet(tr("Rename or remove {0}").replace("{0}", c.name)) { selected = c.name; mode = Mode.ONE; error = ""; redraw() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) }) }
        }
        body.addView(k.btn(tr("Add a computer"), true) { mode = Mode.ADD; error = ""; redraw() }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(Ui.S2) })
    }

    /** Step 1 of adding: one field for the pair code that bridge/server.py prints. */
    private fun drawPaste() {
        val code = k.input(tr("Paste the pair code")).apply { minLines = 3; maxLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val note = k.text(error, Type.CAPTION, Ui.bad)
        fun next(text: String) {
            val c = Computers.parsePair(text)
            if (c == null) { note.text = tr("That is not a pair code. Copy the whole line that starts with invisible-pair:"); return }
            paired = c; error = ""; mode = Mode.NAME; redraw()
        }
        k.card(body, tr("Add a computer"), tr("Run bridge/server.py on that computer, copy the line that starts with invisible-pair: and paste it here.")) {
            addView(code, LinearLayout.LayoutParams(-1, -2))
            addView(k.btn(tr("Next"), true) { next(code.text.toString()) }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(k.btn(tr("Paste from clipboard")) {
                val cm = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                next(cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString().orEmpty())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
            addView(k.quiet(tr("Enter by hand")) { mode = Mode.HAND; error = ""; redraw() })
            addView(note)
        }
    }

    /** Step 2 of adding: the name from the code, which you can change. */
    private fun drawName() {
        val c = paired ?: run { back(); return }
        val name = k.input(tr("Name")).apply { minLines = 1; maxLines = 1; setText(c.name) }
        val note = k.text(error, Type.CAPTION, Ui.bad)
        k.card(body, tr("Add a computer"), c.url) {
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(k.btn(tr("Save"), true) {
                val list = Actions.computers()
                val p = Computers.problem(list, name.text.toString(), c.url, c.token)
                if (p != null) note.text = tr(p)
                else { Actions.setComputers(Computers.add(list, c.copy(name = name.text.toString()))); error = ""; paired = null; mode = Mode.LIST; check() }
            }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(note)
        }
    }

    private fun drawAdd() {
        val name = k.input(tr("Name, e.g. Home")).apply { minLines = 1; maxLines = 1 }
        val url = k.input(tr("Address, e.g. http://100.64.0.5:8765")).apply { minLines = 1; maxLines = 1; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val tok = k.input(tr("Token")).apply { minLines = 1; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val note = k.text(error, Type.CAPTION, Ui.bad)
        k.card(body, tr("Enter by hand"), tr("Run bridge/server.py on that computer, then type the address it prints and the token.")) {
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            addView(tok, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) })
            addView(k.btn(tr("Save"), true) {
                val list = Actions.computers()
                val p = Computers.problem(list, name.text.toString(), url.text.toString(), tok.text.toString())
                if (p != null) note.text = tr(p)
                else { Actions.setComputers(Computers.add(list, Computer(name.text.toString(), url.text.toString(), tok.text.toString()))); error = ""; mode = Mode.LIST; check() }
            }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(note)
        }
    }

    private fun drawOne(c: Computer) {
        body.addView(k.text(c.name, Type.TITLE))
        body.addView(k.text(summary(c), Type.SMALL, Ui.soft), LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
        body.addView(k.btn(tr(if (isActive(c)) "Open rooms" else "Listen and open rooms"), true) { openRooms(c.name) }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(Ui.S2) })
        body.addView(k.btn(tr("Rename")) { mode = Mode.RENAME; error = ""; redraw() }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(Ui.S1) })
        body.addView(k.quiet(tr("Remove"), Ui.bad) {
            k.confirm(tr("Remove {0}?").replace("{0}", c.name), tr("Remove")) { Actions.setComputers(Computers.remove(Actions.computers(), c.name)); mode = Mode.LIST; check() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
    }

    private fun drawRename() {
        val old = selected.orEmpty()
        val name = k.input(tr("Name")).apply { minLines = 1; maxLines = 1; setText(old) }
        val note = k.text(error, Type.CAPTION, Ui.bad)
        k.card(body, tr("Rename"), null) {
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(k.btn(tr("Save"), true) {
                val list = Actions.computers(); val c = Computers.find(list, old)
                val p = if (c == null) "Write a name." else Computers.problem(list, name.text.toString(), c.url, c.token, renaming = old)
                if (p != null) note.text = tr(p)
                else { val wasActive = c != null && isActive(c); Actions.setComputers(Computers.rename(list, old, name.text.toString())); if (wasActive) Actions.setActive(name.text.toString()); selected = Computers.clean(name.text.toString()); mode = Mode.LIST; check() }
            }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(note)
        }
    }
}
