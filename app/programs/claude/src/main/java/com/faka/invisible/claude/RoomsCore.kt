package com.faka.invisible.claude

import com.faka.invisible.logickit.firstWords
import com.faka.invisible.logickit.squash
import java.util.Calendar
import java.util.TimeZone

/** One room of the laptop: a tmux pane (bridge/ROOMS-API.md). */
data class Room(val id: String, val name: String, val project: String, val kind: String, val state: String,
                val need: String, val options: List<String>, val actions: List<String>,
                /** The short story (what it was doing, what happened, what it wants) and what a plain "go" approves; "" when the server has none yet. */
                val story: String = "", val go: String = "",
                /** An open permission ask of this window (state "needs"): Claude Code wants to run a command or edit a file. */
                val ask: RoomAsk? = null,
                /** A multiple-choice menu on the screen of this window (state "needs"). */
                val choice: RoomChoice? = null)

/** A permission ask (bridge/ROOMS-API.md, room.ask): [id] for the answer, [what] the exact command or file, [plain] a one-line meaning from the laptop's model ("" if none), [options] the hook's labels in its order. */
data class RoomAsk(val id: String, val what: String, val plain: String, val options: List<String>)

/** Maps the hook's option labels to what the phone offers: No, Yes, "Yes and do not ask again", in that order; a kind the hook did not offer is never offered. */
object Approval {
    enum class Kind { NO, YES, ALWAYS }
    data class Opt(val kind: Kind, val label: String)

    private val denyWords = setOf("no", "deny", "denied", "reject", "refuse", "block", "decline", "cancel", "never")
    private val allowWords = setOf("yes", "allow", "approve", "accept", "ok", "okay", "proceed", "run", "continue")
    private val alwaysWords = setOf("always", "remember", "persist")

    /** The kind of one label: a denial word wins (so "Always deny" is No), then always, then allow; null when it means none of them. */
    fun classify(label: String): Kind? {
        val w = label.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val text = w.joinToString(" ")
        return when {
            w.any { it in denyWords } -> Kind.NO
            w.any { it in alwaysWords } || "do not ask" in text || "dont ask" in text || "don t ask" in text || "not ask again" in text -> Kind.ALWAYS
            w.any { it in allowWords } -> Kind.YES
            else -> null
        }
    }

    /** The options to offer, always in the order No, Yes, Always; the first label of each kind that the hook gave. */
    fun options(labels: List<String>): List<Opt> {
        val byKind = LinkedHashMap<Kind, String>()
        for (l in labels) { val k = classify(l) ?: continue; if (k !in byKind) byKind[k] = l }
        return Kind.values().mapNotNull { k -> byKind[k]?.let { Opt(k, it) } }
    }
}

/** A folder you can open a Claude chat in. */
data class Project(val name: String, val path: String, val uses: Int)

/** A parent folder where a new project may be made (GET /areas). */
data class Area(val name: String, val path: String, val uses: Int)

/** The server says a room turned to "needs" (an /events item of type room). */
data class RoomEvent(val id: String, val name: String, val state: String)

object RoomState {
    const val NEEDS = "needs"
    const val DONE = "done"
    /** The word the phone says after the room name. */
    fun word(state: String): String = when (state) {
        "needs" -> "needs you"
        "failed" -> "failed"
        "done" -> "done"
        "working" -> "working"
        else -> "idle"
    }
    /** True for a Tailscale address (100.x.y.z): when it is not reachable the usual cause is Tailscale being off on the phone. */
    fun isTailscale(url: String): Boolean = url.trim().substringAfter("://").startsWith("100.")
    /** The short topic of a window: its name (Claude pane title, or the shell window name) in at most 7 words; blank if it only repeats the project. */
    fun topic(r: Room): String { val t = r.name.squash().firstWords(7); return if (t.equals(r.project.squash(), true)) "" else t }
    /** The line of a window in the list: "project, topic, state" (the topic and the project may be missing). */
    fun windowLine(r: Room): String = listOf(r.project.squash(), topic(r), word(r.state)).filter { it.isNotEmpty() }.joinToString(", ")
    /** The name shown on the screen and heard: a blank name becomes the project, then "Room". */
    fun nameOf(r: Room): String = r.name.squash().ifEmpty { r.project.squash() }.ifEmpty { "Room" }
}

/** One action id of a room (ROOMS-API.md "Action ids"). */
data class RoomAction(val id: String) {
    val option: String? get() = if (id.startsWith("option:")) id.removePrefix("option:").squash().ifEmpty { null } else null
    val makeTarget: String? get() = if (id.startsWith("make:")) id.removePrefix("make:").squash().ifEmpty { null } else null
    /** Push and Clear ask "No, go back" / "Yes" first. */
    val needsConfirm get() = id == "push" || id == "clear" || id == "preview"
    /** Handled on the phone, not sent with POST /room/act. */
    val onPhone get() = id == "readall" || id == "screen" || id == "write" || id == HEAR || id == TOOLS || id == MORE || id == REPLIES || id == READ || id == "show" || id == "preview"
    /** True for something the earbud list can show (the screen view is the "screen" action itself). */
    val earbud get() = id != "screen" && id.isNotBlank()

