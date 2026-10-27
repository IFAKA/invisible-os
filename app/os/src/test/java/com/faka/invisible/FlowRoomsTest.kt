package com.faka.invisible

import com.faka.invisible.claude.*
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Voice
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Laptop tool and the room events against a fake Rooms server on localhost (real HTTP, real BridgeClient, real ClaudeAndroid and
 * ClaudeWatch and RoomEvents, the same class Actions uses; real Android handler in fake time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowRoomsTest {
    private val servers = ArrayList<FakeRooms>()
        private fun server(token: String = "e2e-token", rooms: List<org.json.JSONObject> = FakeRooms.DEFAULT_ROOMS) = FakeRooms(token, rooms).also { servers.add(it) }
    @After fun stop() { servers.forEach { it.stop() } }

    private class Port(var all: List<Computer>, var current: Computer?, val onSwitch: (String) -> Unit = {}) : RoomsPort {
        override fun computers() = all
        override fun active() = current
        override fun switchTo(name: String) { current = all.first { it.name == name }; onSwitch(name) }
        override fun write(sendTo: (String) -> Boolean) {}
        override fun writeSeq(cues: List<Phrase>, finish: (List<String>) -> Phrase) { cues.forEach { cueSeen.add(it.text) } }
        override fun testDone() {}
        val cueSeen = ArrayList<String>()
        companion object { val cueSeen = ArrayList<String>() }
    }

    private fun start(rig: RoboRig, port: RoomsPort): RoboRig.Host {
        val host = rig.Host(); val p = ClaudeAndroid(port); rig.running = p; p.start(host); return host
    }

    // ---- rooms_go ----
    @Test fun rooms_go() {
        val s = server(); val rig = RoboRig()
        start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark()
        rig.needLive("alpha-proj, Alpha, needs you", i); rig.advance(60)
        rig.press("120")   // picks Alpha, the item just heard
        val j = rig.mark(); rig.needLive("The design doc is ready", j)
        rig.needLive("Go ahead", j); rig.advance(60)
        rig.press("120")
        rig.needLive("Sent to Alpha", j)
        assertTrue("fake server got ${s.posts}", s.posts.any { (path, b) -> path == "/room/act" && b.optString("id") == "%1" && b.optString("action") == "go" })
    }

    // ---- rooms_new_project ----
    @Test fun rooms_new_project() {
        val s = server(); val rig = RoboRig(); val port = Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token))
        start(rig, port)
        val i = rig.mark(); rig.needLive("New window", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("New project", j); rig.advance(60); rig.press("120")
        val k = rig.mark(); rig.needLive("hardware", k); rig.advance(60); rig.press("120")
        val t0 = System.currentTimeMillis()
        while (port.cueSeen.isEmpty() && System.currentTimeMillis() - t0 < 10_000) { rig.advance(50); Thread.sleep(5) }
        assertTrue("the name cue never came: ${port.cueSeen}", port.cueSeen.any { it.contains("Write the project name", true) })
    }

    /** The OS's own room-event code ([RoomEvents], the same class [Actions] uses), on the test rig's handler and voice. */
    private inner class Os(val port: Port, val rig: RoboRig) {
        var running = false
        val events = RoomEvents(rig.handler, { port.current }, { running }, { false }, {}, { rig.said.add(it.text) })
        val watch = events
        fun restart() = events.restart()
        fun waitingBlocking(): Int { events.waitingNow(); val t0 = System.currentTimeMillis(); var n = 0
            while (System.currentTimeMillis() - t0 < 10_000) { Thread.sleep(20); n = events.waitingNow(); if (n > 0) break }; return n }
    }

    // ---- rooms_done_spoken ----
    @Test fun rooms_done_spoken() {
        val s = server(); val rig = RoboRig(); val c = Computer("Home", s.url, s.token)
        val os = Os(Port(listOf(c), c), rig)
        val i = rig.mark(); os.restart(); s.fire.add("Delta")
        rig.needLive("Delta, done", i)
        assertEquals(Voice.roomDone("Delta").text, rig.said.last { it.contains("Delta") })
    }

    // ---- rooms_waiting_on_home ----
    @Test fun rooms_waiting_on_home() {
        val s = server(); val rig = RoboRig(); val c = Computer("Home", s.url, s.token)
        val os = Os(Port(listOf(c), c), rig)
        val waiting = os.waitingBlocking()   // the count renewed at the first wake
        assertEquals(1, waiting)   // Alpha needs you
        val f = Flow(); f.r.rooms = waiting
        f.r.extraPrograms = listOf("hub:claude" to "Laptop"); f.m.settings = LauncherSettings(hub = listOf("claude", "timer", "today", "games"))
        val i = f.mark(); f.press("900")
        f.heardStart("needs you", i, 20_000)
        val h = f.heard(i).distinct()
        assertTrue("the waiting entry is not first: $h", h.take(5).any { "needs you" in it })
    }

    // ---- computers_two ----
    @Test fun computers_two() {
        val home = server("tok-home"); val work = server("tok-work", listOf(FakeRooms.room("%5", "Zeta", "zeta-proj", "claude", "working", "", listOf("stop", "readall", "screen", "write"))))
        val h = Computer("Home", home.url, "tok-home"); val w = Computer("Work", work.url, "tok-work")
        val rig = RoboRig()
        lateinit var os: Os
        val port = Port(listOf(h, w), h) { os.restart() }
        os = Os(port, rig)
        os.restart()
        val i = rig.mark(); work.fire.add("Omega"); home.fire.add("Sigma")
        rig.needLive("Sigma, done", i)
        rig.advance(5000); Thread.sleep(500); rig.advance(500)
        assertFalse("heard Work (Omega) while Home is the active computer", rig.said("Omega", i))
        val j = rig.mark()
        val host = rig.Host(); val p = ClaudeAndroid(port); rig.running = p; os.running = true; p.start(host)
        rig.needLive("alpha-proj, Alpha", j)   // the windows list is being read
        p.tap(host.screens.last().first.indexOf("Laptop settings"))   // the settings are the last entry; the app screen taps it
        rig.needLive("Switch to Work", j); rig.advance(60); rig.press("120")
        rig.needLive("Work, listening", j); rig.needLive("zeta-proj, Zeta, working", j)
        p.stop(); rig.running = null; os.running = false
        val m = rig.mark(); work.fire.add("Psi")
        rig.needLive("Psi, done", m)
    }

    // ---- new flow: the windows list, the app screen, tap, retry ----
    @Test fun windows_list_and_tap() {
        val s = server(); val rig = RoboRig(); val c = Computer("Home", s.url, s.token)
        val host = start(rig, Port(listOf(c), c))
        val i = rig.mark(); rig.needLive("alpha-proj, Alpha, needs you", i)
        val rows = host.screens.last().first
        assertEquals(listOf("alpha-proj, Alpha, needs you", "beta-proj, Beta, working", "gamma-proj, Gamma, done", "New window", "Laptop settings"), rows)
        assertEquals("the marked row is the one read", 0, host.screens.last().second)
        rig.advance(2500); assertTrue("the mark moved on: ${host.screens.takeLast(3)}", host.screens.last().second >= 1)
        val j = rig.mark(); (rig.running as ClaudeAndroid).tap(2)   // Gamma, a shell window
        rig.needLive("build finished", j); rig.needLive("Run again", j)
        assertEquals(listOf("Run again", "Make test", "Stop", "Write"), host.screens.last().first)
    }

    @Test fun retry_gives_feedback_and_speaks_again() {
        val rig = RoboRig(); val c = Computer("Home", "http://127.0.0.1:1", "x")
        val host = start(rig, Port(listOf(c), c))
        val i = rig.mark(); rig.needLive("Laptop not reachable", i)
        assertEquals("Laptop not reachable. Click to try again.", host.screens.last().first.single())
        val j = rig.mark(); rig.advance(60); rig.press("120"); rig.advance(400)   // a click
        assertTrue("no feedback: ${rig.said.drop(j)}", rig.said("Trying again", j)); assertEquals("Trying again", host.screens.last().first.single())
        rig.needLive("Laptop not reachable", j)   // the retry failed: it speaks again, never silent
        val k = rig.mark(); (rig.running as ClaudeAndroid).tap(0)   // a tap does the same
        rig.needLive("Trying again", k); rig.needLive("Laptop not reachable", k)
    }

    @Test fun hold_is_fast_scroll_and_triple_click_leaves() {
        val s = server(); val rig = RoboRig(); val c = Computer("Home", s.url, s.token)
        val host = start(rig, Port(listOf(c), c))
        rig.needLive("alpha-proj, Alpha", rig.mark()); val i = rig.mark()
        rig.press("1400")   // a hold: fast scroll, then the item reached is spoken in full on release
        rig.advance(300)
        assertTrue("nothing spoken by the fast scroll: ${rig.said.drop(i)}", rig.mark() > i)
        rig.press("100,100,100,100,100"); rig.advance(800)   // three clicks: back from the windows list leaves the tool
        assertEquals(1, host.ended)
    }
}
