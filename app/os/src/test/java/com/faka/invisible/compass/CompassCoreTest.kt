package com.faka.invisible.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassCoreTest {
    @Test fun namesTheNearestDirection() {
        assertEquals("North", CompassCore.name(0.0)); assertEquals("North", CompassCore.name(359.0)); assertEquals("North", CompassCore.name(22.0))
        assertEquals("North-east", CompassCore.name(23.0)); assertEquals("East", CompassCore.name(90.0)); assertEquals("North-west", CompassCore.name(-45.0))
    }
    @Test fun turnTakesTheShortWay() {
        assertEquals(20.0, CompassCore.turn(10.0, 350.0), 1e-9)
        assertEquals(-20.0, CompassCore.turn(350.0, 10.0), 1e-9)
        assertEquals(180.0, CompassCore.turn(180.0, 0.0), 1e-9)
    }
    @Test fun smoothCrossesNorthWithoutSwinging() {
        val s = CompassCore.smooth(350.0, 10.0, 0.5)
        assertEquals(0.0, s, 1e-9)
    }
    @Test fun guideRisesAsYouTurnTowardTarget() {
        assertTrue(CompassCore.pitch(90.0) > CompassCore.pitch(170.0))
        assertTrue(CompassCore.locked(5.0)); assertFalse(CompassCore.locked(9.0))
        assertTrue(CompassCore.pitch(0.0) > CompassCore.pitch(30.0))
        assertTrue(CompassCore.pan(40.0) > 0 && CompassCore.pan(-40.0) < 0 && CompassCore.pan(3.0) == 0.0)
    }
}
