package com.faka.invisible

import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.*
import com.faka.invisible.voicekit.*
import org.junit.Assert.*
import org.junit.Test

/*
 * LAPTOP TWIN OF tools/e2e.py (no phone, fake time, about a second in all). Each test is one phone scenario: same presses,
 * same lines heard, same screens. Real button timing in fake time (350 ms gap, 500 ms hold, 1300 ms click-then-hold, 500 ms grace).
 * The phone script stays: only the phone checks the real Bluetooth / media key path, the real TTS engine making sound, real
 * permissions, and other apps' notifications.
 *
 *  e2e scenario                  laptop test                                     covered
 *  wake_reads_home_menu          FlowTest.wake_reads_home_menu                   fully (the voice is the Rig's fake voice)
 *  click_picks                   FlowTest.click_picks                            fully
 *  triple_click_goes_back        FlowTest.triple_click_goes_back                 fully
 *  click_hold_closes             FlowTest.click_hold_closes                      fully
 *  screen_mirrors_menu           FlowTest.screen_mirrors_menu                    fully
 *  screen_tap_picks              FlowTest.screen_tap_picks                       partly: the Duel itself is not run (FlowButtonTest runs a program's raw button)
 *  practice_end_to_end           FlowButtonTest.practice_end_to_end              fully (real ButtonInput, real practice program)
 *  phone_list                    FlowTest.phone_list                             fully
 *  emergency_confirm_goes_back   FlowTest.emergency_confirm_goes_back            fully
 *  fake_incoming_call            FlowListenerTest.incoming_call                  partly: real Notification objects and the listener's call rules; the real dialer and Android posting it are the phone's
 *  fake_chat_message             FlowListenerTest.chat_message_is_read           partly: the real chat module takes a real notification; Android posting it and the real language detector are the phone's
 *  chat_arrival_spoken           FlowTest.chat_arrival_spoken                    partly: the pure burst + lines; the listener glue is in FlowListenerTest
 *  live_app_first_on_home        FlowTest.live_app_first_on_home                 partly: LiveApps.pick on fed data; the listener turning a real notification into it is the phone's
 *  voice_speed_applied           FlowTest.voice_speed_applied                    partly: the rate number the engine gets; the real engine speaking at it is the phone's
 *  rooms_go                      FlowRoomsTest.rooms_go                          fully (real HTTP to a fake Rooms server in the test)
 *  rooms_waiting_on_home         FlowRoomsTest.rooms_waiting_on_home             fully (the private count glue in Actions is mirrored in the test)
 *  rooms_done_spoken             FlowRoomsTest.rooms_done_spoken                 fully (the private roomDone glue in Actions is mirrored in the test)
 *  rooms_new_project             FlowRoomsTest.rooms_new_project                 fully
 *  computers_two                 FlowRoomsTest.computers_two                     fully (the private switch / roomDone glue in Actions is mirrored in the test)
 *  idle_clicks_go_to_the_player  FlowTest.idle_clicks_go_to_the_player           partly: choosing the real player / sending the key is the phone's
 */
class FlowTest {
    private fun flow(): Flow = Flow()

    // ---- wake_reads_home_menu ----
    @Test fun wake_reads_home_menu() {
        val f = flow(); f.press("900")
        var t = 0; while (f.heard().toSet().size < 3 && t < 20_000) { f.advance(10); t += 10 }
        assertTrue("home menu not read; heard: " + f.heard(), f.heard().toSet().size >= 3)
        assertTrue(f.active()); assertEquals(ControllerMode.Screen.HUB, f.screen())
    }

    // ---- click_picks ----
    @Test fun click_picks() {
        val f = flow(); f.m.devShow("PHONE"); val i = f.mark()
        f.heardStart("Emergency", i)
        f.press("120")   // a click right after hearing an item picks it
        f.need("Call the emergency number", i)
        assertEquals(ControllerMode.Screen.EMERGENCY, f.screen())
    }

    // ---- triple_click_goes_back ----
    @Test fun triple_click_goes_back() {
        val f = flow(); f.m.devShow("PHONE"); var i = f.mark()
        f.heardStart("Emergency", i); f.press("120"); f.advance(400)
        assertEquals(ControllerMode.Screen.EMERGENCY, f.screen())
        i = f.mark(); f.heardStart("No, go back", i)
        f.press("100,150,100,150,100"); f.advance(400)
        assertEquals(ControllerMode.Screen.PHONE, f.screen())
    }

    // ---- click_hold_closes ----
    @Test fun click_hold_closes() {
        val f = flow(); f.m.devShow("PHONE"); f.heardStart("Emergency")
        f.press("120,150,900"); f.advance(400)
        assertFalse("menu still open", f.active())
    }

    // ---- screen_mirrors_menu ----
    @Test fun screen_mirrors_menu() {
        val f = flow(); f.m.devShow("PHONE"); f.heardStart("Emergency")
        assertTrue("mirror empty while a list is read", ScreenMirror.active && ScreenMirror.items.isNotEmpty())
        f.press("120"); f.advance(400)
        assertEquals("click", ScreenMirror.lastPress)
        f.press("120,150,900"); f.advance(400)
        assertFalse("mirror still on after close", ScreenMirror.active)
    }

    // ---- screen_tap_picks ----
    @Test fun screen_tap_picks() {
        val f = flow(); f.m.devShow("GAMES"); f.heardStart("Duel")
        // a tap on the first item of the mirror, as a finger on the app screen would (Actions.tapItem)
        assertTrue(f.m.pickAt(0, ScreenMirror.items[0])); EventLog.pressed(Gesture.SINGLE)
        assertTrue("the Duel did not start: " + f.r.log, f.r.log.contains("startGame false"))
        assertEquals("click", ScreenMirror.lastPress)   // the tap shows as a click, the same as the earbud
        // a tap on text that is not the item there does nothing
        val g = flow(); g.m.devShow("GAMES"); g.heardStart("Duel")
        assertFalse(g.m.pickAt(0, "Something else")); assertFalse(g.r.log.contains("startGame false"))
    }

