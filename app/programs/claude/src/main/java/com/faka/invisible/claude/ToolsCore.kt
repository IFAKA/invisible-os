package com.faka.invisible.claude

import com.faka.invisible.logickit.squash
import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of the Tools tree of a window (bridge/ROOMS-API.md, GET /room/tools and POST /room/tool). The same shape is a list entry
 * (a group with [children], or a tool) and a follow-up item of a result (Create, Edit, a ticket, a branch...).
 * [tool] "" = Cancel (go back); [write] = the text comes from the Write keyboard; [confirm] = ask "No, go back" / "Yes" first.
 */
data class ToolNode(val label: String, val tool: String, val arg: String = "", val confirm: Boolean = false, val write: Boolean = false, val children: List<ToolNode> = emptyList()) {
    /** The argument sent to the bridge: the item's own [arg], then the written [text] on a new line (a ticket key, then the comment). */
    fun argWith(text: String): String = if (arg.isEmpty()) text else "$arg\n$text"
    companion object { const val HEAR = "hear" }
}

/** The answer of POST /room/tool. */
data class ToolReply(val ok: Boolean, val story: String, val items: List<ToolNode>)

/** What GET /info says about the laptop: the profile it suggests (work when a project has dw.json) and its short name. */
data class BridgeInfo(val suggest: String, val host: String)

object ToolsJson {
    private fun each(a: JSONArray?, f: (JSONObject) -> ToolNode?): List<ToolNode> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(f) }

    /** GET /room/tools: [{id,label,confirm,write?,children?}]. */
    fun list(a: JSONArray?): List<ToolNode> = each(a) { o ->
        val label = o.optString("label").squash()
        if (label.isEmpty() || o.optString("id").isBlank()) null
        else ToolNode(label, o.optString("id"), "", o.optBoolean("confirm"), o.optBoolean("write"), list(o.optJSONArray("children")))
    }

    /** POST /room/tool: {ok, story, items?:[{label,tool,arg,confirm?,write?}]}. */
    fun reply(o: JSONObject): ToolReply = ToolReply(o.optBoolean("ok"), o.optString("story").squash(), each(o.optJSONArray("items")) { i ->
        val label = i.optString("label").squash()
        if (label.isEmpty()) null else ToolNode(label, i.optString("tool"), i.optString("arg"), i.optBoolean("confirm"), i.optBoolean("write"))
    })
}
