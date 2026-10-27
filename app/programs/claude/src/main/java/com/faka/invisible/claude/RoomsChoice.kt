package com.faka.invisible.claude

import com.faka.invisible.logickit.firstWords
import com.faka.invisible.logickit.squash

/** One option of a menu Claude Code shows (bridge/ROOMS-API.md, room.choice.options). [n] is the number printed on the laptop screen. */
data class ChoiceOpt(val n: Int, val label: String, val desc: String)

/** A multiple-choice question or plan approval on the screen of a window (room.choice). [other] = it has a "type your own" entry. */
data class RoomChoice(val question: String, val multi: Boolean, val options: List<ChoiceOpt>, val other: Boolean)

object ChoiceText {
    const val MAX_WORDS = 12

    /** What is spoken for one option: "Label: description", at most 12 words. */
    fun spoken(o: ChoiceOpt): String {
        val label = o.label.squash().trimEnd('.', ':')
        val d = o.desc.squash()
        return (if (d.isEmpty()) label else "$label: $d").firstWords(MAX_WORDS)
    }
}

/** Claude's last message in parts, for the "More" option. */
object Paragraphs {
    private const val MAX_WORDS = 70

    /** The paragraphs of [text] (blank-line or line breaks), a long one cut at sentence ends into parts of at most about 70 words. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split(Regex("\\n\\s*\\n|\\n"))) {
            val p = raw.squash()
            if (p.isEmpty()) continue
            if (p.split(' ').size <= MAX_WORDS) { out.add(p); continue }
            var cur = StringBuilder()
            for (s in p.split(Regex("(?<=[.!?])\\s+"))) {
                if (cur.isNotEmpty() && (cur.toString() + " " + s).split(' ').size > MAX_WORDS) { out.add(cur.toString()); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }
}
