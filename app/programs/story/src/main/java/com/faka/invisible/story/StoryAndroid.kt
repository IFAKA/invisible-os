package com.faka.invisible.story

import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.GameSounds
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Synth
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone

/**
 * Story. A bundled branching story: a scene is read, then its 2-3 choices are read one after the other (the kit Scanner);
 * a click picks the choice you just heard. A sound marks each new scene, a different one marks an ending. The last scene
 * is saved, so the game resumes there. Hold 3 s leaves. No microphone: only the button.
 */
class StoryAndroid : Program {
    private enum class State { LOADING, INTRO, LIST, READING, CHOOSING, ENDING, THE_END, DONE }

    private lateinit var host: ProgramHost
    private val sounds = GameSounds()
    private var state = State.LOADING
    private var story: Story? = null
    private var scene: Scene? = null
    private var resumed = false
    private var idle = 0
    private var downAt = 0L
    private val keyed = HashMap<String, Runnable>()
    private val leave = Runnable { shutdown() }

    private val timers = object : Timers {
        override fun set(key: String, delayMs: Long, task: () -> Unit) {
            clear(key)
            val r = Runnable { keyed.remove(key); task() }
            keyed[key] = r; host.later(r, delayMs)
        }
        override fun clear(key: String) { keyed.remove(key)?.let { host.cancel(it) } }
    }

    private val out = object : ScanOut {
        override fun say(index: Int, label: Phrase, spearcon: Boolean) { host.speak(label) }
        override fun sayFast(word: Phrase) { host.speak(word) }
        override fun tone(t: Tone) {}
        override fun leave() {   // read through twice with no click: once more, then leave
            if (++idle >= 2) shutdown() else if (state == State.LIST) showList() else scene?.let { scan(it) }
        }
    }
    private val scanner = Scanner<Phrase>(timers, { it }, out, ScanCadence(pauseMs = { Pacing.ITEM_GAP_MS }, loops = { 2 }, ceremony = false))

    override val active get() = state != State.DONE

    override fun start(host: ProgramHost) {
        if (state != State.LOADING) return
        this.host = host
        host.pauseMusic()
        val w = host.waiting()
        sounds.warm(host.handler, { StorySynth.warm() }) {
            w()
            if (state == State.LOADING) begin()
        }
    }

    private fun begin() {
        if (!sounds.open()) { host.speak(StoryVoice.LOADING); shutdown(); return }
        state = State.INTRO
        val saved = StoryCore.resume(host.prefs.getString(SAVE, null))
        Trace.ev("program", "story start ${if (saved != null) "resume ${saved.first.id}/${saved.second.id}" else "new"}")
        if (saved != null) {
            story = saved.first; resumed = true
            host.speak(Phrase.join(listOf(StoryVoice.BACK, StoryVoice.LEAVE)))
        } else {
            host.speak(Phrase.join(listOf(StoryVoice.INTRO, StoryVoice.LEAVE)))
        }
    }

    override fun speechDone() {
        when (state) {
            State.INTRO -> if (resumed) show(scene ?: StoryCore.resume(host.prefs.getString(SAVE, null))!!.second) else showList()
            State.READING -> scene?.let { scan(it) }
            State.CHOOSING, State.LIST -> scanner.itemDone()
            State.ENDING -> { state = State.THE_END; host.speak(StoryVoice.THE_END) }
            State.THE_END -> { resumed = false; showList() }
            else -> {}
        }
    }

    private fun showList() {
        state = State.LIST
        story = null
        scanner.load(StoryCore.stories.map { Phrase.content(it.title) })
        scanner.speakItemIn(300, 0)
    }

    private fun show(sc: Scene) {
        scene = sc
        idle = 0
        scanner.stop()
        val st = story ?: return
        Trace.ev("program", "story ${st.id}/${sc.id}")
        if (sc.isEnding) {
            host.prefs.edit().remove(SAVE).apply()
            sounds.play(StorySynth.end, 0.0, 0.5)
            state = State.ENDING
        } else {
            host.prefs.edit().putString(SAVE, "${st.id}:${sc.id}").apply()
            sounds.play(StorySynth.change, 0.0, 0.4)
            state = State.READING
        }
        host.speak(Phrase.content(sc.text))
    }

    private fun scan(sc: Scene) {
        state = State.CHOOSING
        scanner.load(sc.choices.map { Phrase.content(it.label) })
        scanner.speakItemIn(300, 0)
    }

    override fun down(time: Long) { downAt = time; host.cancel(leave); host.later(leave, Pacing.LEAVE_MS) }

    /** A short press while choices (or titles) are read picks the one just heard. */
    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt >= Pacing.HOLD_MS) return
        when (state) {
            State.LIST -> {
                val st = StoryCore.stories.getOrNull(scanner.list.index) ?: return
                scanner.stop(); host.stopSpeech()
                story = st
                Trace.ev("program", "story pick ${st.id}")
                StoryCore.stories.firstOrNull { it === st }?.let { s -> s.scene(s.start)?.let { show(it) } }
            }
            State.CHOOSING -> {
                val c = scene?.choices?.getOrNull(scanner.list.index) ?: return
                scanner.stop(); host.stopSpeech()
                val next = story?.scene(c.to) ?: return
                Trace.ev("program", "story choice ${c.label}")
                show(next)
            }
            else -> {}
        }
    }

    override fun stop() = shutdown()

    private fun shutdown() {
        if (state == State.DONE) return
        val was = state
        state = State.DONE
        host.cancel(leave)
        scanner.stop()
        host.stopSpeech()
        if (was != State.LOADING) sounds.close()
        host.resumeMusic()
        host.ended()
    }

    companion object { const val SAVE = "story_save" }
}

/** The sounds of the Story game (pure maths). */
object StorySynth {
    val change by lazy { Synth.tone(587.0, 180, 90.0) }
    val end by lazy { Synth.then(Synth.then(Synth.tone(523.0, 200, 90.0), Synth.tone(659.0, 200, 90.0), 120), Synth.tone(784.0, 500, 220.0), 120) }
    fun warm() { change; end }
}

/** What the OS registry lists for the Story game. */
object StoryProgram {
    val entry = ProgramEntry("games:story", StoryVoice.TITLE) { StoryAndroid() }
}
