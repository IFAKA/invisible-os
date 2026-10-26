package com.faka.invisible.counter

import org.junit.Assert.assertEquals
import org.junit.Test

class CounterCoreTest {
    @Test fun countsUpAndStartsAgain() {
        val c = CounterCore()
        assertEquals(1, c.clicked(1)); assertEquals(2, c.clicked(1))
        assertEquals(0, c.clicked(2)); assertEquals(1, c.clicked(1))
    }
}
