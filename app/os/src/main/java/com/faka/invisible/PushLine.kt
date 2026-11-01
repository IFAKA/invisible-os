package com.faka.invisible

import android.os.Handler
import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.ClaudeVoice
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.LineAnswer
import com.faka.invisible.claude.PushItem
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice
import java.io.IOException

/** What the push line needs from the phone. The OS implements it in [Actions]; a Flow test implements it on the fake voice. */
interface LinePorts {
    fun now(): Long
    /** The state of the phone (menu, program, call, mute); [LineRules.Ctx.busy] is set by [PushLine]. */
    fun ctx(): LineRules.Ctx
    fun computer(): Computer?
    /** The Claude tone. */
    fun beep()
    /** Say [p] after what is said now, and show it (the kit draws what it speaks). */
    fun speak(p: Phrase)
    fun speaking(): Boolean
    fun stopSpeech()
    /** Pause the music; true = we paused it (so we resume it). */
    fun duck(): Boolean
    fun resume()
    /** Open the Laptop tool straight at this room or ask (the full walk). */
    fun openWalk(target: String)
}

/**
 * The push line (DESIGN-LAYER.md sections 2 to 4). A laptop item with a line is queued; when the rules allow ([LineRules.decide]) the tone,
 * the line and a hint are spoken ("Click: Go. Two: Push."), and for 8 s after the hint the button belongs to that item
 * (click = a, two = b, hold = the full walk, three = later). A wake with items waiting reads them one by one ([wake]); four clicks replay the last line.
 * Main thread only, except the send, which runs in its own thread and posts back.
 */
class PushLine(private val handler: Handler, private val ports: LinePorts) {
    private enum class St { IDLE, SPEAKING, WINDOW, SENDING }
    private enum class Res { SENT, LATE, DOWN }

    val queue = LineQueue()
    private var st = St.IDLE
    private var cur: PushItem? = null
    private var gen = 0
    private var ducked = false
    private var walk = false
    private var home: (() -> Unit)? = null
    private val walkSeen = HashSet<String>()
    private var windowAt = 0L

    /** The button belongs to the line now (speaking it, its window, a send, or the wake walk). */
    val busy get() = st != St.IDLE

    /** A laptop item arrived ([replay] = it came with the first answer after a (re)start: old news, queue only). */
    fun offer(item: PushItem, replay: Boolean = false) {
        val now = ports.now()
        queue.offer(item, now)
        val d = LineRules.decide(item, ports.ctx().copy(busy = busy), now, queue.lastAny(), queue.lastFor(item.key), replay)
        Trace.ev("line", "push room=${item.who} level=${d.level.name.lowercase()} why=${d.why}")
        when (d.level) {
            LineRules.Level.TONE -> ports.beep()
            LineRules.Level.QUEUED -> {}
            LineRules.Level.SPOKEN -> { queue.noteSpoken(item, now); ports.beep(); present(item) }
        }
    }

    /** Wake (a hold with the menu closed): true = items wait, the walk reads them and calls [goHome] when it ends; false = the normal wake. */
    fun wake(goHome: () -> Unit): Boolean {
        if (busy) return true
        queue.dropOlder(ports.now(), MAX_AGE_MS)
        val first = queue.top() ?: return false
        walk = true; home = goHome; walkSeen.clear(); walkSeen += first.key
        Trace.ev("line", "walk start items=${queue.size}")
        present(first)
        return true
    }

    /** The button, with the menu closed and no program: true = the line took it (it is not play/pause). */
    fun onGesture(g: Gesture): Boolean {
        if (g == Gesture.QUAD) return replay()
        when (st) {
            St.IDLE -> return false
            St.SENDING -> return walk
            St.SPEAKING, St.WINDOW -> {
                val item = cur ?: return false
                if (item.a == null) return false   // a notice has no window
                if (g == Gesture.CLICK_HOLD_END) return true
                if (walk && g == Gesture.TRIPLE) { Trace.ev("line", "answer=home room=${item.who} ms=${sinceWindow()}"); goHome(); return true }
                val m = LineRules.move(item, g)
                if (m == LineRules.Move.NONE) return true
                run(item, m)
                return true
            }
        }
    }

    private fun sinceWindow() = if (st == St.WINDOW) ports.now() - windowAt else 0L
    private fun traceAnswer(what: String, item: PushItem) = Trace.ev("line", "answer=$what room=${item.who} ms=${sinceWindow()}")

