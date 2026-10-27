package com.faka.invisible

import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.Computer
import com.faka.invisible.logickit.AiAssist
import com.faka.invisible.logickit.squash
import com.faka.invisible.oskit.Notif
import java.io.IOException

/**
 * The laptop's model helps with notifications (bridge/ROOMS-API.md /ai/digest, /ai/short). Never blocks: it asks in a background
 * thread, caches the answer, and gives "" until there is one (then the OS reads the original, as before). Nothing happens when no
 * computer is set. Private notifications are never sent.
 */
class AiHelp(private val active: () -> Computer?, private val run: (() -> Unit) -> Unit = { Thread(it, "ai-help").apply { isDaemon = true }.start() },
             private val client: (Computer) -> AiClient = { c -> BridgeClient(c.url, c.token).let { b -> object : AiClient {
                 override fun digest(items: List<Triple<String, String, String>>) = b.digest(items)
                 override fun short(text: String) = b.short(text)
             } } }) {
    interface AiClient {
        @Throws(IOException::class) fun digest(items: List<Triple<String, String, String>>): String
        @Throws(IOException::class) fun short(text: String): String
    }

    private val lock = Any()
    private var digestKey = ""        // the set the cached line (or the failure) is for
    private var digestLine = ""
    private var asking: String? = null
    private var latest: List<Notif> = emptyList()
    private val shorts = HashMap<String, String>()   // text -> short ("" = no answer)
    private val shortBusy = HashSet<String>()

    private fun computer(): Computer? = active()?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
    private fun split(all: List<Notif>) = all.filter { !it.hidden } to all.count { it.hidden }
    private fun keyOf(all: List<Notif>): String { val (open, hidden) = split(all); return AiAssist.digestKey(open.map { Triple(it.app, it.title, it.text) }, hidden) }

    /** The notifications changed: ask for a new digest (and for the short version of long messages) in the background. */
    fun changed(all: List<Notif>) {
        if (computer() == null) return
        synchronized(lock) { latest = all }
        all.filter { !it.hidden }.forEach { prefetchShort(it.text) }
        askDigest()
    }

    private fun askDigest() {
        val all: List<Notif>; val key: String
        synchronized(lock) {
            all = latest; key = keyOf(all)
            if (key == digestKey || asking == key) return
            val open = split(all).first
            if (!AiAssist.digestable(open.size)) { digestKey = key; digestLine = ""; return }
            asking = key
        }
        val c = computer() ?: return
        val items = split(all).first.map { Triple(it.app, it.title, it.text) }
        run {
            val line = try { client(c).digest(items) } catch (_: IOException) { "" } catch (_: RuntimeException) { "" }
            val again: Boolean
            synchronized(lock) { digestKey = key; digestLine = line; asking = null; again = keyOf(latest) != key }
            if (again) askDigest()
        }
    }

    /** The digest line for exactly this set of notifications, with the private ones added ("3 chats, 2 junk, 2 private"); "" if not known (yet). */
    fun digest(all: List<Notif>): String {
        if (computer() == null) return ""
        val hidden = all.count { it.hidden }
        synchronized(lock) { latest = all }
        val line = synchronized(lock) { if (digestKey == keyOf(all)) digestLine else "" }
        if (line.isEmpty()) askDigest()
        return AiAssist.withPrivate(line, hidden)
    }

    private fun prefetchShort(text: String) {
        val t = text.squash()
        if (!AiAssist.isLong(t)) return
        val c = computer() ?: return
        synchronized(lock) { if (t in shorts || !shortBusy.add(t)) return }
        run {
            val s = try { client(c).short(t) } catch (_: IOException) { "" } catch (_: RuntimeException) { "" }
            synchronized(lock) { shorts[t] = s; shortBusy.remove(t) }
        }
    }

    /** The short version of a long [text] when the laptop made one, else "" (asks now if it has not yet). */
    fun short(text: String): String {
        val t = text.squash()
        if (!AiAssist.isLong(t) || computer() == null) return ""
        val s = synchronized(lock) { shorts[t] }
        if (s == null) prefetchShort(t)
        return s.orEmpty()
    }
}
