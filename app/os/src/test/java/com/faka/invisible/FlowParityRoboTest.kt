package com.faka.invisible

import com.faka.invisible.echo.*
import com.faka.invisible.guard.*
import com.faka.invisible.logickit.*
import com.faka.invisible.practice.PracticeProgram
import com.faka.invisible.voicekit.*
import com.faka.invisible.write.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parity (what you hear is what you see) for the programs, on a real looper in fake time: the shared `ParityRecorder` of the rig compares
 * what the fake voice, tones and mixer played with the cues the kit published. See FlowParityTest for the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowParityRoboTest {
    private val store = object : LoopStore { override fun load() = emptyList<Double>(); override fun level() = LoopLevel.TOP; override fun save(offsets: List<Double>) {} }

    /** A game mixer on the rig's clock: renders a 5 ms block every 5 ms, so FrameMixer announces each sound like the audio thread does. */
    private class RigMixer(val rig: RoboRig, val sr: Int) {
        val mixer = FrameMixer()
        private val block = sr * 5 / 1000
        private val buf = FloatArray(block * 2)
        private val tick = object : Runnable { override fun run() { mixer.render(buf, block); rig.handler.postDelayed(this, 5) } }
        fun run() { rig.handler.post(tick) }
        fun stop() { rig.handler.removeCallbacks(tick) }
        /** The picture must show this sound near its start plus the output delay. */
        fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) {
            val inMs = maxOf(0L, (atFrame - mixer.nowFrame()) * 1000 / sr)
            rig.parity.sounded("beat", rig.now() + inMs + SoundCues.LATENCY_MS)
            mixer.play(sound, atFrame, pan, gain)
        }
    }

    private class HandlerTimers(val rig: RoboRig) : Timers {
        private val keyed = HashMap<String, Runnable>()
        override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { keyed.remove(key); task() }; keyed[key] = r; rig.handler.postDelayed(r, delayMs) }
        override fun clear(key: String) { keyed.remove(key)?.let { rig.handler.removeCallbacks(it) } }
    }

    // ---- parity_echo: the robot's rhythm (game sounds) and the spoken lines ----
    @Test fun parity_echo() {
        val rig = RoboRig(); val host = rig.Host(); rig.parity.start()
        try {
            EchoSynth.warm()
            val mx = RigMixer(rig, EchoSynth.SR); val timers = HandlerTimers(rig)
            lateinit var game: EchoGame
            val out = object : EchoOut {
                override fun nowFrame() = mx.mixer.nowFrame()
                override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) = mx.play(sound, atFrame, pan, gain)
                override fun speak(text: Phrase) { host.speak(text); rig.handler.postDelayed({ game.onSpeechDone() }, 500) }
                override fun stopSpeech() {}
                override fun best() = 0
                override fun saveBest(score: Int) {}
                override fun log(line: String) {}
                override fun leave() {}
            }
            game = EchoGame(out, timers, rig::now, { 0L }, seed = 5)
            mx.run(); game.start()
            rig.advance(12_000)
            mx.stop()
            rig.parity.assertParity(minSpoken = 1, minSounds = 4)
            assertTrue("the strip has marks", ScreenMirror.state.sounds.isNotEmpty())
        } finally { rig.parity.stop() }
    }

    // ---- parity_guard: the beat of the gate and the spoken tutorial ----
    @Test fun parity_guard() {
        val rig = RoboRig(); val host = rig.Host(); rig.parity.start()
        try {
            GuardSynth.warm()
            val mx = RigMixer(rig, GuardSynth.SR); val timers = HandlerTimers(rig)
            lateinit var game: GuardGame
            val out = object : GuardOut {
                override fun nowFrame() = mx.mixer.nowFrame()
                override fun play(sound: FloatArray, atFrame: Long, pan: Double, gain: Double) = mx.play(sound, atFrame, pan, gain)
                override fun clearScheduled() = mx.mixer.clear()
                override fun speak(text: Phrase) { host.speak(text); rig.handler.postDelayed({ game.onSpeechDone() }, 500) }
                override fun stopSpeech() {}
                override fun best() = 0
                override fun saveBest(score: Int) {}
                override fun bias(): Double? = null
                override fun saveBias(ms: Double) {}
                override fun taught() = false
                override fun saveTaught(v: Boolean) {}
                override fun log(line: String) {}
                override fun leave() {}
            }
            game = GuardGame(out, timers, rig::now, { 0L }, seed = 4)
            mx.run(); game.start()
            rig.advance(15_000)
            mx.stop()
            rig.parity.assertParity(minSpoken = 1, minSounds = 3)
        } finally { rig.parity.stop() }
    }

    // ---- parity_practice: a program that speaks lines and answers each press with a sound ----
    @Test fun parity_practice() {
        val rig = RoboRig(); val host = rig.Host(); val p = PracticeProgram(); rig.parity.start()
        try {
            rig.running = p; p.start(host)
            rig.need("click once now")
            rig.press("120"); rig.need("click twice")
            rig.press("120,150,120"); rig.advance(3000)
            rig.parity.assertParity(minSpoken = 3)
        } finally { rig.parity.stop() }
    }

    // ---- parity_home_letters: the letter loop (items, the pick sound) ----
    @Test fun parity_home_letters() {
        val rig = RoboRig(); val host = rig.Host(); val audio = FlowButtonTest.RigLoopAudio(rig)
        val apps = object : ActionSource { override val id = "apps"; override fun actions() = listOf(HomeAction(ActionKind.OPEN, "WhatsApp", "com.whatsapp", 30.0), HomeAction(ActionKind.OPEN, "Maps", "com.maps", 20.0)) }
        val p = HomeLetters(listOf(apps), ActionRanker(), audio, audio, store, { true }, {})
        ScreenMirror.clear(); rig.parity.start()
        try {
            rig.running = p; p.start(host)
            rig.advance(9000)
            rig.parity.assertParity(minSpoken = 30)
        } finally { rig.parity.stop(); ScreenMirror.clear(); p.stop() }
    }

    // ---- parity_write_loop: Write's letter loop ----
    @Test fun parity_write_loop() {
        val rig = RoboRig(); val audio = FlowButtonTest.RigLoopAudio(rig)
        val lm = object : Ranker { override fun ranked(context: List<String>) = listOf("hello" to -2.0, "hi" to -3.0) }
        val out = object : TypeOut {
            override fun pair(left: String, right: String?) {}
            override fun hush() {}
            override fun prefetch(words: List<String>) {}
            override fun say(text: Phrase) {}
            override fun cue(c: TypeCue) {}
            override fun readBack(text: String) {}
            override fun finished(text: String) {}
        }
        val p = LoopProgram(lm, out, audio, audio, Frame(), store) {}
        ScreenMirror.clear(); rig.parity.start()
        try {
            rig.rawOwner = p; p.start()
            rig.advance(20_000)
            rig.parity.assertParity(minSpoken = 10)
        } finally { rig.parity.stop(); ScreenMirror.clear(); p.stop() }
    }
}
