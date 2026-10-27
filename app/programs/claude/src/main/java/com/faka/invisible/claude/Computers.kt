package com.faka.invisible.claude

import android.content.SharedPreferences
import com.faka.invisible.logickit.squash
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice

/** One computer that runs its own bridge/server.py: a name you choose, its address and its token. */
data class Computer(val name: String, val url: String, val token: String, /** "work", "personal" or "" (not chosen yet; treated as personal). Decides which tools a window offers. */ val profile: String = "")

/** The stored list of computers (JSON in prefs), the checks on names, and the one-time move from the old single address. */
object Computers {
    const val KEY = "claude_computers"
    /** The name of the one computer the phone listens to. */
    const val ACTIVE_KEY = "claude_active"
    /** The name of the computer made from the old single address and token. */
    const val DEFAULT_NAME = "Laptop"
    private const val OLD_URL = "claude_url"
    private const val OLD_TOKEN = "claude_token"

    /** A name as stored: white space squashed. */
    fun clean(name: String) = name.squash()

    // --- JSON (hand written: the list is tiny, and this stays testable on the laptop) ---

    fun encode(list: List<Computer>): String =
        list.joinToString(",", "[", "]") { "{\"name\":${q(it.name)},\"url\":${q(it.url)},\"token\":${q(it.token)},\"profile\":${q(it.profile)}}" }

    private fun q(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n"); ch == '\r' -> append("\\r"); ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /** The text fields of one flat JSON object like {"n":"Home","u":"http://..","t":"x"}; null if it is not one. Numbers and other values are not allowed. */
    fun flatObject(json: String): Map<String, String>? {
        val r = Reader(json.trim())
        if (!r.eat('{')) return null
        val out = HashMap<String, String>()
        if (r.eat('}')) return out
        while (true) {
            val k = r.str() ?: return null
            if (!r.eat(':')) return null
            out[k] = r.str() ?: return null
            if (r.eat(',')) continue
            return if (r.eat('}')) out else null
        }
    }

    private class Reader(val json: String) {
        var i = 0
        fun ws() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean { ws(); return if (i < json.length && json[i] == c) { i++; true } else false }
        fun str(): String? {
            ws()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length && json[i] != '"') {
                var ch = json[i++]
                if (ch == '\\' && i < json.length) {
                    ch = when (val e = json[i++]) {
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { val h = json.substring(i, minOf(i + 4, json.length)); i += h.length; h.toIntOrNull(16)?.toChar() ?: '?' }
                        else -> e
                    }
                }
                sb.append(ch)
            }
            if (i >= json.length) return null
            i++
            return sb.toString()
        }
    }

    // --- the pair code: one line printed by bridge/server.py ---

    private const val PAIR_PREFIX = "invisible-pair:"
    private const val PAIR_WEB = "/pair#"

