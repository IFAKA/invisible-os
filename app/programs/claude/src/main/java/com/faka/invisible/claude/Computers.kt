package com.faka.invisible.claude

import android.content.SharedPreferences
import com.faka.invisible.logickit.squash
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice

/** One computer that runs its own bridge/server.py: a name you choose, its address and its token. */
data class Computer(val name: String, val url: String, val token: String)

/** The stored list of computers (JSON in prefs), the checks on names, and the one-time move from the old single address. */
object Computers {
    const val KEY = "claude_computers"
    /** The name of the computer made from the old single address and token. */
    const val DEFAULT_NAME = "Laptop"
    private const val OLD_URL = "claude_url"
    private const val OLD_TOKEN = "claude_token"

    /** A name as stored: white space squashed. */
    fun clean(name: String) = name.squash()

    // --- JSON (hand written: the list is tiny, and this stays testable on the laptop) ---

    fun encode(list: List<Computer>): String =
        list.joinToString(",", "[", "]") { "{\"name\":${q(it.name)},\"url\":${q(it.url)},\"token\":${q(it.token)}}" }

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

    /** The list from [json]; anything it cannot read is skipped (never throws). */
    fun decode(json: String): List<Computer> {
        val out = ArrayList<Computer>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun str(): String? {
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
        skipWs()
        if (i >= json.length || json[i] != '[') return out
        i++
        while (i < json.length) {
            skipWs()
            if (i >= json.length || json[i] == ']') break
            if (json[i] == ',') { i++; continue }
            if (json[i] != '{') break
            i++
            val f = HashMap<String, String>()
            while (i < json.length) {
                skipWs()
                if (i < json.length && json[i] == '}') { i++; break }
                if (i < json.length && json[i] == ',') { i++; continue }
                val k = str() ?: return out
                skipWs(); if (i >= json.length || json[i] != ':') return out
                i++; skipWs()
                f[k] = str() ?: return out
            }
            val name = clean(f["name"].orEmpty())
            if (name.isNotEmpty() && out.none { it.name.equals(name, true) }) out.add(Computer(name, f["url"].orEmpty().trim(), f["token"].orEmpty().trim()))
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
    fun remove(list: List<Computer>, name: String): List<Computer> = list.filter { !it.name.equals(name, true) }
    fun find(list: List<Computer>, name: String): Computer? = list.firstOrNull { it.name.equals(clean(name), true) }

    /** The old dev hook: one address replaces the whole list; a blank address clears it. */
    fun single(url: String, token: String): List<Computer> =
        if (url.isBlank() || token.isBlank()) emptyList() else listOf(Computer(DEFAULT_NAME, url.trim(), token.trim()))

    /** Dev hook text "name|url|token;name|url|token" as a list. */
    fun parse(spec: String): List<Computer> = spec.split(';').mapNotNull { p ->
        val f = p.split('|'); if (f.size < 3) null else Computer(clean(f[0]), f[1].trim(), f[2].trim()).takeIf { it.name.isNotEmpty() }
    }
}

/** Laptop windows that finished close together are one spoken line. Pure: the OS adds names and asks for the line after its 3 s wait. */
class DoneBatch {
    private data class Item(val computer: String, val window: String)
    private val items = ArrayList<Item>()

    /** True if this is the first item (the OS starts its wait now). The same window twice counts once. */
    fun add(computer: String, window: String): Boolean {
        val first = items.isEmpty()
        if (items.none { it.computer == computer && it.window == window }) items.add(Item(computer, window))
        return first
    }

    /** The line for what was collected, then empty. The computer is named only when [many] (2 or more computers exist). Null if nothing. */
    fun take(many: Boolean): Phrase? {
        val n = items.toList(); items.clear()
        val a = n.firstOrNull() ?: return null
        return when {
            n.size == 1 -> if (many) Voice.roomDoneOn(a.computer, a.window) else Voice.roomDone(a.window)
            many -> Voice.roomsDoneOn(a.computer, a.window, n.size - 1)
            else -> Voice.roomsDone(a.window, n.size - 1)
        }
    }
}
