package com.faka.invisible

import android.app.Activity
import android.widget.LinearLayout
import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.Room
import com.faka.invisible.claude.RoomAction
import com.faka.invisible.claude.RoomState
import com.faka.invisible.claude.RoomText
import java.io.IOException

/**
 * The "Laptop" page of the app (phone unlocked): the rooms of the laptop as rows, a room's screen text and its action buttons.
 * Same server and same action ids as the earbud tool (bridge/ROOMS-API.md). All network work runs on a background thread.
 * Only UI Kit parts.
 */
class LaptopPage(private val a: Activity, private val k: com.faka.invisible.Kit, private val body: LinearLayout, private val redraw: () -> Unit) {
    private var rooms: List<Room>? = null
    private var down = false
    private var loading = false
    private var room: Room? = null
    private var screen = ""
    private var note = ""

    private fun client(): BridgeClient? {
        val c = Actions.activeComputer() ?: return null
        return if (c.url.isBlank() || c.token.isBlank()) null else BridgeClient(c.url, c.token)
    }

    /** True when the back link goes out of a room. */
    val inRoom get() = room != null
    fun title() = room?.let { RoomState.nameOf(it) } ?: Actions.activeComputer()?.name ?: "Computer"
    fun backLabel() = "Rooms"
    fun leaveRoom() { room = null; note = ""; redraw(); load() }

    /** Runs [work] off the main thread, then [done] on it. A null result means the laptop did not answer. */
    private fun <T> bg(work: (BridgeClient) -> T, done: (T?) -> Unit) {
        val c = client()
        if (c == null) { done(null); return }
        Thread({
            val r = try { work(c) } catch (_: IOException) { null }
            a.runOnUiThread { done(r) }
        }, "laptop-page").start()
    }

    /** Opening the page: forget the room and load the rooms of the active computer. */
    fun start() { room = null; note = ""; rooms = null; down = false; load() }

    fun load() {
        loading = true; redraw()
        bg({ it.rooms() }) { r -> loading = false; down = r == null; if (r != null) rooms = r; redraw() }
    }

    private fun loadScreen(r: Room) {
        bg({ it.screen(r.id) }) { t -> if (room?.id == r.id) { if (t == null) note = (Actions.activeComputer()?.name ?: "The computer") + " not reachable" else { screen = t; note = "" }; redraw() } }
    }

    private fun runAction(r: Room, act: RoomAction) {
        note = "Sending..."; redraw()
        bg({ it.act(r.id, act.id) }) { ok ->
            note = when (ok) { true -> "Sent: " + RoomText.label(act); false -> "The computer did not take it"; null -> (Actions.activeComputer()?.name ?: "The computer") + " not reachable" }
            redraw()
        }
    }

    private fun openTermux() {
        val i = a.packageManager.getLaunchIntentForPackage("com.termux")
        if (i == null) { note = "Termux is not installed on this phone"; redraw() } else a.startActivity(i)
    }

    fun draw() {
        val r = room
        if (r == null) drawRooms() else drawRoom(r)
    }

    private fun drawRooms() {
        val list = rooms
        when {
            loading && list == null -> body.addView(k.text("Loading...", Type.BODY, Ui.soft))
            down -> body.addView(k.text((Actions.activeComputer()?.name ?: "The computer") + " not reachable", Type.HEAD, Ui.bad))
        }
        if (list != null) {
            if (list.isEmpty()) body.addView(k.text("No rooms open.", Type.BODY, Ui.soft))
            else k.group(body) { list.forEach { r -> k.navRow(this, RoomState.nameOf(r) + ", " + RoomState.word(r.state), r.need.ifBlank { r.project }) { openRoom(r) } } }
        }
        body.addView(k.btn("Refresh") { load() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
    }

    private fun openRoom(r: Room) { room = r; screen = ""; note = ""; redraw(); loadScreen(r) }

    private fun drawRoom(r: Room) {
        body.addView(k.text(RoomState.word(r.state) + if (r.need.isNotBlank()) ". " + r.need else "", Type.BODY, Ui.soft))
        if (note.isNotEmpty()) body.addView(k.text(note, Type.SMALL, if (note.startsWith("Sent")) Ui.good else Ui.bad), LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1) })
        body.addView(k.mono(screen.ifBlank { "..." }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
        body.addView(k.btn("Refresh") { loadScreen(r) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
        k.group(body, "Actions") {
            RoomAction.screenList(r).forEach { act ->
                val label = RoomText.label(act)
                k.navRow(this, label, "") { if (act.needsConfirm) k.confirm("$label?", "Yes") { runAction(r, act) } else runAction(r, act) }
            }
        }
        body.addView(k.btn("Open full terminal", true) { openTermux() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S2) })
    }
}
