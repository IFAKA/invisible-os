package com.faka.invisible

import com.faka.invisible.voicekit.Cue
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.PressHint
import com.faka.invisible.voicekit.PressHint.Glyph.BAR
import com.faka.invisible.voicekit.PressHint.Glyph.DOT
import com.faka.invisible.voicekit.ScreenMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The press a line asks for becomes the orange glyph: one source for the voice and the picture. */
class PressHintTest {
    @Test fun clickIsOneDot() = assertEquals(listOf(DOT), PressHint.click(1).glyphs)
    @Test fun clicksAreDots() { assertEquals(listOf(DOT, DOT), PressHint.click(2).glyphs); assertEquals(listOf(DOT, DOT, DOT), PressHint.click(3).glyphs) }
    @Test fun holdIsABar() = assertEquals(listOf(BAR), PressHint.HOLD.glyphs)
    @Test fun clickThenHoldIsDotThenBar() = assertEquals(listOf(DOT, BAR), PressHint.CLICK_HOLD.glyphs)
    @Test fun clickCountIsCapped() = assertEquals(4, PressHint.click(9).glyphs.size)

    @Test fun gestureNamesMapToGlyphs() {
        assertEquals(PressHint.click(1), PressHint.fromGestureText(ScreenMirror.gestureText("SINGLE")))
        assertEquals(PressHint.click(2), PressHint.fromGestureText(ScreenMirror.gestureText("DOUBLE")))
        assertEquals(PressHint.click(3), PressHint.fromGestureText(ScreenMirror.gestureText("TRIPLE")))
        assertEquals(PressHint.HOLD, PressHint.fromGestureText(ScreenMirror.gestureText("CLICK_HOLD")))
        assertEquals(PressHint.CLICK_HOLD, PressHint.fromGestureText(ScreenMirror.gestureText("HOLD")))
        assertNull(PressHint.fromGestureText(null)); assertNull(PressHint.fromGestureText("shake"))
    }

    @Test fun lineCarriesItsPress() {
        val s = ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.LINE, "Click once now.", press = PressHint.click(1)))
        assertEquals(PressHint.click(1), s.asks)
        assertNull(ScreenMirror.reduce(s, Cue(Cue.Kind.LINE, "Good.")).asks)   // the press belongs to the line now heard
        assertNull(ScreenMirror.reduce(s, Cue(Cue.Kind.LIST_ITEM, "A", index = 0, list = listOf("A", "B"))).asks)
        assertEquals(PressHint.click(1), ScreenMirror.reduce(s, Cue(Cue.Kind.PRESS, "hold")).asks)   // a press does not erase what is asked
    }

    @Test fun phraseAsksDoesNotChangeTheWords() {
        val p = Phrase.content("Click now").asks(PressHint.click(1))
        assertEquals("Click now", p.text); assertEquals(Phrase.content("Click now"), p); assertEquals(PressHint.click(1), p.press)
    }
}
