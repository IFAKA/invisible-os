package com.faka.invisible.claude

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The phone's side of the laptop bridge (bridge/server.py). Blocking: call it from a background thread, never the main thread. */
class BridgeClient(base: String, private val token: String) {
    private val base = base.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }

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

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun json(path: String, body: String? = null, method: String = "GET", timeoutMs: Int = 8000): JSONObject {
        val (code, text) = call(method, path, body, timeoutMs)
        if (code != 200) throw IOException("HTTP $code")
        return try { JSONObject(text) } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }

    /** GET /rooms. */
    @Throws(IOException::class)
    fun rooms(): List<Room> {
        val a = json("/rooms").optJSONArray("rooms") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.getJSONObject(i)
            val id = r.optString("id")
            if (id.isBlank()) null else Room(id, r.optString("name"), r.optString("project"), r.optString("kind"), r.optString("state"), r.optString("need"), strings(r.optJSONArray("options")), strings(r.optJSONArray("actions")))
        }
    }
    /** GET /room/last: Claude's whole last message. */
    @Throws(IOException::class) fun last(id: String): String = json("/room/last?id=${enc(id)}").optString("text")
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
    /** POST /project/open: a new tmux window with claude; mode is "continue" or "new". */
    @Throws(IOException::class) fun openProject(path: String, mode: String): Boolean = json("/project/open", JSONObject().put("path", path).put("mode", mode).toString(), "POST", 10_000).optBoolean("ok")

    private fun call(method: String, path: String, body: String?, timeoutMs: Int): Pair<Int, String> {
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
}
