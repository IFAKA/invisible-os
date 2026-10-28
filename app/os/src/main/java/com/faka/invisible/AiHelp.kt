package com.faka.invisible

import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.Computer
import com.faka.invisible.logickit.AiAssist
import com.faka.invisible.logickit.AiChoice
import com.faka.invisible.logickit.AiGuard
import com.faka.invisible.logickit.AiSource
import com.faka.invisible.logickit.squash
import com.faka.invisible.logickit.words
import com.faka.invisible.oskit.Notif
import com.faka.invisible.oskit.Trace
import java.io.IOException

/**
 * The AI for notifications: the digest on home, the short version of a long message, reply ideas and tasks for a message.
 * ONE order for every job (`AiChoice`): the computer's model if a computer is set and answers, else the model on the phone if it is
 * downloaded, else nothing; ONE switch (`enabled`, the AI setting): off means no AI anywhere. Never blocks: it works in a background
 * thread, caches the answer and gives "" until there is one (then the OS reads the original). The phone model uses the same prompts
 * and the same accuracy guard as the laptop (`AiGuard`). Private notifications are never sent, as the message or as context.
 */
class AiHelp(private val active: () -> Computer?, private val enabled: () -> Boolean = { true }, private val phone: PhoneAi? = null,
             private val run: (() -> Unit) -> Unit = { Thread(it, "ai-help").apply { isDaemon = true }.start() },
             private val now: () -> Long = System::currentTimeMillis,
             private val client: (Computer) -> AiClient = { c -> BridgeClient(c.url, c.token).let { b -> object : AiClient {
                 override fun digest(items: List<Triple<String, String, String>>) = b.digest(items)
                 override fun short(text: String, context: String) = b.short(text, context)
                 override fun taskLine(text: String) = b.short(text, "", "task")
                 override fun tasksSummary(items: List<String>) = b.short(items.joinToString("\n"), "", "tasks")
                 override fun replies(text: String, lang: String, context: String) = b.replies(text, lang, context)
                 override fun complete(typed: String, lang: String, context: String) = b.complete(typed, lang, context)
             } } }) {
    interface AiClient {
        @Throws(IOException::class) fun digest(items: List<Triple<String, String, String>>): String
        @Throws(IOException::class) fun short(text: String, context: String): String
        /** A short action phrase for a to-do item ("" = none). */
        @Throws(IOException::class) fun taskLine(text: String): String = ""
        /** The 2-3 main themes of a whole to-do list ("" = none). */
        @Throws(IOException::class) fun tasksSummary(items: List<String>): String = ""
        /** Reply sentences and tasks; both empty = the laptop's model had nothing. */
        @Throws(IOException::class) fun replies(text: String, lang: String, context: String): Pair<List<String>, List<String>>
        /** Up to 5 whole texts the typed letters mean (the bridge waits 1 s at most). */
        @Throws(IOException::class) fun complete(typed: String, lang: String, context: String): List<String> = emptyList()
    }

    /** The model on the phone (LocalAi). Slow: only called from the background thread. */
    interface PhoneAi {
        fun available(): Boolean
        fun generate(prompt: String): String
        fun replies(text: String, lang: String, context: String): List<String>
        fun tasks(text: String, lang: String, context: String): List<String>
    }

    private val lock = Any()
    private val askLock = Any()
    private var digestKey = ""        // the set the cached line (or the failure) is for
    private var digestLine = ""
    private var asking: String? = null
    private var latest: List<Notif> = emptyList()
    private val shorts = HashMap<String, String>()   // text + context -> short ("" = no answer)
    private val shortBusy = HashSet<String>()
    private val retryAt = HashMap<String, Long>()   // key -> when an empty answer may be asked again
    private var downUntil = 0L

    private fun computer(): Computer? = active()?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
    private fun split(all: List<Notif>) = all.filter { !it.hidden } to all.count { it.hidden }
    private fun source(): AiSource = AiChoice.pick(enabled(), computer() != null, now() < downUntil, phone?.available() == true)
    private fun markDown() { downUntil = now() + DOWN_MS }
    private fun keyOf(all: List<Notif>): String { val (open, hidden) = split(all); return AiAssist.digestKey(open.map { Triple(it.app, it.title, it.text) }, hidden) + "/" + source() }

    /** Runs [onComputer] when the computer is the AI, falling back to [onPhone]; [empty] tells a useless computer answer (then the phone is tried too). */
    private fun <T> chain(empty: T, isEmpty: (T) -> Boolean, onComputer: (AiClient) -> T, onPhone: (PhoneAi) -> T): T {
        val src = source()
        if (src == AiSource.NONE) return empty
        if (src == AiSource.COMPUTER) {
            val c = computer()
            if (c != null) {
                try { val r = onComputer(client(c)); if (!isEmpty(r) || phone?.available() != true || !enabled()) return r }
                catch (_: IOException) { markDown() } catch (_: RuntimeException) { markDown() }
            }
        }
        val p = phone?.takeIf { enabled() && it.available() } ?: return empty
        return try { onPhone(p) } catch (_: Throwable) { empty }
    }

    /** The notifications changed: ask for a new digest (and for the short version of long messages) in the background. */
    fun changed(all: List<Notif>) {
        if (source() == AiSource.NONE) return
        synchronized(lock) { latest = all }
        all.filter { !it.hidden }.forEach { prefetchShort(it, all) }
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
        val items = split(all).first.map { Triple(it.app, it.title, it.text) }
        run {
            val line = chain("", { it.isEmpty() }, { it.digest(items) }, { p -> AiGuard.makeDigest(items) { p.generate(it) } })
            val again: Boolean
            synchronized(lock) { digestKey = key; digestLine = line; asking = null; again = keyOf(latest) != key }
            if (again) askDigest()
        }
    }

    /** The digest line for exactly this set of notifications, with the private ones added ("3 chats, 2 junk, 2 private"); "" if not known (yet). */
    fun digest(all: List<Notif>): String {
        if (source() == AiSource.NONE) return ""
        val hidden = all.count { it.hidden }
        synchronized(lock) { latest = all }
        val line = synchronized(lock) { if (digestKey == keyOf(all)) digestLine else "" }
        if (line.isEmpty()) askDigest()
        return AiAssist.withPrivate(line, hidden)
    }

    private fun shortKey(text: String, context: String) = text + "\u0001" + context + "\u0001" + source()

    private fun prefetchShort(n: Notif, all: List<Notif>) {
        val t = n.text.squash()
        if (n.hidden || !AiAssist.isLong(t) || source() == AiSource.NONE) return
        val ctx = AiAssist.earlier(n, all)
        val k = shortKey(t, ctx)
        synchronized(lock) { if (k in shorts || !shortBusy.add(k)) return }
        run {
            val s = chain("", { it.isEmpty() }, { it.short(t, ctx) }, { p -> AiGuard.makeShort(t, ctx) { p.generate(it) } })
            synchronized(lock) { shorts[k] = s; shortBusy.remove(k) }
        }
    }

    /** The short version of a long message [n] when the AI made one, else "" (private, short, no AI, or no answer yet: asks now if it has not yet). */
    fun short(n: Notif, all: List<Notif>): String {
        val t = n.text.squash()
        if (n.hidden || !AiAssist.isLong(t) || source() == AiSource.NONE) return ""
        val k = shortKey(t, AiAssist.earlier(n, all))
        val s = synchronized(lock) { shorts[k] }
        if (s == null) prefetchShort(n, all)
        return s.orEmpty()
    }

    /** The cached answer for [k], or "" while it is asked in the background (an empty answer is asked again after a minute: the computer may have been off). */
    private fun cachedAsk(k: String, what: String, ask: () -> String): String {
        val s: String?
        val go: Boolean
        synchronized(lock) {
            s = shorts[k]
            go = (s == null || (s.isEmpty() && now() >= retryAt.getOrDefault(k, 0L))) && shortBusy.add(k)
        }
        if (go) run {
            val r = synchronized(askLock) { ask() }   // one question at a time: 18 at once made the computer time out
            Trace.ev("ai", "$what [${source()}] -> '$r'")
            synchronized(lock) { shorts[k] = r; shortBusy.remove(k); retryAt[k] = now() + RETRY_MS }
        }
        return s.orEmpty()
    }

    /** A short action phrase for the to-do item [text] when the AI made one, else "" (a short item, no AI, or no answer yet: asks now if it has not yet). */
    fun taskLine(text: String): String {
        val t = text.squash()
        if (t.words().size <= TASK_SHORT_WORDS || source() == AiSource.NONE) return ""
        return cachedAsk("task\u0001" + t + "\u0001" + source(), "task line ${t.take(40)}") {
            chain("", { it.isEmpty() }, { c -> c.taskLine(t) }, { p -> AiGuard.makeTaskLine(t) { p.generate(it) } })
        }
    }

    /** What the to-do list [texts] is mostly about, when the AI made it, else "" (fewer than 4 tasks, no AI, or no answer yet: asks now if it has not yet). */
    fun tasksSummary(texts: List<String>): String {
        if (texts.size < TASKS_SUMMARY_MIN || source() == AiSource.NONE) return ""
        return cachedAsk("tasks\u0001" + texts.joinToString("\u0002") + "\u0001" + source(), "tasks summary ${texts.size} tasks") {
            chain("", { it.isEmpty() }, { c -> c.tasksSummary(texts) }, { p -> AiGuard.makeTasksSummary(texts) { p.generate(it) } })
        }
    }

    /** True when some AI can write replies now. */
    fun canReply(): Boolean = source() != AiSource.NONE

    /**
     * Reply sentences and to-do items for [message] ("Sender: text") in [lang], with the earlier messages [context] of that chat; [done] gets them
     * on a worker thread (both empty = nothing). The caller must not call this for a private notification.
     */
    fun replies(message: String, lang: String, context: String, done: (List<String>, List<String>) -> Unit) {
        if (source() == AiSource.NONE) { done(emptyList(), emptyList()); return }
        run {
            val r = chain(emptyList<String>() to emptyList<String>(), { it.first.isEmpty() && it.second.isEmpty() },
                { it.replies(message, lang, context) }, { p -> p.replies(message, lang, context) to p.tasks(message, lang, context) })
            done(r.first, r.second)
        }
    }

    /**
     * Up to 5 whole texts that the typed letters [typed] may mean ("hay" -> "how are you"; the start of the word or the first letters of the next words).
     * BLOCKS: call it from a worker thread; the caller gives up after 1 s. Computer first, then the phone model, same guard as the bridge (`AiGuard`).
     * [context] = the earlier messages of the chat. The caller must not call this for a private notification or a password field. A slow computer is not marked
     * as down (it only answered late); no AI or no answer = empty.
     */
    fun complete(typed: String, lang: String, context: String): List<String> {
        val t = typed.squash()
        if (t.isEmpty() || source() == AiSource.NONE) return emptyList()
        val r = chain(emptyList<String>(), { it.isEmpty() },
            { c -> try { c.complete(t, lang, context) } catch (_: java.net.SocketTimeoutException) { emptyList() } },
            { p -> AiGuard.makeCompletions(t, context, lang) { p.generate(it) } })
        Trace.ev("ai", "complete [${source()}] '$t' -> ${r.size}")
        return r
    }

    private companion object { const val DOWN_MS = 60_000L; const val TASK_SHORT_WORDS = 4; const val TASKS_SUMMARY_MIN = 4; const val RETRY_MS = 60_000L }
}
