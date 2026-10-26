@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.os.Handler

/**
 * ORGANISM. Everything a screen or a program can say or play: sentences, earcons, typing cues, and the stereo word pair
 * (left ear then right ear). It owns no Android audio itself; it composes a [SpeechPort] and a [TonePort].
 */
class Narrator(private val speech: SpeechPort, private val tones: TonePort, private val handler: Handler) {
    private var pairSeq = 0
    /** True = the first word of a pair plays on the right (these earbuds play the channels reversed). The app sets it from its option. */
    var swapEars: () -> Boolean = { true }

    fun say(p: Phrase, queue: Boolean = false, fast: Boolean = false, slow: Boolean = false) = speech.speak(p, queue, fast, slow)
    fun stopSpeech() = speech.stop()
    val speaking get() = speech.speaking

    fun beep(k: Tone, vol: Float = 1f) = tones.beep(k, vol)
    fun cue(c: TypeCue) = tones.cue(c)
    fun findLoop(on: Boolean) = tones.findLoop(on)

    /** Two words as one stereo sound. Both clips are made first; the sentence being spoken is never cut. */
    fun pair(left: String, right: String?) {
        hush(); val me = pairSeq
        android.util.Log.d("Invisible", "PAIR ask $left | $right")
        var pending = if (right == null) 1 else 2
        val one = { if (--pending == 0) playPair(left, right, me) }
        speech.words.ensure(left, one); right?.let { speech.words.ensure(it, one) }
    }

    /** Stops a pair that is playing or still being made. */
    fun hush() { pairSeq++; tones.stopStereo() }

    /** Makes the clips of [words] ahead of time. */
    fun prefetch(words: List<String>) { words.forEach { speech.words.ensure(it) {} } }

    private fun playPair(left: String, right: String?, me: Int) {
        android.util.Log.d("Invisible", "PAIR play $left | $right me=$me seq=$pairSeq speaking=${speech.speaking}")
        if (me != pairSeq) return
        if (speech.speaking) { handler.postDelayed({ playPair(left, right, me) }, 300); return }   // the sentence first
        val (pcm, rate) = speech.words.stereo(left, right, Pacing.PAIR_GAP_MS, swapEars()) ?: run { say(Phrase.content(left)); return }
        tones.playStereo(pcm, rate)
    }
}
