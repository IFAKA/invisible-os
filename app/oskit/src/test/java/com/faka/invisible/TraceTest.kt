package com.faka.invisible

import com.faka.invisible.oskit.TraceFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TraceTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s); set(Calendar.MILLISECOND, ms) }.timeInMillis

    @Test fun lineAndFileName() {
        val t = at(2026, 10, 27, 9, 5, 3, 7)
        assertEquals("09:05:03.007 gesture single screen=HUB", TraceFormat.line(t, "gesture single screen=HUB"))
        assertEquals("trace-2026-10-27.log", TraceFormat.fileName(t))
    }

    @Test fun oneLineAndCut() {
        assertEquals("a|b", TraceFormat.clip("a\nb\n"))
        assertTrue(TraceFormat.clip("x".repeat(2000)).length < 700)
    }

    @Test fun keepsSevenDays() {
        val now = at(2026, 10, 27, 12, 0, 0, 0)
        val names = listOf("trace-2026-10-27.log", "trace-2026-10-20.log", "trace-2026-10-19.log", "trace-2026-09-01.log", "notes.txt")
        assertEquals(listOf("trace-2026-10-19.log", "trace-2026-09-01.log"), TraceFormat.expired(names, now, 7))
    }

    @Test fun stackIsOneLine() {
        val s = TraceFormat.stack(IllegalStateException("boom"))
        assertTrue(s.startsWith("java.lang.IllegalStateException: boom at "))
        assertFalse(s.contains("\n"))
    }
}
