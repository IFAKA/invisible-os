package com.faka.invisible.oskit.testing

import com.faka.invisible.logickit.squash
import com.faka.invisible.voicekit.*
import java.io.File

/**
 * For tests of "what you hear is what you see" (step 5 of app/CUE-PLAN.md). One shared set of fakes, so a test rig never emulates the
 * cue path by hand:
 *  - [CueClock] puts the sound and press cues on the rig's fake clock.
 *  - [FakeVoice] is a fake speech engine: it starts a line [startMs] after the call and then reports the start the way the real engine does
 *    (`Narrator.speechStarted()`, or, with no Narrator, its own [CueGate]).
 *  - [FakeTones] is a fake `TonePort`: every beep tells `SoundCues.played(k.cueName)` like `TonePlayer` does.
 *  - [ParityRecorder] watches the cues and checks them against what the fakes say was heard.
 * Use: `val rec = ParityRecorder(clock).start()`, give the fakes the recorder, run the scenario, `rec.assertParity()`, `rec.stop()`.
 */
object CueClock {
    /** Sound cues and press times follow [now]; delayed sound cues (game mixer) are posted with [post] (delay ms, task). */
    fun install(now: () -> Long, post: (Long, () -> Unit) -> Unit) {
        SoundCues.clock = now; SoundCues.post = post; ScreenMirror.now = now
    }
    fun reset() {
        SoundCues.clock = { android.os.SystemClock.uptimeMillis() }
        SoundCues.post = { d, f -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(f, d) }
        ScreenMirror.now = { System.currentTimeMillis() }
        Cues.tap = null; Cues.clearStaged()
    }
}

/**
 * A fake speech engine. [speak] records the line; [startMs] later (a newer line replaces it, like the real engine) it tells the kit
 * the sound started: [narrator] (when the rig has a real Narrator) or else the own [gate]. [schedule] runs a task after a delay on the
 * rig's clock; [now] reads that clock. Lines that start are given to the [recorder] as "heard".
 */
class FakeVoice(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val narrator: () -> Narrator? = { null },
    var recorder: ParityRecorder? = null,
    private val startMs: Long = 50,
    val said: MutableList<String> = ArrayList(),
) : SpeechPort {
    val gate = CueGate()
    private val lines = ArrayList<String>()
    private var token = 0
    /** Called after each start (a rig that wants its own bookkeeping). */
    var onStarted: () -> Unit = {}

    override fun speak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) {
        said.add(p.text)
        if (!queue) lines.clear()
        if (p.text.isNotBlank()) lines += p.text
        if (narrator() == null) Cues.takeStagedFor(p.text)?.let { gate.hold(it, queue) }   // what Narrator.say does
        val t = ++token
        schedule(startMs) { if (t == token) started() }
    }
    private fun started() {
        val at = now()
        lines.forEach { recorder?.heard(it, at) }; lines.clear()
        val n = narrator()
        if (n != null) n.speechStarted() else gate.started(at)
        onStarted()
    }
    /** The engine was stopped. */
    override fun stop() { token++; lines.clear(); gate.cancel() }
    override val speaking = false
    override val words = WordClips(File("."), { _, _, _ -> false }, { it() })
}

/** A fake `TonePort`: records the beeps and announces each one to the picture exactly like `TonePlayer` (at once, from the call). */
class FakeTones(private val now: () -> Long, var recorder: ParityRecorder? = null) : TonePort {
    val beeps = ArrayList<Tone>()
    override fun beep(k: Tone, vol: Float) { beeps.add(k); recorder?.sounded(k.cueName, now()); SoundCues.played(k.cueName) }
    override fun playPcm(pcm: ShortArray) { recorder?.sounded("sound", now()); SoundCues.played("sound") }
    override fun playStereo(pcm: ShortArray, rate: Int) {}
    override fun stopStereo() {}
    override fun findLoop(on: Boolean) {}
}

/**
 * Records what the fake engines say was HEARD (a spoken item or a sound, with its time) and every [Cue] the kit published (what is SHOWN),
 * then [problems] compares them:
 *  1. every spoken cue (list item, line, loop item) has the same words in [heard] at its onset (within [toleranceMs]);
 *  2. a cue is never published before its onset, and has an onset (not "at the call");
 *  3. every heard item has a cue (nothing is spoken without being shown);
 *  4. every sound has a SOUND cue of the same name near its time, and no SOUND cue appears without a sound.
 * Times are on the clock the rig gives ([clock]).
 */
class ParityRecorder(private val clock: () -> Long, private val toleranceMs: Long = 50) {
    data class Heard(val text: String, val atMs: Long)
    data class Seen(val cue: Cue, val seenAtMs: Long)

