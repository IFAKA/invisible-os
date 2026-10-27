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
        val shown = ArrayList<Pair<String, String>>()   // (computer, room id) of every Show on phone
        override fun showOnPhone(computer: Computer, room: Room) { shown.add(computer.name to room.id) }
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
        val j = rig.mark(); rig.needLive("Wrote the design doc", j)
        rig.needLive("Go: start building", j); rig.advance(60)
        rig.press("120")
        rig.needLive("Sent to Alpha", j)
        assertTrue("fake server got ${s.posts}", s.posts.any { (path, b) -> path == "/room/act" && b.optString("id") == "%1" && b.optString("action") == "go" })
    }

    // ---- rooms_story_first ----
    @Test fun rooms_story_first() {
        val s = server(); val rig = RoboRig()
        start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark(); rig.needLive("alpha-proj, Alpha, needs you", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("Go: start building", j)
        val h = rig.said.drop(j)
        val story = h.indexOfFirst { it.contains("Wrote the design doc. It is ready.") }
        val hear = h.indexOfFirst { it.contains("Hear it again") }
        val go = h.indexOfFirst { it.contains("Go: start building") }
        assertTrue("story first, then Hear it again, then Go: $h", story in 0 until hear && hear < go)
        assertFalse("a story the list already has is not asked again", s.gets.any { it.startsWith("/room/story") })
    }

    // ---- rooms_story_asked ----
    @Test fun rooms_story_asked_when_the_list_has_none() {
        val s = server(); val rig = RoboRig()
        start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark(); rig.needLive("beta-proj, Beta, working", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("Fake story for Beta", j); rig.needLive("Hear it again", j)
        assertEquals(1, s.gets.count { it.startsWith("/room/story?id=") })
    }

    // ---- rooms_approval ----
    /** Opens the ask window; returns the mark taken just before the click, so [RoboRig.said] from it holds what the window says. */
    private fun enterAsk(rig: RoboRig, s: FakeRooms): Int {
        start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark(); rig.needLive("delta-proj, Delta, needs you", i); rig.advance(60)
        val j = rig.mark(); rig.press("120")
        return j
    }

    @Test fun rooms_approval_reads_what_it_wants_no_before_yes_and_yes_is_sent() {
        val s = server(rooms = listOf(FakeRooms.askRoom())); val rig = RoboRig()
        val j = enterAsk(rig, s)
        rig.needLive("Wants to: rm -rf build/cache", j)
        rig.needLive("That means: Deletes the build cache folder", j)
        rig.needLive("Yes", j)   // the item Yes has just started: a click picks it
        val h = rig.said.drop(j)
        val wants = h.indexOfFirst { it.contains("Wants to: rm -rf build/cache") }
        val hear = h.indexOfFirst { it.contains("Hear it again") }
        val no = h.indexOfFirst { it == "No" }
        val yes = h.indexOfFirst { it == "Yes" }
        assertTrue("order wants, hear, No, Yes: $h", wants in 0 until hear && hear < no && no < yes)
        s.posts.clear()
        rig.press("120")
        rig.needLive("Sent to Delta", j)
        assertTrue("answer posted: ${s.posts}", s.posts.any { (path, b) -> path == "/answer" && b.optString("id") == "7" && b.optString("choice") == "Allow" })
        assertTrue("nothing else was sent: ${s.posts}", s.posts.none { (path, _) -> path == "/room/act" || path == "/room/send" })
    }

    @Test fun rooms_approval_list_order_and_no_write() {
        val s = server(rooms = listOf(FakeRooms.askRoom())); val rig = RoboRig()
        val j = enterAsk(rig, s)
        rig.needLive("Read the details", j)
        val h = rig.said.drop(j)
        val at = listOf("Hear it again", "No", "Yes", "Yes and do not ask again", "Read the details").map { w -> h.indexOfFirst { it == w || (w == "Hear it again" && it.contains(w)) } }
        assertTrue("all heard, in order: $at $h", at.all { it >= 0 } && at == at.sorted())
        assertFalse("Write is not offered", h.any { it == "Write" })
    }

    @Test fun rooms_approval_has_no_always_when_the_hook_did_not_offer_it() {
        val s = server(rooms = listOf(FakeRooms.askRoom(listOf("Allow", "Deny")))); val rig = RoboRig()
        val j = enterAsk(rig, s)
        rig.needLive("Read the details", j)
        val h = rig.said.drop(j)
        assertTrue("No and Yes are offered: $h", h.contains("No") && h.contains("Yes"))
        assertFalse("no always option: $h", h.any { it.contains("do not ask again") })
    }

    // ---- rooms_hear_again ----
    @Test fun rooms_hear_again_replays_the_story() {
        val s = server(); val rig = RoboRig()
        start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark(); rig.needLive("alpha-proj, Alpha, needs you", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("Hear it again", j); rig.advance(60)
        val before = rig.said.drop(j).count { it.contains("Wrote the design doc") }
        rig.press("120")   // picks "Hear it again"
        val k = rig.mark(); rig.needLive("Wrote the design doc", k)
        rig.needLive("Hear it again", k)   // and then the options again, from the first
        assertEquals(1, before)
        assertFalse("never sent to the laptop", s.posts.any { it.first == "/room/act" })
    }

    // ---- show on phone ----
    private fun showRooms() = listOf(
        FakeRooms.room("%1", "Web", "web-proj", "claude", "done", "", listOf("show", "readall"), "Changed the page. Take a look."),
        FakeRooms.room("%2", "Fresh", "fresh-proj", "claude", "done", "", listOf("preview", "readall"), "Made the site."),
    )

    @Test fun show_on_phone_says_sent_and_calls_the_port() {
        val s = server(rooms = showRooms()); val rig = RoboRig(); val c = Computer("Home", s.url, s.token); val port = Port(listOf(c), c)
        start(rig, port)
        val i = rig.mark(); rig.needLive("web-proj, Web, done", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("Hear it again", j); rig.advance(60)
        rig.needLive("Show on phone", j); rig.advance(60); rig.press("120")
        rig.needLive("Sent to phone.", j)
        assertEquals(listOf("Home" to "%1"), port.shown)
        assertFalse("it is not an act on the laptop", s.posts.any { it.first == "/room/act" })
        assertFalse("show alone does not start anything", s.posts.any { it.first == "/room/preview/start" })
    }

    @Test fun start_preview_asks_first_then_starts_then_sends_to_phone() {
        val s = server(rooms = showRooms()); val rig = RoboRig(); val c = Computer("Home", s.url, s.token); val port = Port(listOf(c), c)
        start(rig, port)
        val i = rig.mark(); rig.needLive("fresh-proj, Fresh, done", i); rig.advance(60); rig.press("120")
        // the list started at the first window; walk to the second with one click-click? the windows list reads Web first, then Fresh
        val j = rig.mark(); rig.needLive("Start preview", j); rig.advance(60); rig.press("120")
        val k = rig.mark(); rig.needLive("Start the preview?", k); rig.needLive("No, go back", k); rig.advance(60)
        rig.needLive("Yes", k); rig.advance(60)
        assertTrue("nothing started before the Yes", s.posts.none { it.first == "/room/preview/start" })
        rig.press("120")
        rig.needLive("Sent to phone.", k)
        assertTrue("fake server got ${s.posts}", s.posts.any { (p, b) -> p == "/room/preview/start" && b.optString("id") == "%2" })
        assertEquals(listOf("Home" to "%2"), port.shown)
    }

    @Test fun notification_is_posted_with_the_window_and_opens_changes() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val room = Room("%1", "Web", "web-proj", "claude", "done", "", emptyList(), emptyList())
        ChangesNotice.post(ctx, Computer("Home", "http://x", "t"), room)
        val nm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java))
        val n = nm.allNotifications.single()
        assertEquals("Web: see changes", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        val open = org.robolectric.Shadows.shadowOf(n.contentIntent).savedIntent
        assertEquals(ChangesActivity::class.java.name, open.component?.className)
        assertEquals("%1", open.getStringExtra(ChangesNotice.EXTRA_ROOM))
        assertEquals("Home", open.getStringExtra(ChangesNotice.EXTRA_COMPUTER))
    }

    // ---- rooms_choice ----
    private var lastHost: RoboRig.Host? = null
    private fun enterWindow(rig: RoboRig, s: FakeRooms, label: String): Int {
        lastHost = start(rig, Port(listOf(Computer("Home", s.url, s.token)), Computer("Home", s.url, s.token)))
        val i = rig.mark(); rig.needLive(label, i); rig.advance(60)
        val j = rig.mark(); rig.press("120")
        return j
    }

    @Test fun rooms_choice_single_reads_question_then_options_and_a_click_picks() {
        val s = server(rooms = listOf(FakeRooms.choiceRoom())); val rig = RoboRig()
        val j = enterWindow(rig, s, "eps-proj, Eps, needs you")
        rig.needLive("Tree: Group apps by what you want to do", j)
        val h = rig.said.drop(j)
        val q = h.indexOfFirst { it.contains("Which approach do you want?") }
        val hear = h.indexOfFirst { it.contains("Hear it again") }
        val tree = h.indexOfFirst { it.startsWith("Tree:") }
        assertTrue("question, Hear it again, then the first option: $h", q in 0 until hear && hear < tree)
        s.posts.clear()
        rig.press("120")   // the item just heard
        rig.needLive("Sent to Eps", j)
        assertTrue("picked option 1: ${s.posts}", s.posts.any { (path, b) -> path == "/room/choose" && b.optString("id") == "%6" && b.getJSONArray("picks").toString() == "[1]" && !b.optBoolean("other") })
    }

    @Test fun rooms_choice_multi_toggles_and_sends_with_done() {
        val s = server(rooms = listOf(FakeRooms.choiceRoom(multi = true))); val rig = RoboRig()
        val j = enterWindow(rig, s, "eps-proj, Eps, needs you")
        rig.needLive("Tree: Group apps", j); rig.advance(60)
        val k = rig.mark(); rig.press("120")
        rig.needLive("Selected", k)
        rig.needLive("Flat list: One long list", k)   // the list goes on
        rig.needLive("Other", k); rig.needLive("Done, send", k); rig.advance(60)
        assertTrue("nothing sent by a toggle: ${s.posts}", s.posts.none { it.first == "/room/choose" })
        rig.press("120")
        rig.needLive("Sent to Eps", k)
        assertTrue("sent picks [1]: ${s.posts}", s.posts.any { (path, b) -> path == "/room/choose" && b.getJSONArray("picks").toString() == "[1]" })
    }

    // ---- rooms_more ----
    @Test fun rooms_more_reads_a_paragraph_per_click_and_triple_click_goes_back() {
        val s = server(rooms = listOf(FakeRooms.partsRoom())); val rig = RoboRig()
        val j = enterWindow(rig, s, "zeta-proj, Zeta, done")
        rig.needLive("More", j); rig.advance(60)
        val k = rig.mark(); rig.press("120")
        rig.needLive("First paragraph here.", k)
        rig.advance(5000)
        assertFalse("waits for a click: the second paragraph does not start alone", rig.said("Second paragraph", k))
        rig.press("120")
        rig.needLive("Second paragraph here.", k)
        val m = rig.mark(); rig.press("120,100,120,100,120")   // three clicks: back to the options
        rig.needLive("Hear it again", m)
        assertTrue("the paragraphs are on the app screen", lastHost!!.screens.any { (rows, _) -> rows.contains("Third paragraph here.") })
    }

    // ---- rooms_tools ----
    private fun enterTools(rig: RoboRig, s: FakeRooms, profile: String = ""): Int {
        start(rig, Port(listOf(Computer("Home", s.url, s.token, profile)), Computer("Home", s.url, s.token, profile)))
        val i = rig.mark(); rig.needLive("alpha-proj, Alpha, needs you", i); rig.advance(60); rig.press("120")
        val j = rig.mark(); rig.needLive("Tools", j)   // the item Tools has just started: a click picks it
        val k = rig.mark(); rig.press("120")
        return k
    }

    @Test fun rooms_tools_git_status_speaks_the_story() {
        val s = server(); val rig = RoboRig()
        val j = enterTools(rig, s)
        rig.needLive("Status", j)
        assertTrue("personal profile sent: ${s.gets}", s.gets.any { it.startsWith("/room/tools?id=%251") && it.contains("profile=personal") })
        rig.press("120")
        rig.needLive("Branch main. 2 changed files. Ahead 1.", j)
        val post = s.posts.last { it.first == "/room/tool" }.second
        assertEquals("git.status", post.optString("tool"))
    }

    @Test fun rooms_tools_commit_reads_cancel_before_create() {
        val s = server(); val rig = RoboRig()
        val j = enterTools(rig, s)
        rig.needLive("Commit", j); rig.press("120")
        rig.needLive("Message: Fix the loop.", j); rig.needLive("Cancel", j); rig.needLive("Create", j)
        val h = rig.said.drop(j)
        assertTrue("Cancel is read before Create: $h", h.indexOfFirst { it == "Cancel" } < h.indexOfFirst { it == "Create" })
        assertTrue("nothing committed yet", s.posts.none { it.second.optString("tool") == "git.commit.do" })
        rig.press("120")   // Create
        rig.needLive("Committed.", j)
        assertEquals("Fix the loop", s.posts.last { it.first == "/room/tool" }.second.optString("arg"))
    }

    @Test fun rooms_tools_work_profile_has_more_groups() {
        val s = server(); val rig = RoboRig()
        val j = enterTools(rig, s, "work")
        rig.needLive("Jira", j)
        assertTrue("work profile sent: ${s.gets}", s.gets.any { it.contains("profile=work") })
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
        rig.needLive("Fake story for Gamma", j); rig.needLive("Run again", j)
        assertEquals(listOf("Hear it again", "Run again", "Make test", "Stop", "Tools", "Write"), host.screens.last().first)
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
