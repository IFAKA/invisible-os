package com.faka.invisible.tell

import com.faka.invisible.voicekit.*

import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/** A fake clock plus keyed timers that fire in time order. */
class FakeWorld : Timers {
    var now = 0L
    var beforeTick: (Long) -> Unit = {}
    private class T(val due: Long, val seq: Long, val task: () -> Unit)
    private val timers = HashMap<String, T>()
    private var seq = 0L
    override fun set(key: String, delayMs: Long, task: () -> Unit) { timers[key] = T(now + delayMs, seq++, task) }
    override fun clear(key: String) { timers.remove(key) }
    fun advanceTo(t: Long) {
        while (true) {
            val next = timers.entries.filter { it.value.due <= t }.minWithOrNull(compareBy({ it.value.due }, { it.value.seq })) ?: break
            timers.remove(next.key)
            beforeTick(next.value.due)
            now = maxOf(now, next.value.due)
            next.value.task()
        }
        beforeTick(t)
        now = t
    }
    fun advanceBy(ms: Long) = advanceTo(now + ms)
}

class FakeOut(val world: FakeWorld, var rung: Int = 1) : TellOut {
    val events = ArrayList<Pair<Long, String>>()
    var saved = -1
    var left = false
    var level = 4
    private fun e(s: String) { events.add(world.now to s) }
    override fun play(cue: Cue, side: Side) = e("play $cue $side")
    override fun status(myEnergy: Int, oppEnergy: Int) = e("status $myEnergy $oppEnergy")
    override fun lifeNotes(side: Side, life: Int) = e("life $side $life")
    override fun setHold(level: Int) = e("hold $level")
    override fun startBed() = e("startBed")
    override fun setTension(level: Int) = e("tension $level")
    override fun stopBed(tapeStop: Boolean) = e("stopBed $tapeStop")
    override fun speak(text: Phrase) = e("speak ${text.text}")
    override fun stopSpeech() = e("stopSpeech")
    override fun pauseMusic() = e("pauseMusic")
    override fun resumeMusic() = e("resumeMusic")
    override fun log(line: String) = e("log $line")
    override fun savedRung() = rung
    override fun saveRung(rung: Int) { saved = rung; this.rung = rung; e("saveRung $rung") }
    override fun level() = level
    override fun saveLevel(level: Int) { this.level = level; e("saveLevel $level") }
    override fun leave() { left = true; e("leave") }
    fun count(prefix: String) = events.count { it.second.startsWith(prefix) }
    fun times(prefix: String) = events.filter { it.second.startsWith(prefix) }.map { it.first }
    /** My move cues (centre) in order of play. */
    fun myMoves(): List<String> = events.map { it.second }.filter { it in setOf("play JAB ME", "play GUARD ME", "play CHARGE ME") }.map { it.substring(5, it.length - 3) }
}

private fun always(m: Move): (Int, TellSolver, Long) -> Opponent = { _, _, _ ->
    object : Opponent {
        override fun choose(round: Int, state: State, rng: Random) = m
        override fun observe(playerMove: Move, round: Int) {}
    }
}

class TellGameTest {
    private val solver = TellSolver()
    private val intro = TellGame.INTRO_MS

    private class Rig(val w: FakeWorld, val out: FakeOut, val g: TellGame) {
        val t0 get() = out.times("startBed").first()
        fun roundStart(k: Int) = t0 + (k - 1) * 2000L
        /** press at [rel] ms after the start of round [k] and hold it [hold] ms. */
        fun press(k: Int, rel: Long, hold: Long) {
            w.advanceTo(roundStart(k) + rel); g.down(w.now); w.advanceBy(hold); g.up(w.now)
        }
    }

    private fun rig(opp: (Int, TellSolver, Long) -> Opponent, latency: Long = 0, rung: Int = 1, seed: Long = 7): Rig {
        val w = FakeWorld(); val out = FakeOut(w, rung)
        val g = TellGame(solver, out, w, { w.now }, { latency }, seed, opp)
        g.start(); w.advanceTo(intro + 1)
        return Rig(w, out, g)
    }

    private fun move(hold: Long): String {
        val r = rig(always(Move.GUARD))
        r.press(1, 100, hold)
        r.w.advanceTo(r.roundStart(2) + 10)
        return r.out.myMoves().first()
    }

    @Test fun holdThresholds() {
        assertEquals("JAB", move(299))
        assertEquals("GUARD", move(300))
        assertEquals("GUARD", move(799))
        assertEquals("CHARGE", move(800))
    }

