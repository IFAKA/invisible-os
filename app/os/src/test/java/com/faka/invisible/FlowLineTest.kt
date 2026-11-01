package com.faka.invisible

import com.faka.invisible.claude.*
import com.faka.invisible.voicekit.Cues
import com.faka.invisible.voicekit.Gesture
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Tone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN-LAYER.md steps 4 and 5 (the push line, the answer window, the wake queue, the four-click replay) against a fake Rooms server:
 * real HTTP and parsing, the real [RoomEvents], [ClaudeWatch], [PushLine], real button timing, fake time. C1, C4, C5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowLineTest {
    private val servers = ArrayList<FakeRooms>()
    private fun server(rooms: List<JSONObject> = FakeRooms.DEFAULT_ROOMS) = FakeRooms("e2e-token", rooms).also { servers.add(it) }
    @After fun stop() { servers.forEach { it.stop() } }

    /** The OS side of the line on the rig: the same classes [Actions] builds, with the rig's narrator, fake voice and tones. */
    private inner class Env(val rig: RoboRig, val s: FakeRooms) : LinePorts {
        val c = Computer("Home", s.url, s.token)
        var menuOpen = false; var program = false; var call = false; var muted = false
        var speechUntil = 0L; var ducks = 0; var resumes = 0; var homes = 0
        val walks = ArrayList<String>()
        override fun now() = rig.now()
        override fun ctx() = LineRules.Ctx(menuOpen, program, call, muted)
        override fun computer() = c
        override fun beep() { rig.narrator.beep(Tone.CLAUDE) }
        override fun speak(p: Phrase) {
            if (!Cues.hasStagedFor(p.text)) Cues.stageLine(p.text)
            rig.narrator.say(p, true)
            speechUntil = maxOf(rig.now(), speechUntil) + 600
        }
        override fun speaking() = rig.now() < speechUntil
        override fun stopSpeech() { rig.narrator.stopSpeech(); speechUntil = 0 }
        override fun duck(): Boolean { ducks++; return true }
        override fun resume() { resumes++ }
        override fun openWalk(target: String) { walks.add(target) }

        val line = PushLine(rig.handler, this).also { l -> rig.gestureHook = { g -> l.onGesture(g) } }
        val events = RoomEvents(rig.handler, { c }, { program }, { muted }, { rig.narrator.beep(Tone.CLAUDE) }, { rig.narrator.say(it, true) }, push = line)

        /** Starts watching and waits (real time) until the watcher is in its second long poll, so the first (old news) answer is behind us. */
        fun start() {
            events.restart()
            val end = System.currentTimeMillis() + 10_000
            while (s.gets.count { it.startsWith("/events") } < 2 && System.currentTimeMillis() < end) { rig.advance(20); Thread.sleep(10) }
        }
        fun await(what: String, ms: Long = 20_000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (!cond() && System.currentTimeMillis() < end) { rig.advance(50); Thread.sleep(5) }
            if (!cond()) throw AssertionError("never: $what; heard ${rig.said.takeLast(8)}")
        }
        fun acts() = s.posts.filter { it.first == "/room/act" }
        fun answers() = s.posts.filter { it.first == "/answer" }
    }

    private fun ans(label: String, id: String, action: String, risky: Boolean = false) =
        JSONObject().put("label", label).put("risky", risky).put("act", JSONObject().put("id", id).put("action", action))
    private fun askAns(label: String, id: String, choice: String, risky: Boolean = false) =
        JSONObject().put("label", label).put("risky", risky).put("answer", JSONObject().put("id", id).put("choice", choice))

    private fun needs(id: String = "%3", name: String = "os", line: String = "os: tests pass, commit?", a: JSONObject? = ans("Go", id, "go"), b: JSONObject? = ans("Push", id, "push"), active: Boolean? = false) =
        JSONObject().put("type", "room").put("id", id).put("name", name).put("state", "needs").put("line", line).put("laptop_active", active ?: JSONObject.NULL)
            .put("a", a ?: JSONObject.NULL).put("b", b ?: JSONObject.NULL)
    private fun ask(line: String = "Delta wants to delete the build cache", active: Boolean? = false) =
        JSONObject().put("id", "7").put("kind", "permission").put("project", "Delta").put("summary", "rm -rf build/cache").put("options", JSONArray(listOf("Allow", "Deny")))
            .put("line", line).put("laptop_active", active ?: JSONObject.NULL).put("a", askAns("Allow", "7", "Allow")).put("b", askAns("Deny", "7", "Deny"))

    // ---- C1: needs event, line, one click, sent, within 6 s ----
    @Test fun line_one_click_sends_the_action_within_six_seconds() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        val i = rig.mark(); val t0 = rig.now()
        s.fireJson.add(needs())
        rig.needLive("Click: Go. Two: Push.", i)
        val h = rig.said.drop(i)
        assertTrue("line, then hint: $h", h.indexOf("os: tests pass, commit?") in 0 until h.indexOf("Click: Go. Two: Push."))
        assertTrue("the Claude tone came first: ${rig.beeps}", rig.beeps.contains(Tone.CLAUDE))
        rig.press("120")
        env.await("act sent") { env.acts().isNotEmpty() }
        val ms = rig.now() - t0
        assertTrue("event to send took $ms ms fake time", ms <= 6000)
        val (path, body) = env.acts().single()
        assertEquals("/room/act", path); assertEquals("%3", body.optString("id")); assertEquals("go", body.optString("action"))
        rig.needLive("Sent to os", i)
        assertTrue("the click was taken by the line, not play/pause: ${rig.unhandled}", rig.unhandled.isEmpty())
        assertEquals(0, env.line.queue.size)
    }

    @Test fun two_clicks_send_answer_b() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs()); rig.needLive("Click: Go", 0)
        rig.press("120,100,120")
        env.await("act sent") { env.acts().isNotEmpty() }
        assertEquals("push", env.acts().single().second.optString("action"))
    }

    // ---- C4: a risky answer needs a hold ----
    @Test fun risky_single_click_sends_nothing_and_hold_sends_it() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        val risky = ans("Push", "%3", "push", risky = true)
        val i = rig.mark(); s.fireJson.add(needs(line = "os: ready to push", a = risky, b = null))
        rig.needLive("Click: no. Hold: Push.", i)
        rig.press("120"); rig.needLive("Skipped.", i)
        rig.advance(3000)
        assertTrue("a single click sent nothing: ${s.posts}", env.acts().isEmpty())
        // a second risky item, a hold sends it
        rig.advance(61_000)
        val j = rig.mark(); s.fireJson.add(needs(line = "os: ready to push again", a = risky, b = null))
        rig.needLive("Click: no. Hold: Push.", j)
        rig.press("900")
        env.await("push sent") { env.acts().isNotEmpty() }
        assertEquals("push", env.acts().single().second.optString("action"))
    }

    // ---- ask ----
    @Test fun ask_click_allows_and_two_clicks_deny() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        val i = rig.mark(); s.fireJson.add(ask())
        rig.needLive("Click: Allow. Two: Deny.", i)
        assertFalse("an ask with a line does not open the tool by itself", env.program)
        rig.press("120")
        env.await("answer sent") { env.answers().isNotEmpty() }
        assertEquals("7", env.answers().single().second.optString("id")); assertEquals("Allow", env.answers().single().second.optString("choice"))
        rig.needLive("Sent to Delta", i)

        val s2 = server(); val rig2 = RoboRig(); val env2 = Env(rig2, s2); env2.start()
        s2.fireJson.add(ask()); rig2.needLive("Click: Allow", 0)
        rig2.press("120,100,120")
        env2.await("deny sent") { env2.answers().isNotEmpty() }
        assertEquals("Deny", env2.answers().single().second.optString("choice"))
    }

    @Test fun ask_too_late_is_said() {
        val s = server(); s.answerCode = 409; val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(ask()); rig.needLive("Click: Allow", 0); rig.press("120")
        rig.needLive("Too late", 0)
    }

    // ---- silent while the laptop is in use; wake reads the queue ----
    @Test fun laptop_active_is_queued_silently_and_wake_reads_it() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        val i = rig.mark(); s.fireJson.add(needs(active = true))
        env.await("queued") { env.line.queue.size == 1 }
        rig.advance(3000)
        assertTrue("nothing was said: ${rig.said.drop(i)}", rig.said.drop(i).isEmpty())
        assertTrue("and no tone: ${rig.beeps}", rig.beeps.isEmpty())
        // wake: the queue first
        assertTrue(env.line.wake { env.homes++ })
        rig.needLive("Click: Go. Two: Push.", i)
        rig.press("120")
        env.await("act sent") { env.acts().isNotEmpty() }
        rig.needLive("All clear.", i)
        env.await("home") { env.homes == 1 }
        assertEquals(0, env.line.queue.size)
        assertTrue("music was ducked and given back", env.ducks >= 1)
    }

    @Test fun wake_with_an_empty_queue_is_the_normal_wake() {
        val rig = RoboRig(); val env = Env(rig, server())
        assertFalse(env.line.wake { env.homes++ }); assertEquals(0, env.homes)
    }

    @Test fun wake_reads_asks_first_and_triple_click_goes_home() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs(active = true)); s.fireJson.add(ask(active = true))
        env.await("both queued") { env.line.queue.size == 2 }
        val i = rig.mark()
        assertTrue(env.line.wake { env.homes++ })
        rig.needLive("Delta wants to delete", i)
        assertFalse("the room line waits for its turn", rig.said("os: tests pass", i))
        rig.press("120,100,120,100,120")
        env.await("home") { env.homes == 1 }
        assertEquals(2, env.line.queue.size)
    }

    @Test fun no_press_keeps_the_item_and_walks_on() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs(active = true)); s.fireJson.add(ask(active = true))
        env.await("both queued") { env.line.queue.size == 2 }
        val i = rig.mark(); env.line.wake { env.homes++ }
        rig.needLive("Click: Allow", i)
        rig.advance(10_000)   // no press: later
        rig.needLive("os: tests pass", i)
        rig.advance(10_000)
        rig.needLive("All clear.", i)
        env.await("home") { env.homes == 1 }
        assertEquals(2, env.line.queue.size)
        assertTrue(s.posts.none { it.first == "/room/act" || it.first == "/answer" })
    }

    // ---- rate limit; no press = later ----
    @Test fun a_second_line_within_20_seconds_is_queued() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs()); rig.needLive("Click: Go", 0)
        rig.advance(9000)   // the 8 s window ends: later
        val j = rig.mark(); s.fireJson.add(needs(id = "%5", name = "web", line = "web: build done, deploy?"))
        env.await("second queued") { env.line.queue.size == 2 }
        rig.advance(2000)
        assertFalse("the second line was not spoken: ${rig.said.drop(j)}", rig.said("web: build done", j))
        assertTrue(rig.unhandled.isEmpty())
        // after the window the click is the media meaning again
        rig.press("120"); rig.advance(600)
        assertEquals(listOf(Gesture.SINGLE), rig.unhandled)
    }

    // ---- program, call, menu, mute ----
    @Test fun nothing_is_spoken_in_a_call_a_program_or_when_muted_and_an_open_menu_gets_the_tone_only() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        env.call = true; s.fireJson.add(needs(id = "%1", name = "a", line = "a: one")); env.await("q1") { env.line.queue.size == 1 }
        env.call = false; env.program = true; s.fireJson.add(needs(id = "%2", name = "b", line = "b: two")); env.await("q2") { env.line.queue.size == 2 }
        env.program = false; env.muted = true; s.fireJson.add(needs(id = "%4", name = "c", line = "c: three")); env.await("q3") { env.line.queue.size == 3 }
        rig.advance(2000)
        assertTrue("silent: ${rig.said}", rig.said.isEmpty())
        env.muted = false; env.menuOpen = true; s.fireJson.add(needs(id = "%6", name = "d", line = "d: four")); env.await("tone") { env.line.queue.size == 4 && rig.beeps.isNotEmpty() }
        rig.advance(2000)
        assertTrue("the open menu got the tone only: ${rig.said}", rig.said.isEmpty())
    }

    // ---- hold = the full walk ----
    @Test fun hold_opens_the_full_walk_at_that_room() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs()); rig.needLive("Click: Go", 0)
        rig.press("900")
        env.await("walk opened") { env.walks.isNotEmpty() }
        assertEquals(listOf("%3"), env.walks)
        assertTrue(env.acts().isEmpty())
        assertEquals(0, env.line.queue.size)
    }

    // ---- QUAD replays ----
    @Test fun four_clicks_replay_the_last_line_while_it_is_unanswered() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(needs()); rig.needLive("Click: Go", 0)
        rig.advance(9000)   // window over: later
        val before = rig.said.count { it == "os: tests pass, commit?" }
        rig.press("120,100,120,100,120,100,120"); rig.advance(800)
        assertEquals(before + 1, rig.said.count { it == "os: tests pass, commit?" })
        rig.advance(2500)   // window of the replay
        rig.press("120")
        env.await("act sent") { env.acts().isNotEmpty() }
        // answered: four clicks now do nothing
        rig.advance(9000)
        val n = rig.said.size
        rig.press("120,100,120,100,120,100,120"); rig.advance(800)
        assertEquals(n, rig.said.size)
    }

    // ---- done: only after real work ----
    @Test fun a_done_window_that_worked_under_30_seconds_is_not_said() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        s.fireJson.add(JSONObject().put("type", "room").put("id", "%7").put("name", "Quick").put("state", "done").put("worked_s", 5))
        rig.advance(1000); Thread.sleep(300); rig.advance(5000)
        assertFalse("quiet: ${rig.said}", rig.said("Quick"))
        s.fireJson.add(JSONObject().put("type", "room").put("id", "%8").put("name", "Slow").put("state", "done").put("worked_s", 120))
        rig.needLive("Slow, done", 0)
    }

    @Test fun old_events_after_a_restart_are_queued_not_spoken() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s)
        s.fireJson.add(needs())   // already waiting when the watcher starts: its first answer carries it
        env.events.restart()
        env.await("queued") { env.line.queue.size == 1 }
        rig.advance(3000)
        assertTrue(rig.said.isEmpty())
    }

    // ---- C5: parity ----
    @Test fun parity_push_line_and_hint() {
        val s = server(); val rig = RoboRig(); val env = Env(rig, s); env.start()
        rig.parity.start()
        try {
            s.fireJson.add(needs()); rig.needLive("Click: Go", 0)
            rig.advance(3000)
            rig.parity.assertParity()
        } finally { rig.parity.stop() }
    }

    // ---- ControllerMode border: the line comes before the media meaning, and a wake reads the queue first ----
    @Test fun the_mode_gives_the_line_the_button_before_play_pause_and_wake_reads_the_queue_first() {
        val f = Flow()
        f.r.lineTakes = true
        f.press("120"); f.advance(800)
        assertEquals(listOf(Gesture.SINGLE), f.r.lineSeen)
        assertFalse("not play/pause: ${f.r.log}", f.r.log.contains("fwd PLAY_PAUSE"))
        f.r.lineTakes = false; f.r.lineSeen.clear()
        f.press("120,100,120,100,120,100,120"); f.advance(800)
        assertTrue(f.r.lineSeen.contains(Gesture.QUAD))
        f.press("120"); f.advance(800)
        assertTrue("with no line the click is play/pause again: ${f.r.log}", f.r.log.contains("fwd PLAY_PAUSE"))
        f.r.lineWaiting = true
        f.press("900"); f.advance(800)
        assertFalse("the queue is read first, the menu waits", f.m.active)
        f.r.lineHome()
        assertTrue("the end of the queue walk opens home as today", f.m.active)
    }
}
