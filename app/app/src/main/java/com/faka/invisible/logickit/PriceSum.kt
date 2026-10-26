package com.faka.invisible.logickit

/**
 * A running total of prices, for the Sum prices tool. Pure, no Android.
 * Money is kept in cents (a Long), so 0.10 + 0.20 is exactly 0.30.
 */
class PriceSum {
    private val prices = ArrayList<Long>()

    val count get() = prices.size
    val isEmpty get() = prices.isEmpty()
    val totalCents get() = prices.sum()

    /** Add a typed price like "1250" or "12.5" (cents past 2 decimals are rounded). False when it is not a number. */
    fun add(text: String): Boolean {
        val v = text.toDoubleOrNull() ?: return false
        prices += Math.round(v * 100)
        return true
    }

    /** Remove the last price added. */
    fun undo() { if (prices.isNotEmpty()) prices.removeAt(prices.lastIndex) }

    /** The total in words, so the voice never guesses: "1590", "12 point 5". */
    fun spoken() = Calculator.say(format(totalCents))

    companion object {
        /** 159000 -> "1590", 1250 -> "12.5", 5 -> "0.05". */
        fun format(cents: Long): String {
            val sign = if (cents < 0) "-" else ""; val a = Math.abs(cents)
            val frac = (a % 100).toString().padStart(2, '0').trimEnd('0')
            return sign + (a / 100) + if (frac.isEmpty()) "" else ".$frac"
        }
    }
}
