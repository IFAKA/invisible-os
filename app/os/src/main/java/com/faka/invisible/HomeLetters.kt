package com.faka.invisible

import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.*
import com.faka.invisible.write.LoopSeed
import com.faka.invisible.write.LoopStore

/** How the home letters ended. */
sealed class HomeResult {
    class Act(val action: HomeAction, val rank: Int) : HomeResult()
    /** Hold 3 s or nothing heard for a while: back to the home list. */
    object Leave : HomeResult()
    /** Click then hold: the OS closes. */
    object Exit : HomeResult()
}

/**
 * Home is a request too (DESIGN-TYPING step 9). A hold on home starts this: the letter loop ([LetterLoop], the same as Write) offers the best
 * ACTIONS first, in the guess slot (up to 3, each read ALONE at normal speech: "Open WhatsApp", "Play Billie Jean", "Reply to Ana"), then the short letters, then Undo. A click on an action runs it; a click on a
 * letter narrows the actions that fit; Undo takes the last letter back. The actions come from [ActionSource]s mixed by [ActionRanker]
 * (shares learned from picks, priors from use counts). Every app and program is reachable by its letters, so this replaces Find's letter ranges
 * (Reset learning still walks [NameFinder]). A program of the OS: it owns the raw button; hold 3 s leaves to home. Click then hold does NOT close the OS here (like Write): the earbud's long-press signal arrives about 600 ms after a plain click and was read as an exit.
 */
