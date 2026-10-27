package com.faka.invisible.logickit

import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice

/** Pure: what the phone says when a chat message arrives, and how a burst from one chat becomes one reading. */
object ArrivalPlan {
    const val MAX_CHARS = 160
    const val BURST_MS = 1500L

    /**
     * The lines to speak, in order. A private message gives only "<App>, new message". Otherwise "<App>, <Sender>"
     * in the menu voice, then the text in its own language [lang], cut at [max] characters ("and more").
     */
    fun lines(app: String, sender: String, text: String, lang: String, hidden: Boolean, max: Int = MAX_CHARS): List<Phrase> {
        if (hidden) return listOf(Voice.newMessage(app))
        val body = MessageLogic.stripLinks(text).ifBlank { return listOf(Voice.arrivalFrom(app, sender)) }
        return listOf(Voice.arrivalFrom(app, sender), Voice.cut(body, max, lang == "es", lang))
    }

    /** False while a call rings or runs, or a program is running (then only the tone is heard). */
    fun mayRead(inCall: Boolean, programRunning: Boolean) = !inCall && !programRunning
}

/** Pure: keeps the latest message per chat for [window] ms, so a burst is read once (the last one). */
class ArrivalBurst<T>(private val window: Long = ArrivalPlan.BURST_MS) {
    private class Slot<T>(var item: T, var first: Long)
    private val slots = HashMap<String, Slot<T>>()

    /** Stores [item] for chat [key]; true if it is the first of a burst (the caller starts the timer). */
    @Synchronized fun add(key: String, item: T, now: Long): Boolean {
        val s = slots[key]
        if (s == null) { slots[key] = Slot(item, now); return true }
        s.item = item
        return false
    }

    /** Takes the latest item of the chat (ends the burst). */
    @Synchronized fun take(key: String): T? = slots.remove(key)?.item

    val windowMs get() = window
}
