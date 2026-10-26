package com.faka.invisible.practice

import com.faka.invisible.voicekit.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeCoreTest {
    @Test fun rightGestureMovesOn() {
        assertEquals(PracticeStep.TWO, PracticeCore.next(PracticeStep.ONE, Gesture.SINGLE))
        assertEquals(PracticeStep.THREE, PracticeCore.next(PracticeStep.TWO, Gesture.DOUBLE))
        assertEquals(PracticeStep.CLOSE, PracticeCore.next(PracticeStep.THREE, Gesture.TRIPLE))
    }
    @Test fun wrongGestureStays() {
        assertNull(PracticeCore.next(PracticeStep.ONE, Gesture.DOUBLE))
        assertNull(PracticeCore.next(PracticeStep.TWO, Gesture.SINGLE))
        assertNull(PracticeCore.next(PracticeStep.CLOSE, Gesture.SINGLE))
        assertFalse(PracticeCore.passes(PracticeStep.CLOSE, Gesture.TRIPLE))
        assertTrue(PracticeCore.passes(PracticeStep.THREE, Gesture.TRIPLE))
    }
}
