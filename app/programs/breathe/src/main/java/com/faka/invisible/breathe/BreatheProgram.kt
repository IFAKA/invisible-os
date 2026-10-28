package com.faka.invisible.breathe

import android.content.Context
import android.media.AudioManager
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Earcons
import com.faka.invisible.voicekit.Earcons.Timbre
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.GestureRules
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit

/** The three parts of a breath. Each lasts [seconds]; its sound has one note a second: rising = in, flat = hold, falling = out. */
enum class BreathePhase(val seconds: Int, val hz: List<Double>) {
    IN(4, listOf(262.0, 311.0, 370.0, 440.0)),
    HOLD(4, listOf(440.0, 440.0, 440.0, 440.0)),
    OUT(6, listOf(392.0, 349.0, 311.0, 277.0, 247.0, 220.0));
    val ms get() = seconds * 1000L
}

/** Pure rules of Breathe and of the sleep fade. */
object BreatheCore {
    const val DEFAULT_ROUNDS = 5
    const val MAX_ROUNDS = 30
    const val FADE_MS = 60_000L
    fun addRound(n: Int) = minOf(MAX_ROUNDS, n + 1)
    fun roundMs() = BreathePhase.values().sumOf { it.ms }
    /** Time between two volume steps so that [volume] steps reach 0 in [FADE_MS]. */
    fun fadeStepMs(volume: Int) = if (volume <= 0) FADE_MS else FADE_MS / volume
}

/** The music volume, so the fade can be tested. */
interface MusicVolume { fun get(): Int; fun set(v: Int) }

class AndroidMusicVolume(private val ctx: Context) : MusicVolume {
    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun get() = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    override fun set(v: Int) { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
}

/**
 * Breathe: guided breathing with tones only (in 4 s, hold 4 s, out 6 s). Click starts, 5 rounds; a click while breathing adds a
 * round. Two clicks start the Sleep timer: the music volume goes down over 60 s, then the music is paused and the volume comes back.
 * Triple click or a hold of 3 s leaves (and puts the volume back). No microphone.
 * The three sounds are built on a background thread (a phone is slow at it) before the first breath.
 */
class BreatheProgram(
    private val volume: (ProgramHost) -> MusicVolume = { AndroidMusicVolume(it.context) },
    private val build: (BreathePhase) -> ShortArray = { p -> Earcons.sequence(Timbre.MALLET, 1000, 900, *p.hz.toDoubleArray(), reverb = true, peakDb = -20.0) },
    private val play: (ShortArray) -> Unit = { VoiceKit.narrator.play(it) },
    private val beep: (Tone) -> Unit = { VoiceKit.narrator.beep(it) },
    private val background: (() -> Unit) -> Unit = { work -> Thread(work, "breathe-build").start() },
) : Program {
    private enum class Mode { MENU, BREATHING, SLEEP }
    private lateinit var host: ProgramHost
    private var running = false
    private var mode = Mode.MENU
    private var rounds = BreatheCore.DEFAULT_ROUNDS
    private var round = 0
    private var phase = BreathePhase.IN
    @Volatile private var sounds: Map<BreathePhase, ShortArray>? = null
    private var vol: MusicVolume? = null
    private var startVolume = 0
    private var presses = ArrayList<Pair<Long, Long>>()
    private var downAt = 0L
    private val leave = Runnable { finish() }
    private val resolve = Runnable { val g = GestureRules.classify(presses); presses = ArrayList(); if (running) onGesture(g) }
    private val firstBreath = Runnable { if (running && mode == Mode.BREATHING) breath() }
    private var slept = false
    private val nextPhase = Runnable { if (running && mode == Mode.BREATHING) advance() }
    private val fadeStep = Runnable { if (running && mode == Mode.SLEEP) fade() }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true; mode = Mode.MENU; rounds = BreatheCore.DEFAULT_ROUNDS; slept = false
        Trace.ev("program", "breathe start")
        host.speak(BreatheVoice.INTRO)
        host.speak(BreatheVoice.LEAVE)
        background {
            val built = BreathePhase.values().associateWith(build)
            sounds = built
        }
    }

