package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {
    @Test fun typeScaleHasExactlyTheAllowedSteps() {
        assertEquals(setOf(40f, 28f, 18f, 14f), Type.values().map { it.size }.toSet())
        assertEquals(listOf("DISPLAY", "TITLE", "BODY", "CAPTION", "MONO"), Type.values().map { it.name })
        assertEquals("MONO shares the caption size", Type.CAPTION.size, Type.MONO.size, 0f)
    }

    @Test fun weightRule() {
        assertEquals("sans-serif-medium", Type.DISPLAY.face); assertEquals("sans-serif-medium", Type.TITLE.face)
        assertEquals("sans-serif", Type.BODY.face); assertEquals("sans-serif", Type.CAPTION.face)
    }

    @Test fun spacingAndRadiiAreTheAllowedValues() {
        assertEquals(listOf(8, 16, 24, 32, 48), Space.steps)
        assertEquals(listOf(12, 20), Space.radii)
        assertEquals(48, Space.TOUCH)
        assertTrue(Space.BUTTON >= Space.TOUCH)
    }

    @Test fun textRolesReachFourPointFiveOnThePage() {
        for (r in Palette.all.filter { it.text }) {
            assertTrue("${r.name} light " + Palette.contrast(r.light, Palette.PAGE.light), Palette.contrast(r.light, Palette.PAGE.light) >= 4.5)
            assertTrue("${r.name} dark " + Palette.contrast(r.dark, Palette.PAGE.dark), Palette.contrast(r.dark, Palette.PAGE.dark) >= 4.5)
        }
    }

    @Test fun textRolesAlsoReadOnTheCard() {
        for (r in Palette.all.filter { it.text }) {
            assertTrue("${r.name} light on card", Palette.contrast(r.light, Palette.CARD.light) >= 4.5)
            assertTrue("${r.name} dark on card", Palette.contrast(r.dark, Palette.CARD.dark) >= 4.5)
        }
    }

    @Test fun whiteTextOnTheFilledButtonReads() {
        assertTrue(Palette.contrast("#FFFFFF", Palette.ACCENT_FILL.light) >= 4.5)
    }

    @Test fun nowLineStepIsDiscrete() {
        val lines = mapOf(Type.DISPLAY to 4, Type.TITLE to 3, Type.BODY to 2)
        assertEquals(Type.DISPLAY, Type.nowStep { lines.getValue(it) })
        assertEquals(Type.TITLE, Type.nowStep { if (it == Type.DISPLAY) 5 else 6 })
        assertEquals(Type.BODY, Type.nowStep { if (it == Type.BODY) 3 else 9 })
    }

    @Test fun roleNamesAreUnique() {
        assertEquals(Palette.all.size, Palette.all.map { it.name }.toSet().size)
    }
}
