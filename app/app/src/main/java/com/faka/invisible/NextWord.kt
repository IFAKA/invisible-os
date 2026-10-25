package com.faka.invisible

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Tiny next-word LSTM (Utter ml/utter/lm.py, int8 `lm.bin` "ULM1" + `vocab.txt`). Ranks the words that may come next. */
class NextWord(bin: InputStream, vocabText: String) : Ranker {
    val V: Int; val E: Int; val H: Int
    private val vocab: List<String>
    private val ids: Map<String, Int>
    private val emb: FloatArray; private val wih: FloatArray; private val whh: FloatArray
    private val b: FloatArray; private val proj: FloatArray; private val bias: FloatArray
    private val states = HashMap<String, Pair<FloatArray, FloatArray>>()

    init {
        val d = ByteBuffer.wrap(bin.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { d.get(it) }
        require(String(magic) == "ULM1") { "not an ULM1 model" }
        V = d.int; E = d.int; H = d.int
        fun f32s(n: Int) = FloatArray(n) { d.float }
        fun q8(): Triple<FloatArray, Int, Int> {
            val rows = d.int; val cols = d.int
            val s = f32s(rows)
            val w = FloatArray(rows * cols)
            for (r in 0 until rows) for (c in 0 until cols) w[r * cols + c] = d.get().toFloat() * s[r]
            return Triple(w, rows, cols)
        }
        emb = q8().first; wih = q8().first; whh = q8().first
        b = f32s(d.int)
        proj = q8().first
        bias = f32s(d.int)
        vocab = vocabText.split("\n").take(V)
        ids = HashMap<String, Int>().also { m -> vocab.forEachIndexed { i, w -> m.putIfAbsent(w, i) } }
        require(vocab.size == V && emb.size == V * E && bias.size == V) { "model and vocab do not match" }
    }

    private fun gemv(w: FloatArray, rows: Int, cols: Int, x: FloatArray): FloatArray {
        val y = FloatArray(rows)
        for (r in 0 until rows) { var s = 0f; val o = r * cols; for (c in 0 until cols) s += w[o + c] * x[c]; y[r] = s }
        return y
    }

    private fun sig(v: Float) = 1f / (1f + exp(-v))

    private fun step(id: Int, h0: FloatArray, c0: FloatArray): Pair<FloatArray, FloatArray> {
        val g = gemv(wih, 4 * H, E, emb.copyOfRange(id * E, (id + 1) * E))
        val r = gemv(whh, 4 * H, H, h0)
        for (k in 0 until 4 * H) g[k] += r[k] + b[k]
        val h = FloatArray(H); val c = FloatArray(H)
        for (k in 0 until H) { // gate order i, f, g, o
            c[k] = sig(g[H + k]) * c0[k] + sig(g[k]) * tanh(g[2 * H + k])
            h[k] = sig(g[3 * H + k]) * tanh(c[k])
        }
        return h to c
    }

    private fun state(words: List<String>): Pair<FloatArray, FloatArray> {
        val key = words.joinToString(" ")
        states[key]?.let { return it }
        val s = if (words.isEmpty()) step(1, FloatArray(H), FloatArray(H))
        else state(words.dropLast(1)).let { step(ids[words.last()] ?: 2, it.first, it.second) }
        if (states.size > 2000) states.clear()
        states[key] = s
        return s
    }

    private var rankedKey: String? = null
    private var rankedAll: List<Pair<String, Double>> = emptyList()

    /** Every word, most likely first, for [context]. Kept for the last context, so paging and letter views cost nothing. */
    override fun ranked(context: List<String>): List<Pair<String, Double>> {
        val key = context.joinToString(" ")
        if (key != rankedKey) { rankedAll = top(context, V - 3); rankedKey = key }
        return rankedAll
    }

    /** The [n] most likely next words after [context] (lowercase words), best first, with log probability. */
    fun top(context: List<String>, n: Int): List<Pair<String, Double>> {
        val p = gemv(proj, E, H, state(context).first)
        val logits = gemv(emb, V, E, p)
        for (i in 0 until V) logits[i] += bias[i]
        val mx = logits.max()
        var sum = 0.0
        for (v in logits) sum += exp((v - mx).toDouble())
        val lse = ln(sum) + mx
        return (3 until V).sortedByDescending { logits[it] }.take(n).map { vocab[it] to (logits[it] - lse).toDouble() }
    }

    companion object {
        fun words(text: String): List<String> = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value }.toList()
    }
}
