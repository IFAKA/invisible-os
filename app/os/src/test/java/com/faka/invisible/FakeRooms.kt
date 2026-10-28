package com.faka.invisible

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** The Kotlin twin of bridge/fake_rooms.py: a tiny fake Rooms server on localhost (same token header, same fixed answers, records every POST). */
internal class FakeRooms(val token: String = "e2e-token", val rooms: List<JSONObject> = DEFAULT_ROOMS) {
    val posts = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    /** Path (with query) of every GET, in order. */
    val gets = CopyOnWriteArrayList<String>()
    /** Names of windows that finished: the next /events says so, one name per call. */
    val fire = CopyOnWriteArrayList<String>()
    /** -1 until POST /project/open; then the number of GET /rooms since. The opened window %9 is listed from the second one on (it takes a moment to appear). */
    @Volatile private var polls = -1
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    @Volatile private var open = true

    init {
        Thread({ while (open) { try { val c = server.accept(); Thread({ try { serve(c) } catch (_: Exception) {} finally { c.close() } }).apply { isDaemon = true }.start() } catch (_: Exception) {} } }, "fake-rooms").apply { isDaemon = true }.start()
    }
    fun stop() { open = false; server.close() }

    private fun reply(c: Socket, o: Any, code: Int = 200) {
        val b = o.toString().toByteArray()
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
    }

