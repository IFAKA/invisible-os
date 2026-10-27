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
    private enum class Mode { LIST, ADD, ONE, RENAME }
    private class Status(val ok: Boolean, val windows: Int)

    private var mode = Mode.LIST
    private var selected: String? = null
    private var status = HashMap<String, Status>()
    private var checking = false
    private var error = ""
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
        return c.url + if (state.isEmpty()) "" else " · $state"
    }
    private fun dot(c: Computer) = when (status[c.name]?.ok) { true -> Ui.good; false -> Ui.edge; null -> Ui.edge }

    fun draw() {
        when (mode) {
            Mode.LIST -> drawList()
            Mode.ADD -> drawAdd()
            Mode.ONE -> Computers.find(Actions.computers(), selected.orEmpty())?.let { drawOne(it) } ?: back()
            Mode.RENAME -> drawRename()
        }
    }

    private fun drawList() {
        val list = Actions.computers()
        if (list.isEmpty()) body.addView(k.text(tr("No computer yet. Run bridge/server.py on your computer, then add its address and token here."), Type.BODY, Ui.soft))
        else k.group(body, null, tr("Each computer runs its own bridge. A second user on the same Mac needs another port: python3 server.py --port 8766.")) {
            list.forEach { c -> k.dotRow(this, dot(c), c.name, summary(c)) { selected = c.name; mode = Mode.ONE; error = ""; redraw() } }
        }
        body.addView(k.btn(tr("Add a computer"), true) { mode = Mode.ADD; error = ""; redraw() }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(Ui.S2) })
    }

    private fun drawAdd() {
        val name = k.input(tr("Name, e.g. Home")).apply { minLines = 1; maxLines = 1 }
        val url = k.input(tr("Address, e.g. http://100.64.0.5:8765")).apply { minLines = 1; maxLines = 1; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val tok = k.input(tr("Token")).apply { minLines = 1; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val note = k.text(error, Type.CAPTION, Ui.bad)
        k.card(body, tr("Add a computer"), tr("Run bridge/server.py on that computer, then type the address it prints and the token.")) {
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
        body.addView(k.btn(tr("Open rooms"), true) { openRooms(c.name) }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(Ui.S2) })
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
                else { Actions.setComputers(Computers.rename(list, old, name.text.toString())); selected = Computers.clean(name.text.toString()); mode = Mode.LIST; check() }
            }, LinearLayout.LayoutParams(-1, k.dp(52)).apply { topMargin = k.dp(10) })
            addView(note)
        }
    }
}
