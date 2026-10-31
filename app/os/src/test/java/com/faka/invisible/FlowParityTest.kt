package com.faka.invisible

import com.faka.invisible.oskit.testing.*
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/**
 * "What you hear is what you see" for the whole OS menu, on the laptop in fake time: the shared parity helper
 * (`com.faka.invisible.oskit.testing.ParityRecorder`) checks at every audio onset that the item shown as "now heard" is the item spoken.
 * How to prove parity for a new screen: build the rig, `rig.parity.start()`, run the scenario, `rig.parity.assertParity(...)`, `rig.parity.stop()`.
 * Programs on the real looper are in FlowParityRoboTest.
 */
class FlowParityTest {
    private fun parity(f: Flow, minSpoken: Int, minSounds: Int = 0, scenario: (Flow) -> Unit) {
        f.r.parity.start()
        try { scenario(f); f.r.parity.assertParity(minSpoken = minSpoken, minSounds = minSounds) } finally { f.r.parity.stop() }
    }

    // ---- parity_home_menu_walk: the home list is walked by the voice; every item is shown when it starts ----
    @Test fun parity_home_menu_walk() = parity(Flow(), minSpoken = 4, minSounds = 1) { f -> f.press("900"); f.advance(9000) }

    // ---- parity_sublist_pick: a click picks an item; the next list is shown item by item ----
    @Test fun parity_sublist_pick() = parity(Flow(), minSpoken = 4) { f ->
        f.m.devShow("PHONE"); val i = f.mark()
        f.heardStart("Emergency", i); f.press("120"); f.advance(400)
        f.advance(4000)
    }

    // ---- parity_spoken_line: a line outside a list (the weather answer) ----
    @Test fun parity_spoken_line() {
        val f = Flow(ModeTest.Rig(gap = null).withInfo().also { it.weatherAnswer = "Sunny, 20 degrees." })
        parity(f, minSpoken = 2) {
            f.press("900"); f.r.pick("Today"); f.r.pick("Weather"); f.advance(3000)
            assertTrue("a LINE cue was shown: " + f.r.parity.seen.map { it.cue.kind.name + ":" + it.cue.text }.takeLast(12) + " heard=" + f.r.parity.heard.takeLast(6), f.r.parity.seen.any { it.cue.kind == Cue.Kind.LINE })
        }
    }

    // ---- the helper itself must fail on a difference ----
    private fun rec(): ParityRecorder = ParityRecorder(clock = { 1000L }).start()

    @Test fun parity_fails_when_shown_but_not_spoken() {
        val r = rec()
        try { Cues.emit(Cue(Cue.Kind.LINE, "Hello", onsetMs = 1000)); assertTrue(r.problems().any { "shown but not heard" in it }) } finally { r.stop() }
    }
    @Test fun parity_fails_when_spoken_but_not_shown() {
        val r = rec()
        try { r.heard("Hello", 500); assertTrue(r.problems().any { "heard but never shown" in it }) } finally { r.stop() }
    }
    @Test fun parity_fails_when_shown_at_the_call() {
        val r = rec()
        try { r.heard("Hello", 1000); Cues.emit(Cue(Cue.Kind.LINE, "Hello")); assertTrue(r.problems().any { "no onset" in it }) } finally { r.stop() }
    }
    @Test fun parity_fails_when_shown_before_its_onset() {
        val r = rec()
        try { r.heard("Hello", 1500); Cues.emit(Cue(Cue.Kind.LINE, "Hello", onsetMs = 1500)); assertTrue(r.problems().any { "before its onset" in it }) } finally { r.stop() }
    }
    @Test fun parity_fails_when_a_sound_makes_no_mark() {
        val r = rec()
        try { r.sounded("tick", 100); assertTrue(r.problems().any { "made no mark" in it }) } finally { r.stop() }
    }
    @Test fun parity_passes_when_they_agree() {
        val r = rec()
        try { r.heard("Hello", 1000); Cues.emit(Cue(Cue.Kind.LINE, "Hello", onsetMs = 1000)); r.sounded("tick", 900); Cues.emit(Cue(Cue.Kind.SOUND, "tick", onsetMs = 900)); assertEquals(emptyList<String>(), r.problems()) } finally { r.stop() }
    }

    // ---- a staged cue whose speak call never came must not show under a later, unrelated line ----
    @Test fun staged_cue_without_its_say_does_not_attach_to_a_later_line() {
        Cues.clearStaged()
        Cues.stageLine("Orphan line")
        assertNull("a different spoken line must not take it", Cues.takeStagedFor("Something else entirely"))
        assertFalse(Cues.hasStaged())
        Cues.stageLine("Same line")
        assertNotNull(Cues.takeStagedFor("Same line"))
    }

    // ---- KNOWN EXCEPTION (documented in KIT.md): the Write pair keyboard panel is published at the call, with no onset ----
    @Test fun known_exception_pair_keyboard_panel_has_no_onset() {
        val r = rec()
        try {
            Cues.pairScreen(listOf("Nothing written yet"), listOf("Words, left: yes", "Words, right: no"))
            assertEquals(-1L, r.seen.single().cue.onsetMs)
            assertTrue(r.problems().any { "no onset" in it })
        } finally { r.stop() }
    }

    // ---- a list title spoken after item 0 was staged must not show item 0 early ----
    @Test fun staged_list_item_is_not_shown_under_the_list_title() {
        Cues.clearStaged()
        Cues.stageListItem(listOf("Timer", "Phone"), 0)
        assertNull(Cues.takeStagedFor("Choose one"))
    }
}
