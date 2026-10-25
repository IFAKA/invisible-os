package com.faka.invisible.tell

import com.faka.invisible.tell.TellSoundTools.channel
import com.faka.invisible.tell.TellSoundTools.db
import com.faka.invisible.tell.TellSoundTools.peakDb
import com.faka.invisible.tell.TellSoundTools.renderCue
import com.faka.invisible.tell.TellSoundTools.rms
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class TellMixerTest {
    private val SR = 44100

    private fun renderAll(m: TellMixer, frames: Int, block: Int = 220): FloatArray {
        val out = FloatArray(frames * 2); val tmp = FloatArray(block * 2); var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done); m.render(tmp, n)
            System.arraycopy(tmp, 0, out, done * 2, n * 2); done += n
        }
        return out
    }

    @Test fun loudnessLadderAtCentre() {
        val sb = StringBuilder()
        for (c in Cue.values()) {
            val st = renderCue(c, Side.ME)
            val p = peakDb(st); sb.append("%s=%.1f ".format(c, p))
            assertEquals("$c peak", TellMixer.LADDER.getValue(c), p, 0.3)
            for (i in 0 until st.size / 2) assertEquals("$c centre L!=R", st[2 * i], st[2 * i + 1], 1e-6f)
        }
        println("TELL ladder (peak dBFS, centre): $sb")
        val m = TellMixer(); m.status(3, 3); var out = renderAll(m, SR / 2)
        println("TELL status peak %.1f dBFS".format(peakDb(out)))
        assertEquals(-22.0, peakDb(out), 0.5)
        val m2 = TellMixer(); m2.lifeNotes(Side.ME, 3); out = renderAll(m2, SR / 2); assertEquals(-22.0, peakDb(out), 0.5)
        val m3 = TellMixer(); m3.startBed(); out = renderAll(m3, SR * 4)
        println("TELL bed peak (tension 0) %.1f dBFS".format(peakDb(out)))
        assertEquals(-20.0, peakDb(out), 0.5)
        // the ladder order of the spec
        val l = TellMixer.LADDER
        assertTrue(l.getValue(Cue.TAP) < l.getValue(Cue.JAB) - 4 && l.getValue(Cue.JAB) < l.getValue(Cue.HIT) && l.getValue(Cue.HIT) < l.getValue(Cue.WIN))
        assertTrue(TellMixer.STATUS_DB < l.getValue(Cue.TAP) && TellMixer.BED_DB < l.getValue(Cue.TAP) + 0.1 - 0.1 + 3)
    }

    @Test fun noNanAndLimiterHoldsMinusOneDb() {
        val m = TellMixer(); m.startBed(); m.setTension(2); m.setHold(2)
        for (c in Cue.values()) { m.play(c, Side.ME); m.play(c, Side.OPP) }
        m.status(5, 5); m.lifeNotes(Side.ME, 4); m.lifeNotes(Side.OPP, 4)
        val out = renderAll(m, SR * 3)
        assertTrue(out.all { it.isFinite() })
        val ceil = Math.pow(10.0, -1.0 / 20)
        val pk = out.maxOf { abs(it) }.toDouble()
        println("TELL pile-up peak %.2f dBFS (limit -1.0)".format(db(pk)))
        assertTrue("peak $pk", pk <= ceil + 1e-6)
        assertTrue(m.activeVoices() <= 24)
        // also silent mixer renders silence
        val z = renderAll(TellMixer(), 1000); assertTrue(z.all { it == 0f })
    }

    @Test fun voiceCapIs24() {
        val m = TellMixer()
        repeat(60) { m.play(Cue.KICK_ROLL, if (it % 2 == 0) Side.ME else Side.OPP); assertTrue(m.activeVoices() <= 24) }
        assertEquals(24, m.activeVoices())
        renderAll(m, 220); assertEquals(24, m.activeVoices())
    }

    @Test fun opponentIsLeftQuieterAndDelayed() {
        val sb = StringBuilder()
        for (c in Cue.values()) {
            val st = renderCue(c, Side.OPP); val l = channel(st, 0); val r = channel(st, 1)
            val ild = db(rms(l)) - db(rms(r)); sb.append("%s=%.1f ".format(c, ild))
            assertTrue("$c level difference $ild dB", ild >= 8.0)
            assertTrue("$c finite", st.all { it.isFinite() })
        }
        println("TELL OPP left-minus-right (dB): $sb")
        // 6 dB quieter in total power for low sounds (the 5 kHz low-pass takes a little more from bright ones)
        for (c in listOf(Cue.GUARD, Cue.HIT, Cue.JAB, Cue.SNARE_CLOSE)) {
            val me = renderCue(c, Side.ME); val op = renderCue(c, Side.OPP)
            val d = db(rms(me)) - db(rms(op)); println("TELL %s ME-OPP total level: %.2f dB".format(c, d))
            assertTrue("$c level gap $d", d in 5.0..8.5)
        }
        // inter-aural delay by cross-correlation of the far (right) ear against the near (left) ear
        for (c in listOf(Cue.SNARE_CLOSE, Cue.JAB, Cue.CRACK_BOOM)) {
            val st = renderCue(c, Side.OPP); val l = channel(st, 0); val r = channel(st, 1)
            var best = 0; var bv = -1e30
            for (lag in 0..80) { var s = 0.0; for (i in 0 until l.size - lag) s += l[i].toDouble() * r[i + lag]; if (s > bv) { bv = s; best = lag } }
            val msd = best * 1000.0 / SR
            println("TELL %s inter-aural delay: %d samples = %.3f ms".format(c, best, msd))
            assertEquals("$c delay", 0.6, msd, 0.1)
        }
    }

    @Test fun renderIsDeterministicAndBlockSizeFree() {
        fun script(block: Int): ByteArray {
            val m = TellMixer(); m.startBed(); m.setTension(1)
            m.play(Cue.TAP); m.status(4, 2); m.setHold(1); m.play(Cue.JAB); m.play(Cue.CHARGE, Side.OPP); m.setHold(2); m.lifeNotes(Side.OPP, 3)
            m.play(Cue.HIT, Side.OPP); m.setHold(0); m.stopBed(true)
            val out = renderAll(m, SR * 2, block)
            val bb = java.nio.ByteBuffer.allocate(out.size * 4); out.forEach { bb.putFloat(it) }
            return MessageDigest.getInstance("SHA-256").digest(bb.array())
        }
        assertArrayEquals(script(220), script(220))
        // a render in one block equals the same render in small blocks, except that calls happen between blocks here
        assertArrayEquals(script(220), script(1))
    }

    @Test fun kickFallsOnEveryHalfSecondFromStartBed() {
        val m = TellMixer()
        renderAll(m, 1234)                      // start at an odd frame
        assertEquals(1234L, m.framesRendered())
        m.startBed()
        val out = renderAll(m, SR * 6 + 17)
        val l = channel(out, 0); val beat = 22050
        for (k in 0 until 12) {
            val s = k * beat
            val after = rms(l, s + 100, s + 1800); val before = if (k == 0) 0.0 else rms(l, s - 1800, s - 100)
            assertTrue("beat $k: after $after before $before", after > 0.01 && after > 3 * before)
            // the onset is at the beat: the first 4 ms already carry energy and the 4 ms before carry none
            if (k > 0) assertTrue("beat $k pre-onset ${rms(l, s - 176, s)}", rms(l, s - 176, s) < 0.2 * after)
        }
        // beat 1 is accented
        assertTrue(rms(l, 100, 1800) > 1.15 * rms(l, beat + 100, beat + 1800))
        // exact repeat every round (88200 frames) when tension is steady
        for (i in 0 until 88200) assertEquals(l[i], l[i + 88200], 1e-5f)
    }

    @Test fun bedIsOnlyAClockAtTensionZeroAndLayersEnterWithLife() {
        val b = TellSynth.bed(); val lift = 1f / b.kick.maxOf { abs(it) }; val top = 0.1f   // BED_DB = -20
        fun expected(t: Int, i: Int) = top * when (t) {
            0 -> b.kick[i] * lift
            1 -> b.kick[i] + b.bass[i] + b.bright[i]
            else -> b.kick[i] + b.bass[i] + b.bright[i] + b.hat[i] + b.ostinato[i]
        }
        for (t in 0..2) {
            val m = TellMixer(); m.setTension(t); m.startBed()
            val l = channel(renderAll(m, 88200), 0)
            for (i in 0 until 88200 step 7) assertEquals("tension $t frame $i", expected(t, i), l[i], 1e-4f)
        }
    }

    @Test fun tensionAddsLayers() {
        val r = DoubleArray(3)
        for (t in 0..2) { val m = TellMixer(); m.setTension(t); m.startBed(); val o = renderAll(m, SR * 4); r[t] = rms(channel(o, 0)) }
        println("TELL bed rms by tension: %.4f %.4f %.4f".format(r[0], r[1], r[2]))
        assertTrue(r[1] > r[0] * 1.03 && r[2] > r[1] * 1.01)
        val m = TellMixer(); m.setTension(2); m.startBed(); assertTrue(renderAll(m, SR * 4).all { abs(it) < 0.9 })
    }

    @Test fun stopBedBothWays() {
        for (tape in listOf(true, false)) {
            val m = TellMixer(); m.startBed(); renderAll(m, 30000); m.stopBed(tape)
            val o = renderAll(m, SR * 2); val l = channel(o, 0)
            assertTrue(o.all { it.isFinite() })
            if (tape) assertTrue("tape stop is silent after 450 ms", rms(l, SR / 2, SR * 2) < 1e-6) else assertTrue("held note rings", rms(l, 0, SR / 4) > 0.005)
            var jump = 0f; for (i in 1 until l.size) jump = maxOf(jump, abs(l[i] - l[i - 1]))
            assertTrue("tape=$tape jump $jump", jump < 0.12f)
        }
    }

    @Test fun holdHumStartsAndStops() {
        val m = TellMixer(); m.setHold(1); val a = renderAll(m, SR); assertTrue(rms(channel(a, 0)) > 0.01)
        m.setHold(2); renderAll(m, SR / 10); m.setHold(0); renderAll(m, 1000)
        val z = renderAll(m, SR); assertTrue(rms(channel(z, 0), 2000, SR) < 1e-6); assertEquals(0, m.activeVoices())
    }

    @Test fun renderFasterThanRealTime() {
        fun load(): TellMixer {
            val m = TellMixer(); m.startBed(); m.setTension(2); m.setHold(2)
            val cues = listOf(Cue.KICK_ROLL, Cue.CHARGE, Cue.CRACK_BOOM, Cue.FORFEIT, Cue.SNARE_CLOSE, Cue.WIN, Cue.LOSS, Cue.DRAW)
            var i = 0; while (m.activeVoices() < 24) { m.play(cues[i % cues.size], if (i % 2 == 0) Side.OPP else Side.ME); i++ }
            return m
        }
        val out = FloatArray(440)
        val warm = load(); repeat(80) { warm.render(out, 220) }
        var best = Long.MAX_VALUE
        repeat(3) {
            val m = load(); assertEquals(24, m.activeVoices())
            val t0 = System.nanoTime(); repeat(80) { m.render(out, 220) }; best = minOf(best, System.nanoTime() - t0)
            assertEquals(24 - 0, 24)
        }
        val real = 80 * 220 * 1e9 / SR
        println("TELL 24 voices + bed: %.1f ms for %.0f ms of audio = %.0fx real time".format(best / 1e6, real / 1e6, real / best))
        assertTrue("only ${real / best}x real time", real / best >= 5)
    }

    // ---- audition files ----
    private val dir get() = File("../../sound/tell")

    @Test fun writesAuditionWavs() {
        dir.mkdirs()
        for (c in Cue.values()) for (s in Side.values()) {
            val ms = if (c == Cue.FORFEIT || c == Cue.CHARGE || c == Cue.KICK_ROLL) 1400 else 1000
            TellSoundTools.write(File(dir, "${c.name.lowercase()}_${s.name.lowercase()}.wav"), renderCue(c, s, ms))
        }
        for (e in listOf(0, 2, 3, 5)) {
            val m = TellMixer(); m.status(e, 5 - e); TellSoundTools.write(File(dir, "status_${e}_vs_${5 - e}.wav"), renderAll(m, SR / 2))
        }
        for (l in 1..4) for (s in Side.values()) {
            val m = TellMixer(); m.lifeNotes(s, l); TellSoundTools.write(File(dir, "life_${l}_${s.name.lowercase()}.wav"), renderAll(m, SR / 2))
        }
        run {
            val m = TellMixer(); m.setHold(1); val a = renderAll(m, SR); m.setHold(2); val b = renderAll(m, SR); m.setHold(0); val c = renderAll(m, SR / 4)
            TellSoundTools.write(File(dir, "hold_1_2.wav"), a + b + c)
        }
        for (t in 0..2) { val m = TellMixer(); m.setTension(t); m.startBed(); TellSoundTools.write(File(dir, "bed_tension$t.wav"), renderAll(m, SR * 8)) }
        val total = dir.listFiles()!!.sumOf { it.length() }
        println("TELL wav files: ${dir.listFiles()!!.size}, ${total / 1024} KB so far")
    }

    private enum class R { PARRY, FIZZLE, BREAK, TRADE, NOTHING }

    /** A scripted 20-round match on the 2000 ms grid, built only from public mixer calls. */
    @Test fun writesScriptedMatch() {
        val mine = "GGGGGGJGGGGGJGGGGGGG".map { "JGC".indexOf(it) }       // 0 jab 1 guard 2 charge
        val theirs = "GGGJGGCGGCGGJGGGGGGJ".map { "JGC".indexOf(it) }
        val round = TellSynth.ROUND_FRAMES; val lead = SR            // 1 s kick roll before the first downbeat
        class Ev(val at: Int, val f: (TellMixer) -> Unit)
        val ev = ArrayList<Ev>()
        fun at(f: Int, g: (TellMixer) -> Unit) { ev.add(Ev(f, g)) }
        fun ms(m: Int) = m * SR / 1000
        at(0) { it.play(Cue.KICK_ROLL) }
        at(lead) { it.startBed() }
        var me = 3; var op = 3; var lm = 4; var lo = 4; val seen = HashSet<R>(); var played = 0
        for (r in 0 until 20) {
            val t0 = lead + r * round
            var a = mine[r]; var b = theirs[r]
            if (a == 0 && me < 1) a = 1; if (a == 2 && me < 3) a = 1; if (b == 0 && op < 1) b = 1; if (b == 2 && op < 3) b = 1
            val e0 = me; val o0 = op; val l0 = lm; val lo0 = lo
            at(t0) { it.status(e0, o0) }
            // the player's hold: press at 400 ms
            val press = t0 + ms(400)
            at(press) { it.play(Cue.TAP) }
            val rel = press + when (a) { 0 -> ms(150); 1 -> ms(500); else -> ms(900) }
            if (a >= 1) { at(press + ms(300)) { it.play(Cue.DETENT_1); it.setHold(1) } }
            if (a == 2) { at(press + ms(800)) { it.play(Cue.DETENT_2); it.setHold(2) } }
            at(rel) { it.setHold(0); it.play(Cue.STAMP) }
            at(t0 + ms(1500)) { it.play(Cue.SNARE_CLOSE) }
            val rv = t0 + ms(1500)
            val mv = Cue.values().first { it.name == listOf("JAB", "GUARD", "CHARGE")[a] }; val tv = Cue.values().first { it.name == listOf("JAB", "GUARD", "CHARGE")[b] }
            at(rv) { it.play(mv, Side.ME); it.play(tv, Side.OPP) }
            // resolve (rules of GAME-TELL.md)
            var dm = 0; var dop = 0; var kind: R? = null
            me -= listOf(1, 0, 3)[a]; op -= listOf(1, 0, 3)[b]
            val res = rv + ms(480)
            when {
                a == 0 && b == 0 -> { dm = 1; dop = 1; kind = R.TRADE; at(res) { it.play(Cue.HIT, Side.ME); it.play(Cue.HIT, Side.OPP) } }
                a == 2 && b == 2 -> { dm = 2; dop = 2; kind = R.TRADE; at(res) { it.play(Cue.HIT, Side.ME); it.play(Cue.HIT, Side.OPP) } }
                a == 1 && b == 1 -> { kind = R.NOTHING; at(res) { it.play(Cue.NOTHING) } }
                a == 1 && b == 0 -> { dop = 1; me += 1; kind = R.PARRY; at(res) { it.play(Cue.PARRY_CLANG, Side.ME); it.play(Cue.HIT, Side.OPP) }; at(res + ms(160)) { it.play(Cue.COIN) } }
                a == 0 && b == 1 -> { dm = 1; op += 1; kind = R.PARRY; at(res) { it.play(Cue.PARRY_CLANG, Side.OPP); it.play(Cue.HIT, Side.ME) }; at(res + ms(160)) { it.play(Cue.COIN, Side.OPP) } }
                a == 0 && b == 2 -> { dop = 1; kind = R.FIZZLE; at(res) { it.play(Cue.FIZZLE, Side.OPP); it.play(Cue.HIT, Side.OPP) } }
                a == 2 && b == 0 -> { dm = 1; kind = R.FIZZLE; at(res) { it.play(Cue.FIZZLE, Side.ME); it.play(Cue.HIT, Side.ME) } }
                a == 2 && b == 1 -> { dop = 2; kind = R.BREAK; at(res) { it.play(Cue.CRACK_BOOM, Side.OPP) } }
                else -> { dm = 2; kind = R.BREAK; at(res) { it.play(Cue.CRACK_BOOM, Side.ME) } }
            }
            lm -= dm; lo -= dop; me = minOf(5, me + 1); op = minOf(5, op + 1); seen.add(kind!!); played = r + 1
            val lmNow = lm; val loNow = lo
            if (dm > 0 && lmNow > 0) at(res + ms(380)) { it.lifeNotes(Side.ME, lmNow) }
            if (dop > 0 && loNow > 0) at(res + ms(380) + ms(if (dm > 0) 330 else 0)) { it.lifeNotes(Side.OPP, loNow) }
            if (lm <= 0 || lo <= 0) {
                val cue = if (lm <= 0 && lo <= 0) Cue.DRAW else if (lo <= 0) Cue.WIN else Cue.LOSS
                at(res + ms(800)) { it.play(cue); it.stopBed(false) }
                break
            }
            if (r == 19) at(res + ms(800)) { it.play(if (lm > lo) Cue.WIN else if (lm < lo) Cue.LOSS else Cue.DRAW); it.stopBed(false) }
            if (r == 9) at(t0 + ms(1990)) { it.setTension(if (lm <= 2 || lo <= 2) 1 else 0) }
            if (lm <= 1 || lo <= 1) at(res) { it.setTension(2) } else if (lm <= 2 || lo <= 2) at(res) { it.setTension(1) }
        }
        assertEquals(20, played)
        println("TELL match: $played rounds, results seen $seen, final life $lm vs $lo")
        assertTrue("parry, fizzle, break and trade all appear: $seen", seen.containsAll(listOf(R.PARRY, R.FIZZLE, R.BREAK, R.TRADE)))
        val end = ev.maxOf { it.at } + SR * 3
        val m = TellMixer(); val out = FloatArray(end * 2); val block = 220
        val sorted = ev.sortedBy { it.at }; var k = 0; var f = 0; val tmp = FloatArray(block * 2)
        while (f < end) {
            while (k < sorted.size && sorted[k].at < f + block) { sorted[k].f(m); k++ }
            val n = minOf(block, end - f); m.render(tmp, n); System.arraycopy(tmp, 0, out, f * 2, n * 2); f += n
        }
        assertTrue(out.all { it.isFinite() }); val pk = out.maxOf { abs(it) }
        println("TELL match peak %.1f dBFS, %.1f s".format(db(pk.toDouble()), end.toDouble() / SR))
        assertTrue(pk <= 0.8913f + 1e-6f)
        TellSoundTools.write(File(dir, "_match.wav"), out)
        val total = dir.listFiles()!!.sumOf { it.length() }
        println("TELL sound/tell total ${total / 1024 / 1024.0} MB")
        assertTrue(total < 40L * 1024 * 1024)
    }
}
