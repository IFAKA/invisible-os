package com.faka.invisible.logickit

/**
 * The state of the spoken calculator: one number being typed, an operator, and the left number. Pure, no Android.
 * Left to right, like a pocket calculator: 2 + 3 times 4 = 20. "%" means "left percent of right" (15 % 80 = 12).
 */
class Calculator {
    private var left: Double? = null
    private var op: Char? = null
    private var cur = ""
    private var showing = false   // [cur] is a result, not typed digits
    var error = false; private set

    val hasLeft get() = left != null || cur.isNotEmpty()
    val canEquals get() = op != null && cur.isNotEmpty() && left != null
    val canOperate get() = cur.isNotEmpty() || left != null
    fun isResult() = showing
    val typing get() = cur.isNotEmpty() && !showing
    val isEmpty get() = left == null && cur.isEmpty() && op == null

    fun digit(d: Int) {
        if (showing) { cur = ""; showing = false }
        error = false
        if (cur.length >= MAX_DIGITS) return
        cur = if (cur == "0") d.toString() else cur + d
    }

    fun point() {
        if (showing) { cur = ""; showing = false }
        if ('.' in cur || cur.length >= MAX_DIGITS) return
        cur = (cur.ifEmpty { "0" }) + "."
    }

    fun operate(o: Char) {
        if (cur.isNotEmpty() && op != null && left != null) { if (!compute()) return }
        else if (cur.isNotEmpty()) { left = cur.toDouble(); cur = "" }
        if (left != null) { op = o; showing = false }
    }

    /** The result as a number, or null when there is nothing to do or it is an error. */
    fun equals(): Double? {
        if (!canEquals) return null
        if (!compute()) return null
        op = null
        cur = format(left!!); left = null; showing = true
        return cur.toDouble()
    }

    /** Remove the last thing: a digit, else the operator (the left number is typed again). */
    fun undo() {
        error = false
        when {
            showing -> { cur = ""; showing = false }
            cur.isNotEmpty() -> cur = cur.dropLast(1)
            op != null -> { op = null; cur = format(left!!); left = null }
            else -> {}
        }
    }

    fun clear() { left = null; op = null; cur = ""; showing = false; error = false }

    /** What the phone says for the whole state, in words: "twelve plus 3". */
    fun spoken(): String = when {
        error -> "Cannot divide by zero"
        isEmpty -> "Zero"
        else -> listOfNotNull(left?.let { say(format(it)) }, op?.let { WORDS[it] }, cur.takeIf { it.isNotEmpty() }?.let { say(it) }).joinToString(" ")
    }

    /** The number being typed or the result, in words. */
    fun current(): String = say(cur.ifEmpty { left?.let { format(it) } ?: "0" })

    private fun compute(): Boolean {
        val a = left ?: return false; val b = cur.toDoubleOrNull() ?: return false
        val r = when (op) { '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> { if (b == 0.0) { clear(); error = true; return false }; a / b }; '%' -> a * b / 100; else -> return false }
        left = r; cur = ""
        return true
    }

    companion object {
        const val MAX_DIGITS = 12
        val WORDS = mapOf('+' to "plus", '-' to "minus", '*' to "times", '/' to "divided by", '%' to "percent of")

        /** 12.0 -> "12", 0.1+0.2 -> "0.3": whole numbers without a point, others rounded to 6 places. */
        fun format(d: Double): String {
            val r = Math.round(d * 1_000_000.0) / 1_000_000.0
            return if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
        }

        /** "-3.5" -> "minus 3 point 5", so the voice never guesses. */
        fun say(s: String): String = s.replace("-", "minus ").replace(".", " point ").squash()
    }
}
