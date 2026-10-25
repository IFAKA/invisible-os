package com.faka.invisible.tell

import com.faka.invisible.TellVoice
import com.faka.invisible.Timers
import java.util.Random

/** Everything the game asks the outside world to do. The Android side and the tests both implement it. */
interface TellOut {
    fun play(cue: Cue, side: Side = Side.ME)
    fun status(myEnergy: Int, oppEnergy: Int)
    fun lifeNotes(side: Side, life: Int)
    fun setHold(level: Int)
    fun startBed()
    fun setTension(level: Int)
    fun stopBed(tapeStop: Boolean)
    fun speak(text: String)
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
    fun log(line: String)
    fun savedRung(): Int
    fun saveRung(rung: Int)
    /** The level the player has reached (1..3 are coached, 4 = the full game, persisted). Default: full game. */
    fun level(): Int = 4
    fun saveLevel(level: Int) {}
    /** Speak at the slow coach pace (the practice and the narrated match). */
    fun coachSpeak(text: String) = speak(text)
    /** The game is over for good (leave or forfeit): the host shuts the audio down after the tape stop. */
    fun leave()
}

/**
 * The TELL round loop and input rules (GAME-TELL.md). Pure Kotlin: time comes from [clock] (ms), delays from [timers].
 * Feed raw button events with [down] / [up] (times in the same clock as [clock]). Call everything on one thread.
 *
 * Grid: the bed starts at time T0; round k starts at T0 + (k-1) * 2000. The snare is written at +1500, the window
 * closes at +1500 + latency (so it closes when the snare is HEARD), and the reveal starts then.
 */
