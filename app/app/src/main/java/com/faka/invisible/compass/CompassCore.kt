package com.faka.invisible.compass

import kotlin.math.abs
import kotlin.math.min

/** Pure rules of the compass: names of directions, the turn needed to face a target, and the tone that guides the turn. */
object CompassCore {
    private val NAMES = listOf("North", "North-east", "East", "South-east", "South", "South-west", "West", "North-west")
    const val LOCK_DEG = 8.0

    fun norm(deg: Double): Double = ((deg % 360) + 360) % 360

    /** The name of the nearest of 8 directions. */
    fun name(heading: Double): String = NAMES[((norm(heading) + 22.5) / 45).toInt() % 8]

    /** Degrees to turn from [heading] to face [target]: positive = turn right, negative = turn left, in -180..180. */
    fun turn(target: Double, heading: Double): Double { val d = norm(target - heading); return if (d > 180) d - 360 else d }

    /** Low-pass for a shaky sensor, correct across the 359 to 0 jump. */
    fun smooth(prev: Double, now: Double, k: Double = 0.2): Double = norm(prev + k * turn(now, prev))

    fun locked(turn: Double) = abs(turn) <= LOCK_DEG

    /** Guide pitch in Hz: low when facing away, rising as you turn toward the target, a high note when locked. */
    fun pitch(turn: Double): Double = if (locked(turn)) 1046.5 else 330.0 + 500.0 * (1 - abs(turn) / 180.0)

    /** -1 left .. 1 right: the sound sits on the side you must turn to. Centre when locked. */
    fun pan(turn: Double): Double = if (locked(turn)) 0.0 else Math.signum(turn) * min(1.0, abs(turn) / 45.0) * 0.8
}
