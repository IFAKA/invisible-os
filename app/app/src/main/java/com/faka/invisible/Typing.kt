package com.faka.invisible

/** What the Write program needs to know about the words: all words, most likely first, after the given words. */
interface Ranker { fun ranked(context: List<String>): List<Pair<String, Double>> }

/** What the Write program asks of the app. The words of a pair are heard together: left ear and right ear. */
interface TypeOut {
    fun pair(left: String, right: String?)
    fun prefetch(words: List<String>)
    fun say(text: String)
    fun cue(c: TypeCue)
    fun finished(text: String)   // the written text; "" = nothing written
}

enum class TypeCue { PICK_LEFT, PICK_RIGHT, NEXT, LETTERS, WORDS, DELETE, DONE, ARMED }

/**
 * Write: a one-button keyboard (PLAN-OS.md). The phone plays two likely items at once, one in each ear.
 * click = left, hold (0.5 s up to 3 s) = right, 2 clicks = next pair, 3 clicks = words/letters, 4 clicks = done, 5 clicks = delete.
 * Hold 3 s always leaves. It gets the raw button ([down]/[up]) and owns no menu. Time and timers are injected.
 */
class TypeProgram(
    private val lm: Ranker,
    private val out: TypeOut,
    private val timers: Timers,
    private val onEnded: () -> Unit,
) {
    companion object {
        const val HOLD_MS = 500L
        const val LEAVE_MS = 3000L
        const val GAP_MS = 350L
        const val REPLAY_MS = 7000L
        const val MAX_REPLAYS = 3
        const val WORD_PAGES = 4
        val LETTER_RE = Regex("[a-z']+")
    }

    private val written = ArrayList<String>()
    private var prefix = ""
    private var letters = false
    private var page = 0
    private var items: List<String> = emptyList()
    private var replays = 0
    private var clicks = 0
    private var downAt = -1L
    private var armed = false
    var active = false; private set

    fun start() {
        active = true
        out.say(TypeVoice.START); out.say(TypeVoice.START2)
        show()
    }

    fun stop() { if (active) { active = false; timers.clear("t:gap"); timers.clear("t:hold"); timers.clear("t:leave"); timers.clear("t:replay") } }

    fun text(): String = written.joinToString(" ").replaceFirstChar { it.uppercase() }

    // ---- what is offered ----
    private fun candidates(): List<String> {
        val all = lm.ranked(written.takeLast(8))
        return if (letters) {
            val mass = LinkedHashMap<Char, Double>()
            for ((w, lp) in all) if (w.length > prefix.length && w.startsWith(prefix) && LETTER_RE.matches(w)) mass.merge(w[prefix.length], Math.exp(lp), Double::plus)
            mass.entries.sortedByDescending { it.value }.map { it.key.toString() }
        } else all.asSequence().filter { it.first.startsWith(prefix) && LETTER_RE.matches(it.first) }.take(WORD_PAGES * 2).map { it.first }.toList()
    }

    private fun show(again: Boolean = false) {
        if (!again) replays = 0
        items = candidates()
        if (items.isEmpty()) { if (letters) { letters = false; page = 0; items = candidates() } else { prefix = ""; items = candidates() } }
        val n = (items.size + 1) / 2
        if (n > 0) page %= n else page = 0
        val l = items.getOrNull(page * 2) ?: return
        val r = items.getOrNull(page * 2 + 1)
        out.prefetch(items.drop(page * 2 + 2).take(4) )
        out.pair(l, r)
        timers.set("t:replay", REPLAY_MS) { if (active && replays < MAX_REPLAYS) { replays++; show(true) } }
    }

    // ---- the raw button ----
    fun down(time: Long) {
        if (!active) return
        timers.clear("t:gap"); timers.clear("t:replay")
        downAt = time; armed = false
        timers.set("t:hold", HOLD_MS) { if (active && downAt >= 0) { armed = true; out.cue(TypeCue.ARMED) } }
        timers.set("t:leave", LEAVE_MS) { if (active && downAt >= 0) leave(false) }
    }

    fun up(time: Long) {
        if (!active || downAt < 0) return
        timers.clear("t:hold"); timers.clear("t:leave")
        val long = armed || time - downAt >= HOLD_MS
        downAt = -1; armed = false
        if (long) { clicks = 0; choose(false); return }
        clicks++
        timers.set("t:gap", GAP_MS) { val n = clicks; clicks = 0; if (active) many(n) }
    }

    private fun many(n: Int) = when (n) {
        1 -> choose(true)
        2 -> { out.cue(TypeCue.NEXT); page++; show() }
        3 -> { letters = !letters; page = 0; out.cue(if (letters) TypeCue.LETTERS else TypeCue.WORDS); show() }
        4 -> leave(true)
        else -> { out.cue(TypeCue.DELETE); if (prefix.isNotEmpty()) prefix = "" else if (written.isNotEmpty()) written.removeAt(written.size - 1); letters = false; page = 0; show() }
    }

    private fun choose(left: Boolean) {
        val item = items.getOrNull(page * 2 + if (left) 0 else 1)
        if (item == null) { out.cue(TypeCue.NEXT); show(true); return }
        out.cue(if (left) TypeCue.PICK_LEFT else TypeCue.PICK_RIGHT)
        if (letters) { prefix += item; letters = false } else { written.add(item); prefix = "" }
        page = 0
        show()
    }

    private fun leave(done: Boolean) {
        val t = if (done) text() else ""
        stop()
        if (done) out.cue(TypeCue.DONE)
        out.finished(t)
        onEnded()
    }
}
