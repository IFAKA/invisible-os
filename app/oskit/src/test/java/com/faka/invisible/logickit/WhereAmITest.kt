package com.faka.invisible.logickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereAmITest {
    @Test fun streetNumberAreaCity() =
        assertEquals(listOf("Calle Mayor 120", "Centro", "Toledo"), WhereAmI.lines(Place("Calle Mayor", "120", "Centro", "Toledo"), null))
    @Test fun turnComesFirst() =
        assertEquals(listOf("In 200 meters turn left", "Mayor", "Toledo"), WhereAmI.lines(Place("Mayor", null, null, "Toledo"), "  In 200 meters   turn left "))
    @Test fun sameAreaAndCityOnce() =
        assertEquals(listOf("Toledo"), WhereAmI.lines(Place(null, null, "toledo", "Toledo"), null))
    @Test fun featureWhenNoStreet() =
        assertEquals(listOf("Near Central Park", "New York"), WhereAmI.lines(Place(null, null, null, "New York", "Central Park"), null))
    @Test fun nothingFound() { assertTrue(WhereAmI.lines(null, null).isEmpty()); assertFalse(WhereAmI.hasPlace(Place(" ", null, "", null))) }
    @Test fun turnAloneIsNotAPlace() { assertEquals(listOf("Turn right"), WhereAmI.lines(null, "Turn right")); assertFalse(WhereAmI.hasPlace(null)) }
}
