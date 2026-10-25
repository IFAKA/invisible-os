package com.faka.invisible.tell

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
) {
    companion object {
        const val ROUND_MS = 2000L
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
        private val NUM = listOf("zero", "one", "two", "three", "four", "five")
    }

    private enum class Phase { IDLE, INTRO, ROUND, ENDING, AFTER, OVER }

    private val rng = Random(seed)
    private var phase = Phase.IDLE
    private var rung = 1
    private var opp: Opponent? = null

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
        for (k in listOf("start", "snare", "close", "next", "result", "life", "end", "speech", "d1", "d2", "f1", "f3", "leave")) clear(k)
    }

    /** Level of a hold: 0 = JAB, 1 = GUARD, 2 = CHARGE. */
    fun levelOf(holdMs: Long) = if (holdMs >= CHARGE_MS) 2 else if (holdMs >= GUARD_MS) 1 else 0
    private fun moveOf(level: Int) = when (level) { 0 -> Move.JAB; 1 -> Move.GUARD; else -> Move.CHARGE }

    // ---- start ----
    fun start() {
        if (phase != Phase.IDLE) return
        out.pauseMusic()
        rung = out.savedRung().coerceIn(1, 5)
        newMatch()
    }

    private fun newMatch() {
        clearAll()
        state = TellRules.start(); rounds = ArrayList(); committed.clear(); holdLens.clear()
        pressAt = -1L; pressLive = false; pressUsed = false
        roundNo = 0
        opp = opponentFor(if (rung >= 3 && !solved()) 2 else rung, solver, rng.nextLong())
        phase = Phase.INTRO
        out.setTension(0)
        out.play(Cue.KICK_ROLL)
        out.speak("Duel. Rung ${NUM[rung]}.")
        at("start", clock() + INTRO_MS) { out.startBed(); beginRound(1, clock()) }
    }

    private fun beginRound(k: Int, start: Long) {
        phase = Phase.ROUND
        roundNo = k; roundStart = start
        val lat = latencyMs().coerceIn(0L, MAX_LATENCY_MS)
        closeAt = start + WINDOW_MS + lat
        out.setTension(tension())
        out.status(state.myE, state.oppE)
        at("snare", start + WINDOW_MS) { out.play(Cue.SNARE_CLOSE) }
        at("close", closeAt) { closeWindow() }
    }

    private fun tension(): Int {
        val low = minOf(state.myL, state.oppL)
        return if (low <= 1) 2 else if (low <= 2) 1 else 0
    }

    // ---- input ----
    fun down(timeMs: Long) {
        when (phase) {
            Phase.INTRO, Phase.ROUND, Phase.ENDING -> {
                out.play(Cue.TAP)
                pressAt = timeMs; pressUsed = false; pressLive = true
                pressRound = if (phase == Phase.ROUND && timeMs < closeAt - LATE_MS) roundNo else roundNo + 1
                if (phase == Phase.ROUND) {
                    at("d1", timeMs + GUARD_MS) { out.play(Cue.DETENT_1); out.setHold(1) }
                    at("d2", timeMs + CHARGE_MS) { out.play(Cue.DETENT_2); out.setHold(2) }
                }
                at("f1", timeMs + FORFEIT_CUE_MS) { out.play(Cue.FORFEIT) }
                at("f3", timeMs + FORFEIT_MS) { forfeit() }
            }
            Phase.AFTER -> {
                out.play(Cue.TAP)
                pressAt = timeMs
                at("leave", timeMs + LEAVE_MS) { pressAt = -1L; leave(true) }
            }
            else -> {}
        }
    }

    fun up(timeMs: Long) {
        val p = pressAt
        if (p < 0) return
        pressAt = -1L
        clear("d1"); clear("d2"); clear("f1"); clear("f3"); clear("leave")
        when (phase) {
            Phase.AFTER -> { out.stopSpeech(); newMatch() }
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
        committed[forRound] = moveOf(levelOf(holdMs))
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
        val mine = committed.remove(roundNo) ?: Move.GUARD
        val o = opp!!
        val theirs = o.choose(roundNo, TellRules.swap(state), rng)
        val r = TellRules.resolve(state, mine, theirs, roundNo)
        o.observe(r.mine, roundNo)
        rounds.add(r)
        state = r.after
        reveal(r)
        val done = TellRules.finished(state, roundNo)
        val nextStart = roundStart + ROUND_MS
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
        at("result", clock() + delay) { results(r) }
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
        if (result == Result.WIN) rung = minOf(5, rung + 1)
        if (rung != before) out.saveRung(rung)
        log(before, result.name)
        val word = when (result) { Result.WIN -> "You win."; Result.LOSS -> "You lose."; Result.DRAW -> "Draw." }
        val text = listOf(word, costliestMistake(rounds), "Tap to play again. Hold to leave.").filter { it.isNotEmpty() }.joinToString(" ")
        at("speech", clock() + SPEECH_AFTER_MS) { out.speak(text) }
    }

    private fun log(rungPlayed: Int, result: String) {
        val c = Move.values().associateWith { m -> rounds.count { it.mine == m } }
        val avg = if (holdLens.isEmpty()) 0 else holdLens.sum() / holdLens.size
        out.log("TELL rung=$rungPlayed result=$result rounds=${rounds.size} jab=${c[Move.JAB]} guard=${c[Move.GUARD]} " +
            "charge=${c[Move.CHARGE]} avgHoldMs=$avg")
    }

    private fun forfeit() {
        if (phase == Phase.IDLE || phase == Phase.OVER || phase == Phase.AFTER) return
        log(rung, "FORFEIT")   // counts as a loss; the rung does not change
        leave(true)
    }

    private fun leave(tape: Boolean) {
        if (phase == Phase.OVER) return
        val wasAfter = phase == Phase.AFTER
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