    private fun run(item: PushItem, m: LineRules.Move) {
        val a = item.a ?: return
        traceMove(item, m)
        gen++
        ports.stopSpeech()
        when (m) {
            LineRules.Move.A -> send(item, a)
            LineRules.Move.B -> send(item, item.b ?: return)
            LineRules.Move.NO -> {
                val deny = item.b?.takeIf { it.isAsk && a.isAsk }
                if (deny != null) send(item, deny)
                else { queue.remove(item.key); st = St.SENDING; ports.speak(ClaudeVoice.SKIPPED); quiet(gen) { advance() } }
            }
            LineRules.Move.LATER -> { st = St.SENDING; advance() }
            LineRules.Move.MORE -> {
                queue.remove(item.key)
                st = St.IDLE; cur = null; walk = false; ducked = false   // the Laptop tool pauses the music itself and the OS resumes it when it ends
                ports.openWalk(item.target)
            }
            LineRules.Move.NONE -> {}
        }
    }

    private fun traceMove(item: PushItem, m: LineRules.Move) = traceAnswer(m.name.lowercase(), item)

    /** Says the line and its hint, then opens the answer window. */
    private fun present(item: PushItem) {
        gen++
        st = St.SPEAKING; cur = item
        if (!ducked && !ports.ctx().menuOpen) ducked = ports.duck()
        ports.speak(Phrase.content(item.line, "en"))
        val a = item.a
        if (a != null) ports.speak(Voice.lineHint(a.label, item.b?.label, a.risky))
        quiet(gen) {
            if (a == null) { queue.remove(item.key); advance() }
            else {
                st = St.WINDOW; windowAt = ports.now()
                val g = gen
                handler.postDelayed({ if (g == gen && st == St.WINDOW) { traceAnswer("later", item); st = St.SENDING; advance() } }, LineRules.WINDOW_MS)
            }
        }
    }

    private fun replay(): Boolean {
        if (st != St.IDLE) { val item = cur ?: return false; if (item.a == null) return false; ports.stopSpeech(); Trace.ev("line", "replay room=${item.who}"); present(item); return true }
        val item = queue.lastPushed?.takeIf { queue.contains(it.key) } ?: return false
        Trace.ev("line", "replay room=${item.who}")
        present(item)
        return true
    }

    /** After an item is done (answered, skipped, later or timed out): the next one of a walk, else back to the button's normal meaning. */
    private fun advance() {
        if (walk) {
            val next = queue.top(walkSeen)
            if (next != null) { walkSeen += next.key; present(next); return }
            walk = false
            ports.speak(Voice.ALL_CLEAR)
            quiet(gen) { val h = home; home = null; finish(); h?.invoke() }
            return
        }
        finish()
    }

    private fun finish() {
        st = St.IDLE; cur = null; gen++
        if (ducked) quiet(gen) { if (st == St.IDLE && ducked) { ducked = false; ports.resume() } }
    }

    private fun goHome() {
        gen++; ports.stopSpeech(); walk = false; st = St.IDLE; cur = null
        if (ducked) { ducked = false; ports.resume() }
        val h = home; home = null; h?.invoke()
    }

    private fun send(item: PushItem, ans: LineAnswer) {
        st = St.SENDING
        val c = ports.computer()
        val g = gen
        Thread({
            val r = try {
                if (c == null) Res.DOWN else {
                    val cl = BridgeClient(c.url, c.token)
                    if (ans.isAsk) when (cl.answer(ans.askId!!, ans.choice!!)) { 200 -> Res.SENT; 409 -> Res.LATE; else -> Res.DOWN }
                    else if (cl.act(ans.roomId!!, ans.action!!)) Res.SENT else Res.DOWN
                }
            } catch (_: IOException) { Res.DOWN }
            handler.post {
                if (g != gen) return@post
                if (r != Res.DOWN) queue.remove(item.key)
                ports.speak(when (r) { Res.SENT -> ClaudeVoice.sentTo(item.who); Res.LATE -> ClaudeVoice.TOO_LATE; Res.DOWN -> ClaudeVoice.DOWN })
                Trace.ev("line", "send room=${item.who} result=${r.name.lowercase()}")
                quiet(gen) { advance() }
            }
        }, "push-line-send").apply { isDaemon = true }.start()
    }

    /** Runs [then] when the speech has ended (two quiet checks in a row; at most 6 s if it never started). A newer step (gen) cancels it. */
    private fun quiet(g: Int, then: () -> Unit) {
        var saw = false; var still = 0; var n = 0
        val poll = object : Runnable {
            override fun run() {
                if (g != gen) return
                n++
                if (ports.speaking()) { saw = true; still = 0 } else still++
                if ((saw && still >= 2) || (!saw && n >= 40) || n >= 600) then() else handler.postDelayed(this, 150)
            }
        }
        handler.postDelayed(poll, 150)
    }

    private companion object { const val MAX_AGE_MS = 30 * 60_000L }
}
