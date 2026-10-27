package com.faka.invisible

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.content.SharedPreferences
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.testing.FakeProgramHost
import com.faka.invisible.voicekit.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * For the flow tests that need a real Android Handler and the real [ButtonInput] (Robolectric, a paused main looper = fake time).
 * One clock for everything: [advance] moves the looper, and the button's gesture timers, the program's timers and the fake
 * voice all fire in order. Presses are the phone script's strings and enter at `VoiceKit.buttons.key`, the same entry as the
 * `press` debug hook of the app.
 */
internal class RoboRig {
    val handler = Handler(Looper.getMainLooper())
    val said = ArrayList<String>()
    val beeps = ArrayList<Tone>()
    val ctx: Context = RuntimeEnvironment.getApplication()

    /** The program that owns the raw button now (the OS's `running`), null = the menu / idle. */
    var running: Program? = null
    var exits = 0

    val speech = object : SpeechPort {
        override fun speak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) { said.add(p.text) }
        override fun stop() {}
        override val speaking = false
        override val words = WordClips(File("."), { _, _, _ -> false }, { it() })
    }
    val tones = object : TonePort {
        override fun beep(k: Tone, vol: Float) { beeps.add(k) }
        override fun playPcm(pcm: ShortArray) {}
        override fun playStereo(pcm: ShortArray, rate: Int) {}
        override fun stopStereo() {}
        override fun findLoop(on: Boolean) {}
    }
    val target = object : ButtonTarget {
        override fun raw(): RawButton? = (running as? RawButton)?.takeIf { it.active }
        override val menuActive get() = false
        override fun gesture(g: Gesture, durationMs: Long) {}
        override fun exitProgram() { exits++; running?.takeIf { it.active }?.let { if (it.exitOnClickHold) it.stop() } }
    }
    val narrator = Narrator(speech, tones, handler)
    val buttons = ButtonInput(handler, narrator, target)

    init {
        // VoiceKit is the OS's one door (Actions calls its init, which starts the real voice); the tests put the same real
        // ButtonInput and a Narrator on fake ports into it, so programs find their `VoiceKit.narrator` and `VoiceKit.buttons`.
        for ((name, v) in listOf("narrator" to narrator, "buttons" to buttons))
            VoiceKit::class.java.getDeclaredField(name).apply { isAccessible = true }.set(VoiceKit, v)
    }

    /** What a program may ask of the OS, on the real handler; its speech starts 50 ms after the call and ends 500 ms after it. */
    inner class Host : ProgramHost {
        var ended = 0
        var speechToken = 0
        /** Every picture the program put on the app screen: the rows and the marked row. */
        val screens = ArrayList<Pair<List<String>, Int>>()
        override fun screen(rows: List<String>, mark: Int) { screens.add(rows to mark) }
        override val context: Context get() = ctx
        override val handler: Handler get() = this@RoboRig.handler
        override val prefs: SharedPreferences = FakeProgramHost.MemoryPrefs()
        override fun waiting(): () -> Unit = {}
        override fun speak(text: Phrase, slow: Boolean) {
            said.add(text.text)
            val t = ++speechToken
            handler.postDelayed({ if (t == speechToken) running?.takeIf { it.active }?.speechDone() }, 500)
        }
        override fun stopSpeech() { speechToken++ }
        override fun pauseMusic() {}
        override fun resumeMusic() {}
        override fun log(line: String) {}
        override fun outputDevice() = "test"
        override fun ended() { ended++; running = null }
    }

    fun now() = SystemClock.uptimeMillis()
    fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    /** The phone script's `press`: "down,gap,down,..." in ms, through the real ButtonInput. */
    fun press(spec: String) {
        var downAt = 0L
        spec.split(",").map { it.trim().toLong() }.forEachIndexed { i, d ->
            if (i % 2 == 0) { downAt = now(); buttons.key("dev", true, 0, downAt, downAt); advance(d); buttons.key("dev", false, 0, downAt, now()) } else advance(d)
        }
    }

    /** Like [need], for work done on real background threads (the network): fake time runs in small steps, with a real pause between. */
    fun needLive(text: String, i: Int = 0, realMs: Long = 20_000) {
        val end = System.currentTimeMillis() + realMs
        while (!said(text, i) && System.currentTimeMillis() < end) { advance(50); Thread.sleep(5) }
        if (!said(text, i)) throw AssertionError("never heard \"$text\"; heard: " + said.drop(i).take(8).joinToString(" | "))
    }
    fun mark() = said.size
    fun said(text: String, i: Int = 0) = said.drop(i).any { it.contains(text, ignoreCase = true) }
    /** Waits in fake time until [text] is spoken after [i]. */
    fun need(text: String, i: Int = 0, withinMs: Long = 15_000) {
        var t = 0L
        while (!said(text, i) && t < withinMs) { advance(10); t += 10 }
        if (!said(text, i)) throw AssertionError("never heard \"$text\"; heard: " + said.drop(i).take(8).joinToString(" | "))
    }
}