class TellGame(
    private val solver: TellSolver,
    private val out: TellOut,
    private val timers: Timers,
    private val clock: () -> Long,
    private val latencyMs: () -> Long = { 0L },
    seed: Long = System.nanoTime(),
    private val opponentFor: (Int, TellSolver, Long) -> Opponent = ::opponentForRung,
    private val solved: () -> Boolean = { true },
    /** Coach even if already taught (the "How to play" menu item). */
    private val forceCoach: Boolean = false,
    /** Round length of a coached match: the extra rest after the reveal leaves room for the spoken result. */
    private val coachRoundMs: Long = COACH_ROUND_MS,
) {
    companion object {
        const val ROUND_MS = 2000L
        const val COACH_ROUND_MS = 5000L
        const val COACH_SHORT_ROUND_MS = 3000L
        const val DRILL_TRIES = 3
        const val WINDOW_MS = 1500L
        const val LATE_MS = 150L
        const val GUARD_MS = 300L
        const val CHARGE_MS = 800L
        const val LEAVE_MS = 800L
        const val FORFEIT_CUE_MS = 2500L
        const val FORFEIT_MS = 3000L
        const val MAX_LATENCY_MS = 400L
        /** From the kick roll to the first downbeat (the voice "Duel. Rung three." fits inside). */
        const val INTRO_MS = 2400L
        /** After the end cue (600 ms) the voice starts. */
        const val SPEECH_AFTER_MS = 1000L
        private const val K = "tell."
    }

    private enum class Phase { IDLE, PRACTICE, INTRO, ROUND, ENDING, AFTER, OVER }

    private val rng = Random(seed)
    private var phase = Phase.IDLE
    private var rung = 1
    private var opp: Opponent? = null
    private var narrate = false        // this match speaks each result
    private var roundMs = ROUND_MS
    private var tries = 0
    private var listening = false

    private var state = TellRules.start()
    private var roundNo = 0
    private var roundStart = 0L
    private var closeAt = 0L
    private var rounds = ArrayList<Round>()
    private val committed = HashMap<Int, Move>()
    private val holdLens = ArrayList<Long>()

    private var pressAt = -1L
    private var pressRound = 0
    private var pressLive = false      // the hold feedback is running for this press
    private var pressUsed = false      // this press already committed a move

    val isOver get() = phase == Phase.OVER
    val currentRung get() = rung
    val roundsPlayed get() = rounds.size

    // ---- time helpers ----
    private fun at(key: String, time: Long, task: () -> Unit) = timers.set(K + key, maxOf(0L, time - clock()), task)
    private fun clear(key: String) = timers.clear(K + key)
    private fun clearAll() {
        for (k in listOf("start", "snare", "close", "next", "result", "life", "end", "speech", "d1", "d2", "coach", "fb", "hint", "en0", "en1", "en2", "en3", "en4", "en5")) clear(k)
    }

    /** Level of a hold: 0 = JAB, 1 = GUARD, 2 = CHARGE. */
    fun levelOf(holdMs: Long) = if (holdMs >= CHARGE_MS) 2 else if (holdMs >= GUARD_MS) 1 else 0
    /** The move of a hold. Before level 2 a hold past the first click is still a guard. */
    private fun holdMove(holdMs: Long): Move { val l = levelOf(holdMs); return if (l == 2 && !chargeOk) Move.GUARD else moveOf(l) }
    private fun moveOf(level: Int) = when (level) { 0 -> Move.JAB; 1 -> Move.GUARD; else -> Move.CHARGE }

    // ---- start ----
    fun start() {
        if (phase != Phase.IDLE) return
        out.pauseMusic()
        rung = out.savedRung().coerceIn(1, 5)
        level = if (forceCoach) 1 else out.level().coerceIn(1, 4)
        if (forceCoach) out.saveLevel(1)
        matchesInLevel = 0
        if (level <= 3) startPractice() else newMatch()
    }

    // ---- coach practice: one idea per step, the player sets the pace, no round clock, no bed ----
    private enum class Kind { DRILL, TAP, SAY }
    private class Step(val kind: Kind, val lines: List<String>, val want: Int = -1)
    private fun stepsFor(level: Int) = when (level) {
        1 -> listOf(Step(Kind.DRILL, TellVoice.L1_TAP, 0), Step(Kind.DRILL, TellVoice.L1_GUARD, 1), Step(Kind.SAY, TellVoice.L1_RULE))
        2 -> listOf(Step(Kind.DRILL, TellVoice.L2_CHARGE, 2), Step(Kind.SAY, TellVoice.L2_RULE))
        else -> listOf(Step(Kind.TAP, TellVoice.L3_ENERGY))
    }
    private var steps: List<Step> = emptyList()
    private var level = 4              // 1 jab+guard, 2 +charge, 3 +energy, 4 the full game
    private var matchesInLevel = 0
    private var advanced = false       // the level just went up: teach it before the next match
    private var lastMine: Move? = null
    private var lastTheirs: Move? = null
    private var notAgain = false
    private val noEnergy get() = level <= 2
    private val chargeOk get() = level >= 2
    private var stepNo = 0
    private var downAt = -1L           // the time of the press that is still down (-1 = button up); the leave timers check it
    private var hints = 0
    private var fromPress = false      // the step was started by a press: skip its talk, judge the press
    private var pendingNext: (() -> Unit)? = null   // where a press jumps to while the coach is talking
    private var doneAction: (() -> Unit)? = null    // runs when the current sentence is over (done-callback or estimate)
    private var doneToken = 0

    private fun startPractice() {
        clearAll()
        phase = Phase.PRACTICE
        pressAt = -1L; pressLive = false; listening = false
        tries = 0; hints = 0; pendingNext = null; doneAction = null
        steps = stepsFor(level)
        runStep(0)
    }

    /** The speech layer says the last sentence is over (an estimate runs as the fallback). */
    fun onSpeechDone() { doneAction?.invoke() }

    /** Speak one sentence at the coach pace, wait until it is over, then [pauseMs] of quiet, then [next]. */
    private fun speakThen(text: String, pauseMs: Long, next: () -> Unit) {
        out.coachSpeak(text)
        val tok = ++doneToken
        doneAction = {
            if (tok == doneToken) {
                doneToken++; doneAction = null; clear("fb")
                at("coach", clock() + pauseMs) { next() }
            }
        }
        at("fb", clock() + TellVoice.speakMs(text)) { doneAction?.invoke() }
    }

    private fun cancelTalk() {
        doneToken++; doneAction = null
        clear("fb"); clear("coach"); clear("hint")
        for (e in 0..5) clear("en$e")
    }

    private fun runPending() {
        val n = pendingNext ?: return
        pendingNext = null
        cancelTalk()
        n()
    }

    /** Say a step's lines one by one, with the human pause after each; then [then]. A press jumps straight to [then]. */
    private fun playLines(lines: List<String>, action: Boolean, then: () -> Unit) {
        val finish = { pendingNext = null; then() }
        pendingNext = then
        fun item(k: Int) {
            if (phase != Phase.PRACTICE) return
            if (k >= lines.size) { finish(); return }
            val line = lines[k]
            val last = k == lines.size - 1
            when (line) {
                TellVoice.CLICK1 -> { out.play(Cue.DETENT_1); at("coach", clock() + TellVoice.quietAfterSound(400)) { item(k + 1) } }
                TellVoice.CLICK2 -> {
                    out.play(Cue.DETENT_1); at("en0", clock() + 450) { out.play(Cue.DETENT_2) }
                    at("coach", clock() + TellVoice.quietAfterSound(850)) { item(k + 1) }
                }
                TellVoice.NOTES -> {
                    for (e in 0..5) at("en$e", clock() + TellVoice.NOTE_SPACING_MS * e) { out.status(e, e) }
                    at("coach", clock() + TellVoice.NOTE_SPACING_MS * 5 + TellVoice.quietAfterSound(300)) { item(k + 1) }
                }
                else -> speakThen(line, TellVoice.pauseAfter(line) + if (last && action) TellVoice.READY_MS else 0L) { item(k + 1) }
            }
        }
        item(0)
    }

    private fun runStep(n: Int) {
        if (phase != Phase.PRACTICE) return
        if (n >= steps.size) { newMatch(); return }
        if (fromPress && steps[n].kind == Kind.SAY) { runStep(n + 1); return }
        stepNo = n; tries = 0; hints = 0; listening = false
        clear("hint")
        if (fromPress) { enterWait(); return }
        val st = steps[n]
        if (st.kind == Kind.SAY) { playLines(st.lines, false) { goNext() }; return }
        playLines(st.lines, st.kind == Kind.DRILL) { enterWait() }
    }

    private fun enterWait() {
        if (phase != Phase.PRACTICE) return
        listening = true
        armHint()
    }

    /** Silence is never allowed to last: a hint every HINT_MS, the leave line as the third, a demo after that. */
    private fun armHint() { at("hint", clock() + TellVoice.HINT_MS) { hint() } }

    private fun hint() {
        if (phase != Phase.PRACTICE || !listening || pressLive) return
        hints++
        val st = steps[stepNo]
        if (hints >= 4 && st.kind != Kind.TAP) { demo(); return }
        val text = if (hints == 3) TellVoice.LEAVE_HINT else TellVoice.hint(st.kind == Kind.TAP)
        speakThen(text, 0L) { if (listening) armHint() }
    }

    /** Show the right answer, then go on (drills and mini-rounds only). */
    private fun demo() {
        val st = steps[stepNo]
        listening = false
        clear("hint")
        val w = st.want
        if (w >= 1) out.play(Cue.DETENT_1)
        if (w >= 2) at("en0", clock() + 450) { out.play(Cue.DETENT_2) }
        at("en1", clock() + 450L * w + 400) { out.play(cueOfLevel(w)) }
        pendingNext = { runStep(stepNo + 1) }
        at("coach", clock() + 450L * w + TellVoice.quietAfterSound(600)) { runPending() }
    }

    private fun cueOfLevel(level: Int) = when (level) { 0 -> Cue.JAB; 1 -> Cue.GUARD; else -> Cue.CHARGE }

    private fun goNext() { pendingNext = null; runStep(stepNo + 1) }

    /** The player let go after [holdMs]. */
    private fun drillPress(holdMs: Long) {
        if (!listening) return
        val st = steps[stepNo]
        clear("hint")
        if (st.kind == Kind.TAP) { listening = false; runStep(stepNo + 1); return }
        val lv = if (levelOf(holdMs) == 2 && !chargeOk) 1 else levelOf(holdMs)   // before level 2 a long hold is a guard
        if (lv == st.want) { success(st); return }
        val feedback = TellVoice.wrong(lv, st.want)
        if (++tries >= DRILL_TRIES) {
            listening = false
            playLines(feedback + TellVoice.SKIP, false) { goNext() }
            pendingNext = { goNext() }
        } else {
            playLines(feedback, true) { enterWait() }
        }
    }

    private fun success(st: Step) {
        listening = false
        clear("hint")
        pendingNext = { goNext() }
        out.play(cueOfLevel(st.want))
        val word = TellVoice.DONE[st.want]
        speakThen(word, maxOf(TellVoice.pauseAfter(word), TellVoice.SOUND_QUIET_MS)) { goNext() }
    }

    private fun newMatch() {
        clearAll()
        state = when (level) { 1 -> State(5, 5, 3, 3); 2 -> State(5, 5, TellRules.LIFE, TellRules.LIFE); else -> TellRules.start() }
        rounds = ArrayList(); committed.clear(); holdLens.clear()
        pressAt = -1L; pressLive = false; pressUsed = false
        roundNo = 0
        narrate = level <= 3
        lastMine = null; lastTheirs = null; notAgain = false
        chargeReadySaid = false
        roundMs = when (level) { 1, 2 -> COACH_SHORT_ROUND_MS; 3 -> coachRoundMs; else -> ROUND_MS }
        opp = opponentFor(if (level <= 2) 1 else if (rung >= 3 && !solved()) 2 else rung, solver, rng.nextLong())
        phase = Phase.INTRO
        out.setTension(0)
        out.play(Cue.KICK_ROLL)
        if (level >= 4) out.speak(TellVoice.duelRung(rung))
        at("start", clock() + INTRO_MS) { out.startBed(); beginRound(1, clock()) }
    }

    private fun beginRound(k: Int, start: Long) {
        phase = Phase.ROUND
        roundNo = k; roundStart = start
        val lat = latencyMs().coerceIn(0L, MAX_LATENCY_MS)
        closeAt = start + WINDOW_MS + lat
        out.setTension(tension())
        if (!noEnergy) out.status(state.myE, state.oppE)
        at("snare", start + WINDOW_MS) { out.play(Cue.SNARE_CLOSE) }
        at("close", closeAt) { closeWindow() }
    }

    private fun tension(): Int {
        if (narrate) return 0   // a coached match is only a clock: kick and snare
        val low = minOf(state.myL, state.oppL)
        return if (low <= 1) 2 else if (low <= 2) 1 else 0
    }

    // ---- input ----
    fun down(timeMs: Long) {
        when (phase) {
            Phase.PRACTICE, Phase.INTRO, Phase.ROUND, Phase.ENDING -> {
                if (phase == Phase.PRACTICE) practiceDown()
                if (phase != Phase.PRACTICE && phase != Phase.INTRO && phase != Phase.ROUND && phase != Phase.ENDING) return
                out.play(Cue.TAP)
                pressAt = timeMs; pressUsed = false; pressLive = true
                pressRound = if (phase == Phase.ROUND && timeMs < closeAt - LATE_MS) roundNo else roundNo + 1
                if (phase == Phase.ROUND || (phase == Phase.PRACTICE && listening)) {   // clicks while holding
                    at("d1", timeMs + GUARD_MS) { out.play(Cue.DETENT_1); out.setHold(1) }
                    if (chargeOk) at("d2", timeMs + CHARGE_MS) { out.play(Cue.DETENT_2); out.setHold(2) }
                }
                downAt = timeMs
                at("f1", timeMs + FORFEIT_CUE_MS) { if (downAt == timeMs) out.play(Cue.FORFEIT) }
                at("f3", timeMs + FORFEIT_MS) { if (downAt == timeMs) forfeit() }
            }
            Phase.AFTER -> {
                out.play(Cue.TAP)
                pressAt = timeMs
                downAt = timeMs
                at("leave", timeMs + LEAVE_MS) { if (downAt == timeMs) { pressAt = -1L; leave(true) } }
            }
            else -> {}
        }
    }

    /** A press in practice: it stops the voice and is judged at once; a pending next step runs first. */
    private fun practiceDown() {
        if (pendingNext != null) { fromPress = true; runPending(); fromPress = false } else cancelTalk()
        out.stopSpeech()
        clear("hint"); clear("d1"); clear("d2")
    }

    fun up(timeMs: Long) {
        downAt = -1L; clear("f1"); clear("f3"); clear("leave")   // the button is up, whatever the steps did meanwhile
        val p = pressAt
        if (p < 0) return
        pressAt = -1L
        clear("d1"); clear("d2")
        when (phase) {
            Phase.AFTER -> { out.stopSpeech(); if (advanced) { advanced = false; startPractice() } else newMatch() }
            Phase.PRACTICE -> {
                if (!pressLive) return
                pressLive = false
                out.setHold(0)
                drillPress(timeMs - p)
            }
            Phase.ROUND, Phase.ENDING, Phase.INTRO -> {
                if (!pressLive) return
                pressLive = false
                out.setHold(0)
                if (phase == Phase.ROUND && !pressUsed) {
                    // a press for this round must end inside the window; later ones were cut by the close timer
                    val end = if (pressRound == roundNo) minOf(timeMs, closeAt) else timeMs
                    commit(pressRound, end - p)
                    out.play(Cue.STAMP)
                }
            }
            else -> {}
        }
    }

    private fun commit(forRound: Int, holdMs: Long) {
        if (committed.containsKey(forRound)) return
        committed[forRound] = holdMove(holdMs)
        holdLens.add(holdMs)
        if (forRound == pressRound) pressUsed = true
    }

    // ---- the round ----
    private fun closeWindow() {
        if (phase != Phase.ROUND) return
        // still holding a press that belongs to this round: the level reached now commits
        if (pressAt >= 0 && pressLive && !pressUsed && pressRound == roundNo) {
            commit(roundNo, closeAt - pressAt)
            clear("d1"); clear("d2")
            out.setHold(0)
            out.play(Cue.STAMP)
        }
        var mine = committed.remove(roundNo) ?: Move.GUARD
        if (level == 2 && mine == Move.CHARGE && lastMine == Move.CHARGE) { mine = Move.GUARD; notAgain = true }
        val o = opp!!
        var theirs = o.choose(roundNo, TellRules.swap(state), rng)
        // the robot plays by the rules of the level, too
        if (theirs == Move.CHARGE && (level == 1 || (level == 2 && lastTheirs == Move.CHARGE))) theirs = Move.GUARD
        val r = TellRules.resolve(state, mine, theirs, roundNo)
        o.observe(r.mine, roundNo)
        lastMine = r.mine; lastTheirs = r.theirs
        rounds.add(r)
        state = if (noEnergy) r.after.copy(myE = 5, oppE = 5) else r.after   // no energy before level 3
        reveal(r)
        val done = TellRules.finished(state, roundNo)
        val nextStart = roundStart + roundMs
        if (done != null) {
            phase = Phase.ENDING
            at("end", nextStart) { endMatch(done) }
        } else {
            at("next", nextStart) { beginRound(roundNo + 1, nextStart) }
        }
    }

    private fun reveal(r: Round) {
        out.play(cueOf(r.mine), Side.ME)
        out.play(cueOf(r.theirs), Side.OPP)
        val delay = if (r.mine == Move.CHARGE || r.theirs == Move.CHARGE) 450L else 200L
        at("result", clock() + delay) { results(r); if (narrate) narrateRound(r) }
    }

    private var chargeReadySaid = false
    private fun narrateRound(r: Round) {
        var text = when (level) {
            1 -> TellVoice.narrationL1(r)
            2 -> TellVoice.narrationL2(r)
            else -> {
                val ready = !chargeReadySaid && r.before.myE < 3 && r.after.myE >= 3
                if (ready) chargeReadySaid = true
                TellVoice.narration(r, lowEnergy = r.after.myE <= 1, chargeReady = ready)
            }
        }
        if (notAgain) { notAgain = false; text = (TellVoice.NOT_AGAIN + " " + text).trim() }
        if (text.isNotEmpty()) out.coachSpeak(text)
    }

    private fun cueOf(m: Move) = when (m) { Move.JAB -> Cue.JAB; Move.GUARD -> Cue.GUARD; Move.CHARGE -> Cue.CHARGE }

    private fun results(r: Round) {
        var hitMe = r.damageToMe > 0
        var hitOpp = r.damageToOpp > 0
        if (r.myParry) { out.play(Cue.PARRY_CLANG, Side.ME); out.play(Cue.COIN, Side.ME) }
        if (r.theirParry) { out.play(Cue.PARRY_CLANG, Side.OPP); out.play(Cue.COIN, Side.OPP) }
        if (r.myChargeFizzled) out.play(Cue.FIZZLE, Side.ME)
        if (r.theirChargeFizzled) out.play(Cue.FIZZLE, Side.OPP)
        if (r.mine == Move.GUARD && r.theirs == Move.CHARGE) { out.play(Cue.CRACK_BOOM, Side.ME); hitMe = false }
        if (r.theirs == Move.GUARD && r.mine == Move.CHARGE) { out.play(Cue.CRACK_BOOM, Side.OPP); hitOpp = false }
        if (hitMe) out.play(Cue.HIT, Side.ME)
        if (hitOpp) out.play(Cue.HIT, Side.OPP)
        if (!hitMe && !hitOpp && r.damageToMe == 0 && r.damageToOpp == 0 && r.mine == Move.GUARD && r.theirs == Move.GUARD) out.play(Cue.NOTHING, Side.ME)
        if (r.damageToMe > 0 || r.damageToOpp > 0) {
            at("life", clock() + 450L) {
                if (r.damageToMe > 0) out.lifeNotes(Side.ME, r.after.myL)
                if (r.damageToOpp > 0) out.lifeNotes(Side.OPP, r.after.oppL)
            }
        }
    }

    // ---- the end ----
    private fun endMatch(result: Result) {
        phase = Phase.AFTER
        pressAt = -1L; pressLive = false
        out.setHold(0)
        out.stopBed(false)
        out.play(when (result) { Result.WIN -> Cue.WIN; Result.LOSS -> Cue.LOSS; Result.DRAW -> Cue.DRAW })
        val before = rung
        val playedLevel = level
        if (level <= 3) {
            // a win, or the second match of a level, goes up; level 3 goes up after its first match. Losing never goes back.
            matchesInLevel++
            if (level == 3 || result == Result.WIN || matchesInLevel >= 2) {
                level++; matchesInLevel = 0; advanced = level <= 3
                out.saveLevel(level)
            }
        }
        narrate = false
        if (playedLevel >= 3) {
            if (result == Result.WIN) rung = minOf(5, rung + 1)
            if (rung != before) out.saveRung(rung)
        }
        log(before, result.name)
        val word = when (result) { Result.WIN -> "You win."; Result.LOSS -> "You lose."; Result.DRAW -> "Draw." }
        val text = listOf(word, if (playedLevel >= 3) costliestMistake(rounds) else "", "Tap to play again. Hold to leave.").filter { it.isNotEmpty() }.joinToString(" ")
        at("speech", clock() + SPEECH_AFTER_MS) { out.speak(text) }
    }

    private fun log(rungPlayed: Int, result: String) {
        val c = Move.values().associateWith { m -> rounds.count { it.mine == m } }
        val avg = if (holdLens.isEmpty()) 0 else holdLens.sum() / holdLens.size
        out.log("TELL rung=$rungPlayed result=$result rounds=${rounds.size} jab=${c[Move.JAB]} guard=${c[Move.GUARD]} " +
            "charge=${c[Move.CHARGE]} avgHoldMs=$avg")
    }

    private fun forfeit() {
        if (phase == Phase.IDLE || phase == Phase.OVER) return   // AFTER too: a hold that began in a round and outlived the match still leaves
        if (phase != Phase.PRACTICE && phase != Phase.AFTER) log(rung, "FORFEIT")   // counts as a loss; the rung does not change
        leave(true)
    }

    private fun leave(tape: Boolean) {
        if (phase == Phase.OVER) return
        downAt = -1L; clear("f1"); clear("f3"); clear("leave")
        val wasAfter = phase == Phase.AFTER || phase == Phase.PRACTICE   // no bed is running in either
        phase = Phase.OVER
        clearAll()
        out.setHold(0)
        if (!wasAfter) out.stopBed(tape)
        out.stopSpeech()
        out.resumeMusic()
        out.leave()
    }

    /** The host ends the game from outside (service stopped): same as leaving. */
    fun stop() {
        if (phase == Phase.IDLE) { phase = Phase.OVER; return }
        leave(false)
    }
}