    @Test fun detentCuesAtThresholds() {
        val r = rig(always(Move.GUARD))
        r.press(1, 100, 900)
        val p = r.roundStart(1) + 100
        assertEquals(listOf(p + 300), r.out.times("play DETENT_1"))
        assertEquals(listOf(p + 800), r.out.times("play DETENT_2"))
        assertTrue(r.out.events.any { it.second == "hold 1" } && r.out.events.any { it.second == "hold 2" })
        assertEquals(1, r.out.count("play STAMP"))
        assertEquals(1, r.out.count("play TAP"))
    }

    @Test fun noPressIsGuard() {
        val r = rig(always(Move.GUARD))
        r.w.advanceTo(r.roundStart(3) + 10)
        assertEquals(listOf("GUARD", "GUARD"), r.out.myMoves())
    }

    @Test fun windowClosesAtOnePointFiveSecondsPlusLatency() {
        for ((lat, expect) in listOf(0L to "JAB", 100L to "GUARD")) {
            // press at 1220 and hold 310: at 1500 it is 280 ms (JAB); at 1530 it is 310 ms (GUARD)
            val r = rig(always(Move.GUARD), latency = lat)
            r.press(1, 1220, 310)
            r.w.advanceTo(r.roundStart(2) + 10)
            assertEquals("latency $lat", expect, r.out.myMoves().first())
            assertEquals(r.roundStart(1) + 1500 + lat, r.out.events.first { it.second == "play $expect ME" }.first)
        }
    }

    @Test fun pressHeldThroughTheCloseCommitsTheLevelReached() {
        val r = rig(always(Move.GUARD))
        r.press(1, 600, 1200)      // at the close (1500) it is 900 ms: CHARGE; the release at 1800 changes nothing
        r.w.advanceTo(r.roundStart(3) + 10)
        assertEquals(listOf("CHARGE", "GUARD"), r.out.myMoves())
    }

    @Test fun latePressCountsForTheNextRound() {
        val r = rig(always(Move.GUARD))
        r.press(1, 1400, 100)
        r.w.advanceTo(r.roundStart(3) + 10)
        assertEquals(listOf("GUARD", "JAB"), r.out.myMoves())
        val s = rig(always(Move.GUARD))
        s.press(1, 1400, 1100)     // measured from the real press: 1100 ms by the release in round 2 = CHARGE
        s.w.advanceTo(s.roundStart(3) + 10)
        assertEquals(listOf("GUARD", "CHARGE"), s.out.myMoves())
    }

    @Test fun onlyOneMovePerRound() {
        val r = rig(always(Move.GUARD))
        r.press(1, 100, 100); r.press(1, 500, 400)
        r.w.advanceTo(r.roundStart(2) + 10)
        assertEquals(listOf("JAB"), r.out.myMoves())
    }

    @Test fun unaffordableChargeBecomesGuard() {
        val r = rig(always(Move.GUARD))
        for (k in 1..3) r.press(k, 100, 900)   // energy 3: one charge, then 1 left
        r.w.advanceTo(r.roundStart(4) + 10)
        assertEquals("CHARGE", r.out.myMoves()[0])
        assertEquals("GUARD", r.out.myMoves()[1])
    }

    @Test fun forfeitAfterThreeSecondsDoesNotChangeTheRung() {
        val r = rig(always(Move.GUARD), rung = 2)
        r.w.advanceTo(r.roundStart(3) + 200)
        r.g.down(r.w.now); val p = r.w.now
        r.w.advanceTo(p + 2999); assertFalse(r.out.left)
        assertEquals(listOf(p + 2500), r.out.times("play FORFEIT"))
        r.w.advanceTo(p + 3000)
        assertTrue(r.out.left); assertTrue(r.g.isOver)
        assertEquals(1, r.out.count("resumeMusic"))
        assertTrue(r.out.events.any { it.second == "stopBed true" })
        assertEquals(-1, r.out.saved)
        assertTrue(r.out.events.any { it.second.startsWith("log TELL rung=2 result=FORFEIT") })
        val n = r.out.events.size
        r.w.advanceBy(20000)
        assertEquals(n, r.out.events.size)
    }

    @Test fun releasingBeforeThreeSecondsCancelsTheForfeit() {
        val r = rig(always(Move.GUARD))
        r.press(1, 100, 2900)
        r.w.advanceBy(1000)
        assertFalse(r.out.left)
        assertEquals(1, r.out.count("play FORFEIT"))   // the 2.5 s warning played, the leave did not
    }