    override fun stop() = finish()

    override fun down(time: Long) { downAt = time; host.cancel(resolve); host.later(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        host.cancel(leave)
        if (time - downAt >= Pacing.LEAVE_MS) return
        presses.add(downAt to time)
        host.later(resolve, Pacing.GAP_MS)
    }

    private fun onGesture(g: Gesture?) {
        if (g == Gesture.TRIPLE) { finish(); return }
        when (mode) {
            Mode.MENU -> when (g) { Gesture.SINGLE -> begin(); Gesture.DOUBLE -> sleep(); else -> {} }
            Mode.BREATHING -> if (g == Gesture.SINGLE) {
                rounds = BreatheCore.addRound(rounds); beep(Tone.PICK)
                Trace.ev("program", "breathe rounds $rounds")
            }
            Mode.SLEEP -> if (g == Gesture.SINGLE || g == Gesture.DOUBLE) { stopSleep(); host.speak(BreatheVoice.SLEEP_OFF); mode = Mode.MENU }
        }
    }

    private fun begin() {
        host.stopSpeech(); host.pauseMusic()
        mode = Mode.BREATHING; round = 1; phase = BreathePhase.IN
        Trace.ev("program", "breathe begin $rounds")
        host.speak(BreatheVoice.BEGIN)
        host.screen(listOf("Breathe", "$rounds rounds"), 0)
        host.later(firstBreath, BEGIN_MS)
    }

    private fun breath() {
        sounds?.get(phase)?.let(play)
        host.later(nextPhase, phase.ms)
    }

    private fun advance() {
        phase = when (phase) {
            BreathePhase.IN -> BreathePhase.HOLD
            BreathePhase.HOLD -> BreathePhase.OUT
            BreathePhase.OUT -> { round++; BreathePhase.IN }
        }
        if (round > rounds) {
            Trace.ev("program", "breathe done $rounds")
            beep(Tone.PICK); host.speak(BreatheVoice.DONE); mode = Mode.MENU
            host.later(leave, END_MS)
            return
        }
        breath()
    }

    private fun sleep() {
        val v = volume(host).also { vol = it }
        startVolume = v.get()
        if (startVolume <= 0) { host.speak(BreatheVoice.NO_MUSIC); return }
        mode = Mode.SLEEP
        Trace.ev("program", "breathe sleep from $startVolume")
        host.speak(BreatheVoice.SLEEP)
        host.later(fadeStep, BreatheCore.fadeStepMs(startVolume))
    }

    private fun fade() {
        val v = vol ?: return
        val now = v.get() - 1
        if (now <= 0) {
            host.pauseMusic(); slept = true
            v.set(startVolume)   // the music is paused: bring the volume back for the next song
            Trace.ev("program", "breathe sleep done")
            mode = Mode.MENU
            host.later(leave, END_MS)
            return
        }
        v.set(now)
        host.later(fadeStep, BreatheCore.fadeStepMs(startVolume))
    }

    private fun stopSleep() { host.cancel(fadeStep); vol?.let { if (startVolume > 0) it.set(startVolume) } }

    private fun finish() {
        if (!running) return
        if (mode == Mode.SLEEP) stopSleep()
        running = false
        host.cancel(leave); host.cancel(resolve); host.cancel(nextPhase); host.cancel(fadeStep); host.cancel(firstBreath)
        host.stopSpeech()
        if (!slept) host.resumeMusic()   // after the sleep timer the music stays paused
        host.ended()
    }

    private companion object { const val BEGIN_MS = 3000L; const val END_MS = 2000L }
}

object BreatheEntry {
    val entry = ProgramEntry("hub:breathe", BreatheVoice.TITLE) { BreatheProgram() }
}
