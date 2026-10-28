package com.faka.invisible

import com.faka.invisible.logickit.*

import com.faka.invisible.oskit.*

import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home programs before Set alarm and Calculator were added; the older tests count their menu items. */
private val OLD_HUB = LauncherSettings.DEFAULT_HUB - "alarm" - "calculator" - "write" - "compass" - "whereami" - "claude" - "sum" - "reset"

class ModeTest {
    internal class Rig(ranker: OptionRanker = OptionRanker.DEFAULT, gap: Long? = 800, reactionText: String = "", wakeFresh: Boolean = true) {
        val log = ArrayList<String>()
        var playing = false
        var msgs = listOf<Msg>()
        var replyOk = true
        var ringing = false
        var dnd = true
        var last = 0
        var song: String? = null
        val taskStore = TaskStore()
        var offers = listOf<Task>()
        var aiTaskList = listOf<Task>(); var aiList = listOf<Task>(); val prepared = ArrayList<String>()
        var replyOffersList = listOf<Task>(); val remembered = ArrayList<String>()
        var favs = listOf<String>()
        var suggested = listOf<String>()
        var findSeen = true
        var savedReactions = reactionText
        val pickTimes = ArrayList<Long>()   // time-to-pick of each list pick
        var now = 1_000_000L
        var vol = 7
        var wired = false
        var nav: String? = null
        var digest = ""; var shorts = mapOf<String, String>()
        var groups = listOf<NotifGroup>(); var muted = 0
        var freshUser = false; val groupPickCount = HashMap<String, Int>()   // freshUser: groups speak the names inside them until 3 picks
        var installed = listOf<InstalledApp>(); val opened = ArrayList<String>(); val mutedPkgs = HashSet<String>(); val picksOf = HashMap<String, Int>()
        var weatherAnswer: String? = null     // null = the answer has not come yet
        var pendingWeather: ((Phrase) -> Unit)? = null
        var accessOff = false
        var live = listOf<LiveApp>(); val liveNotifs = HashMap<String, Notif>(); var rooms = 0; var caller: String? = "Mama"; var extraPrograms = listOf<Pair<String, String>>()   // for the flow tests
        var codes = 100          // experienced by default; learning tests set 0
        private val timers = HashMap<String, Pair<Long, () -> Unit>>()
        /** Platform speech: starts 50 ms after the call, ends 500 ms after it. */
        private fun speech() {
            timers["tts-start"] = (now + 50) to { m.onSpeechStarted() }
            timers["tts"] = (now + 500) to { m.onSpeechDone() }
        }
        val m: ControllerMode = ControllerMode(object : ModeOutput {
            override fun speak(text: Phrase, queue: Boolean, fast: Boolean) {
                log.add(if (text.lang == null) "say ${text.text}" else "say[${text.lang}] ${text.text}"); speech()
            }
            override fun stopSpeech() { log.add("stopspeech"); timers.remove("tts"); timers.remove("tts-start") }
            override fun tone(kind: Tone) { log.add("tone $kind") }
            override fun programs() = listOf("games:duel" to "Duel", "games:echo" to "Echo", "games:guard" to "Guard", "games:howto" to "How to play", "hub:compass" to "Compass", "hub:write" to "Write", "hub:sum" to "Sum prices").plus(extraPrograms).map { (id, title) -> ProgramEntry(id, Phrase.content(title)) { error("not started in this test") } }
            override fun startProgram(id: String) { log.add(when (id) { "games:duel" -> "startGame false"; "games:howto" -> "startGame true"; else -> "start" + id.substringAfter(':').replaceFirstChar { it.uppercase() } }) }
            override fun runAction(name: String) { log.add("run $name"); speech() }
            override fun forward(what: Forward) { log.add("fwd $what") }
            override fun log(line: String) {}
            override fun pauseMusic(): Boolean { if (playing) log.add("pause"); return playing }
            override fun resumeMusic() { log.add("resume") }
            override fun notifAccessOff() = accessOff
            override fun volume(up: Boolean) { log.add("vol $up"); vol += if (up) 1 else -1 }
            override fun volumeLevel() = vol
            override fun startTimer(minutes: Int) { log.add("timer $minutes") }
            override fun setAlarm(hour: Int, minute: Int): Boolean { log.add("alarm $hour:$minute"); return true }
            override fun stopAlarm() { log.add("stop"); speech() }
            override fun timerRinging() = ringing
            override fun messages() = msgs
            override fun notifGroups() = groups
            override fun notifDigest() = digest
            override fun notifShort(n: com.faka.invisible.oskit.Notif) = shorts[n.text].orEmpty()
            override fun installedApps() = installed
            override fun pickCount(intent: String) = picksOf[intent] ?: 0
            override fun appMuted(pkg: String) = pkg in mutedPkgs
            override fun callCaller() = caller
            override fun liveApps() = live
            override fun liveNotif(key: String) = liveNotifs[key]
            override fun roomsWaiting() = rooms
            override fun answerCall(): Boolean { log.add("answer"); return true }
            override fun endCall(): Boolean { log.add("endcall"); return true }
            override fun emergency(): Boolean { log.add("emergency"); return true }
            override fun callContacts() = listOf(com.faka.invisible.logickit.Contact("Ana", "123"))
            override fun dial(c: com.faka.invisible.logickit.Contact): Boolean { log.add("dial ${c.number}"); return true }
            override fun openApp(pkg: String): Boolean { log.add("open $pkg"); opened += pkg; return true }
            override fun notifFire(key: String, index: Int): Boolean { log.add("nfire $key $index"); return true }
            override fun notifDismiss(key: String): Boolean { log.add("ndismiss $key"); groups = groups.map { it.copy(items = it.items.filter { n -> n.key != key }) }.filter { it.items.isNotEmpty() }; return true }
            override fun notifDismissAll(pkg: String?): Int { log.add("nclear ${pkg ?: "all"}"); val n = groups.filter { pkg == null || it.pkg == pkg }.sumOf { it.items.size }; groups = groups.filter { pkg != null && it.pkg != pkg }; return n }
            override fun notifMute(pkg: String) { log.add("nmute $pkg"); mutedPkgs += pkg; groups = groups.filter { it.pkg != pkg }; muted++ }
            override fun notifMutedCount() = muted
            override fun notifUnmuteAll() { log.add("nunmute"); muted = 0 }
            override fun startNotifReply(key: String, index: Int) { log.add("nreply $key $index") }
            override fun reply(key: String, text: String): Boolean { log.add("reply $key $text"); return replyOk }
            override fun status() = Status(15, 5, 80, 7, 30)
            override fun dndAllowed() = dnd
            override fun wiredConnected() = wired
            override fun findPhone(on: Boolean) { log.add("find $on") }
            override fun navigation() = nav
            override fun weather(done: (Phrase) -> Unit) { val a = weatherAnswer; if (a != null) done(Phrase.content(a)) else pendingWeather = done }
            override fun nextEvent(done: (Phrase) -> Unit) { done(Phrase.content("Dentist at 4 PM, in 40 minutes.")) }
            override fun lastTimer() = last
            override fun nowPlaying() = song
            override fun tasks() = taskStore.all()
            override fun completeTask(id: Long) = taskStore.done(id)
            override fun taskOffers() = offers
            override fun prepareAiReplies(m: Msg) { prepared.add(m.key) }
            override fun aiTasks(m: Msg) = aiTaskList
            override fun aiReplies(m: Msg) = aiList
            override fun replyOffers() = replyOffersList
            override fun rememberReply(text: String, lang: String) { remembered.add(text) }
            override fun addTask(text: String, lang: String) = taskStore.add(text, lang) != null
            override fun setLastTimer(minutes: Int) { last = minutes }
            override fun favourites() = favs
            override fun suggest(candidates: Collection<String>, now: Long, others: Collection<String>, accept: (String, Double, Int) -> Boolean) = suggested.filter { it in candidates }
            override fun groupPicks(group: String) = if (freshUser) groupPickCount[group] ?: 0 else 99
            override fun addGroupPick(group: String) { groupPickCount[group] = (groupPickCount[group] ?: 0) + 1 }
            override fun findHintSeen() = findSeen
            override fun setFindHintSeen() { findSeen = true }
            override fun loadReactions() = savedReactions
            override fun saveReactions(text: String) { savedReactions = text }
            override fun codeCount() = codes
            override fun setCodeCount(n: Int) { codes = n }
            override fun choice(screen: String, code: Int, intent: String, sender: String, pickMs: Long) { log.add("choice $screen $code $intent"); if (pickMs >= 0) pickTimes.add(pickMs) }
        }, object : Timers {
            override fun set(key: String, delayMs: Long, task: () -> Unit) { timers[key] = (now + delayMs) to task }
            override fun clear(key: String) { timers.remove(key) }
        }, { now }, ranker, pauseMs = gap, wakeReadsFresh = wakeFresh)
        init { m.settings = LauncherSettings(hub = listOf("messages", "music", "timer", "phone", "today", "games")) }   // the older timing tests count menu items
        fun withTasks() = apply { m.settings = LauncherSettings(hub = OLD_HUB) }
        fun withInfo() = apply { m.settings = LauncherSettings(hub = OLD_HUB - "tasks") }
        fun g(vararg x: Gesture) = x.forEach { m.onGesture(it) }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.entries.filter { it.value.first <= end }.minByOrNull { it.value.first } ?: break
                timers.remove(next.key); now = maxOf(now, next.value.first); next.value.second()
            }
            now = end
        }
        /** The user presses NOW; the gesture is detected 350 ms later (the click gap). */
        /** In the tests a plain click() means "choose" (the old meaning): while options are read, that is two clicks. skip() is the one click that moves on. */
        fun click(x: Gesture = Gesture.SINGLE) { advance(350); m.onGesture(x, 0) }
        fun skip() { advance(350); m.onGesture(Gesture.DOUBLE, 0) }
        fun clearLog() = log.clear()
        fun wake() { g(Gesture.HOLD) }
        fun ev() = log.filter { !it.startsWith("choice") && it != "stopspeech" }
        fun spoken() = log.filter { it.startsWith("say") }
        fun count(s: String) = log.count { it == s }
        fun has(prefix: String) = log.any { it.startsWith(prefix) }
        /** Wait until the option is spoken, press 150 ms later (while its speech or pause runs), then clear the log. */
        fun pick(label: String) {
            val re = Regex("say(\\[..\\])? " + Regex.escape(label))
            repeat(600) {
                if (log.any { re.matches(it) }) { advance(150); clearLog(); click(); return }
                advance(50)
            }
            throw AssertionError("never spoken: $label; log=$log")
        }
    }

    private val S = Gesture.SINGLE
    private val D = Gesture.DOUBLE
    private val T = Gesture.TRIPLE
    private val Q = Gesture.QUAD
    private val H = Gesture.HOLD
    private val ana = Msg("a", "Ana", "quieres venir a cenar?", "es", 999_000)
    private val bob = Msg("b", "Bob", "what time is it", "en", 999_000)
    private val info = Msg("i", "Eve", "ok see you", "en", 999_000)

    // ---------- weather, next event, find my phone ----------
    @Test fun wakeWarnsOnceWhenNotificationAccessIsOff() {
        val r = Rig(); r.accessOff = true; r.wake(); r.advance(3000)
        assertEquals("say ${Voice.NOTIF_ACCESS_OFF}", r.spoken().firstOrNull())
        r.pick("Today"); r.advance(3000)
        assertFalse(r.spoken().contains("say ${Voice.NOTIF_ACCESS_OFF}"))   // pick clears the log: not said again
    }

    @Test fun wakeSaysNoWarningWhenNotificationAccessIsOn() {
        val r = Rig(); r.wake(); r.advance(3000)
        assertTrue(r.spoken().isNotEmpty()); assertFalse(r.spoken().contains("say ${Voice.NOTIF_ACCESS_OFF}"))
    }

    @Test fun weatherSpeaksTheAnswerAfterItArrives() {
        val r = Rig().withInfo(); r.wake(); r.pick("Today"); r.pick("Weather")
        r.advance(300); assertTrue(r.spoken().isEmpty())   // waiting, nothing said yet
        r.weatherAnswer = "Now 18 degrees, rain in 2 hours."; r.pendingWeather?.invoke(Phrase.content(r.weatherAnswer!!))
        r.advance(100); assertEquals(listOf("say Now 18 degrees, rain in 2 hours."), r.spoken())
    }

    @Test fun weatherClickWhileWaitingCancelsAndLateAnswerIsDropped() {
        val r = Rig().withInfo(); r.wake(); r.pick("Today"); r.pick("Weather")
        r.click(); r.clearLog()
        r.pendingWeather?.invoke(Phrase.content("Now 1 degrees, no rain soon."))
        r.advance(100); assertFalse(r.spoken().contains("say Now 1 degrees, no rain soon."))
    }

    @Test fun weatherFailureSpeaksFixedSentence() {
        val r = Rig().withInfo(); r.weatherAnswer = Voice.WEATHER_FAILED.text; r.wake(); r.pick("Today"); r.pick("Weather")
        r.advance(100); assertEquals(listOf("say ${Voice.WEATHER_FAILED}"), r.spoken())
    }

    @Test fun nextTurnSpeaksTheInstruction() {
        val r = Rig().withInfo(); r.nav = "Turn left onto Calle Mayor, 200 m."; r.wake(); r.pick("Today"); r.pick("Next turn")
        r.advance(100); assertEquals(listOf("say Turn left onto Calle Mayor, 200 m."), r.spoken())
    }

    @Test fun nextTurnWithoutNavigationSaysSoFixedSentence() {
        val r = Rig().withInfo(); r.wake(); r.pick("Today"); r.pick("Next turn")
        r.advance(100); assertEquals(listOf("say ${Voice.NO_NAVIGATION}"), r.spoken())
    }

    @Test fun nextEventIsSpoken() {
        val r = Rig().withInfo(); r.wake(); r.pick("Today"); r.pick("Next event")
        r.advance(100); assertEquals(listOf("say Dentist at 4 PM, in 40 minutes."), r.spoken())
    }

    @Test fun findPhoneOnlyWithoutWiredOutput() {
        val a = Rig(); a.wake(); a.pick("Phone"); a.advance(10_000)
        assertTrue(a.spoken().contains("say ${Voice.FIND_PHONE}"))
        val b = Rig(); b.wired = true; b.wake(); b.pick("Phone"); b.advance(10_000)
        assertFalse(b.spoken().contains("say ${Voice.FIND_PHONE}"))
    }

    @Test fun findPhoneRingsAndAnyClickStopsIt() {
        val r = Rig().withInfo(); r.wake(); r.pick("Phone"); r.pick(Voice.FIND_PHONE.text)
        assertTrue(r.has("find true")); assertTrue(r.spoken().isEmpty())
        r.advance(5000); r.click(Gesture.DOUBLE)
        assertTrue(r.has("find false")); assertFalse(r.m.active)
    }

    @Test fun findPhoneStopsByItselfAfter20Seconds() {
        val r = Rig().withInfo(); r.wake(); r.pick("Phone"); r.pick(Voice.FIND_PHONE.text)
        r.advance(19_000); assertFalse(r.has("find false"))
        r.advance(2_000); assertTrue(r.has("find false")); assertFalse(r.m.active)
    }

    // ---------- idle ----------
    @Test fun idlePassthroughAndHoldWakes() {
        val r = Rig(); r.g(S, D, T)
        assertEquals(listOf("fwd PLAY_PAUSE", "fwd NEXT", "fwd PREVIOUS"), r.log)
        assertFalse(r.m.active)
        r.g(Q)
        assertFalse(r.m.active)   // four clicks no longer wake
        r.g(Gesture.HOLD)
        assertTrue(r.m.active)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun wakePausesMusicAndExitResumes() {
        val r = Rig(); r.playing = true; r.wake(); r.g(H)
        assertTrue(r.has("pause")); assertTrue(r.has("resume")); assertTrue(r.log.contains("tone EXIT")); assertFalse(r.m.active)
    }

    // ---------- wake context and queue order ----------
    @Test fun ringingTimerBeatsMessages() {
        val r = Rig(); r.ringing = true; r.msgs = listOf(ana); r.wake()
        assertEquals(ControllerMode.Screen.TIMER_DONE, r.m.screen)
    }

    @Test fun wakeOpensTheHubNotAMessageWhenNothingIsFresh() {
        val r = Rig(); r.favs = listOf("Ana")
        r.msgs = listOf(bob.copy(at = r.now - 250_000), ana.copy(at = r.now - 280_000)); r.wake()
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        r.advance(12000); assertTrue(r.spoken().contains("say Messages, 2 unread"))   // reachable, but unread does not decide its place
    }

    @Test fun messagesItemReadsNewestFirstAndSkipOnLastGoesToHub() {
        val r = Rig(); r.msgs = listOf(bob.copy(at = r.now - 250_000), ana.copy(at = r.now - 280_000)); r.wake()
        r.pick("Messages, 2 unread")
        r.advance(450); assertTrue(r.log.contains("say[en] Bob: what time is it"))
        r.advance(500); r.pick("Skip")
        r.advance(450); assertTrue(r.log.contains("say[es] Ana: quieres venir a cenar?"))
        r.advance(500); r.pick("Skip")
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun recentArrivalWithinTwoMinutesGoesFirst() {
        val r = Rig(); r.favs = listOf("Ana")
        r.msgs = listOf(bob.copy(at = r.now - 60_000), ana.copy(at = r.now - 250_000)); r.wake(); r.advance(450)
        assertTrue(r.log.contains("say[en] Bob: what time is it"))
    }

    @Test fun wakeOpensTheHubEvenWhenAMessageJustArrived() {
        val r = Rig(wakeFresh = false)
        r.msgs = listOf(bob.copy(at = r.now - 10_000)); r.wake(); r.advance(450)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun queueHoldsOnlyLastFiveMinutes() {
        val r = Rig(); r.msgs = listOf(bob.copy(at = r.now - 400_000)); r.wake()
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        // unread but too old for the queue: the hub offers Messages first
        r.advance(12000); assertTrue(r.log.contains("say Messages, 1 unread"))
    }

    // ---------- scan order and timing ----------
    @Test fun hubScansOneAtATimeWithPauseTickAndDing() {
        val r = Rig(); r.wake()
        assertEquals(listOf("tone HUB"), r.ev())
        r.advance(399); assertEquals(listOf("tone HUB"), r.ev())
        r.advance(1); assertEquals(listOf("tone HUB", "say Timer"), r.ev())       // t=400, speech ends t=900
        r.advance(1099); assertEquals(2, r.ev().size)                             // pause: no tick yet (tick at 1500)
        r.advance(1); assertEquals("tone TICK", r.log.last())                    // t=1500
        r.advance(199); assertFalse(r.log.contains("say Phone"))
        r.advance(1); assertEquals("say Phone", r.log.last())                   // t=1700 = 800 ms after "done"
        r.advance(1300 * 4 + 50); assertEquals(listOf("say Timer", "say Phone", "say Today", "say Games", "say Emergency"), r.spoken())
        r.advance(1300) // Games ends, pause, ding
        assertEquals(1, r.count("tone DING"))
    }

    @Test fun listLoopsByItself() {
        val r = Rig(); r.wake()
        r.advance(400 + 5 * 1300 + 800 + 600)
        assertEquals(1, r.count("tone DING"))
        assertEquals(listOf("say Timer", "say Phone", "say Today", "say Games", "say Emergency", "say Timer"), r.spoken())
        assertTrue(r.m.active)
    }

    @Test fun listEndFallsThenRisesBeforeItStartsOver() {
        val r = Rig(); r.wake()
        r.advance(400 + 5 * 1300 + 800 + 600)
        val d = r.log.indexOf("tone DING"); val u = r.log.indexOf("tone RESTART"); val again = r.log.lastIndexOf("say Timer")
        assertTrue("ding, then rise, then the first option", d in 0 until u && u < again)
        assertEquals(1, r.count("tone RESTART"))
    }

    @Test fun twoSilentLoopsLeaveWithLeaveTone() {
        val r = Rig(); r.wake(); r.advance(60_000)
        assertFalse(r.m.active)
        assertEquals(2, r.count("tone DING")); assertEquals(1, r.count("tone LEAVE")); assertEquals(10, r.spoken().size)
    }

    @Test fun aClickResetsTheSilentLoopCount() {
        val r = Rig(); r.wake(); r.advance(400 + 4 * 1300 + 900)   // one ding, list restarted
        r.clearLog(); r.pick("Phone"); r.pick("Volume")                                             // Volume screen: counter starts again
        r.advance(1300 * 4 + 400)                                    // a full loop and one ding there
        assertTrue(r.m.active)
    }

    // ---------- click binding ----------
    @Test fun clickPicksTheOptionSpokenLast() {
        val r = Rig(); r.wake(); r.advance(2300)   // Phone started at 1750; press 550 ms later
        r.click()
        assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun clickAfterNextItemStartsPicksIt() {
        val r = Rig(); r.wake(); r.advance(1900)   // Phone started at 1750 (150 ms ago): its sound has started, so a click means Phone
        r.click()
        assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
        val q = Rig(); q.wake(); q.advance(1700)   // Phone has not started yet: a click means Timer
        q.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, q.m.screen)
    }

    @Test fun clickOnTheFirstItemHasNoPreviousToFallBackTo() {
        val r = Rig(); r.wake(); r.advance(500)    // Timer started at 450
        r.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
    }

    @Test fun clickDuringThePauseAfterAnItemStillPicksIt() {
        val r = Rig(); r.wake(); r.advance(1300)   // Timer's speech ended at 900, pause until 1700
        r.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
    }

    @Test fun clickBeforeAnythingWasSpokenIsIgnored() {
        val r = Rig(); r.wake(); r.advance(10)     // still in the screen earcon gap
        r.click(); assertEquals(ControllerMode.Screen.HUB, r.m.screen); assertTrue(r.m.active)
        r.advance(60_000); assertFalse(r.m.active)
    }

    @Test fun twoClicksGoToThePreviousOptionAtOnce() {
        val r = Rig(); r.wake(); r.advance(3100)   // the second option is being spoken
        val before = r.spoken(); assertTrue(before.size >= 3)
        r.clearLog(); r.skip()
        assertTrue(r.m.active); assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        r.advance(150)                              // no pause: far sooner than the 800 ms gap
        assertEquals(before[before.size - 2], r.spoken().single())   // the one before the third
    }

    @Test fun twoClicksOnTheFirstOptionGoToTheLast() {
        val w = Rig(); w.wake(); w.advance(60_000 / 4)
        val order = w.spoken().distinct()   // every option, in reading order
        val r = Rig(); r.wake();         r.advance(1100)    // the first option (Timer) is spoken
        r.clearLog(); r.skip(); r.advance(150)
        assertEquals(order.last(), r.spoken().last())
    }

    @Test fun threeClicksGoBackOneLevelAndAtTheTopLeave() {
        val r = Rig(); r.wake(); r.advance(500); r.click()
        assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
        r.click(T); assertEquals(ControllerMode.Screen.HUB, r.m.screen); assertTrue(r.m.active)
        r.click(T); assertFalse(r.m.active); assertTrue(r.log.contains("tone LEAVE"))
    }

    @Test fun aGestureThatMadeItsOwnToneIsCountedSoTheGestureToneStaysOut() {
        val r = Rig(); r.wake(); r.advance(500); r.click()
        val n = r.m.tonesPlayed
        r.click(T)   // back to the hub: the mode itself says so (one tone for the event)
        assertTrue(r.m.tonesPlayed - n <= 1)
        val k = r.m.tonesPlayed
        r.click(T)   // top of the menu: LEAVE is the only tone
        assertEquals(1, r.m.tonesPlayed - k)
    }

    @Test fun fourClicksLeaveWithLeaveTone() {
        val r = Rig(); r.wake(); r.advance(1000); r.click(Q)
        assertFalse(r.m.active); assertEquals("tone LEAVE", r.log.last())
        r.advance(60_000); assertEquals(1, r.count("tone LEAVE"))
    }

    @Test fun holdExitsFromAnywhere() {
        val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(450); r.g(H)
        assertFalse(r.m.active); assertTrue(r.log.contains("tone EXIT"))
    }

    // ---------- message ----------
    @Test fun messageTextIsReadThenOptionsStartAfterTheTextEnds() {
        val r = Rig(); r.msgs = listOf(ana); r.wake()
        r.advance(400); assertEquals(listOf("say[es] Ana: quieres venir a cenar?"), r.spoken())   // text, no option yet
        r.advance(499); assertEquals(1, r.spoken().size)
        r.advance(1); assertEquals("say Reply yes", r.spoken().last())                    // t=900, text done
    }

    @Test fun clickSkipsTheReadingAndStartsTheOptions() {
        val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(500)
        r.click(); assertTrue(r.log.contains("stopspeech"))
        assertEquals("say Reply yes", r.spoken().last())
        assertEquals(ControllerMode.Screen.MESSAGE, r.m.screen)
        r.advance(1300 * 3); assertEquals(listOf("Reply yes", "No", "Skip", "More"), r.spoken().drop(1).map { it.removePrefix("say ") })
    }

    @Test fun replyNeverOffersBeforeTextStarted() {
        val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(99)   // the click lands at 449: text requested at 400, speech starts at 450
        r.click(); assertFalse(r.spoken().any { it.contains("Reply") })
        r.advance(2000); assertTrue(r.spoken().any { it.contains("Reply") })
    }

    @Test fun replyLabelDependsOnTypeAndLanguage() {
        val a = Rig(); a.msgs = listOf(bob); a.wake(); a.advance(1000)
        assertEquals("say Reply yes", a.spoken().last())
        val b = Rig(); b.msgs = listOf(info); b.wake(); b.advance(1000)
        assertEquals("say Reply okay", b.spoken().last())
        val c = Rig(); c.msgs = listOf(info.copy(text = "gracias, nos vemos", lang = "es")); c.wake(); c.advance(1000)
        assertEquals("say Reply okay", c.spoken().last())
    }

    @Test fun replyYesSendsAfterUndoWindow() {
        val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(1100); r.click()   // press at 1100: "Responder sí" (start 950)
        assertTrue(r.log.contains("tone UNDO")); assertTrue(r.log.contains("say Sending. Click to cancel"))
        r.advance(500 + 2999); assertFalse(r.has("reply"))
        r.advance(1); assertTrue(r.log.contains("reply a Sí, dale")); assertTrue(r.log.contains("tone SENT"))
    }

    @Test fun anyClickInsideTheUndoWindowCancels() {
        for (g in listOf(S, D, T, Q)) {
            val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(1100); r.click()
            r.advance(1500); r.click(g)
            assertTrue("cancel by $g", r.log.contains("tone CANCEL"))
            r.advance(10_000); assertFalse(r.has("reply"))
            assertEquals(ControllerMode.Screen.MESSAGE, r.m.screen); assertTrue(r.m.active)
        }
    }

    @Test fun undoPromptIsEnglishForEnglishMessageAndHoldStillExits() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(1100); r.click()
        assertTrue(r.log.contains("say Sending. Click to cancel"))
        r.advance(600); r.g(H); assertFalse(r.m.active)
        r.advance(5000); assertTrue(r.has("reply b"))   // the send still goes out
    }

    @Test fun noOnInfoMessageSendsNothing() {
        val r = Rig(); r.msgs = listOf(info); r.wake(); r.advance(500); r.click()   // skip reading; options: Reply okay, No
        r.pick("No")
        r.advance(1000); assertTrue(r.spoken().contains("say Nothing"))
        assertFalse(r.has("reply"))
    }

    @Test fun skipOnTheLastWakeMessageGoesToTheHub() {
        val r = Rig(); r.msgs = listOf(ana, bob.copy(at = r.now - 250_000)); r.wake()
        assertEquals(ControllerMode.Screen.MESSAGE, r.m.screen)
        r.advance(500); r.click(); r.pick("Skip")
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun moreOnMessageScansReadAllLaterCantNowCompose() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(500); r.click(); r.pick("More")
        r.advance(400 + 1300 * 3 + 100)
        assertEquals(listOf("say Read all", "say Later", "say Cant now", "say Compose"), r.spoken())
        assertEquals(ControllerMode.Screen.MORE_MSG, r.m.screen)
    }

    @Test fun readAllSpeaksTheTextThenScansAgain() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Read all")
        r.advance(10); assertEquals(listOf("say[en] what time is it"), r.spoken())
        r.advance(600); assertEquals("say Read all", r.spoken().last())
    }

    @Test fun composeIsOneStepAndPrefillsTheTimeWord() {
        val r = Rig(); r.msgs = listOf(ana.copy(text = "quieres venir hoy a cenar?")); r.wake(); r.advance(500); r.click()
        r.pick("More"); r.pick("Compose")
        r.advance(400 + 1300 * 3 + 600)
        assertEquals(listOf("say Yes", "say No", "say Maybe", "say Ask details"), r.spoken())
    }

    @Test fun composeYesSendsWithTimeWord() {
        val r = Rig(); r.msgs = listOf(ana.copy(text = "quieres venir hoy a cenar?")); r.wake(); r.advance(500); r.click()
        r.pick("More"); r.pick("Compose"); r.pick("Yes")
        r.advance(500 + 3000)
        assertTrue(r.log.toString(), r.log.contains("reply a Sí, hoy va bien"))
    }

    @Test fun composeBackGoesToMoreThenMessage() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Compose")
        r.advance(1000)
        r.click(T); assertEquals(ControllerMode.Screen.MORE_MSG, r.m.screen)
        r.click(T); assertEquals(ControllerMode.Screen.MESSAGE, r.m.screen)
        r.click(T); assertFalse(r.m.active)
    }

    @Test fun askDetailsSendsTheSafeQuestion() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Compose"); r.pick("Ask details")
        r.advance(4000); assertTrue(r.log.contains("reply b What time and where?"))
    }

    // ---------- option ranker ----------
    @Test fun rankerNeverReordersTheHub() {
        val rev = object : OptionRanker { override fun rank(screen: String, context: String, keys: List<String>) = keys.reversed() }
        val r = Rig(rev); r.wake(); r.advance(400 + 1300 * 5 + 100); assertEquals(listOf("say Timer", "say Phone", "say Today", "say Games", "say Emergency"), r.spoken())   // the hub keeps the user's order
    }

    @Test fun countRankerPutsMostPickedFirstAndTiesKeepDefaultOrder() {
        val counts = mapOf("b" to 3, "c" to 3, "a" to 1)
        assertEquals(listOf("b", "c", "a", "d"), CountRanker { counts[it] ?: 0 }.rank("X", "", listOf("a", "b", "c", "d")))
    }

    @Test fun countRankerFromChoiceLogAndMessageContext() {
        val log = ChoiceLog()
        repeat(2) { log.record(0, "MESSAGE", 3, "skip:INVITE", "x") }
        log.record(0, "MESSAGE", 1, "send:yes:INFO", "x")
        val ranker = CountRanker { log.intentCount(it) }
        assertEquals(listOf("skip:INVITE", "send:yes:INVITE", "send:no:INVITE", "more:INVITE"),
            ranker.rank("MESSAGE", "INVITE", listOf("send:yes:INVITE", "send:no:INVITE", "skip:INVITE", "more:INVITE")))
        val r = Rig(ranker); r.msgs = listOf(ana); r.wake(); r.advance(500); r.click()
        r.advance(10); assertTrue(r.log.toString(), r.spoken().last().contains("Skip"))
    }

    @Test fun rankerSeesMessageTypeAsContext() {
        var seen = ""
        val spy = object : OptionRanker { override fun rank(screen: String, context: String, keys: List<String>): List<String> { if (screen == "MESSAGE") seen = context; return keys } }
        val r = Rig(spy); r.msgs = listOf(ana); r.wake(); r.advance(500); r.click()
        assertEquals("INVITE", seen)
    }

    @Test fun hubHasMessagesOnlyWhenUnreadExist() {
        val a = Rig(); a.msgs = listOf(bob.copy(at = 1)); a.wake(); a.advance(12000)
        assertTrue(a.spoken().contains("say Messages, 1 unread"))
        val b = Rig(); b.wake(); b.advance(12000)
        assertFalse(b.spoken().any { it.contains("Messages") })
    }

    // ---------- timer, volume, status, more ----------
    @Test fun timerPathIsTwoPicks() {
        val r = Rig(); r.wake()
        r.pick("Timer"); r.pick("Five minutes")
        assertEquals(102, r.codes)
        r.advance(500 + 2999); assertFalse(r.has("timer"))
        r.advance(1); assertTrue(r.log.contains("timer 5")); assertEquals(5, r.last)
        assertFalse(r.m.active)
    }

    @Test fun playingIsInTheHubOnlyWhenASongExistsAndIsSpokenThenLeaves() {
        val r = Rig(); r.wake(); r.advance(400 + 1300 * 5)
        assertFalse(r.spoken().contains("say Music"))
        val q = Rig(); q.song = "Blue, Ana"; q.wake(); q.pick("Music")
        q.pick("What's playing")
        assertTrue(q.log.contains("say Blue, Ana"))
        val n = Rig(); n.song = "Blue, Ana"; n.wake(); n.pick("Music"); n.advance(400 + 1300 * 4)
        assertTrue(n.spoken().containsAll(listOf("say What's playing", "say Play something")))
    }

    @Test fun tasksAreAlwaysInTheMenu() {
        val r0 = Rig().withTasks(); r0.wake(); r0.advance(400 + 1300 * 8)
        assertTrue(r0.spoken().contains("say Tasks, none"))
    }

    @Test fun pickingATaskMarksItDoneAfterTheCancelWindow() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.taskStore.add("Call mum")
        r.wake(); r.pick("Tasks, 2")
        r.pick("Buy milk"); r.advance(1500); r.pick("Yes, done"); assertEquals(2, r.taskStore.all().size)   // not removed yet
        r.advance(5000)
        assertEquals(listOf("Call mum"), r.taskStore.all().map { it.text })
    }

    @Test fun afterATaskIsDoneTheListContinuesWithTheNextOne() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.taskStore.add("Call mum"); r.taskStore.add("Pay rent")
        r.wake(); r.pick("Tasks, 3")
        r.pick("Call mum"); r.advance(1500); r.pick("Yes, done"); r.advance(5000)
        val said = r.spoken().filter { it.startsWith("say") }
        val after = said.drop(said.indexOfLast { it.endsWith("Mark done?") || it.contains("Undo") || it.contains("undo") } + 1)
        assertTrue(said.toString(), after.firstOrNull { it.contains("milk") || it.contains("rent") }?.endsWith("Pay rent") == true)   // not "Buy milk" again
    }

    @Test fun afterANotificationIsDismissedTheListContinuesWithTheNextOne() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        val ns = listOf(Notif("k1", "com.mail", "Mail", "Ann", "one", 5), Notif("k2", "com.mail", "Mail", "Bob", "two", 4), Notif("k3", "com.mail", "Mail", "Cy", "three", 3), Notif("k4", "com.mail", "Mail", "Di", "four", 2))
        r.groups = listOf(NotifGroup("com.mail", "Mail", "com.mail", ns))
        r.installed = listOf(InstalledApp("com.mail", "Mail"), InstalledApp("com.google.android.apps.maps", "Maps"))
        r.wake(); r.pick("Mail"); r.pick("Bob. two"); r.advance(1500); r.pick("Dismiss"); r.clearLog(); r.advance(6000)
        val said = r.spoken().filter { it.startsWith("say") }
        assertTrue(said.toString(), said.first().endsWith("Cy. three"))   // not "Ann. one" again
    }

    @Test fun goingBackToAListReadsItFromTheItemYouWereOn() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        val ns = listOf(Notif("k1", "com.mail", "Mail", "Ann", "one", 5), Notif("k2", "com.mail", "Mail", "Bob", "two", 4), Notif("k3", "com.mail", "Mail", "Cy", "three", 3))
        r.groups = listOf(NotifGroup("com.mail", "Mail", "com.mail", ns))
        r.installed = listOf(InstalledApp("com.mail", "Mail"), InstalledApp("com.google.android.apps.maps", "Maps"))
        r.wake(); r.pick("Mail"); r.pick("Bob. two"); r.advance(1500)
        r.clearLog(); r.m.onGesture(Gesture.TRIPLE); r.advance(6000)   // back to Mail's list
        val said = r.spoken().filter { it.startsWith("say") }
        assertTrue(said.toString(), said.first().endsWith("Bob. two"))   // not "Ann. one"
    }

    @Test fun lastTaskDoneSaysAllDone() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.wake(); r.pick("Tasks, 1"); r.advance(1500)
        r.pick("Buy milk"); r.advance(1500); r.pick("Yes, done"); r.advance(6000)
        assertTrue(r.taskStore.all().isEmpty()); assertTrue(r.log.contains("say All done"))
    }

    @Test fun addTaskIsTheLastOptionAndTasksComeFirst() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.offers = listOf(Task(0, "Call mum", "es"))
        r.wake(); r.pick("Tasks, 1"); r.advance(2500)
        assertTrue(r.log.indexOf("say[en] Buy milk") in 0 until r.log.indexOf("say Add task"))   // reading is the common job: the task is spoken before Add task
    }

    @Test fun addTaskIsThereOnDayOneWithStarters() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.wake(); r.pick("Tasks, 1"); r.advance(6000)
        assertTrue(r.log.contains("say Add task"))
        r.pick("Add task"); r.advance(3000); assertTrue(r.log.contains("say[en] Buy groceries"))
    }

    @Test fun moreTasksPagesByFive() {
        val r = Rig().withTasks(); for (i in 1..6) r.taskStore.add("task $i")
        r.wake(); r.pick("Tasks, 6"); r.pick("More tasks"); r.advance(3500)
        assertTrue(r.log.contains("say[en] task 6"))
    }

    @Test fun clickingDuringTheCancelWindowKeepsTheTask() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.wake(); r.pick("Tasks, 1"); r.advance(1000)
        r.pick("Buy milk"); r.advance(1500); r.pick("Yes, done"); r.advance(1000); r.click()
        r.advance(5000)
        assertEquals(1, r.taskStore.all().size)
    }

    @Test fun aClickOnATaskOnlyAsksAndNoKeepsIt() {
        val r = Rig().withTasks(); r.taskStore.add("Buy milk"); r.wake(); r.pick("Tasks, 1"); r.advance(1000)
        r.pick("Buy milk"); r.advance(1500)
        assertTrue(r.log.toString(), r.log.any { it.contains("Mark done?") })
        r.pick("No, go back"); r.advance(6000)
        assertEquals(1, r.taskStore.all().size)
    }

    @Test fun longTasksAreCutAndDuplicatesAreSkipped() {
        val r = Rig().withTasks(); r.taskStore.add("x".repeat(400)); r.wake(); r.pick("Tasks, 1"); r.advance(3500)
        assertTrue(r.log.toString(), r.log.any { it.matches(Regex("say(\\[en])? x.*and more")) && it.length < 200 })
        val s = TaskStore(); assertEquals(1, s.addAll("[ ] Buy milk\n[ ] buy MILK")); assertEquals(0, s.addAll("[ ] Buy milk"))
    }

    @Test fun hubSpeaksSuggestedDeepOptionsFirstAndLeavesTheRestInPlace() {
        val r = Rig(); r.suggested = listOf("phone:torch", "info:weather", "phone:back", "hub:today")   // Back and non-deep keys are never offered
        r.wake(); r.advance(1300 * 6 + 50)
        assertEquals(listOf("say Flashlight", "say Weather", "say Timer", "say Phone"), r.spoken().take(4))
    }

    @Test fun hubOrderMatchesWhatIsSpoken() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("timer", "today", "games"), learnOrder = false)
        assertEquals(listOf("timer", "today", "games"), r.m.hubOrder())
        r.m.settings = r.m.settings.copy(learnOrder = true)
        assertEquals(listOf("timer", "today", "games"), r.m.hubOrder())   // learning never changes the user's menu order
    }

    @Test fun sharedNotesAreSplitIntoOpenTasks() {
        assertEquals(listOf("wait clio", "send exam"), TaskStore.parse("Papers\n[X] passport\n[ ] wait clio\n[x] nie\n[ ] send exam"))
        assertEquals(listOf("a b", "c"), TaskStore.parse("[ ] a\n b [X] z [ ] c"))   // joined lines too
        assertEquals(listOf("milk", "eggs"), TaskStore.parse("Shopping\n- milk\n2. eggs"))
        assertEquals(listOf("call mum tonight"), TaskStore.parse("call mum\ntonight"))
        assertTrue(TaskStore.parse("[X] only done").isEmpty())
        assertEquals(listOf("b"), TaskStore.parse("☑ a\n☐ b\n✅ c"))
    }

    @Test fun tasksCanBeReorderedAndTheOrderIsSaved() {
        var saved = listOf<String>()
        val a = TaskStore(save = { saved = it })
        listOf("a", "b", "c").forEach { a.add(it) }
        a.move(2, 0); assertEquals(listOf("c", "a", "b"), a.all().map { it.text })
        assertEquals(listOf("c", "a", "b"), TaskStore(saved).all().map { it.text })
        a.move(0, 9); assertEquals(listOf("c", "a", "b"), a.all().map { it.text })   // out of range: ignored
    }

    @Test fun taskMemoryOffersMostAddedFirstAndSkipsOpenAndMessageTasks() {
        var now = 0L; var saved = listOf<String>()
        val m = PhraseMemory(clock = { now++ }, save = { saved = it })
        m.record("Buy milk", "en"); m.record("Call mum", "en"); m.record("Buy milk", "en"); m.record("Reply to Ana: hi", "es")
        assertEquals(listOf("Buy milk", "Call mum"), m.offers(5, emptySet()).map { it.text })
        assertEquals(listOf("Call mum"), m.offers(5, setOf("buy milk")).map { it.text })
        assertEquals(listOf("Buy milk", "Call mum"), PhraseMemory(saved).offers(5, emptySet()).map { it.text })
    }

    @Test fun addTaskMenuOffersPastTasksAndAddsTheChosenOne() {
        val r = Rig().withTasks(); r.offers = listOf(Task(0, "Buy milk", "en"), Task(0, "Call mum", "es"))
        r.wake(); r.pick("Tasks, none"); r.advance(1500); assertFalse(r.log.contains("say Add a task")); r.pick("Call mum")
        assertEquals(listOf("Call mum"), r.taskStore.all().map { it.text })
        r.advance(2000); assertTrue(r.log.contains("say Task added"))
    }

    @Test fun taskOptionOnAMessageMakesATaskWithoutTyping() {
        val r = Rig(); r.msgs = listOf(ana); r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Task")
        assertTrue(r.taskStore.all().single().text.startsWith("Reply to Ana: "))
    }

    @Test fun composeOffersYourOwnPastReplies() {
        val r = Rig(); r.msgs = listOf(bob); r.replyOffersList = listOf(Task(0, "On my way", "en"))
        r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Compose"); r.advance(9000)
        assertTrue(r.log.contains("say[en] On my way"))
    }

    @Test fun aMessageStartsTheLocalModelAndComposeOffersItsSentences() {
        val r = Rig(); r.msgs = listOf(bob); r.aiList = listOf(Task(0, "Sí, me encantaría ir.", "es"))
        r.wake(); r.advance(500); assertTrue(r.prepared.isNotEmpty())
        r.click(); r.pick("More"); r.pick("Compose"); r.advance(5000)
        assertTrue(r.log.contains("say[es] Sí, me encantaría ir."))
    }

    @Test fun taskOnAMessageOffersTheModelsCleanTasks() {
        val r = Rig(); r.msgs = listOf(ana); r.aiTaskList = listOf(Task(0, "Enviar el informe el viernes", "es"))
        r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Task"); r.advance(1500)
        assertTrue(r.log.contains("say[es] Enviar el informe el viernes"))
        r.pick("Enviar el informe el viernes"); assertEquals(listOf("Enviar el informe el viernes"), r.taskStore.all().map { it.text })
    }

    @Test fun aSentReplyIsRemembered() {
        val r = Rig(); r.msgs = listOf(bob); r.wake(); r.advance(500); r.click(); r.pick("More"); r.pick("Compose"); r.pick("Yes"); r.advance(6000)
        assertEquals(1, r.remembered.size)
    }

    @Test fun taskStoreSavesAndReloads() {
        var saved = listOf<String>()
        val a = TaskStore(save = { saved = it })
        a.add("  Buy\n milk "); assertNull(a.add("   "))
        val b = TaskStore(saved); assertEquals(listOf("Buy milk"), b.all().map { it.text })
        assertEquals("en", TaskStore().also { it.addAll("[ ] Buy milk") { "en" } }.all()[0].lang)
        assertEquals(listOf(Task(7, "old task")), TaskStore(listOf("7\told task")).all())   // saved before languages existed
        assertEquals(2L, b.add("x")!!.id)
    }

    @Test fun timerOptionsAndLastUsed() {
        val r = Rig(); r.last = 7; r.wake(); r.pick("Timer")
        r.advance(400 + 600)
        assertEquals(listOf("say Last used"), r.spoken())   // the last timer is the first option: one click repeats it
        r.click(); assertTrue(r.log.contains("say Starting. Click to cancel"))
        r.advance(4000); assertTrue(r.log.contains("timer 7"))
    }

    @Test fun cancelledTimerReturnsToTheHub() {
        val r = Rig(); r.wake(); r.pick("Timer"); r.pick("Ten minutes")
        r.advance(1000); r.click(); r.advance(5000)
        assertFalse(r.has("timer")); assertEquals(ControllerMode.Screen.HUB, r.m.screen); assertTrue(r.m.active)
    }

    @Test fun volumeSpeaksOnlyTheLevelWordAndKeepsScanning() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Volume")
        r.pick("Louder")
        assertTrue(r.has("vol true")); assertEquals(ControllerMode.Screen.VOLUME, r.m.screen)
        assertEquals(listOf("say eight"), r.spoken())
        r.advance(600)   // the level is spoken, then the list restarts
        assertEquals("say Louder", r.spoken().last())
        r.advance(700); r.clearLog(); r.click()   // picks Louder again
        r.advance(1000); assertEquals("say nine", r.spoken().first())
        r.pick("Quieter"); assertTrue(r.has("vol false"))
        assertEquals(ControllerMode.Screen.VOLUME, r.m.screen)
    }

    @Test fun volumeDialHoldIsQuieterClickIsOneStepLouderTripleGoesBack() {
        val r = Rig(); r.song = "Blue, Ana"; r.wake(); r.pick("Music"); r.pick("Volume")
        assertEquals(ControllerMode.Screen.VOLUME_DIAL, r.m.screen)
        r.clearLog(); r.advance(2000)
        assertFalse(r.has("vol"))   // opening changes nothing
        r.click(Gesture.SINGLE); assertEquals(1, r.count("vol true")); assertEquals(0, r.count("vol false"))   // one click: one step louder
        r.click(Gesture.DOUBLE); assertEquals(1, r.count("vol false"))   // two clicks: one step quieter
        r.clearLog(); r.m.onGesture(Gesture.CLICK_HOLD); r.advance(1400); r.m.onGesture(Gesture.CLICK_HOLD_END, 700)   // the release does not stop it
        assertTrue(r.count("vol false") >= 3); assertFalse(r.has("vol true"))   // a hold only goes quieter
        r.click(Gesture.SINGLE); val n = r.count("vol false"); r.advance(3000); assertEquals(n, r.count("vol false")); assertFalse(r.has("vol true"))   // a press stops it, and is not a step louder
        r.click(Gesture.TRIPLE); assertEquals(ControllerMode.Screen.MUSIC, r.m.screen)
    }

    @Test fun volumeLevelIsSpokenInSpanishForASpanishMessageContext() {
        assertEquals("siete", Spoken.number(7, true)); assertEquals("seven", Spoken.number(7)); assertEquals("45", Spoken.number(45))
    }

    @Test fun volumeDoneGoesBackToPhone() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Volume"); r.pick("Done")
        assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun statusSpeaksOneSentenceThenLeaves() {
        val r = Rig(); r.msgs = emptyList(); r.wake(); r.pick("Phone"); r.pick("Status")
        r.advance(10); assertTrue(r.spoken().single().startsWith("say 3 05 PM, battery 80 percent, no unread messages, next alarm 7 30 AM"))
        assertTrue(r.m.active)
        r.advance(600); assertFalse(r.m.active); assertTrue(r.log.contains("tone EXIT"))
    }

    @Test fun noMoreInTheHubAndOrderIsFixed() {
        val a = Rig(); a.m.settings = LauncherSettings(hub = OLD_HUB); a.msgs = listOf(bob.copy(at = 1)); a.wake()
        assertFalse(a.m.hubOrder().contains("more")); assertEquals(listOf("messages", "tasks", "music", "timer", "today", "phone", "games", "alarm", "calculator", "write", "compass", "whereami", "claude", "sum", "reset"), LauncherSettings.DEFAULT_HUB)
        a.advance(400 + 1300 * 8); assertFalse(a.spoken().contains("say More")); assertFalse(a.spoken().contains("say Exit"))
    }

    @Test fun phoneGroupListsItemsInOrderAndDndOnlyWithAccess() {
        val a = Rig(); a.wake(); a.pick("Phone"); a.advance(400 + 1300 * 7 + 100)
        assertEquals(listOf("say Call a contact", "say Status", "say Volume", "say Flashlight", "say Do not disturb", "say ${Voice.FIND_PHONE}", "say Emergency"), a.spoken()); assertEquals(ControllerMode.Screen.PHONE, a.m.screen)
        val b = Rig(); b.dnd = false; b.wake(); b.pick("Phone"); b.advance(400 + 1300 * 6 + 100)
        assertEquals(listOf("say Call a contact", "say Status", "say Volume", "say Flashlight", "say ${Voice.FIND_PHONE}", "say Emergency"), b.spoken())
    }

    @Test fun phoneGroupHidesFindWhenWired() {
        val r = Rig(); r.wired = true; r.wake(); r.pick("Phone"); r.advance(400 + 1300 * 6 + 100)
        assertEquals(listOf("say Call a contact", "say Status", "say Volume", "say Flashlight", "say Do not disturb", "say Emergency"), r.spoken())
    }

    @Test fun infoAndPhoneFollowTheSettings() {
        assertEquals(listOf("turn", "weather"), LauncherSettings.cleanInfo(listOf("turn", "bogus", "weather", "turn", "back")))
        assertEquals(listOf("find", "volume"), LauncherSettings.cleanPhone(listOf("find", "x", "volume", "find")))
        assertEquals(emptyList<String>(), LauncherSettings.cleanInfo(listOf("")))
        val r = Rig().withInfo(); r.m.settings = r.m.settings.copy(info = listOf("turn", "weather")); r.wake(); r.pick("Today"); r.advance(400 + 1300 * 3 + 100)
        assertEquals(listOf("say Next turn", "say Weather", "say Next turn"), r.spoken())
        val p = Rig(); p.m.settings = p.m.settings.copy(phone = listOf("torch", "volume")); p.wake(); p.pick("Phone"); p.advance(400 + 1300 * 5 + 100)
        assertEquals(listOf("say Call a contact", "say Flashlight", "say Volume", "say Emergency", "say Call a contact"), p.spoken())
    }

    @Test fun infoGroupListsItemsInOrderAndTripleClickReturnsToTheHub() {
        val r = Rig().withInfo(); r.wake(); r.pick("Today"); r.advance(400 + 1300 * 3 + 100)
        assertEquals(listOf("say Weather", "say Next event", "say Next turn"), r.spoken()); assertEquals(ControllerMode.Screen.TODAY, r.m.screen)
        r.click(Gesture.TRIPLE); assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        val p = Rig(); p.wake(); p.pick("Phone"); p.click(Gesture.TRIPLE); assertEquals(ControllerMode.Screen.HUB, p.m.screen)
    }

    @Test fun picksInsideGroupsAreLoggedWithTheirKey() {
        val a = Rig().withInfo(); a.wake(); a.pick("Today"); a.pick("Weather"); assertTrue(a.log.any { it.endsWith("info:weather") })
        val b = Rig(); b.wake(); b.pick("Phone"); b.pick("Flashlight")
        assertTrue(b.log.any { it.endsWith("phone:torch") }); assertTrue(b.has("run Flashlight"))
    }

    @Test fun torchRunsAndTheListRestartsAfterItsSpeech() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Flashlight")
        assertTrue(r.has("run Flashlight")); r.advance(1500)
        assertTrue(r.spoken().contains("say Call a contact")); assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun emergencyAsksFirstAndTheSafeAnswerIsReadFirst() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Emergency")
        assertEquals(ControllerMode.Screen.EMERGENCY, r.m.screen)
        r.advance(3000)
        assertTrue(r.spoken().indexOf("say ${Voice.EMERGENCY_ASK.text}") < r.spoken().indexOf("say No, go back"))
        assertTrue(r.log.none { it.startsWith("emergency") })
        r.clearLog(); r.pick("No, go back"); assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun emergencyYesCalls() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Emergency"); r.pick("Yes, call now")
        assertTrue(r.has("emergency"))
    }

    @Test fun incomingCallListsAnswerAndReject() {
        val r = Rig(); r.wake(); r.m.callIncoming()
        assertEquals(ControllerMode.Screen.CALL_IN, r.m.screen)
        r.advance(4000); assertTrue(r.spoken().contains("say Call from Mama")); assertTrue(r.spoken().contains("say Answer"))
        r.clearLog(); r.pick("Answer"); assertTrue(r.has("answer")); assertEquals(ControllerMode.Screen.CALL_ACTIVE, r.m.screen)
        r.pick("End call"); assertTrue(r.has("endcall"))
    }

    @Test fun callContactsAreListedAndDialled() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Call a contact")
        assertEquals(ControllerMode.Screen.CONTACTS, r.m.screen)
        r.pick("Ana"); assertTrue(r.has("dial 123"))
    }

    @Test fun firstLayerIsShortAndInOrder() {
        assertEquals(listOf("messages", "tasks", "music", "timer", "today", "phone", "games", "alarm", "calculator", "write", "compass", "whereami", "claude", "sum", "reset"), LauncherSettings.DEFAULT_HUB)
        assertEquals(listOf("timer"), LauncherSettings.cleanHub(listOf("weather", "timer", "volume")))
    }

    @Test fun migrationDropsMoreAndUnknownKeepsOffChoicesAndReorders() {
        assertEquals(listOf("messages", "timer", "games"), LauncherSettings.withoutMore(listOf("games", "more", "bogus", "timer", "messages")))
        assertEquals(OLD_HUB, LauncherSettings.withoutMore(OLD_HUB.reversed() + "more"))
        assertEquals(emptyList<String>(), LauncherSettings.withoutMore(listOf("more")))
    }

    @Test fun oldFlatMenuBecomesGroupedKeepingOffChoices() {
        val old = listOf("playing", "tasks", "messages", "timer", "volume", "status", "weather", "turn", "calendar", "games", "find")
        assertEquals(listOf("messages", "tasks", "playing", "timer", "status", "info", "phone", "games"), LauncherSettings.groupHub(old, listOf("read", "torch", "dnd", "exit")))
        assertEquals(listOf("messages", "tasks", "timer", "games"), LauncherSettings.groupHub(listOf("tasks", "messages", "timer", "games", "more"), listOf("read", "exit")))
        assertEquals(listOf("messages", "timer", "phone"), LauncherSettings.groupHub(listOf("messages", "timer", "more"), listOf("torch", "exit")))   // Torch was ON in More
        assertEquals(listOf("messages", "info"), LauncherSettings.groupHub(listOf("messages", "turn", "more"), listOf("exit")))
        assertEquals(listOf("timer", "info"), LauncherSettings.groupHub(listOf("timer", "info"), listOf("exit")))   // already grouped: untouched
    }

    @Test fun gamesListsDuelEchoHowToPlayAndBack() {
        val r = Rig(); r.wake(); r.pick("Games"); r.advance(400 + 1300 * 4 + 100); assertEquals(listOf("say Duel", "say Echo", "say Guard", "say How to play"), r.spoken()); assertEquals(ControllerMode.Screen.GAMES, r.m.screen)
    }

    @Test fun pickingDuelLeavesTheMenuForTheGame() {
        val r = Rig(); r.wake(); r.pick("Games"); r.pick("Duel")
        assertFalse(r.m.active)   // the game takes the button
    }

    @Test fun pickingHowToPlayLeavesTheMenuForTheCoach() {
        val r = Rig(); r.wake(); r.pick("Games"); r.pick("How to play")
        assertFalse(r.m.active)
    }

    @Test fun gamesLogsItsIntentsAndWriteStarts() {
        val r = Rig(); r.wake(); r.pick("Games"); r.pick("Duel"); assertTrue(r.log.any { it.endsWith("games:duel") }); assertTrue(r.has("startGame false"))
        val e = Rig(); e.wake(); e.pick("Games"); e.pick("Echo"); assertTrue(e.has("startEcho"))
        val gd = Rig(); gd.wake(); gd.pick("Games"); gd.pick("Guard"); assertTrue(gd.has("startGuard"))
        val h = Rig(); h.wake(); h.pick("Games"); h.pick("How to play"); assertTrue(h.log.any { it.endsWith("games:howto") }); assertTrue(h.has("startGame true"))
        val w = Rig(); w.m.settings = LauncherSettings(hub = listOf("write")); w.wake(); w.pick("Write")
        assertFalse(w.m.active); assertTrue(w.has("startWrite")); assertTrue(w.log.any { it.endsWith("hub:write") })
        val c = Rig(); c.m.settings = LauncherSettings(hub = listOf("compass")); c.wake(); c.pick("Compass")
        assertFalse(c.m.active); assertTrue(c.has("startCompass")); assertTrue(c.log.any { it.endsWith("hub:compass") })
    }

    @Test fun statusIsInsideThePhoneListAndLogged() {
        val r = Rig(); r.msgs = emptyList(); r.wake(); r.pick("Phone"); r.pick("Status"); assertTrue(r.log.any { it.endsWith("phone:status") })
        assertFalse(Rig().also { it.wake(); it.advance(400 + 1300 * 5) }.spoken().contains("say Status"))   // not in the home menu
    }

    @Test fun suggestedDrawsFromTodayPhoneAndGames() {
        val r = Rig(); r.suggested = listOf("games:duel", "phone:status", "info:turn", "games:back", "today:back")
        r.wake(); r.advance(1300 * 4 + 50)
        assertEquals(listOf("say Duel", "say Status", "say Next turn", "say Timer"), r.spoken().take(4))
    }

    @Test fun oldSavedMenuMapsToApps() {
        assertEquals(listOf("messages", "tasks", "music", "timer", "today", "phone", "games"),
            LauncherSettings.migrateApps(listOf("messages", "tasks", "playing", "timer", "status", "info", "phone", "games")))
        assertEquals(listOf("music", "today", "bogus".let { "timer" }), LauncherSettings.migrateApps(listOf("playing", "bogus", "info", "more", "timer")))
        assertEquals(listOf("timer"), LauncherSettings.migrateApps(listOf("status", "timer", "timer")))
        assertEquals(listOf("music", "timer"), LauncherSettings.migrateApps(listOf("music", "timer")))
        assertEquals(listOf("timer", "games"), LauncherSettings.migrateApps(listOf("timer", "write", "duel", "games")))   // write and duel hub items fold into games   // already new: untouched
    }

    @Test fun readUnreadIsInsideMessagesAndReadsTheUnread() {
        val r = Rig(); r.msgs = listOf(bob.copy(at = 1)); r.wake(); r.pick("Messages, 1 unread")
        assertEquals(ControllerMode.Screen.MESSAGE, r.m.screen)
        r.advance(450); assertTrue(r.log.contains("say[en] Bob: what time is it"))
        r.click(T); assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    // ---------- timer done ----------
    @Test fun timerDoneOptionsStopFiveMoreIgnore() {
        val r = Rig(); r.ringing = true; r.wake()
        r.advance(400); assertEquals(listOf("say Timer done"), r.spoken())
        r.advance(500 + 1300 * 2 + 100)
        assertEquals(listOf("say Timer done", "say Stop", "say Five more minutes", "say Ignore"), r.spoken())
    }

    @Test fun timerDoneStopStopsTheAlarmThenShowsTheHub() {
        val r = Rig(); r.ringing = true; r.wake(); r.pick("Stop")
        assertTrue(r.has("stop")); r.advance(1000)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun timerDoneFiveMoreStartsFive() {
        val r = Rig(); r.ringing = true; r.wake(); r.pick("Five more minutes"); r.advance(4000)
        assertTrue(r.log.contains("timer 5"))
    }

    // ---------- learning ----------
    @Test fun learningModeSpeaksOneHintAtWakeThenOptions() {
        val r = Rig(); r.codes = 0; r.wake()
        r.advance(400)
        assertEquals(listOf("say Click when you hear it. Two clicks: back one. Three clicks: go up."), r.spoken())
        r.advance(500); assertEquals("say Timer", r.spoken().last())
        r.advance(60_000); assertEquals(1, r.spoken().count { it.contains("Click when you") })
    }

    @Test fun noHintAfterFifteenPicks() {
        val r = Rig(); r.codes = 15; r.wake(); r.advance(450)
        assertFalse(r.spoken().any { it.contains("Click when you") })
    }

    @Test fun clickSkipsTheHintAndStartsTheOptions() {
        val r = Rig(); r.codes = 0; r.wake(); r.advance(500); r.click()
        assertEquals("say Timer", r.spoken().last())
    }

    @Test fun picksAreCounted() {
        val r = Rig(); r.codes = 0; r.wake(); r.advance(500); r.click()   // skip hint
        r.pick("Timer"); assertEquals(1, r.codes)
    }

    @Test fun hintComesBeforeTheMessageText() {
        val r = Rig(); r.codes = 0; r.msgs = listOf(bob); r.wake(); r.advance(500)
        assertTrue(r.spoken().single().contains("Click when you"))
        r.advance(800); assertEquals("say[en] Bob: what time is it", r.spoken().last())
    }

    // ---------- choice log ----------
    @Test fun picksAreLoggedWithPositionAndIntent() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.pick("Volume")
        val q = Rig(); q.wake(); q.advance(2300); q.click()
        assertTrue(q.log.contains("choice HUB 2 hub:phone"))
    }

    @Test fun timeToPickIsLoggedFromTheStartOfTheList() {
        val q = Rig(); q.wake(); q.advance(2300); q.click()
        val t = q.pickTimes.single()
        assertTrue("time-to-pick $t", t in 1500..2800)
    }

    // ---------- item gap (most 1.0 s, from the end of each item's speech) ----------
    @Test fun defaultGapIsMeasuredFromTheEndOfSpeech() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake()
        r.advance(400); assertEquals(listOf("say Timer"), r.spoken())        // speech t=400..900
        r.advance(1799); assertEquals(1, r.spoken().size)                    // tick due at 900+1300=2200
        r.advance(1); assertEquals("tone TICK", r.log.last())
        r.advance(199); assertEquals(1, r.spoken().size)
        r.advance(1); assertEquals("say Phone", r.spoken().last())          // t=2400 = 1500 after done
    }

    @Test fun learnedSlowReactionsLengthenTheGapAndAClickIsSaved() {
        val r = Rig(gap = null, reactionText = List(20) { 2000 }.joinToString(",")); r.m.settings = r.m.settings.copy(itemGapMs = 1000); r.wake()   // p90 2000 + 200 -> 2200 ms instead of 1000
        r.advance(400); assertEquals(listOf("say Timer"), r.spoken())        // speech t=400..900
        r.advance(2100); assertEquals(1, r.spoken().size)                    // t=2500: the next item is not due yet (the setting alone would start it at 1900)
        r.advance(600); assertEquals("say Phone", r.spoken().last())         // t=3100: 2200 ms gap
        r.click()
        assertEquals(21, r.savedReactions.split(",").size)                   // the click was learned and saved
    }

    @Test fun defaultGapClickDuringGapBindsToTheItemJustSpoken() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake(); r.advance(2970)   // Phone spoken at 2400, over at ~2900, next item at 4400: a click now means Phone
        r.click(); assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun defaultGapClickJustAfterNextStartsPicksIt() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake(); r.advance(2400 + 50 + 100)   // Phone started at 2450, 100 ms ago
        r.click(); assertEquals(ControllerMode.Screen.PHONE, r.m.screen)   // once the sound of an item starts, a click picks it
    }

    @Test fun defaultGapTwoSilentLoopsLeave() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake()
        r.advance(400 + 4 * 2000 + 1500 + 1500 - 1)   // after the 1st ding's gap the list restarts; leave after the 2nd ding's gap
        assertTrue(r.m.active)
        r.advance(400 + 4 * 2000 + 7000)
        assertFalse(r.m.active); assertEquals(2, r.count("tone DING")); assertEquals(1, r.count("tone LEAVE"))
    }

    // ---------- click then hold: fast scroll ----------
    private fun scanReady(): Rig {
        val r = Rig(); r.wake(); r.pick("Phone")   // a hold on home is Find; fast scroll is for the other lists
        r.advance(3000)   // tone, then the first options are being read
        assertTrue(r.m.scanning())
        return r
    }

    @Test fun clickHoldScrollsFastWithFirstWordsAndTicks() {
        val r = scanReady(); r.clearLog()
        r.m.onGesture(Gesture.CLICK_HOLD)
        r.advance(2200)
        val said = r.spoken()
        assertTrue("steps in 2.2 s: $said", said.size >= 3)
        assertTrue("only first words: $said", said.all { !it.removePrefix("say ").contains(' ') })
        assertTrue(r.count("tone TICK") >= 2)
    }

    @Test fun clickHoldReleaseSpeaksTheOptionInFullAndScanContinues() {
        val r = scanReady()
        val labels = Regex("say (.*)").let { re -> r.spoken().map { re.matchEntire(it)!!.groupValues[1] } }
        r.m.onGesture(Gesture.CLICK_HOLD); r.advance(300)   // 2 steps
        r.clearLog()
        r.m.onGesture(Gesture.CLICK_HOLD_END); r.advance(200)
        val full = r.spoken()
        assertEquals(1, full.size)
        assertTrue(r.m.scanning())
        r.clearLog(); r.advance(5000)
        assertTrue("scan continues: ${r.spoken()}", r.spoken().isNotEmpty())
        assertTrue(labels.isNotEmpty())
    }

    @Test fun clickHoldReleaseThenTwoClicksChoosesThatOption() {
        val r = scanReady()
        r.m.onGesture(Gesture.CLICK_HOLD); r.advance(300)
        r.m.onGesture(Gesture.CLICK_HOLD_END); r.advance(200)
        val heard = r.spoken().last()
        r.advance(500); r.clearLog()
        r.advance(350); r.m.onGesture(D, 0)   // 2 clicks while the scan goes on from there
        assertTrue("the option was run: ${r.log}", r.log.isNotEmpty())
        assertTrue(heard.startsWith("say "))
    }

    @Test fun clickHoldWrapsWithTheRestartTone() {
        val r = scanReady(); r.clearLog()
        r.m.onGesture(Gesture.CLICK_HOLD); r.advance(4000)
        assertTrue(r.count("tone RESTART") >= 1)
    }

    @Test fun plainHoldWhenNotScanningDoesNothing_clickHoldExits() {
        val r = Rig(); r.wake()   // intro phase: not reading options yet
        r.m.onGesture(Gesture.CLICK_HOLD)
        assertTrue(r.m.active)
        r.m.onGesture(Gesture.CLICK_HOLD_END)
        assertTrue(r.m.active)
        r.m.onGesture(Gesture.HOLD)
        assertFalse(r.m.active)
    }

    @Test fun clickHoldWhenIdleWakes() {
        val r = Rig()
        r.m.onGesture(Gesture.CLICK_HOLD)
        assertTrue(r.m.active)
    }

    @Test fun plainHoldStillExits() {
        val r = scanReady()
        r.m.onGesture(H)
        assertFalse(r.m.active)
    }

    // ---------- Notifications in the tree ----------
    private fun inst(vararg p: String) = p.map { InstalledApp(it, it.substringAfterLast('.')) }

    /** Home has Today and these apps only, so it stays one flat list: Mail has 2 notifications, WhatsApp 1, Maps none. */
    private fun mail(): Rig {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        val a = Notif("k1", "com.mail", "Mail", "Boss", "Call me", 5, listOf(NotifAction(0, "Archive", false), NotifAction(1, "Reply", true)))
        val b = Notif("k2", "com.mail", "Mail", "Bank", "secret", 4, hidden = true)
        val w = Notif("k3", "com.whatsapp", "whatsapp", "t", "m", 1L)
        r.groups = listOf(NotifGroup("com.mail", "Mail", "com.mail", listOf(a, b)), NotifGroup("com.whatsapp", "whatsapp", "com.whatsapp", listOf(w)))
        r.installed = listOf(InstalledApp("com.mail", "Mail"), InstalledApp("com.whatsapp", "whatsapp"), InstalledApp("com.google.android.apps.maps", "Maps"))
        return r
    }

    @Test fun aSpanishAppNameIsSpokenInSpanishAndAnEnglishOneIsNot() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        r.installed = listOf(InstalledApp("com.x.pago", "Mercado Pago"), InstalledApp("com.google.android.apps.maps", "Maps"))
        r.wake(); r.advance(6000)
        assertTrue(r.spoken().toString(), r.spoken().contains("say[es] Mercado Pago"))
        assertTrue(r.spoken().toString(), r.spoken().contains("say Maps"))
    }

    /** Many apps with no notifications, so home must hold groups. */
    private fun many(): Rig {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        r.installed = inst("com.chase.sig.android", "com.nu.production", "com.bankinter.launcher", "com.coinbase.android", "de.number26.android", "com.revolut.revolut", "com.transferwise.android",
            "com.paypal.android.p2pmobile", "com.venmo", "com.termux", "com.tailscale.ipn", "com.dropbox.android", "com.atlassian.android.jira.core")
        return r
    }

    @Test fun notificationsHubItemIsGone() {
        assertFalse(LauncherSettings.HUB_ITEMS.containsKey("notifications"))
        assertEquals(listOf("messages", "tasks"), LauncherSettings.cleanHub(listOf("messages", "notifications", "tasks")))
        assertFalse(LauncherSettings.migrateApps(listOf("messages", "notifications")).contains("notifications"))
    }

    @Test fun homeNeverLabelsUnreadButDoesNotReorderByIt() {
        val r = mail(); r.wake(); r.advance(12000)
        assertTrue(r.spoken().contains("say Mail")); assertTrue(r.spoken().contains("say whatsapp")); assertTrue(r.spoken().contains("say Maps"))
        val order = r.spoken().map { it.removePrefix("say ") }
        assertTrue(order.indexOf("Maps") < order.indexOf("Mail") || order.indexOf("Maps") > order.indexOf("Mail"))   // all apps are there, equal weights keep a stable order
        val b = mail(); b.groups = emptyList(); b.wake(); b.advance(12000)
        assertEquals(r.spoken().map { it.substringBefore(",") }.filter { it != "say Notifications" }.take(7), b.spoken().map { it.substringBefore(",") }.take(7))   // the Notifications entry is the only difference
    }

    @Test fun morePickedAppMovesUpOnTheNextHomeList() {
        val a = mail(); a.picksOf["app:com.google.android.apps.maps"] = 4; a.wake(); a.advance(2000)
        assertEquals("say Maps", a.spoken().first { it.contains("Maps") || it.contains("Mail") || it.contains("whatsapp") })
    }

    @Test fun groupsOfAppsWithoutNotificationsAreWalkedAndLeftWithTripleClick() {
        val r = many(); r.wake(); r.advance(12000)
        val group = r.spoken().map { it.removePrefix("say ") }.first { it in setOf("Money", "Tools", "Pay", "Banks", "Dev") }
        r.pick(group); r.advance(3000)
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun appWithoutNotificationsOffersOpenAppAndBack() {
        val r = mail(); r.wake(); r.pick("Maps")
        while (!r.spoken().contains("say Open app")) r.advance(50)
        r.advance(300); r.click(); r.advance(1000)
        assertTrue(r.log.toString(), r.has("open com.google.android.apps.maps"))
    }

    @Test fun notificationsHiddenWhenEmpty() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("timer")); r.wake(); r.advance(100)
        assertFalse(r.spoken().any { it.contains("Notifications") })
    }

    @Test fun suggestedSlotOffersAnAppFromBelowHomeAndPickingItLogsTheTreeIntent() {
        val r = many(); r.suggested = listOf("app:com.termux")
        r.wake(); r.advance(450 + 700); assertEquals("say termux", r.spoken().first())   // in front of home, though it lives in a group
        r.advance(400); r.click()
        assertTrue(r.log.contains("choice HUB -1 suggest:hit"))
        assertTrue(r.log.any { it.endsWith("app:com.termux") })
        assertEquals(ControllerMode.Screen.APP_OPEN, r.m.screen)
    }

    // ---------- Find: a hold on home ----------
    private fun hold(r: Rig) { r.advance(450 + 300); r.m.onGesture(Gesture.CLICK_HOLD, 0) }

    @Test fun holdOnHomeStartsFindAndPickingALeafDoesWhatTheTreeDoes() {
        val r = mail(); r.findSeen = false; r.wake(); hold(r)
        assertEquals(ControllerMode.Screen.FIND, r.m.screen)
        r.advance(3000)
        assertTrue(r.spoken().contains("say Find. Pick the first letter."))   // the first time only
        assertTrue(r.log.contains("choice HUB 7 find"))
        r.clearLog(); r.pick("M"); r.pick("Maps")
        assertEquals(ControllerMode.Screen.APP_OPEN, r.m.screen)
        assertTrue(r.log.any { it.endsWith("find:2") } && r.log.any { it.endsWith("app:com.google.android.apps.maps") })
    }

    @Test fun findHintIsSaidOnlyOnce() {
        val r = mail(); r.wake(); hold(r); r.advance(3000)
        assertFalse(r.spoken().any { it.contains("Pick the first letter") })
    }

    @Test fun findWithManyAppsSpeaksRangesAndTripleClickGoesBack() {
        val r = many(); r.wake(); hold(r); r.advance(2500)
        assertTrue(r.spoken().any { it.contains(" to ") || it.matches(Regex("say [A-Z][a-z]*")) })   // ranges such as "B to Br"
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun holdInAnotherListKeepsFastScroll() {
        val r = Rig(); r.wake(); r.pick("Phone"); r.advance(1500)
        r.m.onGesture(Gesture.CLICK_HOLD, 0)
        assertEquals(ControllerMode.Screen.PHONE, r.m.screen)
    }

    @Test fun walkTreeToAnAppAndBackWithTripleClick() {
        val r = mail(); r.wake(); r.pick("Mail")
        r.advance(3000); assertEquals(ControllerMode.Screen.NOTIF_LIST, r.m.screen)
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun notificationReadInFullThenActionFires() {
        val r = mail(); r.wake(); r.pick("Mail")
        r.pick("Boss. Call me"); r.advance(1500)
        assertTrue(r.spoken().contains("say Boss. Call me"))   // read in full, then the actions
        r.pick("Archive")
        assertTrue(r.has("nfire k1 0"))
    }

    @Test fun privateNotificationSpeaksOnlyTheApp() {
        val r = mail(); r.wake(); r.pick("Mail")
        r.advance(5000)
        assertTrue(r.spoken().contains("say Private notification"))
        assertFalse(r.spoken().any { it.contains("secret") || it.contains("Bank") })
    }

    @Test fun textActionGoesToWriteAndDismissRemoves() {
        val r = mail(); r.wake(); r.pick("Mail"); r.pick("Boss. Call me")
        r.pick("Reply")
        assertTrue(r.has("nreply k1 1"))
        val d = mail(); d.wake(); d.pick("Mail"); d.pick("Boss. Call me"); d.pick("Dismiss")
        assertTrue(d.has("ndismiss k1"))
    }

    @Test fun notificationsEntryAppearsOnlyWithNotificationsAndCountsThem() {
        val r = mail(); r.wake(); r.advance(12000)
        val order = r.spoken().map { it.removePrefix("say ") }
        assertTrue(order.toString(), "Notifications, 3" in order)
        val e = mail(); e.groups = emptyList(); e.wake(); e.advance(12000)
        assertFalse(e.spoken().any { it.startsWith("say Notifications") })
    }

    // ---------- The laptop's model for notifications: digest and long messages ----------
    @Test fun digestIsSpokenAfterTheNotificationsCountOnlyWhenKnown() {
        val r = mail(); r.digest = "2 chats, 1 junk"; r.wake(); r.advance(12000)
        val order = r.spoken().map { it.replace(Regex("^say(\\[..\\])? "), "") }
        assertTrue(order.toString(), "Notifications, 3. 2 chats, 1 junk" in order)
        val n = mail(); n.wake(); n.advance(12000)   // no laptop, or no answer yet: as before
        assertTrue(n.spoken().map { it.replace(Regex("^say(\\[..\\])? "), "") }.toString(), "Notifications, 3" in n.spoken().map { it.replace(Regex("^say(\\[..\\])? "), "") })
    }

    private val longText = (1..45).joinToString(" ") { "word$it" }
    private fun longMsg(shortText: String?): Rig {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("today"))
        r.groups = listOf(NotifGroup("com.chat", "Chat", "com.chat", listOf(Notif("k9", "com.chat", "Chat", "Ana", longText, 5L))))
        r.installed = listOf(InstalledApp("com.chat", "Chat"))
        if (shortText != null) r.shorts = mapOf(longText to shortText)
        return r
    }

    @Test fun aLongMessageIsReadAsItsShortVersionAndReadAllGivesTheOriginal() {
        val r = longMsg("Ana sent a long list of words."); r.wake(); r.pick("Notifications, 1"); r.pick("Chat, 1 notification"); repeat(600) { if (r.log.any { it == "say Read all" }) return@repeat; r.advance(50) }
        val order = r.spoken().map { it.replace(Regex("^say(\\[..\\])? "), "") }
        assertTrue(order.toString(), "Ana: Ana sent a long list of words." in order)
        assertFalse("the original is not read first", order.any { it.contains("word30") })
        assertTrue("Read all is the first option: $order", order.indexOf("Read all") > order.indexOf("Ana: Ana sent a long list of words."))
        r.advance(150); r.clearLog(); r.click(); r.advance(3000)
        assertTrue(r.log.toString(), r.log.any { it.contains("word45") })
    }

    @Test fun aLongMessageWithoutAnAnswerYetIsReadAsToday() {
        val r = longMsg(null); r.wake(); r.pick("Notifications, 1"); r.pick("Chat, 1 notification"); r.advance(15000)
        val order = r.spoken().map { it.replace(Regex("^say(\\[..\\])? "), "") }
        assertTrue(order.toString(), order.any { it.contains("word1 ") })
        assertFalse(order.toString(), "Read all" in order)
    }

    @Test fun notificationsListHasClearAllLastAfterOnlyAppsWithNotifications() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.advance(6000)
        assertEquals(ControllerMode.Screen.NOTIF_APPS, r.m.screen)
        val order = r.spoken().map { it.removePrefix("say ") }.dropWhile { it != "Mail, 2 notifications" && it != "whatsapp, 1 notification" }
        assertEquals(setOf("Mail, 2 notifications", "whatsapp, 1 notification"), order.take(2).toSet())
        assertEquals("Clear all", order[2]); assertFalse(order.any { it == "Maps" })
    }

    @Test fun clearAllInNotificationsListClearsEveryAppSpeaksCountAndGoesHome() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("Clear all"); r.advance(3000)
        assertTrue(r.has("nclear all"))
        assertTrue(r.spoken().contains("say 3 notifications cleared"))
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun appWithTwoNotificationsReadsThemThenClearAllFromThisAppLast() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("Mail, 2 notifications"); r.advance(15000)
        val order = r.spoken().map { it.removePrefix("say ") }
        assertTrue(order.indexOf("Boss. Call me") >= 0 && order.indexOf("Clear all from this app") > order.indexOf("Boss. Call me"))
    }

    @Test fun clearAllFromThisAppGoesBackToTheAppsListWithoutThatApp() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("Mail, 2 notifications"); r.pick("Clear all from this app"); r.advance(3000)
        assertTrue("log=${r.log} screen=${r.m.screen}", r.has("nclear com.mail")); assertEquals(ControllerMode.Screen.NOTIF_APPS, r.m.screen)
        assertFalse(r.spoken().takeLast(6).any { it == "say Mail, 2 notifications" })
    }

    @Test fun appWithOneNotificationSkipsTheListAndReadsItThenItsActions() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("whatsapp, 1 notification"); r.advance(3000)
        assertEquals(ControllerMode.Screen.NOTIF_ACTIONS, r.m.screen)
        assertTrue(r.spoken().contains("say t. m")); assertTrue(r.spoken().contains("say Dismiss"))
        assertFalse(r.spoken().contains("say Clear all from this app"))
    }

    @Test fun singleNotificationIsReadAgainWhenItsOptionsLoop() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("whatsapp, 1 notification"); r.advance(40000)
        val said = r.spoken()
        val i = said.indexOf("say Open app")
        assertTrue(said.toString(), i >= 0 && said.drop(i + 1).contains("say t. m"))   // after the last action: the title again
    }

    @Test fun backFromSingleNotificationGoesToNotificationsListThenHome() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("whatsapp, 1 notification"); r.advance(3000)
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.NOTIF_APPS, r.m.screen)
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun backFromAppListGoesToNotificationsListAndFromTreeGoesToTree() {
        val r = mail(); r.wake(); r.pick("Notifications, 3"); r.pick("Mail, 2 notifications"); r.advance(3000)
        r.m.onGesture(Gesture.TRIPLE); r.advance(300)
        assertEquals(ControllerMode.Screen.NOTIF_APPS, r.m.screen)
        val t = mail(); t.wake(); t.pick("Mail"); t.pick("Boss. Call me"); t.advance(3000)
        t.m.onGesture(Gesture.TRIPLE); t.advance(300)
        assertEquals(ControllerMode.Screen.NOTIF_LIST, t.m.screen)
        t.m.onGesture(Gesture.TRIPLE); t.advance(300)
        assertEquals(ControllerMode.Screen.HUB, t.m.screen)   // this tree is flat: the app was on home
    }

    @Test fun muteThisAppGoesHomeWithoutIt() {
        val r = mail(); r.wake(); r.pick("Mail"); r.pick("Boss. Call me"); r.pick("Mute this app")
        assertTrue(r.has("nmute com.mail"))
        r.advance(30000)
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        assertTrue(r.spoken().contains("say whatsapp"))   // the home list was rebuilt without Mail
        assertFalse(r.spoken().any { it.startsWith("say Mail") })
        assertTrue(r.spoken().contains("say Unmute all apps"))
    }

    @Test fun calculatorSpeaksTheAnswer() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = LauncherSettings.DEFAULT_HUB - "write" - "compass"); r.wake()
        r.pick("Tools"); r.pick("Calculator"); r.pick("Two"); r.pick("Plus"); r.pick("Three"); r.pick("Equals")
        r.advance(3000)
        assertTrue(r.spoken().toString(), r.spoken().any { it.endsWith(" 5") })
    }

    @Test fun sumPricesStartsTheSumProgram() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = LauncherSettings.DEFAULT_HUB - "write" - "compass"); r.wake()
        r.pick("Sum prices")
        assertTrue(r.has("startSum"))
    }

    @Test fun setAlarmAsksHourThenMinuteAndSetsItAfterTheCancelWindow() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = LauncherSettings.DEFAULT_HUB - "write" - "compass" - "sum"); r.wake()
        r.pick("Set alarm"); r.pick("7 AM"); r.pick("Thirty")
        assertFalse(r.has("alarm"))
        r.advance(8000)
        assertTrue(r.log.toString(), r.has("alarm 7:30"))
    }
}
