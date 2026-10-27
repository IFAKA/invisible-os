package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningResetTest {
    @Test fun onePackageMatchesOnlyItsOwnPicks() {
        assertTrue(LearningReset.matches("chat", "app:chat"))
        assertTrue(LearningReset.matches("hub:timer", "hub:timer"))
        assertFalse(LearningReset.matches("chat", "app:bank"))
        assertFalse(LearningReset.matches("chat", "phone:status"))
    }

    @Test fun everythingMatchesAll() = assertTrue(LearningReset.matches(null, "phone:status"))

    @Test fun forgetKeepsOtherPicksAndCounts() {
        var saved = emptyList<String>()
        val log = ChoiceLog(emptyList(), {}, { saved = it })
        log.record(1, "TREE", 1, "app:chat"); log.record(2, "TREE", 1, "app:chat"); log.record(3, "TREE", 1, "app:bank")
        assertEquals(2, log.forget { LearningReset.matches("chat", it) })
        assertEquals(0, log.intentCount("app:chat"))
        assertEquals(1, log.intentCount("app:bank"))
        assertEquals(1, saved.size)
        assertEquals(1, log.forget { true })
        assertEquals(0, log.history().size)
    }
}
