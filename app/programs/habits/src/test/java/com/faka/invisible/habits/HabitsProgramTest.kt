package com.faka.invisible.habits

import com.faka.invisible.oskit.testing.FakeProgramHost
import com.faka.invisible.voicekit.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitsProgramTest {
    private val h = FakeProgramHost()
    private val tones = ArrayList<Tone>()
    private var day = 100L
    private val p = HabitsProgram({ day }, { tones.add(it) })

    private fun clicks(n: Int) { repeat(n) { h.click(p); h.advance(100) }; h.advance(500) }

    @Test fun readsHabitsAndSavesAnswers() {
        p.start(h)
        assertEquals(listOf("Habits. Click: yes. Two clicks: no.", "Water, streak 0."), h.takeSpoken())
        clicks(1)   // Water yes
        assertEquals(listOf("Yes.", "Walk, streak 0."), h.takeSpoken())
        clicks(2)   // Walk no
        assertEquals(listOf("No.", "Read, streak 0."), h.takeSpoken())
        clicks(1)
        assertEquals(listOf("Yes.", "All done."), h.takeSpoken())
        assertEquals(listOf(Tone.PICK, Tone.CANCEL, Tone.PICK), tones)
        h.advance(2000)
        assertEquals(1, h.endedCount)
    }

    @Test fun streakGrowsOverDays() {
        for (d in 98L..99L) { day = d; val q = HabitsProgram({ day }, { }); val hh = FakeProgramHost(); hh.prefs.edit().putString("habit_days_Water", "98,99").apply(); q.start(hh) }
        day = 100
        h.prefs.edit().putString("habit_days_Water", "98,99").apply()
        p.start(h)
        assertTrue(h.spoken.contains("Water, streak 2."))
        clicks(1)
        p.stop(); h.takeSpoken(); p.start(h)
        assertTrue(h.spoken.contains("Water, streak 3."))
    }

    @Test fun ownListFromPrefs() {
        h.prefs.edit().putString(HabitsProgram.LIST_KEY, "Stretch\nFloss").apply()
        p.start(h)
        assertTrue(h.spoken.contains("Stretch, streak 0."))
    }

    @Test fun tripleLeaves() {
        p.start(h); clicks(3)
        assertEquals(1, h.endedCount); assertFalse(p.active)
    }

    @Test fun holdLeaves() {
        p.start(h); h.hold(p, 3100)
        assertEquals(1, h.endedCount)
    }
}
