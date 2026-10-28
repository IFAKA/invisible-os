package com.faka.invisible.claude

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The phone's side of the laptop bridge (bridge/server.py). Blocking: call it from a background thread, never the main thread. */
class BridgeClient(base: String, internal val token: String) {
    internal val base = base.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }

    companion object {
        /** The OS sets this to the phone's AI setting; false adds `ai=0` to /rooms, /room/story and /room/last (bridge/ROOMS-API.md). */
        @Volatile var aiOn: () -> Boolean = { true }
    }

    class Reply(val seq: Int, val events: List<ClaudeEvent>, val rooms: List<RoomEvent> = emptyList())

    /** Long poll: items newer than [since]; returns after [waitSec] seconds at the latest. */
    @Throws(IOException::class)
    fun poll(since: Int, waitSec: Int): Reply {
        val (code, text) = call("GET", "/events?since=$since&wait=$waitSec", null, (waitSec + 10) * 1000)
        if (code != 200) throw IOException("HTTP $code")
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val rooms = ArrayList<RoomEvent>()
            val events = (0 until arr.length()).mapNotNull { i ->
                val it = arr.getJSONObject(i)
                if (it.optString("type") == "room") { rooms.add(RoomEvent(it.optString("id"), it.optString("name"), it.optString("state"))); return@mapNotNull null }
                val opts = it.optJSONArray("options")?.let { a -> (0 until a.length()).map { j -> a.optString(j) } } ?: emptyList()
                ClaudeEvent.of(it.optString("id"), it.optString("kind"), it.optString("project"), it.optString("summary"), opts)
            }
            Reply(o.optInt("seq", since), events, rooms)
        } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }

    /** Sends the choice. 200 = taken, 409 = too late (answered or timed out). */
    @Throws(IOException::class)
    fun answer(id: String, choice: String): Int =
        call("POST", "/answer", JSONObject().put("id", id).put("choice", choice).toString(), 10_000).first

    /** "ai=0" when the phone's AI switch is off (see [aiOn]): the laptop then sends no model text. */
    private fun aiQ(lead: String) = if (aiOn()) "" else "${lead}ai=0"
    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    internal fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    internal fun json(path: String, body: String? = null, method: String = "GET", timeoutMs: Int = 8000): JSONObject {
        val (code, text) = call(method, path, body, timeoutMs)
        if (code != 200) throw IOException("HTTP $code")
        return try { JSONObject(text) } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }

    /** GET /rooms. */
    @Throws(IOException::class)
    fun rooms(): List<Room> {
        val a = json("/rooms" + aiQ("?")).optJSONArray("rooms") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.getJSONObject(i)
            val id = r.optString("id")
            if (id.isBlank()) null else Room(id, r.optString("name"), r.optString("project"), r.optString("kind"), r.optString("state"), r.optString("need"), strings(r.optJSONArray("options")), strings(r.optJSONArray("actions")), r.optString("story"), r.optString("go"), r.optJSONObject("ask")?.let { ak -> RoomAsk(ak.optString("id"), ak.optString("what"), ak.optString("plain"), strings(ak.optJSONArray("options"))) }?.takeIf { it.id.isNotBlank() }, r.optJSONObject("choice")?.let { parseChoice(it) })
        }
    }
    private fun parseChoice(c: JSONObject): RoomChoice? {
        val a = c.optJSONArray("options") ?: return null
        val opts = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { ChoiceOpt(it.optInt("n"), it.optString("label"), it.optString("desc")) } }.filter { it.n > 0 && it.label.isNotBlank() }
        return if (opts.isEmpty() || c.optString("question").isBlank()) null else RoomChoice(c.optString("question"), c.optBoolean("multi"), opts, c.optBoolean("other"))
    }
    /** POST /room/choose: pick [picks] (the numbers on the laptop screen) in the menu of a window; [other] = only move to the "type your own" entry. */
    @Throws(IOException::class) fun choose(id: String, picks: List<Int>, other: Boolean = false): Boolean =
        call("POST", "/room/choose", JSONObject().put("id", id).put("picks", JSONArray(picks)).put("other", other).toString(), 15_000).first == 200
    /** POST /ai/digest: one line over the notifications ([items] = app, title, text), "" when the laptop's model has none. */
    @Throws(IOException::class) fun digest(items: List<Triple<String, String, String>>): String =
        json("/ai/digest", JSONObject().put("items", JSONArray(items.map { JSONObject().put("app", it.first).put("title", it.second).put("text", it.third) })).toString(), "POST", 15_000).optString("line")
    /** POST /ai/short: a one or two sentence version of a long [text], "" when the laptop's model has none. [context] = earlier messages of the same chat. */
    @Throws(IOException::class) fun short(text: String, context: String = "", kind: String = ""): String =
        json("/ai/short", JSONObject().put("text", text).put("context", context).put("kind", kind).toString(), "POST", 15_000).optString("short")
    /** POST /ai/replies: reply sentences and to-do items for a message in [lang] ("en" or "es"); both empty when the laptop's model has none. */
    @Throws(IOException::class) fun replies(text: String, lang: String, context: String = ""): Pair<List<String>, List<String>> =
        json("/ai/replies", JSONObject().put("text", text).put("lang", lang).put("context", context).toString(), "POST", 20_000).let { strings(it.optJSONArray("replies")) to strings(it.optJSONArray("tasks")) }
    /** POST /ai/complete: up to 5 whole texts the typed letters [typed] mean (start of a word or first letters of words); empty when the laptop's model has none. Waits [timeoutMs] at most. */
    @Throws(IOException::class) fun complete(typed: String, lang: String, context: String = "", timeoutMs: Int = 1000): List<String> =
        strings(json("/ai/complete", JSONObject().put("text", typed).put("lang", lang).put("context", context).toString(), "POST", timeoutMs).optJSONArray("completions"))
    /** GET /room/last: Claude's whole last message. */
    @Throws(IOException::class) fun last(id: String): String = json("/room/last?id=${enc(id)}" + aiQ("&")).optString("text")
    /** GET /room/story: the short story and go text of one window (the laptop may take a few seconds to make it). */
    @Throws(IOException::class) fun story(id: String): Pair<String, String> = json("/room/story?id=${enc(id)}" + aiQ("&"), timeoutMs = 12_000).let { it.optString("story") to it.optString("go") }
    /** GET /room/screen: the last lines of the pane. */
    @Throws(IOException::class) fun screen(id: String): String = json("/room/screen?id=${enc(id)}").optString("text")
    /** POST /room/act: run one action id. */
    @Throws(IOException::class) fun act(id: String, action: String): Boolean = json("/room/act", JSONObject().put("id", id).put("action", action).toString(), "POST", 10_000).optBoolean("ok")
    /** POST /room/send: type [text] and Enter. */
    @Throws(IOException::class) fun send(id: String, text: String): Boolean = json("/room/send", JSONObject().put("id", id).put("text", text).toString(), "POST", 10_000).optBoolean("ok")
    /** GET /projects. */
    @Throws(IOException::class)
    fun projects(): List<Project> {
        val a = json("/projects").optJSONArray("projects") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> val p = a.getJSONObject(i); if (p.optString("path").isBlank()) null else Project(p.optString("name").ifBlank { p.optString("path").substringAfterLast('/') }, p.optString("path"), p.optInt("uses")) }
    }
    /** GET /areas. */
    @Throws(IOException::class)
    fun areas(): List<Area> {
        val a = json("/areas").optJSONArray("areas") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> val p = a.getJSONObject(i); if (p.optString("path").isBlank()) null else Area(p.optString("name").ifBlank { p.optString("path").substringAfterLast('/') }, p.optString("path"), p.optInt("uses")) }
    }
    /** POST /project/new: make the folder in [area] (a path from [areas]), git init, open claude there, type [ask] if not blank. */
    @Throws(IOException::class) fun newProject(area: String, name: String, ask: String): Boolean =
        json("/project/new", JSONObject().put("area", area).put("name", name).apply { if (ask.isNotBlank()) put("ask", ask) }.toString(), "POST", 15_000).optBoolean("ok")
    /** POST /project/open: a new tmux window with claude; mode is "continue" or "new". The id of the new window, null if the laptop refused. */
    @Throws(IOException::class) fun openProject(path: String, mode: String): String? {
        val (code, text) = call("POST", "/project/open", JSONObject().put("path", path).put("mode", mode).toString(), 10_000)
        if (code == 400) return null
        if (code != 200) throw IOException("HTTP $code")
        return try { JSONObject(text).takeIf { it.optBoolean("ok") }?.optString("id")?.takeIf { it.isNotBlank() } } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }

    /** GET /info: the profile this laptop suggests (work if a project has dw.json) and its name. */
    @Throws(IOException::class) fun info(): BridgeInfo = json("/info").let { BridgeInfo(Profiles.normalize(it.optString("suggest")), it.optString("host")) }
    /** GET /room/tools: the tools of one window for [profile] (groups with children, or tools). */
    @Throws(IOException::class)
    fun tools(id: String, profile: String): List<ToolNode> {
        val (code, text) = call("GET", "/room/tools?id=${enc(id)}&profile=${enc(profile)}", null, 8000)
        if (code != 200) throw IOException("HTTP $code")
        return try { ToolsJson.list(JSONArray(text)) } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }
    /** POST /room/tool: run [tool] with [arg]; the story to say and the follow-up items. */
    @Throws(IOException::class)
    fun tool(id: String, tool: String, arg: String, profile: String): ToolReply =
        ToolsJson.reply(json("/room/tool?profile=${enc(profile)}", JSONObject().put("id", id).put("tool", tool).put("arg", arg).toString(), "POST", 120_000))

    internal fun call(method: String, path: String, body: String?, timeoutMs: Int): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally { c.disconnect() }
    }

    /** GET [path] as raw bytes (a picture); null body = the laptop said no. Blocking. */
    @Throws(IOException::class)
    internal fun bytes(path: String, timeoutMs: Int = 15_000): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