    companion object {
        /** All actions of a room in the server's order (the old flat list): Hear it again, the server's actions, Tools before Read all / Write, "More" just before "Read all". */
        fun fullList(room: Room): List<RoomAction> {
            val acts = room.actions.map { RoomAction(it.trim()) }.filter { it.earbud && it.id != HEAR && it.id != TOOLS && it.id != MORE && it.id != REPLIES && it.id != READ }.distinctBy { it.id }
            val at = acts.indexOfFirst { it.id == "readall" || it.id == "write" }.let { if (it < 0) acts.size else it }   // Tools sits before Read all / Write
            val rest = acts.drop(at).flatMap { if (it.id == "readall") listOf(RoomAction(MORE), it) else listOf(it) }   // "More" (one paragraph at a time) just before "Read all"
            return listOf(RoomAction(HEAR)) + acts.take(at) + RoomAction(TOOLS) + rest
        }

        /** The replies that may sit at the top of a window list; the rest of them go into "Replies". */
        private val REPLY_IDS = listOf("go", "yes", "commit", "push", "compact", "explain", "recommend", "research", "progress", "hold", "clear")
        const val MAX_TOP = 8
        const val MAX_OPTIONS = 3

        /** Items inside "Read": More (in parts), Read all, then Show on phone / Start preview when the server offered it. */
        fun readItems(room: Room): List<RoomAction> {
            val full = fullList(room)
            return listOf("more", "readall", "show", "preview").mapNotNull { id -> full.firstOrNull { it.id == id } }
        }

        /** Items inside "Replies": every reply and other server action that is not at the top (server order). */
        fun repliesItems(room: Room): List<RoomAction> = split(room).second

        /** (top-level actions between Hear it again and Replies, items for "Replies"). Budget: [MAX_TOP] items in all; what does not fit goes to "Replies". */
        private fun split(room: Room): Pair<List<RoomAction>, List<RoomAction>> {
            val full = fullList(room)
            val options = full.filter { it.option != null }.take(MAX_OPTIONS)
            val stop = full.firstOrNull { it.id == "stop" }?.takeIf { room.state == "working" }
            val replies = full.filter { it.id in REPLY_IDS }
            var top2 = replies.take(2)
            if (top2.size == 2 && top2.any { it.id == "go" } && top2.any { it.id == "yes" }) {   // go and yes together: keep go, take the next reply instead of yes
                top2 = (top2.filter { it.id != "yes" } + listOfNotNull(replies.getOrNull(2))).sortedBy { replies.indexOf(it) }
            }
            // Shell windows keep their own actions (Run again, Make...) at the top while there is room.
            val own = if (room.kind == "claude") emptyList() else full.filter { it.option == null && it.id !in REPLY_IDS && it.id != "stop" && it.id !in FIXED_IDS }
            val hasRead = readItems(room).isNotEmpty()
            // hear + replies-group + read-group + tools + write are counted first; the movable items fill the rest.
            val fixed = 1 + 1 + (if (hasRead) 1 else 0) + 1 + (if (full.any { it.id == "write" }) 1 else 0)
            // If nothing goes to Replies, its entry is not needed and the budget is one larger.
            fun fits(n: Int, anyHidden: Boolean) = n + (if (anyHidden) fixed else fixed - 1) <= MAX_TOP
            val shown = ArrayList<RoomAction>(options + listOfNotNull(stop) + top2 + own)
            val dropOrder = (own.reversed() + top2.reversed() + options.reversed())   // what is hidden first when the list is too long
            fun hiddenCount() = full.count { it.id !in FIXED_IDS && it !in shown }
            for (d in dropOrder) { if (fits(shown.size, hiddenCount() > 0)) break; shown.remove(d) }
            val inReplies = full.filter { it.id !in FIXED_IDS && it !in shown }
            return (shown.filter { it.option != null } + shown.filter { it.id == "stop" } + shown.filter { it.option == null && it.id != "stop" }.sortedBy { full.indexOf(it) }) to inReplies
        }

        private val FIXED_IDS = setOf(HEAR, TOOLS, "write", "more", "readall", "show", "preview")

        /** What the earbud reads in a window (no ask, no menu), at most [MAX_TOP]: Hear it again, Claude's options (3), Stop (working), 2 replies, Replies, Read, Tools, Write. */
        fun earbudList(room: Room): List<RoomAction> {
            val (top, inReplies) = split(room)
            return listOf(RoomAction(HEAR)) + top + (if (inReplies.isNotEmpty()) listOf(RoomAction(REPLIES)) else emptyList()) +
                (if (readItems(room).isNotEmpty()) listOf(RoomAction(READ)) else emptyList()) + RoomAction(TOOLS) + fullList(room).filter { it.id == "write" }
        }
        /** Phone-only: opens the list of the other replies. */
        const val REPLIES = "replies"
        /** Phone-only: opens More, Read all, Show on phone / Start preview. */
        const val READ = "read"
        /** Phone-only option that opens the work tools of the window (Git, merge request...). */
        const val TOOLS = "tools"
        /** Phone-only first option of every window: say the story again. */
        const val HEAR = "hear"
        /** Phone-only: Claude's last message one paragraph at a time. */
        const val MORE = "more"
        /** The actions that make a button on the phone screen: everything the server can run (no read-aloud, no keyboard, no "screen"). */
        fun screenList(room: Room): List<RoomAction> = fullList(room).filter { !it.onPhone }
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
