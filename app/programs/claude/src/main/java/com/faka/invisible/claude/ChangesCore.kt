package com.faka.invisible.claude

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Show on phone (bridge/ROOMS-API.md "Show on phone"): the data of the "Changes" screen and the calls that fetch it. Pure parts first. */

/** One file of `git diff HEAD`. [untracked] = not in git yet (no patch to show). */
data class DiffFile(val path: String, val added: Int, val removed: Int, val patch: String, val untracked: Boolean = false) {
    /** "+3 -1" */
    val counts get() = "+$added -$removed"
}

/** One line of a patch for the screen: [kind] says the colour. */
data class PatchLine(val kind: Kind, val text: String) {
    enum class Kind { ADD, DEL, HUNK, CONTEXT }

    companion object {
        fun parse(patch: String): List<PatchLine> = patch.split('\n').map {
            when {
                it.startsWith("@@") -> PatchLine(Kind.HUNK, it)
                it.startsWith("+") -> PatchLine(Kind.ADD, it)
                it.startsWith("-") -> PatchLine(Kind.DEL, it)
                else -> PatchLine(Kind.CONTEXT, it)
            }
        }
    }
}

/** What Live can open for a window (GET /room/preview). */
data class PreviewInfo(val live: String, val host: String, val start: String, val git: Boolean) {
    val hasLive get() = live == "dev" || (live == "dw" && host.isNotBlank())
    /** Only for a Commerce Cloud folder: its host name (never anything else of dw.json). */
    val dwUrl get() = if (live == "dw" && host.isNotBlank()) "https://$host/" else null
}

/** One picture Claude handled (GET /room/pictures); [n] = 0 is the newest. */
data class Picture(val n: Int, val name: String, val kind: String)

object ChangesText {
    /** The "laptop did not answer" line for the screen (the Tailscale hint for 100.x addresses). */
    fun unreachable(url: String) = if (RoomState.isTailscale(url)) "Laptop not reachable. Is Tailscale on?" else "Laptop not reachable."
    const val NO_CHANGES = "No changes."
    const val NO_PICTURES = "No pictures in this window yet."
    fun title(roomName: String) = "$roomName: see changes"
}

private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

fun parseDiff(o: JSONObject): List<DiffFile> {
    val a = o.optJSONArray("files") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val f = a.optJSONObject(i) ?: return@mapNotNull null
        val p = f.optString("path")
        if (p.isBlank()) null else DiffFile(p, f.optInt("added"), f.optInt("removed"), f.optString("patch"), f.optBoolean("untracked"))
    }
}

fun parsePictures(o: JSONObject): List<Picture> {
    val a = o.optJSONArray("pictures") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Picture(it.optInt("n", i), it.optString("name").ifBlank { "image" }, it.optString("kind")) } }
}

fun parsePreviewInfo(o: JSONObject) = PreviewInfo(o.optString("live", "none"), o.optString("host"), o.optString("start"), o.optBoolean("git"))

/** GET /room/preview. */
@Throws(IOException::class) fun BridgeClient.previewInfo(id: String): PreviewInfo = parsePreviewInfo(json("/room/preview?id=${enc(id)}"))
/** GET /room/diff. */
@Throws(IOException::class) fun BridgeClient.changes(id: String): List<DiffFile> = parseDiff(json("/room/diff?id=${enc(id)}", timeoutMs = 20_000))
/** GET /room/pictures. */
@Throws(IOException::class) fun BridgeClient.pictures(id: String): List<Picture> = parsePictures(json("/room/pictures?id=${enc(id)}", timeoutMs = 20_000))
/** GET /room/picture: the bytes of picture [n]. */
@Throws(IOException::class) fun BridgeClient.picture(id: String, n: Int): ByteArray = bytes("/room/picture?id=${enc(id)}&n=$n")
/** POST /room/preview/start: starts the dev server and waits (up to 60 s) until its port answers. False = it did not start. */
@Throws(IOException::class) fun BridgeClient.startPreview(id: String): Boolean =
    try { json("/room/preview/start", JSONObject().put("id", id).toString(), "POST", 70_000).optBoolean("ok") } catch (e: IOException) { if (e.message == "HTTP 504") false else throw e }
/** The first address the WebView opens: it carries the token once; the bridge turns it into a cookie. The Live tab needs nothing else. */
fun BridgeClient.previewUrl(id: String): String = "$base/preview/${enc(id)}/?token=${enc(token)}"
