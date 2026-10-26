package com.faka.invisible.counter

/** The rules of Counter, with no Android in them, so a plain unit test can check them. */
class CounterCore {
    var total = 0
        private set

    /** The player clicked [clicks] times in a row. One click adds one; two or more start again. Returns the new total. */
    fun clicked(clicks: Int): Int {
        total = if (clicks >= 2) 0 else total + 1
        return total
    }
}
