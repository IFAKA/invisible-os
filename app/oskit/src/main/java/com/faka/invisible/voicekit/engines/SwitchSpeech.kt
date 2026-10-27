@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

/**
 * ENGINE. A door in front of the real speech: normally it only passes every call on. A debug build (the e2e test hook) can put a
 * stand-in in [alt] to speak instantly; release code never sets it.
 */
class SwitchSpeech(private val real: SpeechPort) : SpeechPort {
    @Volatile var alt: SpeechPort? = null
        set(v) { field?.stop(); real.stop(); field = v }
    private val now get() = alt ?: real
    override fun speak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) = now.speak(p, queue, fast, slow)
    override fun stop() = now.stop()
    override val speaking get() = now.speaking
    override val words: WordClips get() = real.words
}
