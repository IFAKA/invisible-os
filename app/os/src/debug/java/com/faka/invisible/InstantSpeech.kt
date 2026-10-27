package com.faka.invisible

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.SpeechListener
import com.faka.invisible.voicekit.SpeechPort
import com.faka.invisible.voicekit.WordClips

/**
 * Debug build only (e2e test: `--es voice instant`). Stands in for the TTS engine: logs the same SPEAK line, then reports started and ended
 * through the same listener, on the main thread, one post later, so the menu goes on without waiting for a spoken sentence.
 * Same rules as the engine: only the newest line counts; a stop or a newer non-queued line cancels the older one.
 */
class InstantSpeech(override val words: WordClips, private val rate: () -> Float, private val listener: SpeechListener) : SpeechPort {
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0
    @Volatile private var open = false
    override val speaking get() = open

    override fun speak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) {
        Log.d("Invisible", "SPEAK[${p.lang}] ${p.text} ready=true fast=$fast rate=${if (slow) Pacing.COACH_RATE else rate()}")
        val me = ++seq; open = true
        handler.post { if (me == seq) listener.started() }
        handler.post { if (me == seq) { open = false; listener.ended() } }
    }

    override fun stop() { seq++; open = false }
}
