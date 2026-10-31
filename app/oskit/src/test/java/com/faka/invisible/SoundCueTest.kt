package com.faka.invisible

import com.faka.invisible.voicekit.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Step 4 of the cue refactor: sounds without words get a picture (a mark with name and onset). */
class SoundCueTest {
    private var t = 1000L
    private val posted = ArrayList<Pair<Long, () -> Unit>>()
    @Before fun setUp() {
        ScreenMirror.clear(); ScreenMirror.onChange = null; ScreenMirror.now = { 7L }
        SoundCues.clock = { t }
        SoundCues.post = { d, f -> posted += d to f }
    }
    @After fun tearDown() { ScreenMirror.clear(); SoundCues.clock = { android.os.SystemClock.uptimeMillis() } }

    @Test fun soundCueReducesIntoSnapshot() {
        val s = ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.SOUND, "tick", onsetMs = 500))
        assertEquals(listOf(ScreenMirror.SoundMark("tick", 500)), s.sounds)
        assertEquals(7L, ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.SOUND, "tick"), { 7L }).sounds.single().onsetMs)
        assertEquals(ScreenMirror.Snapshot(), ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.SOUND, " ")))
    }

    @Test fun fastRepeatsMergeAndTheStripIsCapped() {
        var s = ScreenMirror.Snapshot()
        for (i in 0 until 5) s = ScreenMirror.reduce(s, Cue(Cue.Kind.SOUND, "tick", onsetMs = 1000L + i * 50))   // 50 ms apart: one mark
        assertEquals(1, s.sounds.size); assertEquals(5, s.sounds[0].count); assertEquals(1200L, s.sounds[0].onsetMs)
        for (i in 0 until 30) s = ScreenMirror.reduce(s, Cue(Cue.Kind.SOUND, "beat", onsetMs = 2000L + i * 400))   // a beat each 400 ms: kept, capped
        assertEquals(SoundCues.KEEP, s.sounds.size)
        assertEquals(2000L + 29 * 400, s.sounds.last().onsetMs)
        s = ScreenMirror.reduce(s, Cue(Cue.Kind.SOUND, "tick", onsetMs = 20000))
        assertEquals(listOf("beat", "tick"), s.sounds.takeLast(2).map { it.name })
    }

    @Test fun soundsDoNotDisturbTheListOrTheLoop() {
        var s = ScreenMirror.reduce(ScreenMirror.Snapshot(), Cue(Cue.Kind.LIST_ITEM, "b", index = 1, list = listOf("a", "b")))
        s = ScreenMirror.reduce(s, Cue(Cue.Kind.SOUND, "close", onsetMs = 1))
        assertEquals(listOf("a", "b"), s.items); assertEquals(1, s.index)
    }

    @Test fun playedEmitsAtOnsetNow() {
        t = 4321
        SoundCues.played("close")
        assertEquals(ScreenMirror.SoundMark("close", 4321), ScreenMirror.state.sounds.single())
        assertTrue(posted.isEmpty())
    }

    @Test fun delayedSoundShowsOnlyWhenItIsHeard() {
        SoundCues.played("beat", SoundCues.LATENCY_MS)
        assertTrue(ScreenMirror.state.sounds.isEmpty())   // not before the ear
        assertEquals(SoundCues.LATENCY_MS, posted.single().first)
        t = 1060; posted.single().second()
        assertEquals(ScreenMirror.SoundMark("beat", 1060), ScreenMirror.state.sounds.single())
    }

    @Test fun mixerAnnouncesEachSoundOnceWhenItsBlockStarts() {
        val got = ArrayList<String>()
        val m = FrameMixer(onStart = { got += it })
        m.play(FloatArray(100), atFrame = 250, name = "step")
        m.play(FloatArray(100), atFrame = 0, name = "thud")
        val out = FloatArray(2 * 100)
        m.render(out, 100); assertEquals(listOf("thud"), got)       // frames 0..99: thud starts, step waits
        m.render(out, 100); assertEquals(listOf("thud"), got)       // 100..199
        m.render(out, 100); assertEquals(listOf("thud", "step"), got)   // 200..299 holds frame 250
        m.render(out, 100); assertEquals(2, got.size)               // never twice
        assertEquals("beat", FrameMixer(onStart = { got += it }).also { it.play(FloatArray(4), 0); it.render(out, 10) }.let { got.last() })
    }

    @Test fun tonesHaveShortNames() {
        assertEquals("close", Tone.EXIT.cueName)
        assertEquals("screen tone", Tone.HUB.cueName)
        assertEquals("step back", Tone.STEP_BACK.cueName)
        assertEquals("tick", Tone.TICK.cueName)
        assertTrue(Tone.values().all { it.cueName.isNotBlank() })
    }
}
