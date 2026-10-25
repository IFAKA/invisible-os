package com.faka.invisible.voicekit

/** Keyed one-shot timers (injected so the kit and the screens are testable). Setting a key replaces the old timer with that key. */
interface Timers {
    fun set(key: String, delayMs: Long, task: () -> Unit)
    fun clear(key: String)
}

/** What can be spoken. The only implementation that touches Android is `TtsSpeech` (engines). */
interface SpeechPort {
    /** [fast] = play the sped-up spearcon of the option if there is one; [slow] = the coach rate. */
    fun speak(p: Phrase, queue: Boolean = false, fast: Boolean = false, slow: Boolean = false)
    fun stop()
    val speaking: Boolean
    /** Spoken words kept as sound, to join two words into one stereo sound. */
    val words: WordClips
}

/** What can be played. The only implementation that touches Android is `TonePlayer` (engines). */
interface TonePort {
    fun beep(k: Tone, vol: Float = 1f)
    fun cue(c: TypeCue)
    /** Two spoken words as one stereo sound: left ear then right ear. */
    fun playStereo(pcm: ShortArray, rate: Int)
    fun stopStereo()
    /** The find-my-phone alarm: loops loud until switched off. */
    fun findLoop(on: Boolean)
}

/** Where the one button arrives. `ButtonInput` implements it; the engines that read the hardware only call this. */
interface ButtonPort {
    fun key(source: String, isDown: Boolean, repeat: Int, downTime: Long, time: Long)
}

/** What the speech engine tells whoever listens. Called on the main thread. */
interface SpeechListener {
    fun started()
    fun ended()
}

/** A program (the Duel, Write) that gets the raw button while it runs: no clicks, only down and up times. */
interface RawButton {
    val active: Boolean
    fun down(time: Long)
    fun up(time: Long)
}

/** What the button feeds. The OS (Actions) implements it; the kit does the rest. */
interface ButtonTarget {
    /** The program that owns the raw button right now, or null. */
    fun raw(): RawButton?
    /** True when the menu is on: every press is answered with a tap tone at once. */
    val menuActive: Boolean
    /** A finished gesture. The menu acts on it and plays its own tones. */
    fun gesture(g: Gesture, durationMs: Long)
}
