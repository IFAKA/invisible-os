package com.faka.invisible.logickit

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Ported from Nomon (GPL v3, Broderick & MacKay; Bonaker et al.). See NOMON-LICENSE.md.
 * Sources (Nomon-Simulation, github.com/nbonaker/Nomon-Simulation):
 *   Nomon_Core/clock_inference_engine.py  KernelDensityEstimation, ClockInference
 *   Nomon_Core/broderclocks.py            select(), init_round()
 *   Nomon_Core/config.py                  constants
 *   Nomon_Text/keyboard.py                win_diffs
 *
 * What it does. Every item has a time ("noon") inside a repeating cycle: for us the moment it is SPOKEN in an
 * audio loop (Nomon: where the clock hand is at noon). A click at time t gives each item i an offset
 * y_i = t - noon_i (wrapped into one cycle). A learned density of "how far after noon this person clicks" (a
 * kernel density estimate over 80 bins, damped so old clicks fade) turns each offset into a log score. The scores
 * add up over clicks, starting from log prior (Nomon: from the language model). Posterior = softmax of the scores.
 * An item is chosen when the best score beats the second best by more than log 99 (about 99 to 1).
 * Nomon uses NO negative evidence (not clicking changes nothing), so there is no noClickFor.
 * After a choice the clicks are learned into the density with a delay of 2 choices so that undo() can cancel them.
 *
 * Units: the API is milliseconds; the density works in seconds exactly as Nomon (mu0, sigma0 are seconds).
 */
