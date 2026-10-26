@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.Context
import android.media.AudioTrack
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ENGINE. The only place that speaks: the TTS engine, the voice language, the speech rate, and the spearcon and word-clip
 * files that the TTS engine makes. [rate] gives the normal speech rate (it grows with use).
 */
private val LETTER_LABEL = Regex("[A-Z0-9][a-z0-9]?( to [A-Z0-9][a-z0-9]?)?")

class TtsSpeech(
    private val app: Context,
    private val handler: Handler,
    private val tones: TonePlayer,
    private val rate: () -> Float,
    private val listener: SpeechListener,
) : SpeechPort {
    private val tag = "Invisible"
    private companion object {
        /** Recorded clips are OFF: a recorded voice for fixed words and the phone's voice for the rest sounded like two people. One voice for everything. */
        const val CLIPS = false
        /** The on-phone Piper voice (NeuralSpeech) is OFF: it sounded cheap and took 10 s to load at start. The phone's own voice is used, the best installed one (see [bestVoice]). */
        const val NEURAL = false
    }
    /** The one neural voice (English and Spanish). The Google voice below speaks only what it cannot: other languages, or a model not downloaded yet. */
    val neural = NeuralSpeech(app, handler, rate, listener) { p, q, slow -> googleSpeak(p, q, false, slow) }
    private var tts: TextToSpeech? = null
    private var ready = false
    private var uttSeq = 0
    private var lastUtt = ""
    private var spTrack: AudioTrack? = null

    override val words: WordClips = WordClips(File(app.cacheDir, "words"), { text, f, id ->
        setVoice(null); tts?.setSpeechRate(Pacing.WORD_RATE); tts?.synthesizeToFile(text, null, f, id) == TextToSpeech.SUCCESS
    }, { handler.post(it) })

    override val speaking get() = tts?.isSpeaking == true || spTrack != null || neural.speaking

    private var startTries = 0

    fun start() {
        if (NEURAL && startTries == 0) neural.start()
        tts = TextToSpeech(app) { s ->
            if (s != TextToSpeech.SUCCESS) {   // the engine did not bind in time (seen after an install): the app stayed silent for good. Try again.
                Log.d(tag, "TTS init failed ($s), try ${startTries + 1}")
                tts?.shutdown(); tts = null
                if (startTries++ < 20) handler.postDelayed({ start() }, 3000)
            } else {
                startTries = 0
                tts?.language = Locale.US; ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (id == lastUtt) handler.post { listener.started() } }
                    override fun onDone(id: String?) { if (id?.startsWith("wc_") == true) words.made(id) else if (id?.startsWith("sy_") == true) spearconMade(id.removePrefix("sy_")) else ended(id) }
                    @Deprecated("old api") override fun onError(id: String?) { ended(id) }
                    override fun onStop(id: String?, interrupted: Boolean) {}   // flushed by a newer utterance or a click
                })
            }
        }
    }

    override fun stop() { neural.stop(); tts?.stop(); stopClips(); spTrack?.let { try { it.stop() } catch (e: Exception) {} }; spTrack = null; lastUtt = "" }

    /** Only the newest utterance counts: older ones were flushed. Tells the listener the speech is over. */
    private fun ended(id: String?) { if (id == lastUtt) handler.post { listener.ended() } }

    /** Sets the voice language for the next utterance; falls back to the default locale if the voice is missing. */
    private fun setVoice(lang: String?) {
        val t = tts ?: return
        val want = if (lang == null) Locale.US else Locale(lang)
        val r = t.isLanguageAvailable(want)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(tag, "TTS voice $lang missing, default"); t.language = Locale.getDefault()
        } else t.language = want
        bestVoice(t, want)?.let { if (t.voice?.name != it.name) { Log.d(tag, "TTS voice ${it.name} q=${it.quality}"); t.voice = it } }
    }

    /** The best voice installed for the language: highest quality, then lowest latency; never one that needs the network (it would stall). */
    private fun bestVoice(t: TextToSpeech, want: Locale): android.speech.tts.Voice? = try {
        t.voices.filter { it.locale.language == want.language && !it.isNetworkConnectionRequired && "notInstalled" !in it.features }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency }).firstOrNull()
    } catch (e: Exception) { null }

    override fun speak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) {
        if (NEURAL && !fast && neural.handles(p)) {   // one voice: the neural one, whenever its model is there
            if (!queue) { tts?.stop(); stopClips() }
            lastUtt = ""   // Google events of older speech no longer count
            neural.speak(p, queue, slow); return
        }
        googleSpeak(p, queue, fast, slow)
    }

    private fun googleSpeak(p: Phrase, queue: Boolean, fast: Boolean, slow: Boolean) {
        if (!queue) neural.stop()
        val text = p.text; val lang = p.lang
        Log.d(tag, "SPEAK[$lang] $text ready=$ready fast=$fast")
        if (!ready) { handler.post { listener.ended() }; return }
        if (CLIPS && p.isFixed && !fast && sayClip(p, queue)) return   // the recorded voice; free text (messages, tasks) always uses the live voice
        if (!queue) stopClips()
        val spKey = Spearcon.key(text, lang)
        if (fast) spearcon(spKey)?.let { sp ->
            tts?.stop(); stopClips(); lastUtt = "inv${++uttSeq}"; val me = lastUtt
            handler.post { listener.started() }
            spTrack = tones.play(sp.samples, sp.rate) { if (lastUtt == me) { spTrack = null; listener.ended() } }
            return
        }
        setVoice(lang)
        tts?.setSpeechRate(if (slow) Pacing.COACH_RATE else rate())   // the Duel coach talks slowly; every other call sets its own rate again
        lastUtt = "inv${++uttSeq}"
        if (fast) { val k = spKey; val l = lang; handler.postDelayed({ makeSpearcon(k, text, l) }, 4000) }   // after the word, not during it
        tts?.speak(letters(text), if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, lastUtt)
    }

    /** A Find letter label ("A to F", "C", "Wa to We"): each lone letter is marked to be spelled, so "A" is the letter "ay", not the word "a" ("uh"). Other text is unchanged. */
    private fun letters(text: String): CharSequence {
        if (!LETTER_LABEL.matches(text)) return text
        val s = android.text.SpannableString(text)
        Regex("\\b[A-Z0-9][a-z0-9]?\\b").findAll(text).forEach { m -> s.setSpan(android.text.style.TtsSpan.VerbatimBuilder(m.value).build(), m.range.first, m.range.last + 1, 0) }
        return s
    }

    // ---- the recorded voice (ClipBank): clips of one text play one after the other; a new non-queued phrase replaces them ----
    private val clipQueue = ArrayDeque<Pair<String, Spearcon.Pcm>>()
    private var clipSeq = 0

    private fun stopClips() { clipSeq++; clipQueue.clear(); spTrack?.let { try { it.stop() } catch (e: Exception) {} }; spTrack = null }

    /** True if the fixed [p] was played from clips (all of it); false = the phone's voice must say it. */
    private fun sayClip(p: Phrase, queue: Boolean): Boolean {
        val pcm = ClipBank.load(app, p.text, p.lang)
        if (pcm == null) { if (ClipBank.firstMiss(p.text, p.lang)) try { File(app.filesDir, "spoken_miss.log").appendText("${p.lang ?: "en"}\t${p.text}\n") } catch (e: Exception) {}; return false }
        if (!queue) { tts?.stop(); stopClips() }
        lastUtt = "inv${++uttSeq}"
        clipQueue.addLast(lastUtt to pcm)
        if (spTrack == null) nextClip()
        return true
    }

    private fun nextClip() {
        val (id, pcm) = clipQueue.removeFirstOrNull() ?: return
        val me = ++clipSeq
        if (id == lastUtt) handler.post { listener.started() }
        spTrack = tones.play(pcm.samples, pcm.rate) { if (me == clipSeq) { spTrack = null; if (clipQueue.isEmpty()) { if (id == lastUtt) handler.post { listener.ended() } } else nextClip() } }
    }

    // ---- spearcons: an option picked often is scanned as its sped-up spoken word ----
    private val spMem = ConcurrentHashMap<String, Spearcon.Pcm>()
    private val spWant = ConcurrentHashMap<String, Pair<String, String?>>()   // key -> (text, lang) being made
    private fun spDir() = File(app.cacheDir, "spearcons").apply { mkdirs() }

    private fun spearcon(key: String): Spearcon.Pcm? = spMem[key] ?: try {
        val f = File(spDir(), "$key.pcm")
        if (!f.exists()) null else {
            val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rate = bb.getInt(); Spearcon.Pcm(rate, ShortArray((bb.remaining()) / 2) { bb.getShort() }).also { spMem[key] = it }
        }
    } catch (e: Exception) { null }

    /** Called after the full word was spoken once: ask the TTS engine for a file of it (the next scan can use it). */
    private fun makeSpearcon(key: String, text: String, lang: String?) {
        if (spWant.putIfAbsent(key, text to lang) != null) return
        val r = tts?.synthesizeToFile(text, null, File(spDir(), "$key.wav"), "sy_$key")
        if (r != TextToSpeech.SUCCESS) spWant.remove(key)
    }

    /** Runs on a TTS thread: wav -> compressed PCM, kept in memory and on disk. */
    private fun spearconMade(key: String) {
        try {
            val w = File(spDir(), "$key.wav")
            val pcm = Spearcon.readWav(w.readBytes()) ?: return
            val sp = Spearcon.Pcm(pcm.rate, Spearcon.compress(pcm.samples, pcm.rate))
            val bb = java.nio.ByteBuffer.allocate(4 + sp.samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(sp.rate); sp.samples.forEach { bb.putShort(it) }
            File(spDir(), "$key.pcm").writeBytes(bb.array()); w.delete(); spMem[key] = sp
            Log.d(tag, "SPEARCON made $key ${pcm.samples.size} -> ${sp.samples.size}")
        } catch (e: Exception) { Log.e(tag, "spearcon failed", e) } finally { spWant.remove(key) }
    }
}
