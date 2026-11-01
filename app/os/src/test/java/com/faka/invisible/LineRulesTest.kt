package com.faka.invisible

import com.faka.invisible.claude.LineAnswer
import com.faka.invisible.claude.PushItem
import com.faka.invisible.voicekit.Gesture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pure rules of the push line: speak or queue, rate limits, press to answer, queue order (DESIGN-LAYER.md C3, C4). Robolectric only for org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineRulesTest {
    private fun act(label: String, id: String = "%3", action: String = "go", risky: Boolean = false) = LineAnswer(label, risky, roomId = id, action = action)
    private fun item(key: String = "room:%3", kind: String = PushItem.ROOM, who: String = "os", line: String = "tests pass, commit?", a: LineAnswer? = act("Go"), b: LineAnswer? = act("Push", action = "push"), active: Boolean? = false) =
        PushItem(key, kind, key.substringAfter(':'), who, line, a, b, active)
    private val free = LineRules.Ctx(menuOpen = false, programRuns = false, inCall = false, muted = false)

    @Test fun speaks_when_nothing_blocks() = assertEquals(LineRules.Level.SPOKEN, LineRules.decide(item(), free, 100_000, null, null).level)

    @Test fun silent_while_the_laptop_is_in_use_a_call_a_program_or_muted() {
        assertEquals("laptop_active", LineRules.decide(item(active = true), free, 100_000, null, null).why)
        assertEquals("call", LineRules.decide(item(), free.copy(inCall = true), 100_000, null, null).why)
        assertEquals("program", LineRules.decide(item(), free.copy(programRuns = true), 100_000, null, null).why)
        assertEquals("muted", LineRules.decide(item(), free.copy(muted = true), 100_000, null, null).why)
    }

    @Test fun laptop_unknown_still_speaks() = assertEquals(LineRules.Level.SPOKEN, LineRules.decide(item(active = null), free, 100_000, null, null).level)

    @Test fun open_menu_makes_only_the_tone() = assertEquals(LineRules.Level.TONE, LineRules.decide(item(), free.copy(menuOpen = true), 100_000, null, null).level)

    @Test fun old_news_after_a_restart_is_queued() = assertEquals(LineRules.Level.QUEUED, LineRules.decide(item(), free, 100_000, null, null, replay = true).level)

    @Test fun rate_limits_are_20_seconds_overall_and_60_for_one_room() {
        assertEquals("rate_global", LineRules.decide(item(), free, 119_999, 100_000, null).why)
        assertEquals(LineRules.Level.SPOKEN, LineRules.decide(item(), free, 120_000, 100_000, null).level)
        assertEquals("rate_room", LineRules.decide(item(), free, 150_000, 100_000 + 20_000, 100_000).why)
        assertEquals(LineRules.Level.SPOKEN, LineRules.decide(item(), free, 160_000, 130_000, 100_000).level)
    }

    @Test fun a_busy_line_is_not_interrupted() = assertEquals("busy", LineRules.decide(item(), free.copy(busy = true), 100_000, null, null).why)

    @Test fun short_work_is_not_said_when_done() {
        assertFalse(LineRules.doneSpoken(5.0)); assertFalse(LineRules.doneSpoken(29.9))
        assertTrue(LineRules.doneSpoken(30.0)); assertTrue(LineRules.doneSpoken(null))
    }

    @Test fun normal_presses() {
        val i = item()
        assertEquals(LineRules.Move.A, LineRules.move(i, Gesture.SINGLE))
        assertEquals(LineRules.Move.B, LineRules.move(i, Gesture.DOUBLE))
        assertEquals(LineRules.Move.MORE, LineRules.move(i, Gesture.HOLD))
        assertEquals(LineRules.Move.MORE, LineRules.move(i, Gesture.CLICK_HOLD))
        assertEquals(LineRules.Move.LATER, LineRules.move(i, Gesture.TRIPLE))
        assertEquals(LineRules.Move.NONE, LineRules.move(item(b = null), Gesture.DOUBLE))
    }

    @Test fun risky_answer_needs_a_hold_C4() {
        val i = item(a = act("Push", action = "push", risky = true))
        assertEquals(LineRules.Move.NO, LineRules.move(i, Gesture.SINGLE))
        assertEquals(LineRules.Move.A, LineRules.move(i, Gesture.HOLD))
        assertEquals(LineRules.Move.MORE, LineRules.move(i, Gesture.DOUBLE))
        assertEquals(LineRules.Move.LATER, LineRules.move(i, Gesture.TRIPLE))
    }

    @Test fun no_click_ever_sends_a_risky_answer() {
        for (g in listOf(Gesture.SINGLE, Gesture.DOUBLE, Gesture.TRIPLE, Gesture.QUAD)) {
            val m = LineRules.move(item(a = act("X", risky = true), b = act("Y")), g)
            assertTrue("$g sent a risky answer: $m", m != LineRules.Move.A && m != LineRules.Move.B)
        }
    }

    @Test fun a_notice_has_no_answers() = Gesture.values().forEach { assertEquals(LineRules.Move.NONE, LineRules.move(item(a = null, b = null), it)) }

    @Test fun queue_reads_asks_then_rooms_then_lines_newest_first() {
        val q = LineQueue()
        q.offer(item("line:1", PushItem.LINE, line = "one")); q.offer(item("room:%1", who = "A", line = "two")); q.offer(item("ask:7", PushItem.ASK, who = "D", line = "three"))
        q.offer(item("room:%2", who = "B", line = "four")); q.offer(item("line:2", PushItem.LINE, line = "five"))
        assertEquals(listOf("ask:7", "room:%2", "room:%1", "line:2", "line:1"), q.ordered().map { it.key })
    }

    @Test fun a_newer_item_replaces_the_older_one_of_the_same_key_or_words() {
        val q = LineQueue()
        q.offer(item("room:%3", line = "old")); q.offer(item("room:%3", line = "new"))
        assertEquals(listOf("new"), q.ordered().map { it.line })
        q.offer(item("ask:9", PushItem.ASK, who = "os", line = "new"))   // an ask with the same words as its room: one item
        assertEquals(listOf("ask:9"), q.ordered().map { it.key })
    }

    @Test fun old_items_are_dropped() {
        val q = LineQueue(); q.offer(item("room:%1"), 0); q.offer(item("room:%2", who = "B", line = "x"), 40 * 60_000L)
        q.dropOlder(40 * 60_000L, 30 * 60_000L)
        assertEquals(listOf("room:%2"), q.ordered().map { it.key })
    }

    @Test fun parses_the_bridge_contract_and_ignores_bad_answers() {
        val j = JSONObject("""{"type":"room","id":"%3","name":"os","state":"needs","line":"os: tests pass, commit? one two three four five six seven eight nine ten","a":{"label":"Go","risky":false,"act":{"id":"%3","action":"go"}},"b":{"label":"Allow","risky":false,"answer":{"id":"7","choice":"Allow"}},"laptop_active":true}""")
        val p = PushItem.parse(j, PushItem.ROOM)!!
        assertEquals("room:%3", p.key); assertEquals("os", p.who); assertEquals(true, p.laptopActive)
        assertTrue(p.line.split(" ").size <= 12)
        assertEquals("go", p.a!!.action); assertEquals("%3", p.a!!.roomId)
        assertEquals("7", p.b!!.askId); assertEquals("Allow", p.b!!.choice)
        assertNull(PushItem.parse(JSONObject("""{"id":"%3","name":"os"}"""), PushItem.ROOM))   // no line: the old behaviour
        val bad = PushItem.parse(JSONObject("""{"id":"1","project":"p","line":"hi","a":{"label":"Go","act":{"id":"%3","action":"go"},"answer":{"id":"1","choice":"x"}},"b":{"label":"No"},"laptop_active":null}"""), PushItem.LINE)!!
        assertNull("both act and answer is not an answer", bad.a); assertNull(bad.b); assertNull(bad.laptopActive)
    }
}
