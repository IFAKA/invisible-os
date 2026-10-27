package com.faka.invisible.claude

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The phone's side of the laptop bridge (bridge/server.py). Blocking: call it from a background thread, never the main thread. */
class BridgeClient(base: String, private val token: String) {
    private val base = base.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }

    class Reply(val seq: Int, val events: List<ClaudeEvent>)

    /** Long poll: items newer than [since]; returns after [waitSec] seconds at the latest. */
    @Throws(IOException::class)
    fun poll(since: Int, waitSec: Int): Reply {
        val (code, text) = call("GET", "/events?since=$since&wait=$waitSec", null, (waitSec + 10) * 1000)
        if (code != 200) throw IOException("HTTP $code")
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("items") ?: JSONArray()
            val events = (0 until arr.length()).mapNotNull { i ->
                val it = arr.getJSONObject(i)
                val opts = it.optJSONArray("options")?.let { a -> (0 until a.length()).map { j -> a.optString(j) } } ?: emptyList()
                ClaudeEvent.of(it.optString("id"), it.optString("kind"), it.optString("project"), it.optString("summary"), opts)
            }
            Reply(o.optInt("seq", since), events)
        } catch (e: org.json.JSONException) { throw IOException("bad reply") }
    }

    /** Sends the choice. 200 = taken, 409 = too late (answered or timed out). */
    @Throws(IOException::class)
    fun answer(id: String, choice: String): Int =
        call("POST", "/answer", JSONObject().put("id", id).put("choice", choice).toString(), 10_000).first

    private fun call(method: String, path: String, body: String?, timeoutMs: Int): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally { c.disconnect() }
    }
}