    /** One request per connection: read the head and the body, answer, close. */
    private fun serve(c: Socket) {
        val inp = c.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }; return sb.toString() }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        var auth = ""; var len = 0
        while (true) { val l = line(); if (l.isEmpty()) break; val (k, v) = l.split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }; if (k == "authorization") auth = v; if (k == "content-length") len = v.toInt() }
        val body = if (len > 0) String(ByteArray(len).also { var n = 0; while (n < len) { val r = inp.read(it, n, len - n); if (r < 0) break; n += r } }) else ""
        if (auth != "Bearer $token") { c.getOutputStream().write("HTTP/1.1 401 X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val path = target.substringBefore('?')
        if (method == "POST") {
            val j = JSONObject(body.ifBlank { "{}" }); posts.add(path to j)
            when (path) {
                "/room/act" -> reply(c, JSONObject().put("ok", true).put("said", j.optString("action")))
                "/project/open" -> { polls = 0; reply(c, JSONObject().put("ok", true).put("id", "%9")) }
                "/room/tool" -> reply(c, tool(j))
                else -> reply(c, JSONObject().put("ok", true))
            }
            return
        }
        gets.add(target)
        when (path) {
            "/rooms" -> { val show = polls >= 0 && ++polls >= 2; reply(c, JSONObject().put("seq", 1).put("rooms", JSONArray(rooms + (if (show) listOf(OPENED) else emptyList())))) }
            "/room/last" -> reply(c, JSONObject().put("text", if (target.contains("%25")) "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here." else "Alpha last message. The design doc is ready."))
            "/room/story" -> {
                val id = target.substringAfter("id=", "").substringBefore('&').let { java.net.URLDecoder.decode(it, "UTF-8") }
                val r = rooms.firstOrNull { it.optString("id") == id }
                if (r == null) reply(c, JSONObject().put("error", "no such room"), 404)
                else reply(c, JSONObject().put("story", r.optString("story").ifBlank { "Fake story for ${r.optString("name")}." }).put("go", r.optString("go")))
            }
            "/info" -> reply(c, JSONObject().put("suggest", "work").put("host", "fake-host"))
            "/room/tools" -> {
                val work = target.contains("profile=work")
                val kids = JSONArray().put(t("git.status", "Status")).put(t("git.commit", "Commit")).put(t("git.push", "Push", true))
                val groups = JSONArray().put(JSONObject().put("id", "git").put("label", "Git").put("confirm", false).put("children", kids))
                if (work) groups.put(JSONObject().put("id", "jira").put("label", "Jira").put("confirm", false).put("children", JSONArray().put(t("jira.mine", "My tickets"))))
                reply(c, groups)
            }
            "/room/screen" -> reply(c, JSONObject().put("text", "$ claude\n> working on the design doc\n  done"))
            "/projects" -> reply(c, JSONObject().put("projects", JSONArray().put(proj("alpha-proj", "/tmp/alpha", 10)).put(proj("beta-proj", "/tmp/beta", 5))))
            "/areas" -> reply(c, JSONObject().put("areas", JSONArray().put(proj("hardware", "/tmp/fake/hardware", 9))))
            "/events" -> {
                val name = try { fire.removeAt(0) } catch (_: IndexOutOfBoundsException) { null }
                if (name != null) reply(c, JSONObject().put("seq", 2).put("items", JSONArray().put(JSONObject()
                    .put("type", "room").put("kind", "room").put("id", "%7").put("name", name).put("state", "done").put("project", "p").put("summary", "").put("options", JSONArray()).put("seq", 2))))
                else { Thread.sleep(300); reply(c, JSONObject().put("seq", 1).put("items", JSONArray())) }
            }
            else -> reply(c, JSONObject().put("error", "not found"), 404)
        }
    }
    private fun t(id: String, label: String, confirm: Boolean = false) = JSONObject().put("id", id).put("label", label).put("confirm", confirm)
    /** The fixed answers of POST /room/tool (the real ones are made by bridge/tools.py). */
    private fun tool(j: JSONObject): JSONObject = when (j.optString("tool")) {
        "git.status" -> JSONObject().put("ok", true).put("story", "Branch main. 2 changed files. Ahead 1.")
        "git.commit" -> JSONObject().put("ok", true).put("story", "Message: Fix the loop.").put("items", JSONArray()
            .put(JSONObject().put("label", "Cancel").put("tool", "").put("arg", ""))
            .put(JSONObject().put("label", "Create").put("tool", "git.commit.do").put("arg", "Fix the loop")))
        "git.commit.do" -> JSONObject().put("ok", true).put("story", "Committed.")
        else -> JSONObject().put("ok", true).put("story", "Done.")
    }
    private fun proj(name: String, path: String, uses: Int) = JSONObject().put("name", name).put("path", path).put("uses", uses)

    companion object {
        private val ACTIONS = listOf("go", "yes", "commit", "push", "readall", "screen", "write", "stop")
        fun room(id: String, name: String, project: String, kind: String, state: String, need: String, actions: List<String>, story: String = "", go: String = "") =
            JSONObject().put("id", id).put("name", name).put("project", project).put("path", "/tmp/$name").put("kind", kind).put("state", state)
                .put("need", need).put("options", JSONArray()).put("actions", JSONArray(actions)).put("story", story).put("go", go)
        /** A window with an open permission ask (ROOMS-API.md room.ask); the answer is POST /answer {id, choice}, recorded in [posts]. */
        fun askRoom(options: List<String> = listOf("Allow", "Deny", "Always allow")) =
            room("%4", "Delta", "delta-proj", "claude", "needs", "waiting for your permission", listOf("stop", "readall", "screen", "write"))
                .put("ask", JSONObject().put("id", "7").put("kind", "permission").put("what", "rm -rf build/cache")
                    .put("plain", "Deletes the build cache folder").put("options", JSONArray(options)))
        /** A window with a multiple-choice menu on its screen (ROOMS-API.md room.choice); the pick is POST /room/choose {id, picks, other}, recorded in [posts]. */
        fun choiceRoom(multi: Boolean = false) =
            room("%6", "Eps", "eps-proj", "claude", "needs", "Which approach do you want?", listOf("stop", "readall", "screen", "write"))
                .put("choice", JSONObject().put("question", "Which approach do you want?").put("multi", multi).put("other", true).put("options", JSONArray()
                    .put(JSONObject().put("n", 1).put("label", "Tree").put("desc", "Group apps by what you want to do"))
                    .put(JSONObject().put("n", 2).put("label", "Flat list").put("desc", "One long list"))))
        /** A window whose last message has three paragraphs (the "More" option). */
        fun partsRoom() = room("%7", "Zeta", "zeta-proj", "claude", "done", "", listOf("go", "readall", "screen", "write"), "Zeta story.")
        /** The window that "Continue last chat" opens (id %9, as POST /project/open answers); its story is the resumed chat. */
        val OPENED = room("%9", "Resumed", "alpha-proj", "claude", "idle", "", listOf("go", "readall", "write"), "Resumed chat story: we were fixing the loop.")
        val DEFAULT_ROOMS = listOf(
            room("%1", "Alpha", "alpha-proj", "claude", "needs", "The design doc is ready.", ACTIONS, "Wrote the design doc. It is ready. Say go to start building.", "start building"),
            room("%2", "Beta", "beta-proj", "claude", "working", "", listOf("stop", "readall", "screen", "write")),
            room("%3", "Gamma", "gamma-proj", "shell", "done", "build finished", listOf("again", "make:test", "stop", "screen", "write")),
        )
    }
}