    // ---- phone_list ----
    @Test fun phone_list() {
        val f = flow(); f.m.devShow("PHONE"); val i = f.mark()
        f.need("Emergency", i); f.need("Call a contact", i)
        assertEquals("Emergency", f.heard(i)[0])
    }

    // ---- emergency_confirm_goes_back ----
    @Test fun emergency_confirm_goes_back() {
        val f = flow(); f.m.devShow("PHONE"); var i = f.mark()
        f.heardStart("Emergency", i); f.press("120"); f.advance(400)
        assertEquals(ControllerMode.Screen.EMERGENCY, f.screen())
        i = f.mark(); f.heardStart("No, go back", i)
        assertFalse("Yes was read before the safe answer", f.heard(i).any { it.contains("yes, call", true) })
        f.press("120"); f.advance(400)   // the click lands on "No, go back"
        assertEquals(ControllerMode.Screen.PHONE, f.screen())
        assertTrue("a call was started", f.r.log.none { it.startsWith("emergency") || it.startsWith("dial") })
    }

    // ---- chat_arrival_spoken (pure part: the burst and the lines) ----
    @Test fun chat_arrival_spoken() {
        val burst = ArrivalBurst<String>()
        val key = "com.faka.invisible|E2E Friend"
        assertTrue(burst.add(key, "Hello, are you coming to dinner tonight?", 0))     // the first of a burst starts the timer
        assertFalse(burst.add(key, "Hello, are you coming to dinner tonight?", 400))  // twice in a burst = once
        val text = burst.take(key)!!
        assertNull(burst.take(key))
        val lines = ArrivalPlan.lines("E2E app", "E2E Friend", text, "en", false).map { it.text }
        assertTrue(lines[0], "E2E Friend" in lines[0] && "," in lines[0] && lines[0].indexOf("E2E app") < lines[0].indexOf("E2E Friend"))   // the app first, then who
        assertEquals(1, lines.count { "dinner" in it })
    }

    // ---- live_app_first_on_home ----
    @Test fun live_app_first_on_home() {
        val now = 1_000_000L
        val live = LiveCandidate("run.app", "Workout", now - 1_000, notifKey = "k3", hasActions = true, chronometer = true, line = "12:30 running")
        val static = LiveCandidate("android", "Android System", now - 500, notifKey = "k4", line = "Connected")
        val picked = LiveApps.pick(listOf(static, live), now, AppWeights.SELF, { false })
        assertEquals(listOf("run.app"), picked.map { it.pkg })   // the static ongoing one (like USB debugging) is not live
        val f = flow()
        f.r.installed = listOf(InstalledApp("run.app", "Workout"), InstalledApp("com.google.android.apps.maps", "Maps"))
        f.r.live = picked
        f.r.liveNotifs["k3"] = Notif("k3", "run.app", "Workout", "E2E Workout", "12:30 running", now, listOf(NotifAction(0, "Lap", false)))
        val i = f.mark(); f.press("900")
        f.heardStart("12:30 running", i, 20_000)
        val h = f.heard(i).distinct()
        assertTrue("the live entry is not first: $h", "running" in h[0] || "running" in h.getOrElse(1) { "" })
        assertTrue("a static ongoing notification was offered: $h", h.none { "Connected" in it })
        f.advance(1300)   // a click in the first moments of an item is for the one before it (reaction grace)
        f.press("120"); val j = f.mark()
        f.need("Lap", j); f.need("Dismiss", j)
        assertEquals(ControllerMode.Screen.NOTIF_ACTIONS, f.screen())
        f.press("100,150,100,150,100"); f.advance(400)   // triple click = back to home
        assertEquals(ControllerMode.Screen.HUB, f.screen())
    }

    // ---- voice_speed_applied ----
    @Test fun voice_speed_applied() {
        // the phone sets the slider to 1.4 and wakes; the engine gets slider + a use bonus, 1.4 to 1.6
        val saved = LauncherSettings().copy(speechRate = 1.4f)
        for (picks in listOf(0, 20, 40, 400)) {
            val r = SpeechRate.effective(saved.speechRate, picks)
            assertTrue("voice rate $r", r in 1.4f..1.61f)
        }
        assertEquals(1.8f, SpeechRate.effective(1.8f, 400), 0f)   // never above 1.8
    }

    // ---- idle_clicks_go_to_the_player ----
    @Test fun idle_clicks_go_to_the_player() {
        val f = flow()
        f.press("120"); f.advance(400)
        f.press("100,150,100"); f.advance(400)
        f.press("100,150,100,150,100"); f.advance(400)
        assertEquals(listOf("fwd PLAY_PAUSE", "fwd NEXT", "fwd PREVIOUS"), f.r.log.filter { it.startsWith("fwd") })
        assertFalse(f.active())
        // which player gets it (the phone's session list is fed in): never our own session
        val ours = MediaReplay.Player(AppWeights.SELF, 3, true); val other = MediaReplay.Player("music.app", 3, false)
        val pick = MediaReplay.choose(listOf(ours, other))!!
        assertEquals("music.app", pick.pkg)
        assertEquals("pause", MediaReplay.command(MediaReplay.Action.PLAY_PAUSE, pick))
        assertEquals("next", MediaReplay.command(MediaReplay.Action.NEXT, pick))
        assertEquals("previous", MediaReplay.command(MediaReplay.Action.PREVIOUS, pick))
        assertNull(MediaReplay.choose(listOf(ours)))   // "replay N: no other player"
    }
}
