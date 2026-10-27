package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningResetTest {
    private val cat = { k: String -> if (k == "chat") "Talk/Chat" else if (k == "hub:timer") "Tools/Time" else "Pay" }

    @Test fun groupMatchesOnlyItsOwnApps() {
        assertTrue(LearningReset.matches("Talk", "app:chat", cat))
        assertFalse(LearningReset.matches("Talk", "app:bank", cat))
        assertTrue(LearningReset.matches("Tools", "hub:timer", cat))
        assertFalse(LearningReset.matches("Talk", "phone:status", cat))
    }

    @Test fun everythingMatchesAll() = assertTrue(LearningReset.matches(null, "phone:status", cat))

    @Test fun forgetKeepsOtherPicksAndCounts() {
        var saved = emptyList<String>()
        val log = ChoiceLog(emptyList(), {}, { saved = it })
        log.record(1, "TREE", 1, "app:chat"); log.record(2, "TREE", 1, "app:chat"); log.record(3, "TREE", 1, "app:bank")
        assertEquals(2, log.forget { LearningReset.matches("Talk", it, cat) })
        assertEquals(0, log.intentCount("app:chat"))
        assertEquals(1, log.intentCount("app:bank"))
        assertEquals(1, saved.size)
        assertEquals(1, log.forget { true })
        assertEquals(0, log.history().size)
    }
}