class HomeLetters(
    private val sources: List<ActionSource>,
    private val ranker: ActionRanker,
    private val audio: LoopAudio,
    private val timers: Timers,
    private val store: LoopStore,
    private val hintSeen: () -> Boolean,
    private val setHintSeen: () -> Unit,
    private val picked: (HomeAction) -> Unit = {},
    private val closeAudio: () -> Unit = {},
) : Program {
    private enum class Phase { SPEAKING, LOOP, DONE }
    private lateinit var host: ProgramHost
    private var phase = Phase.SPEAKING
    private var letters = ""
    private val history = ArrayList<String>()
    private var after: (() -> Unit)? = null
    private var downAt = -1L
    private var idleRounds = 0
    private var shown: List<HomeAction> = emptyList()
    private var shownRanked: List<ActionRanker.Ranked> = emptyList()
    private var startedAt = 0L
    override var active = false; private set
    /** A loop owns every press: a click then a hold is ordinary clicking (the earbud sends its long-press signal about 600 ms after a click), so only a hold of 3 s leaves. */
    override val exitOnClickHold = false
    /** Clicks that reached the loop (read by tests and the trace). */
    var clicksSeen = 0; private set
    /** Read by the OS when the program has ended. */
    var result: HomeResult = HomeResult.Exit; private set

    /** The letters typed so far. */
    fun letters() = letters

    private val learned = store.load()
    private val seeded = LoopSeed.choose(learned, store.profile())
    private val level = LoopLevel(store.level()) { store.saveLevel(it) }
    private val loop = LetterLoop<String>(audio, timers, pace = level.pace, seed = learned, params = seeded.second,
        log = { event, fields -> Trace.ev("home", "loop $event ${fields.joinToString(" ")}") },
        onChosen = { chosen(it) }, onIdle = { idle() })

    override fun start(host: ProgramHost) {
        this.host = host; active = true; startedAt = audio.nowMs()
        host.pauseMusic()
        Trace.ev("home", "request start seed=${seeded.first}")
        Trace.ev("loop", level.describe())
        if (hintSeen()) { Trace.ev("home", "hint skipped (heard before)"); round() }
        else { setHintSeen(); Trace.ev("home", "hint spoken"); speakThen(Voice.HOME_LETTERS_HINT) { round() } }
    }

    override fun stop() { finish(HomeResult.Exit) }

    private fun finish(r: HomeResult) {
        if (!active) return
        active = false; phase = Phase.DONE; result = r
        loop.stop(); timers.clear(AFTER); closeAudio(); host.stopSpeech(); host.resumeMusic()
        host.ended()
    }

    // ---- speech between rounds ----
    private fun speakThen(p: Phrase, then: () -> Unit) {
        phase = Phase.SPEAKING; loop.stop(); after = then
        host.speak(p)
        timers.set(AFTER, Pacing.speakMs(p) + 300) { goOn() }
    }
    private fun goOn() { timers.clear(AFTER); val a = after; after = null; if (active && a != null) a() }
    override fun speechDone() { if (active && phase == Phase.SPEAKING && after != null) goOn() }

    // ---- one round: the best actions, then the letters, then Undo ----
    private fun round() {
        if (!active) return
        phase = Phase.LOOP
        val ranked = ranker.rank(sources, letters)
        val top = ranked.take(ACTIONS)
        shown = top.map { it.action }; shownRanked = ranked
        val items = build(top, ranker.nextLetters(ranked, letters), letters.isNotEmpty())
        loop.start(items)
        host.screen(listOf("Type letters to ask for something", if (letters.isEmpty()) "Letters: -" else "Letters: $letters", "Hearing: " + items.take(5).joinToString(", ") { it.say.text } + "..."), 2)
        Trace.ev("home", "round letters=$letters actions=${top.joinToString("/") { it.action.key }} items=${items.size}")
    }

    private fun build(top: List<ActionRanker.Ranked>, next: Map<Char, Double>, canUndo: Boolean): List<LoopItem<String>> {
        val out = ArrayList<LoopItem<String>>()
        val actionP = top.map { it.chance * ACTION_MASS }
        top.forEachIndexed { i, r -> out.add(LoopItem("a:" + r.action.key, r.action.label, actionP[i].coerceAtLeast(1e-6), guess = true)) }
        val left = (1.0 - actionP.sum() - (if (canUndo) UNDO_P else 0.0)).coerceAtLeast(0.2)
        val order = ('a'..'z').sortedWith(compareByDescending<Char> { next[it] ?: 0.0 }.thenBy { it })
        for (c in order) out.add(LoopItem("l:$c", Phrase.content(c.toString()), (next[c] ?: 0.0) * 0.9 * left + 0.1 * left / 26 + 1e-6))
        if (canUndo) out.add(LoopItem(UNDO, Menu.UNDO, UNDO_P, undo = true, risky = true))
        return out
    }

    private fun chosen(c: LoopChoice<String>) {
        if (!active) return
        store.save(loop.offsets()); idleRounds = 0
        val key = c.key
        if (level.chose(key == UNDO)) { loop.pace = level.pace; Trace.ev("loop", level.describe()) }
        when {
            key.startsWith("a:") -> {
                val a = shown.firstOrNull { "a:" + it.key == key } ?: return
                val src = ranker.picked(a.key)
                picked(a)
                Trace.ev("home", "request letters=$letters pick=${a.key} rank=${c.rank} source=${src?.first ?: "none"}/${src?.second ?: -1} ms=${c.ms}")
                finish(HomeResult.Act(a, c.rank))
            }
            key.startsWith("l:") -> { history.add(letters); letters += key.substring(2); speakThen(spelled()) { round() } }
            key == UNDO -> { letters = history.removeLastOrNull() ?: ""; speakThen(spelled()) { round() } }
        }
    }

    private fun spelled(): Phrase = Phrase.content(if (letters.isEmpty()) "." else letters.uppercase().toCharArray().joinToString(", "))

    /** No click for some cycles: after 3 such rounds go back to home; else the loop starts again. */
    private fun idle() {
        if (!active) return
        if (++idleRounds >= 3) { Trace.ev("home", "request idle letters=$letters"); finish(HomeResult.Leave) } else round()
    }

    // ---- the raw button: a click is the time the button went down, counted when it comes up ----
    override fun down(time: Long) {
        if (!active) return
        downAt = time
        host.stopSpeech()
        if (phase == Phase.SPEAKING) goOn()
    }

    override fun up(time: Long) {
        if (!active || downAt < 0) return
        val at = downAt; downAt = -1
        val len = time - at
        if (len >= LEAVE_MS) { Trace.ev("home", "request leave letters=$letters"); finish(HomeResult.Leave); return }
        if (len >= Pacing.HOLD_MS) return   // a plain hold does nothing here
        if (phase == Phase.LOOP) { clicksSeen++; loop.click(at) }
    }

    private companion object {
        const val AFTER = "home:after"; const val UNDO = "undo"
        val ACTIONS = Pacing.LOOP_GUESS_MAX; const val ACTION_MASS = 0.7; const val UNDO_P = 0.03; const val LEAVE_MS = 3000L
    }
}

/** Timers on a Handler (keyed, one task per key). */
internal class HandlerTimers(private val handler: android.os.Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}