    val heard = ArrayList<Heard>()
    val sounds = ArrayList<Heard>()
    val seen = ArrayList<Seen>()

    fun start(): ParityRecorder { Cues.tap = { c -> synchronized(this) { seen += Seen(c, clock()) } }; return this }
    fun stop() { if (Cues.tap != null) Cues.tap = null }

    /** The engine really started this spoken [text] at [atMs] (a list item, a line, a loop item). */
    fun heard(text: String, atMs: Long) { synchronized(this) { heard += Heard(text, atMs) } }
    /** A sound named [name] really starts at [atMs]. */
    fun sounded(name: String, atMs: Long) { synchronized(this) { sounds += Heard(name, atMs) } }

    private fun norm(s: String) = s.squash().lowercase()
    private fun spokenKind(c: Cue) = when (c.kind) {
        Cue.Kind.LIST_ITEM, Cue.Kind.LINE -> c.text.isNotBlank()
        Cue.Kind.LOOP_ITEM -> c.ring == null && c.index >= 0 && c.text.isNotBlank()
        else -> false
    }

    /**
     * @param ignoreHeard heard items that are not meant to be shown (a fast-scroll word, a tick-only line)
     * @param ignoreCue cues that are known not to follow the rule (pass the reason in the test, so each exception is visible)
     * @param soundTolMs extra time allowed between a sound and its cue (the game mixer posts its cue [SoundCues.LATENCY_MS] after the block)
     */
    fun problems(ignoreHeard: (String) -> Boolean = { false }, ignoreCue: (Cue) -> Boolean = { false }, soundTolMs: Long = 0): List<String> {
        val out = ArrayList<String>()
        val h = synchronized(this) { heard.toList() }; val s = synchronized(this) { sounds.toList() }; val sn = synchronized(this) { seen.toList() }
        val end = clock()
        for ((c, at) in sn) {
            if (ignoreCue(c)) continue
            if (spokenKind(c)) {
                if (c.onsetMs < 0) { out += "shown at the call, with no onset: ${c.kind} '${c.text}'"; continue }
                if (at < c.onsetMs) out += "shown before its onset: ${c.kind} '${c.text}' shown at $at, onset ${c.onsetMs}"
                if (h.none { norm(it.text) == norm(c.text) && Math.abs(it.atMs - c.onsetMs) <= toleranceMs })
                    out += "shown but not heard then: ${c.kind} '${c.text}' onset ${c.onsetMs}; heard near: " + h.filter { Math.abs(it.atMs - c.onsetMs) <= 400 }.joinToString { "'${it.text}'@${it.atMs}" }
            } else if (c.kind == Cue.Kind.SOUND) {
                if (s.none { it.text == c.text && Math.abs(it.atMs - c.onsetMs) <= toleranceMs + soundTolMs })
                    out += "sound cue '${c.text}' at ${c.onsetMs} without a sound near it"
            }
        }
        for (x in h) {
            if (ignoreHeard(x.text) || x.atMs > end - toleranceMs) continue
            if (sn.none { spokenKind(it.cue) && norm(it.cue.text) == norm(x.text) && Math.abs(it.cue.onsetMs - x.atMs) <= toleranceMs })
                out += "heard but never shown: '${x.text}' at ${x.atMs}"
        }
        for (x in s) {
            if (x.atMs > end - toleranceMs - soundTolMs) continue
            if (sn.none { it.cue.kind == Cue.Kind.SOUND && it.cue.text == x.text && Math.abs(it.cue.onsetMs - x.atMs) <= toleranceMs + soundTolMs })
                out += "sound '${x.text}' at ${x.atMs} made no mark"
        }
        return out
    }

    /** Fails with every problem listed. [minSpoken] and [minSounds] make sure the scenario really said and played something. */
    fun assertParity(ignoreHeard: (String) -> Boolean = { false }, ignoreCue: (Cue) -> Boolean = { false }, soundTolMs: Long = 0, minSpoken: Int = 0, minSounds: Int = 0) {
        val p = problems(ignoreHeard, ignoreCue, soundTolMs)
        if (p.isNotEmpty()) throw AssertionError("voice and screen differ (${p.size}):\n" + p.take(12).joinToString("\n"))
        val spoken = synchronized(this) { seen.count { spokenKind(it.cue) } }
        val snd = synchronized(this) { seen.count { it.cue.kind == Cue.Kind.SOUND } }
        if (spoken < minSpoken) throw AssertionError("only $spoken spoken cues were shown, expected at least $minSpoken (is the recorder attached?)")
        if (snd < minSounds) throw AssertionError("only $snd sound cues were shown, expected at least $minSounds")
    }
}
