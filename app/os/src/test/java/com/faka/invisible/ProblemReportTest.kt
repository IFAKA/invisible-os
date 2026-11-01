package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemReportTest {
    private val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-11-01 12:00")!!.time

    @Test fun keepsOnlyTheLastTwoDaysAndIgnoresOtherFiles() {
        val names = listOf("trace-2026-10-30.log", "trace-2026-10-31.log", "trace-2026-11-01.log", "notes.txt", "trace-2026-10-01.log")
        assertEquals(listOf("trace-2026-10-31.log", "trace-2026-11-01.log"), ProblemReport.recent(names, now))
    }

    @Test fun reportHasVersionsAndFiles() {
        val r = ProblemReport.build("0.1.0 (12)", "Android 14 (API 34)", "Pixel 8", mapOf("trace-2026-11-01.log" to "12:00:00.000 wake x\n", "trace-2026-09-01.log" to "old"), now)
        assertTrue(r.contains("App: 0.1.0 (12)")); assertTrue(r.contains("Android: Android 14 (API 34)"))
        assertTrue(r.contains("==== trace-2026-11-01.log ====\n12:00:00.000 wake x"))
        assertFalse(r.contains("old"))
    }

    @Test fun noFilesIsSaid() {
        assertTrue(ProblemReport.build("1", "a", "d", emptyMap(), now).contains("(no trace files)"))
    }
}
