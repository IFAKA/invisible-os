package com.faka.invisible

import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.Notif

/** The sources of the home letters (one class each; add one line to the list in [Actions] for a new one). */

/** Every app (also the ones in the Storeroom) and OS program of the last home build: "Open <app>" / "Start <program>". The prior is the use weight of the tree. */
class AppsActions(private val leaves: () -> List<AppLeaf>) : ActionSource {
    override val id = "apps"
    override fun actions() = leaves().map { l ->
        val prog = l.pkg.startsWith("hub:")
        HomeAction(if (prog) ActionKind.START else ActionKind.OPEN, l.label, l.pkg, l.weight.coerceAtLeast(0.1) + (if (l.live) 3.0 else 0.0) + (if (l.stored) -0.05 else 0.0))
    }
}

/** Songs played (see [SongsPlayed]): "Play <title>". Prior = plays, a recent one counts more. */
class SongsActions(private val songs: () -> List<SongsPlayed.Song>, private val now: () -> Long = System::currentTimeMillis) : ActionSource {
    override val id = "songs"
    override fun actions(): List<HomeAction> = songs().map { s ->
        val days = ((now() - s.at) / 86_400_000.0).coerceAtLeast(0.0)
        HomeAction(ActionKind.PLAY, s.title, s.search, s.plays / (1.0 + days / 7.0))
    }
}

/**
 * Recent chats that can be answered: "Reply to <sender>", from the mailbox. A private notification (hidden, or from a bank or password app) has no
 * name here: it is never offered, so only "<App>, new notification" ever names it. One action per sender (the newest message), prior = how many are waiting.
 */
class ChatsActions(private val notifs: () -> List<Notif>) : ActionSource {
    override val id = "chats"
    override fun actions(): List<HomeAction> {
        val ok = notifs().filter { !it.hidden && it.reply != null && it.title.isNotBlank() && !NotifPrivacy.sensitivePackage(it.pkg) }
        return ok.groupBy { sender(it.title) }.filterKeys { it.isNotEmpty() }.map { (name, items) ->
            val newest = items.maxByOrNull { it.at }!!
            HomeAction(ActionKind.REPLY, name, newest.key, items.size.toDouble() + 1.0)
        }
    }
    /** "Ana" from "Ana", "Ana (2 messages)" or "Group: Ana". */
    private fun sender(title: String) = title.substringAfter(": ", title).substringBefore(" (").squash()
}

/** People to call: "Call <name>" (the call still asks "Yes, call now" before it dials). Loaded once off the main thread; empty until then (and when contacts are not allowed). */
class ContactsActions(private val load: () -> List<Contact>, private val run: (() -> Unit) -> Unit = { Thread(it, "home-contacts").apply { isDaemon = true }.start() }) : ActionSource {
    override val id = "contacts"
    @Volatile private var list: List<Contact>? = null
    @Volatile private var loading = false
    override fun actions(): List<HomeAction> {
        val have = list
        if (have == null) { if (!loading) { loading = true; run { list = try { load() } catch (_: Throwable) { emptyList() } } }; return emptyList() }
        return have.filter { it.name.isNotBlank() && it.number.isNotBlank() }.map { c -> HomeAction(ActionKind.CALL, c.name, c.name + "\n" + c.number, 1.0 + (if (c.starred) 2.0 else 0.0) + c.times.coerceAtMost(30) / 5.0) }
    }
}