    @Test fun winRaisesTheRungAndSpeaksTheResult() {
        val r = rig(always(Move.JAB), rung = 4)   // I guard (no press): every round is a parry; 4 rounds win
        r.w.advanceTo(r.roundStart(5) + 3000)
        assertEquals(5, r.out.saved)
        val speech = r.out.events.last { it.second.startsWith("speak") }
        assertTrue(speech.second, speech.second.startsWith("speak You win."))
        assertTrue(speech.second.endsWith("Tap to play again. Hold to leave."))
        assertEquals(r.roundStart(5), r.out.times("play WIN").single())
        assertEquals(r.roundStart(5) + 1000, speech.first)
        assertTrue(r.out.events.any { it.second.startsWith("log TELL rung=4 result=WIN rounds=4 jab=0 guard=4 charge=0") })
    }

    @Test fun rungStopsAtFive() {
        val r = rig(always(Move.JAB), rung = 5)
        r.w.advanceTo(r.roundStart(5) + 10)
        assertEquals(-1, r.out.saved)   // 5 stays 5: nothing to save
    }

    @Test fun lossKeepsTheRung() {
        // they charge whenever they can pay for it (rounds 1, 4); I guard: charge beats guard, 4 life = 2 + 2
        val r = rig(always(Move.CHARGE), rung = 3)
        r.w.advanceTo(r.roundStart(5) + 1100)
        assertEquals(1, r.out.count("play LOSS"))
        assertEquals(-1, r.out.saved)
        assertEquals(3, r.out.rung)
        val speech = r.out.events.last { it.second.startsWith("speak") }.second
        assertTrue(speech, speech.startsWith("speak You lose."))
    }

    @Test fun twentyRoundCapIsADrawAndTakesFortySeconds() {
        val r = rig(always(Move.GUARD), rung = 2)
        r.w.advanceTo(r.roundStart(21) + 10)
        assertEquals(20, r.g.roundsPlayed)
        assertEquals(r.t0 + 40_000, r.out.times("play DRAW").single())
        assertEquals(20, r.out.count("status"))
        assertEquals(-1, r.out.saved)
        assertEquals(1, r.out.count("stopBed false"))
    }

    @Test fun revealCuesFollowTheRules() {
        val r = rig(always(Move.JAB))          // my guard parries their jab
        r.w.advanceTo(r.roundStart(1) + 2200)
        val ev = r.out.events.map { it.second }
        assertTrue(ev.containsAll(listOf("play GUARD ME", "play JAB OPP", "play PARRY_CLANG ME", "play COIN ME", "play HIT OPP")))
        assertFalse(ev.contains("play HIT ME"))
        assertTrue(ev.contains("life OPP 3"))
        val c = rig(always(Move.CHARGE))       // their charge cracks my guard
        c.w.advanceTo(c.roundStart(1) + 2600)
        val ce = c.out.events.map { it.second }
        assertTrue(ce.contains("play CRACK_BOOM ME")); assertFalse(ce.contains("play HIT ME")); assertTrue(ce.contains("life ME 2"))
        val f = rig(always(Move.CHARGE))       // my jab breaks their charge
        f.press(1, 100, 100); f.w.advanceTo(f.roundStart(1) + 2100)
        val fe = f.out.events.map { it.second }
        assertTrue(fe.containsAll(listOf("play FIZZLE OPP", "play HIT OPP")))
        val n = rig(always(Move.GUARD))
        n.w.advanceTo(n.roundStart(1) + 1900)
        assertTrue(n.out.events.any { it.second == "play NOTHING ME" })
    }

    @Test fun tensionFollowsLife() {
        val r = rig(always(Move.JAB))           // each round they lose one life
        r.w.advanceTo(r.roundStart(4) + 100)
        val t = r.out.events.filter { it.second.startsWith("tension") }.map { it.second }
        // set to 0 at the match start, then at each round start: life 4, 3, 2, 1 for them
        assertEquals(listOf("tension 0", "tension 0", "tension 0", "tension 1", "tension 2"), t)
    }

    @Test fun afterAMatchTapPlaysAgainAndHoldLeaves() {
        val r = rig(always(Move.JAB))
        r.w.advanceTo(r.roundStart(5) + 200)
        r.g.down(r.w.now); r.w.advanceBy(100); r.g.up(r.w.now); r.w.advanceBy(TellGame.TAP_WINDOW_MS + 10)
        assertEquals(2, r.out.count("play KICK_ROLL"))
        assertTrue(r.out.events.any { it.second == "stopSpeech" })
        assertFalse(r.out.left)
        r.w.advanceBy(TellGame.INTRO_MS + 100)
        assertEquals(2, r.out.count("startBed"))
        // finish the second match and leave with a long press
        r.w.advanceTo(r.out.times("startBed").last() + 4 * 2000 + 100)
        r.g.down(r.w.now); r.w.advanceBy(799); assertFalse(r.out.left)
        r.w.advanceBy(1); assertTrue(r.out.left)
        r.g.up(r.w.now)
        assertEquals(1, r.out.count("resumeMusic"))
        assertEquals(1, r.out.count("pauseMusic"))
        assertEquals(2, r.out.count("speak Duel."))
    }

