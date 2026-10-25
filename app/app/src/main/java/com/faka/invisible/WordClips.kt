package com.faka.invisible

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Spoken words as PCM, made by the TTS engine in the background and kept in memory, so two words can be joined into
 * one stereo sound: first the left ear, then the right ear. The right voice is the same clip played lower and slower, so the ears can tell them apart.
 */
class WordClips(private val dir: File, private val synth: (text: String, file: File, id: String) -> Boolean, private val post: (() -> Unit) -> Unit) {
    private val mem = ConcurrentHashMap<String, Spearcon.Pcm>()
    private val waiting = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    private fun key(word: String) = word.filter { it.isLetter() || it == '\'' }.lowercase()

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
            Spearcon.readWav(f.readBytes())?.let { mem[k] = Spearcon.Pcm(it.rate, trim(it.samples)) }
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

    /** Interleaved stereo and its sample rate: the left word in the left ear, a short gap, then the right word in the right ear (lower voice). Null if the left clip is missing. */
    fun stereo(left: String, right: String?): Pair<ShortArray, Int>? {
        val l = mem[key(left)] ?: return null
        val rr = right?.let { mem[key(it)] }?.let { lower(it.samples) }
        val gap = l.rate * 150 / 1000
        val frames = l.samples.size + (if (rr != null) gap + rr.size else 0)
        val out = ShortArray(frames * 2)
        for (i in l.samples.indices) out[2 * i] = l.samples[i]
        if (rr != null) for (i in rr.indices) out[2 * (l.samples.size + gap + i) + 1] = rr[i]
        return out to l.rate
    }

    /** Lower and slower by 15%: linear resampling, same sample rate on playback. */
    private fun lower(x: ShortArray, f: Double = 0.85): ShortArray {
        val n = (x.size / f).toInt()
        return ShortArray(n) { i ->
            val p = i * f; val j = p.toInt(); val t = p - j
            val a = x[minOf(j, x.size - 1)].toInt(); val b = x[minOf(j + 1, x.size - 1)].toInt()
            (a + (b - a) * t).toInt().toShort()
        }
    }
}