class ClickModel<K>(
    /** Length of the repeating cycle in ms (Nomon time_rotate, default 6*e^(-6/10) = 3.29 s, config.py:42). */
    val periodMs: Double,
    val params: Params = Params(),
) {
    /** Nomon constants. Each line: where it comes from (Nomon-Simulation Nomon_Core). */
    data class Params(
        /** Bins of the click density (config.py:49 num_divs_click). */
        val bins: Int = 80,
        /** Prior mean of the click offset in s (config.py:77 mu0). */
        val mu0: Double = 0.05,
        /** Prior spread in s (config.py:78 sigma0). */
        val sigma0: Double = 0.14,
        /** Old clicks fade by this factor per new click (clock_inference_engine.py:65 damp). */
        val damp: Double = 0.96,
        /** A win needs this score gap (config.py:63 win_diff_base = ln 99). */
        val winDiff: Double = ln(99.0),
        /** Higher gap for risky items such as "undo" (config.py:64 win_diff_high = ln 999). Per item via winDiffs. */
        val winDiffHigh: Double = ln(999.0),
        /** At the start of a round the leader may lead by at most this (config.py:65 max_init_diff = ln 99 - ln 4). */
        val maxInitDiff: Double = ln(99.0) - ln(4.0),
        /** A choice is learned this many choices later, so undo can cancel it (config.py:74 learn_delay). */
        val learnDelay: Int = 2,
        /** Learned offsets are wrapped into [-w/2, w/2) seconds (clock_inference_engine.py:247 "% 1 ... - 0.5": w = 1 s). */
        val learnWrapS: Double = 1.0,
        /** Floor for a prior of 0 (mine: ln 0 is minus infinity). */
        val minPrior: Double = 1e-9,
    )

    private val bins = params.bins
    private val nKsigma = max(5, (1.0 / (1 - params.damp)).toInt())               // :66
    private val nsFactor = 1.06 / nKsigma.toDouble().pow(0.2)                     // :67
    private val nHist = min(200, (ln(0.02) / ln(params.damp)).toInt())            // :162 (accuracy 0.02)
    private val periodS = periodMs / 1000.0
    private val xs = DoubleArray(bins) { it * periodS / bins - periodS / 2.0 }    // :73
    private val dens = DoubleArray(bins)
    private var z = 0.0
    private val ksigma0 = 1.06 * params.sigma0 / nKsigma.toDouble().pow(0.2)      // :90
    private var ksigma = ksigma0
    private val ys = ArrayList<Double>()   // newest first (y_li)
    private val yk = ArrayList<Double>()   // bandwidth used for each (y_ksigma)

    // one round
    private var keys: List<K> = emptyList()
    private var noonS: DoubleArray = DoubleArray(0)
    private var gate: DoubleArray = DoubleArray(0)
    private var scores: DoubleArray = DoubleArray(0)
    private var startMs = 0.0
    private var sorted: List<Int> = emptyList()

    // history of rounds, newest first: per click the learnable offset of every item (clock_history / win_history)
    private val clockHistory = ArrayList<MutableList<Map<K, Double>>>()
    private val winHistory = ArrayList<K?>()

    init {
        initDens()
        clockHistory.add(mutableListOf()); winHistory.add(null)
    }

    private fun initDens() {                                                     // :79
        z = 0.0
        for (i in 0 until bins) {
            val diff = xs[i] - params.mu0
            var d = exp(-1 / (2 * params.sigma0 * params.sigma0) * diff * diff)
            d /= sqrt(2 * PI * params.sigma0 * params.sigma0)
            d *= nKsigma
            dens[i] = d
            z += d
        }
    }

    private fun incrementDens(yin: Double, ks: Double) {                         // :121
        z = 0.0
        val ksSq = ks * ks
        for (i in 0 until bins) {
            val diff = xs[i] - yin
            var d = exp(-1 / (2 * ksSq) * diff * diff)
            d /= sqrt(2 * PI * ksSq)
            dens[i] = params.damp * dens[i] + d
            z += dens[i]
        }
    }

    private fun calcKsigma(yLenEff: Int) {                                       // :101, :112
        var ysum = 0.0
        var y2sum = 0.0
        for (i in 0 until yLenEff) { ysum += ys[i]; y2sum += ys[i] * ys[i] }
        var a = (nKsigma - yLenEff) * ksigma0 * ksigma0
        if (yLenEff > 0) a += y2sum - ysum * ysum / yLenEff
        a /= nKsigma
        ksigma = nsFactor * sqrt(max(0.0001, a))
    }

    /** Learn one click offset (seconds) into the density (inc_score_inc, :193). */
    private fun incScore(yin: Double) {
        if (ys.size > nHist) { ys.removeAt(ys.size - 1); yk.removeAt(yk.size - 1) }
        ys.add(0, yin); yk.add(0, ksigma)
        incrementDens(yin, ksigma)
        calcKsigma(min(nKsigma, ys.size))
    }

    private fun scoreInc(yS: Double): Double {                                   // get_score_inc, :179
        val idx = Math.floorMod(floor(bins * (yS / periodS + 0.5)).toInt(), bins)
        return if (z != 0.0) ln(dens[idx] / z) else 1.0
    }

    /** Offset of a click from item i's noon, seconds, wrapped into [-T/2, T/2). */
    private fun offsetS(i: Int, atMs: Double): Double {
        val y = (atMs - startMs) / 1000.0 - noonS[i]
        return wrap(y + periodS / 2, periodS) - periodS / 2
    }

    /**
     * Start a round (Nomon init_round with is_start/is_win). [priors] = probability of each item (any scale; only
     * ratios matter); [timesMs] = the noon of each item inside the cycle (ms after [cycleStartMs]); [winDiffs]
     * optionally a bigger gap for some items (Nomon: undo/back keys use ln 999).
     */
    fun start(
        priors: Map<K, Double>,
        timesMs: Map<K, Double>,
        cycleStartMs: Double = 0.0,
        winDiffs: Map<K, Double> = emptyMap(),
    ) {
        keys = priors.keys.toList()
        noonS = DoubleArray(keys.size) { (timesMs[keys[it]] ?: 0.0) / 1000.0 }
        gate = DoubleArray(keys.size) { winDiffs[keys[it]] ?: params.winDiff }
        scores = DoubleArray(keys.size) { ln(max(priors.getValue(keys[it]), params.minPrior)) }
        startMs = cycleStartMs
        // As Nomon: clicks of a round that did not win stay in the history and join the next choice's learning.
        sort()
        if (keys.size > 1 && scores[sorted[0]] - scores[sorted[1]] > params.maxInitDiff) {   // handicap_cscores, :303
            scores[sorted[0]] = scores[sorted[1]] + params.maxInitDiff
        }
    }

    private fun sort() { sorted = keys.indices.sortedByDescending { scores[it] } }      // update_sorted_inds, :258

    /** A click at [atMs] (same clock as cycleStartMs). Returns true if an item is now chosen (is_winner, :263). */
    fun click(atMs: Double): Boolean {
        if (keys.isEmpty()) return false
        val hist = HashMap<K, Double>()
        for (i in keys.indices) {                                                // update_scores, :216
            val y = offsetS(i, atMs)
            scores[i] += scoreInc(y)
            val w = params.learnWrapS                                            // update_history, :247
            hist[keys[i]] = wrap(y + w / 2, w) - w / 2
        }
        clockHistory[0].add(hist)
        sort()
        return chosen() != null
    }

    /** Probability of each item now (softmax of the scores). */
    fun posteriors(): Map<K, Double> {
        if (keys.isEmpty()) return emptyMap()
        val m = scores.max()
        val e = DoubleArray(keys.size) { exp(scores[it] - m) }
        val s = e.sum()
        return keys.indices.associate { keys[it] to e[it] / s }
    }

    /** The most likely item, or null if no round is on. */
    fun best(): K? = sorted.firstOrNull()?.let { keys[it] }

    /** The chosen item when the best leads by more than its gap, else null. One item is chosen at once. */
    fun chosen(): K? {
        if (keys.isEmpty()) return null
        if (keys.size <= 1) return keys[0]
        val a = sorted[0]
        return if (scores[a] - scores[sorted[1]] > gate[a]) keys[a] else null
    }

    /**
     * The person accepted [key] (normally chosen()): learn from this round (win_history[0] = key; learn_scores, :272).
     * The clicks of the choice before are learned now (delay 2). Call start() for the next round afterwards.
     */
    fun learn(key: K) {
        winHistory[0] = key
        learnScores(false)
    }

    /** The person undid the last choice: its clicks and the undo's own are dropped, not learned (:274). */
    fun undo() = learnScores(true)

    private fun learnScores(isUndo: Boolean) {
        val n = clockHistory.size
        if (isUndo) {
            if (n > 1) { clockHistory.subList(0, 2).clear(); winHistory.subList(0, 2).clear() }
            else { clockHistory.clear(); winHistory.clear() }
        } else if (n > params.learnDelay) {
            val sel = n - params.learnDelay
            val win = winHistory[sel]
            if (win != null) for (press in clockHistory[sel]) press[win]?.let { incScore(it) }
            for (i in n - 1 downTo params.learnDelay) { clockHistory.removeAt(i); winHistory.removeAt(i) }
        }
        clockHistory.add(0, mutableListOf()); winHistory.add(0, null)
    }
}

/** a modulo m, always in [0, m) (Python's %). */
private fun wrap(a: Double, m: Double): Double = a - floor(a / m) * m