    @Test fun startSpeechNamesTheRung() {
        val r = rig(always(Move.GUARD), rung = 3)
        assertTrue(r.out.events.any { it.second == "speak Duel. Rung three." })
        assertTrue(r.out.events.any { it.second == "pauseMusic" })
    }

    @Test fun sameSeedGivesTheSameMatch() {
        fun run(seed: Long): List<Pair<Long, String>> {
            val r = rig(::opponentForRung, rung = 1, seed = seed)
            val rnd = Random(5)
            for (k in 1..20) { if (r.g.isOver) break; r.press(k, 100, listOf(100L, 500L, 900L)[rnd.nextInt(3)]) }
            r.w.advanceTo(r.roundStart(22))
            return r.out.events
        }
        assertEquals(run(11), run(11))
    }

    /** A scripted player with a REAL mixer: writes the whole session to sound/tell/_game.wav. */
    @Test fun scriptedSessionWritesAWav() {
        val sr = 44100
        val mixer = TellMixer(sr)
        val w = FakeWorld()
        val chunks = ArrayList<FloatArray>()
        var frames = 0L
        w.beforeTick = { t ->
            val want = t * sr / 1000
            while (frames < want) {
                val n = minOf(220L, want - frames).toInt()
                val b = FloatArray(n * 2); mixer.render(b, n); chunks.add(b); frames += n
            }
        }
        val marks = ArrayList<String>()
        var left = false
        val out = object : TellOut {
            override fun play(cue: Cue, side: Side) = mixer.play(cue, side)
            override fun status(myEnergy: Int, oppEnergy: Int) = mixer.status(myEnergy, oppEnergy)
            override fun lifeNotes(side: Side, life: Int) = mixer.lifeNotes(side, life)
            override fun setHold(level: Int) = mixer.setHold(level)
            override fun startBed() = mixer.startBed()
            override fun setTension(level: Int) = mixer.setTension(level)
            override fun stopBed(tapeStop: Boolean) = mixer.stopBed(tapeStop)
            override fun speak(text: Phrase) { marks.add("${w.now}: ${text.text}") }   // spoken in the silent gaps (no speech audio here)
            override fun stopSpeech() {}
            override fun pauseMusic() {}
            override fun resumeMusic() {}
            override fun log(line: String) { marks.add("${w.now}: $line") }
            override fun savedRung() = 3
            override fun saveRung(rung: Int) {}
            override fun leave() { left = true }
        }
        solver.solve()
        val g = TellGame(solver, out, w, { w.now }, { 0L }, 42L)
        g.start()
        w.advanceTo(TellGame.INTRO_MS + 1)
        val t0 = TellGame.INTRO_MS
        val rnd = Random(3)
        val holds = longArrayOf(120, 500, 1000)
        var k = 1
        while (marks.none { it.contains("TELL rung") } && w.now < t0 + 45_000) {
            w.advanceTo(t0 + (k - 1) * 2000L + 100)
            if (marks.any { it.contains("TELL rung") }) break
            g.down(w.now); w.advanceBy(holds[rnd.nextInt(3)]); g.up(w.now)
            k++
        }
        while (marks.none { it.contains("Tap to play again") }) w.advanceBy(100)
        val speechAt = w.now
        w.advanceBy(1200)          // 1.2 s of silence where the end speech is spoken
        g.down(w.now); w.advanceBy(900)
        assertTrue(left)
        w.advanceBy(900)           // tail
        val all = FloatArray(chunks.sumOf { it.size }); var o = 0
        for (c in chunks) { System.arraycopy(c, 0, all, o, c.size); o += c.size }
        assertTrue(all.none { it.isNaN() })
        val peak = TellSoundTools.peakDb(all)
        assertTrue("peak $peak", peak <= -1.0 + 0.01)
        assertTrue(peak > -20.0)
        val seconds = all.size / 2.0 / sr
        assertTrue("length $seconds", seconds >= speechAt / 1000.0 + 1.2)
        assertEquals(w.now / 1000.0, seconds, 0.01)
        println("session ${"%.1f".format(seconds)} s, peak ${"%.1f".format(peak)} dBFS; speech marks:\n" + marks.joinToString("\n"))
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "GAME-TELL.md").exists() }
        TellSoundTools.write(File(root, "sound/tell/_game.wav"), all)
    }
}
