package com.faka.invisible.claude

import com.faka.invisible.logickit.firstWords
import com.faka.invisible.logickit.squash
import org.json.JSONObject

/**
 * One answer the laptop offers for a pushed line (bridge/ROOMS-API.md "ANSWER"): a [label] to speak, whether a single click may not send it
 * ([risky]), and what to send: a room action (POST /room/act {id, action}) or an answer to a permission ask (POST /answer {id, choice}).
 */
data class LineAnswer(val label: String, val risky: Boolean, val roomId: String? = null, val action: String? = null, val askId: String? = null, val choice: String? = null) {
    val isAsk get() = askId != null

    companion object {
        /** Null unless it has a label and exactly one of `act` / `answer` (a bad answer is ignored, never guessed). */
        fun parse(o: JSONObject?): LineAnswer? {
            if (o == null) return null
            val label = o.optString("label").squash().ifEmpty { return null }
            val act = o.optJSONObject("act"); val ans = o.optJSONObject("answer")
            if ((act == null) == (ans == null)) return null
            val risky = o.optBoolean("risky")
            return if (act != null) {
                val id = act.optString("id"); val action = act.optString("action")
                if (id.isBlank() || action.isBlank()) null else LineAnswer(label, risky, roomId = id, action = action)
            } else {
                val id = ans!!.optString("id"); val choice = ans.optString("choice")
                if (id.isBlank() || choice.isBlank()) null else LineAnswer(label, risky, askId = id, choice = choice)
            }
        }
    }
}

/**
 * A thing that needs the owner, pushed by the laptop with one spoken [line] and the likely answers [a] and [b] (DESIGN-LAYER.md). [key] is one per room,
 * ask or line id (a newer item with the same key replaces the older one); [target] is the room id or ask id the full walk opens at;
 * [laptopActive] null = the laptop did not say. [a] null = a notice (spoken, nothing to answer).
 */
data class PushItem(val key: String, val kind: String, val target: String, val who: String, val line: String, val a: LineAnswer?, val b: LineAnswer?, val laptopActive: Boolean?) {
    companion object {
        const val ROOM = "room"
        const val ASK = "ask"
        const val LINE = "line"
        const val MAX_WORDS = 12

        /** The push of a /events item, or null when it has no `line` (the old behaviour then). [kind] is [ROOM], [ASK] or [LINE]. */
        fun parse(o: JSONObject, kind: String): PushItem? {
            val line = o.optString("line").squash().firstWords(MAX_WORDS)
            val id = o.optString("id")
            if (line.isEmpty() || id.isBlank()) return null
            val who = (if (kind == ROOM) o.optString("name") else o.optString("project")).squash().ifEmpty { "Laptop" }
            val a = LineAnswer.parse(o.optJSONObject("a"))
            val b = if (a == null) null else LineAnswer.parse(o.optJSONObject("b"))
            val active = if (o.has("laptop_active") && !o.isNull("laptop_active")) o.optBoolean("laptop_active") else null
            return PushItem("$kind:$id", kind, id, who, line, a, b, active)
        }
    }
}
