package com.faka.invisible.claude

import com.faka.invisible.logickit.squash
import java.util.Calendar
import java.util.TimeZone

/** One room of the laptop: a tmux pane (bridge/ROOMS-API.md). */
data class Room(val id: String, val name: String, val project: String, val kind: String, val state: String,
                val need: String, val options: List<String>, val actions: List<String>)

/** A folder you can open a Claude chat in. */
data class Project(val name: String, val path: String, val uses: Int)

/** A parent folder where a new project may be made (GET /areas). */
data class Area(val name: String, val path: String, val uses: Int)

/** The server says a room turned to "needs" (an /events item of type room). */
data class RoomEvent(val id: String, val name: String, val state: String)

object RoomState {
    const val NEEDS = "needs"
    /** The word the phone says after the room name. */
    fun word(state: String): String = when (state) {
        "needs" -> "needs you"
        "failed" -> "failed"
        "done" -> "done"
        "working" -> "working"
        else -> "idle"
    }
    /** The name shown on the screen and heard: a blank name becomes the project, then "Room". */
    fun nameOf(r: Room): String = r.name.squash().ifEmpty { r.project.squash() }.ifEmpty { "Room" }
}

/** One action id of a room (ROOMS-API.md "Action ids"). */
data class RoomAction(val id: String) {
    val option: String? get() = if (id.startsWith("option:")) id.removePrefix("option:").squash().ifEmpty { null } else null
    val makeTarget: String? get() = if (id.startsWith("make:")) id.removePrefix("make:").squash().ifEmpty { null } else null
    /** Push and Clear ask "No, go back" / "Yes" first. */
    val needsConfirm get() = id == "push" || id == "clear"
    /** Handled on the phone, not sent with POST /room/act. */
    val onPhone get() = id == "readall" || id == "screen" || id == "write"
    /** True for something the earbud list can show (the screen view is the "screen" action itself). */
    val earbud get() = id != "screen" && id.isNotBlank()

    companion object {
        /** The actions to read, in the order the server gave them, without repeats and without "screen". */
        fun earbudList(room: Room): List<RoomAction> = room.actions.map { RoomAction(it.trim()) }.filter { it.earbud }.distinctBy { it.id }
        /** The actions that make a button on the phone screen: everything the server can run (no read-aloud, no keyboard, no "screen"). */
        fun screenList(room: Room): List<RoomAction> = earbudList(room).filter { !it.onPhone }
    }
}

/** Mute the room earcon until the next 07:00 local time. */
object RoomMute {
    const val KEY = "rooms_mute_until"
    const val MORNING_HOUR = 7

    /** The next 07:00 after [now] (epoch ms) in [tz]. */
    fun nextMorning(now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, MORNING_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
    fun muted(until: Long, now: Long) = now < until
}
