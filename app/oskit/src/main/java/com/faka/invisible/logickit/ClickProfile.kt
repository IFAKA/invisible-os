package com.faka.invisible.logickit

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How late (or early) one person clicks after the sound they wait for: the mean and the spread in ms, how many clicks
 * were measured, and the click density in the form [ClickModel] uses (80 bins over one cycle of [periodMs], offsets in
 * seconds, sum 1). Made by the Click test tool, saved under [KEY] in the app prefs, read by whoever scans with one click.
 * Pure: no Android (the JSON is written by hand so it works in a plain JVM test).
 */
data class ClickProfile(
    val meanMs: Double,
    val sdMs: Double,
    val count: Int,
    val periodMs: Double = DEFAULT_PERIOD_MS,
    val hist: List<Double> = emptyList(),
) {
    /** Nomon's start values with this person's: mean and spread of the click offset (seconds). Use it as `ClickModel(period, profile.params())`. */
    fun params(base: ClickModel.Params = ClickModel.Params()): ClickModel.Params =
        if (count < MIN_COUNT) base else base.copy(mu0 = meanMs / 1000.0, sigma0 = (sdMs / 1000.0).coerceIn(MIN_SD_S, MAX_SD_S))

    fun <K> model(periodMs: Double): ClickModel<K> = ClickModel(periodMs, params())

    fun toJson(): String =
        "{\"meanMs\":${num(meanMs)},\"sdMs\":${num(sdMs)},\"count\":$count,\"periodMs\":${num(periodMs)},\"hist\":[${hist.joinToString(",") { num(it) }}]}"

    private fun num(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e12) d.toLong().toString() else d.toString()

    companion object {
        /** The prefs key (shared prefs of the app). */
        const val KEY = "click_profile"
        const val DEFAULT_PERIOD_MS = 3290.0
        /** Under this many clicks the profile is not trusted: [params] gives Nomon's start values. */
        const val MIN_COUNT = 8
        private const val MIN_SD_S = 0.03
        private const val MAX_SD_S = 0.4
        private const val BINS = 80

        /** Offsets in ms (click time minus the time of the sound; positive = late). */
        fun of(offsetsMs: List<Double>, periodMs: Double = DEFAULT_PERIOD_MS): ClickProfile {
            val n = offsetsMs.size
            if (n == 0) return ClickProfile(0.0, 0.0, 0, periodMs)
            val mean = offsetsMs.average()
            val sd = if (n > 1) sqrt(offsetsMs.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
            return ClickProfile(mean, sd, n, periodMs, density(offsetsMs, periodMs, sd))
        }

        /** Kernel density of the offsets on the ClickModel grid (xs = i*T/80 - T/2 seconds), normalised to sum 1. Bandwidth as Nomon's (1.06 s n^-0.2), at least 15 ms. */
        private fun density(offsetsMs: List<Double>, periodMs: Double, sdMs: Double): List<Double> {
            val periodS = periodMs / 1000.0
            val h = max(0.015, 1.06 * (sdMs / 1000.0) * offsetsMs.size.toDouble().pow(-0.2))
            val d = DoubleArray(BINS) { i ->
                val x = i * periodS / BINS - periodS / 2.0
                offsetsMs.sumOf { o -> val z = x - o / 1000.0; exp(-z * z / (2 * h * h)) / sqrt(2 * PI * h * h) }
            }
            val s = d.sum()
            return d.map { if (s > 0) it / s else 0.0 }
        }

        fun fromJson(json: String?): ClickProfile? {
            if (json.isNullOrBlank()) return null
            fun field(name: String) = Regex("\"$name\":\\s*(-?[0-9.eE+-]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
            val mean = field("meanMs") ?: return null
            val sd = field("sdMs") ?: return null
            val count = field("count")?.toInt() ?: return null
            val period = field("periodMs") ?: DEFAULT_PERIOD_MS
            val hist = Regex("\"hist\":\\s*\\[([^\\]]*)]").find(json)?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: emptyList()
            return ClickProfile(mean, sd, count, period, hist)
        }
    }
}
