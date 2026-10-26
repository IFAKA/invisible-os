package com.faka.invisible

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Spoken words as PCM, made by the TTS engine in the background and kept in memory, so two words can be joined into
 * one stereo sound: first the left ear, then the right ear.
 */
class WordClips(private val dir: File, private val synth: (text: String, file: File, id: String) -> Boolean, private val post: (() -> Unit) -> Unit) {
    private val mem = ConcurrentHashMap<String, Spearcon.Pcm>()
    private val waiting = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    private fun key(word: String) = word.filter { it.isLetterOrDigit() || it == '\'' }.lowercase()

    /** Main thread: [then] runs on the main thread when the clip of [word] is ready (at once if it is). */
    fun ensure(word: String, then: () -> Unit) {
        val k = key(word)
        if (mem.containsKey(k)) { then(); return }
        val first = !waiting.containsKey(k)
        waiting.getOrPut(k) { ArrayList() }.add(then)
        if (first && !synth(word, File(dir.apply { mkdirs() }, "w_$k.wav"), "wc_$k")) { waiting.remove(k)?.forEach { it() } }
    }

    /** TTS thread: the file of [id] ("wc_" + key) is written. */
    fun made(id: String) {
        val k = id.removePrefix("wc_")
        try {
            val f = File(dir, "w_$k.wav")
            Spearcon.readWav(f.readBytes())?.let { mem[k] = Spearcon.Pcm(it.rate, loud(trim(it.samples))) }
            f.delete()
        } catch (e: Exception) { /* the waiters still run: the pair is played with what exists */ }
        post { waiting.remove(k)?.forEach { it() } }
    }

    private fun trim(x: ShortArray, thr: Int = 500): ShortArray {
        var a = 0; var b = x.size
        while (a < b && Math.abs(x[a].toInt()) < thr) a++
        while (b > a && Math.abs(x[b - 1].toInt()) < thr) b--
        return x.copyOfRange(a, b)
    }

    /** Peak to about 75% of full scale, so every word is equally clear and loud. */
    private fun loud(x: ShortArray): ShortArray {
        val peak = x.maxOfOrNull { Math.abs(it.toInt()) } ?: return x
        if (peak < 100) return x
        val g = 24500.0 / peak
        return ShortArray(x.size) { (x[it] * g).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** Interleaved stereo and its sample rate: the first word in the left ear, a gap, then the second word in the right ear. Same voice for both. Null if the first clip is missing. */
    /** The first word goes to the left channel, the second to the right. [swap]: for earbuds that play the channels the other way round. */
    fun stereo(left: String, right: String?, gapMs: Int, swap: Boolean = false): Pair<ShortArray, Int>? {
        val l = mem[key(left)] ?: return null
        val r = right?.let { mem[key(it)] }?.samples
        val gap = l.rate * gapMs / 1000
        val frames = l.samples.size + (if (r != null) gap + r.size else 0)
        val out = ShortArray(frames * 2)
        val a = if (swap) 1 else 0
        for (i in l.samples.indices) out[2 * i + a] = l.samples[i]
        if (r != null) for (i in r.indices) out[2 * (l.samples.size + gap + i) + 1 - a] = r[i]
        return out to l.rate
    }
}