    fun pairCode(c: Computer): String =
        PAIR_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"n\":${q(c.name)},\"u\":${q(c.url)},\"t\":${q(c.token)}}".toByteArray(Charsets.UTF_8))

    /** The computer in a pasted pair code (extra words around the code are ignored); null if there is no usable code. Never throws. */
    fun parsePair(text: String): Computer? {
        val at = text.indexOf(PAIR_PREFIX); val web = text.indexOf(PAIR_WEB)
        val b64 = when {
            at >= 0 -> text.substring(at + PAIR_PREFIX.length).trimStart('/')   // "invisible-pair://..." (pasted, or the site's Connect button)
            web >= 0 -> text.substring(web + PAIR_WEB.length)   // "https://invisible-os.vercel.app/pair#..." is the form in the QR code
            else -> return null
        }.takeWhile { !it.isWhitespace() }
        val json = try { String(java.util.Base64.getUrlDecoder().decode(b64), Charsets.UTF_8) } catch (_: IllegalArgumentException) { return null }
        val f = flatObject(json) ?: return null
        val url = f["u"].orEmpty().trim(); val token = f["t"].orEmpty().trim()
        if (!(url.startsWith("http://") || url.startsWith("https://")) || token.isEmpty()) return null
        return Computer(clean(f["n"].orEmpty()), url, token)
    }

    /** The list from [json]; anything it cannot read is skipped (never throws). */
    fun decode(json: String): List<Computer> {
        val out = ArrayList<Computer>()
        val r = Reader(json)
        if (!r.eat('[')) return out
        while (true) {
            if (r.eat(']')) break
            if (r.eat(',')) continue
            if (!r.eat('{')) break
            val f = HashMap<String, String>()
            while (true) {
                if (r.eat('}')) break
                if (r.eat(',')) continue
                val k = r.str() ?: return out
                if (!r.eat(':')) return out
                f[k] = r.str() ?: return out
            }
            val name = clean(f["name"].orEmpty())
            if (name.isNotEmpty() && out.none { it.name.equals(name, true) }) out.add(Computer(name, f["url"].orEmpty().trim(), f["token"].orEmpty().trim(), Profiles.normalize(f["profile"].orEmpty())))
        }
        return out
    }

    // --- the stored list and the move from the old single address ---

    /** The list to use: [json] if it has computers; otherwise the old single address as one computer named [DEFAULT_NAME]; otherwise none. */
    fun resolve(json: String, oldUrl: String, oldToken: String): List<Computer> {
        val list = decode(json)
        if (list.isNotEmpty()) return list
        return if (oldUrl.isNotBlank() && oldToken.isNotBlank()) listOf(Computer(DEFAULT_NAME, oldUrl.trim(), oldToken.trim())) else emptyList()
    }

    /** The saved computers. The first time after an update the old single address becomes "Laptop" and is saved in the new key. */
    fun load(prefs: SharedPreferences): List<Computer> {
        val json = prefs.getString(KEY, null)
        val list = resolve(json.orEmpty(), prefs.getString(OLD_URL, "").orEmpty(), prefs.getString(OLD_TOKEN, "").orEmpty())
        if (json == null && list.isNotEmpty()) save(prefs, list)
        return list
    }

    fun save(prefs: SharedPreferences, list: List<Computer>) { prefs.edit().putString(KEY, encode(list)).apply() }

    // --- changes (each returns the new list; check with [problem] first) ---

    /** Why [name] / [url] / [token] cannot be saved, or null. [renaming] = the existing computer being edited (it may keep its own name). */
    fun problem(list: List<Computer>, name: String, url: String, token: String, renaming: String? = null): String? {
        val n = clean(name)
        return when {
            n.isEmpty() -> "Write a name."
            list.any { it.name.equals(n, true) && !it.name.equals(renaming, true) } -> "Another computer has this name."
            url.isBlank() -> "Write the address."
            token.isBlank() -> "Write the token."
            else -> null
        }
    }
    fun add(list: List<Computer>, c: Computer): List<Computer> = list + c.copy(name = clean(c.name), url = c.url.trim(), token = c.token.trim())
    fun rename(list: List<Computer>, from: String, to: String): List<Computer> = list.map { if (it.name.equals(from, true)) it.copy(name = clean(to)) else it }
    fun withProfile(list: List<Computer>, name: String, profile: String): List<Computer> = list.map { if (it.name.equals(name, true)) it.copy(profile = Profiles.normalize(profile)) else it }
    fun remove(list: List<Computer>, name: String): List<Computer> = list.filter { !it.name.equals(name, true) }
    fun find(list: List<Computer>, name: String): Computer? = list.firstOrNull { it.name.equals(clean(name), true) }

    // --- the one active computer ---

    /** The computer to listen to: the one named [name] if it exists, otherwise the first; null if there are none. */
    fun active(list: List<Computer>, name: String?): Computer? = (name?.let { find(list, it) }) ?: list.firstOrNull()
    /** The other computers, in list order. */
    fun others(list: List<Computer>, active: Computer?): List<Computer> = list.filter { !it.name.equals(active?.name, true) }
    /** The active name once the list changed from [before] to [after]: the same computer if it is still there, otherwise the next one that is (the list wraps), otherwise the first; null if none are left. */
    fun activeAfter(before: List<Computer>, after: List<Computer>, active: String?): String? {
        val was = active(before, active) ?: return after.firstOrNull()?.name
        find(after, was.name)?.let { return it.name }
        val at = before.indexOfFirst { it.name.equals(was.name, true) }
        return (before.drop(at + 1) + before.take(at)).firstOrNull { find(after, it.name) != null }?.name ?: after.firstOrNull()?.name
    }

    /** What adding a pair-code computer did: the new list, the name it got, whether it is the one the phone listens to, and whether it was there already. */
    data class Added(val list: List<Computer>, val name: String, val active: Boolean, val already: Boolean)

    /** Add the computer from a pair code without questions: a name that is taken gets " 2", " 3"...; the same address and token again changes nothing. The scanned computer always becomes the active one: you scan it to use it now. */
    fun addPaired(list: List<Computer>, c: Computer, name: String = c.name): Added {
        list.firstOrNull { it.url.trim() == c.url.trim() && it.token == c.token.trim() }?.let { return Added(list, it.name, true, true) }
        val base = clean(name).ifEmpty { "Computer" }
        var n = base; var i = 2
        while (list.any { it.name.equals(n, true) }) n = "$base ${i++}"
        return Added(add(list, c.copy(name = n)), n, true, false)
    }

    /** The line to show after [addPaired]. */
    fun addedSentence(a: Added) = when {
        a.already -> "${a.name}: the phone listens to it."
        else -> "Added ${a.name}. The phone listens to it."
    }

    /** The old dev hook: one address replaces the whole list; a blank address clears it. */
    fun single(url: String, token: String): List<Computer> =
        if (url.isBlank() || token.isBlank()) emptyList() else listOf(Computer(DEFAULT_NAME, url.trim(), token.trim()))

    /** Dev hook text "name|url|token;name|url|token" as a list. */
    fun parse(spec: String): List<Computer> = spec.split(';').mapNotNull { p ->
        val f = p.split('|'); if (f.size < 3) null else Computer(clean(f[0]), f[1].trim(), f[2].trim()).takeIf { it.name.isNotEmpty() }
    }
}

/** Laptop windows that finished close together are one spoken line. Pure: the OS asks for the line after its 3 s wait. */
class DoneBatch {
    private val windows = ArrayList<String>()

    /** True if this is the first window (the OS starts its wait now). The same window twice counts once. */
    fun add(window: String): Boolean {
        val first = windows.isEmpty()
        if (window !in windows) windows.add(window)
        return first
    }

    /** The line for what was collected, then empty. Null if nothing. */
    fun take(): Phrase? {
        val n = windows.toList(); windows.clear()
        val a = n.firstOrNull() ?: return null
        return if (n.size == 1) Voice.roomDone(a) else Voice.roomsDone(a, n.size - 1)
    }
}
