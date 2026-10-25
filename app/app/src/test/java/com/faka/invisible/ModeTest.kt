package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTest {
    private class Rig(ranker: OptionRanker = OptionRanker.DEFAULT, gap: Long = 800) {
        val log = ArrayList<String>()
        var playing = false
        var msgs = listOf<Msg>()
        var replyOk = true
        var ringing = false
        var dnd = true
        var last = 0
        var song: String? = null
        val taskStore = TaskStore()
        var favs = listOf<String>()
        var now = 1_000_000L
        var vol = 7
        var codes = 100          // experienced by default; learning tests set 0
        private val timers = HashMap<String, Pair<Long, () -> Unit>>()
        /** Platform speech: starts 50 ms after the call, ends 500 ms after it. */
        private fun speech() {
            timers["tts-start"] = (now + 50) to { m.onSpeechStarted() }
            timers["tts"] = (now + 500) to { m.onSpeechDone() }
        }
        val m: ControllerMode = ControllerMode(object : ModeOutput {
            override fun speak(text: String, queue: Boolean, lang: String?, fast: Boolean) {
                log.add(if (lang == null) "say $text" else "say[$lang] $text"); speech()
            }
            override fun stopSpeech() { log.add("stopspeech"); timers.remove("tts"); timers.remove("tts-start") }
            override fun tone(kind: Tone) { log.add("tone $kind") }
            override fun runAction(name: String) { log.add("run $name"); speech() }
            override fun forward(what: Forward) { log.add("fwd $what") }
            override fun log(line: String) {}
            override fun pauseMusic(): Boolean { if (playing) log.add("pause"); return playing }
            override fun resumeMusic() { log.add("resume") }
            override fun volume(up: Boolean) { log.add("vol $up"); vol += if (up) 1 else -1 }
            override fun volumeLevel() = vol
            override fun startTimer(minutes: Int) { log.add("timer $minutes") }
            override fun stopAlarm() { log.add("stop"); speech() }
            override fun timerRinging() = ringing
            override fun messages() = msgs
            override fun reply(key: String, text: String): Boolean { log.add("reply $key $text"); return replyOk }
            override fun status() = Status(15, 5, 80, 7, 30)
            override fun dndAllowed() = dnd
            override fun lastTimer() = last
            override fun nowPlaying() = song
            override fun tasks() = taskStore.all()
            override fun completeTask(id: Long) = taskStore.done(id)
            override fun setLastTimer(minutes: Int) { last = minutes }
            override fun favourites() = favs
            override fun codeCount() = codes
            override fun setCodeCount(n: Int) { codes = n }
            override fun choice(screen: String, code: Int, intent: String, sender: String) { log.add("choice $screen $code $intent") }
        }, object : Timers {
            override fun set(key: String, delayMs: Long, task: () -> Unit) { timers[key] = (now + delayMs) to task }
            override fun clear(key: String) { timers.remove(key) }
        }, { now }, ranker, pauseMs = gap)
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
        fun click(x: Gesture = Gesture.SINGLE) { advance(350); m.onGesture(x, 0) }
        fun clearLog() = log.clear()
        fun wake() { g(Gesture.HOLD) }
        fun ev() = log.filter { !it.startsWith("choice") && it != "stopspeech" }
        fun spoken() = log.filter { it.startsWith("say") }
        fun count(s: String) = log.count { it == s }
        fun has(prefix: String) = log.any { it.startsWith(prefix) }
        /** Wait until the option is spoken, press 700 ms later (while its speech or pause runs), then clear the log. */
        fun pick(label: String) {
            val re = Regex("say(\\[..\\])? " + Regex.escape(label))
            repeat(600) {
                if (log.any { re.matches(it) }) { advance(700); clearLog(); click(); return }
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
        r.advance(450); assertEquals("say Messages, 2 unread", r.spoken().last())
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

    @Test fun queueHoldsOnlyLastFiveMinutes() {
        val r = Rig(); r.msgs = listOf(bob.copy(at = r.now - 400_000)); r.wake()
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        // unread but too old for the queue: the hub offers Messages first
        r.advance(450); assertTrue(r.log.contains("say Messages, 1 unread"))
    }

    // ---------- scan order and timing ----------
    @Test fun hubScansOneAtATimeWithPauseTickAndDing() {
        val r = Rig(); r.wake()
        assertEquals(listOf("tone HUB"), r.ev())
        r.advance(399); assertEquals(listOf("tone HUB"), r.ev())
        r.advance(1); assertEquals(listOf("tone HUB", "say Timer"), r.ev())       // t=400, speech ends t=900
        r.advance(1099); assertEquals(2, r.ev().size)                             // pause: no tick yet (tick at 1500)
        r.advance(1); assertEquals("tone TICK", r.log.last())                    // t=1500
        r.advance(199); assertFalse(r.log.contains("say Volume"))
        r.advance(1); assertEquals("say Volume", r.log.last())                   // t=1700 = 800 ms after "done"
        r.advance(1300 * 2); assertEquals(listOf("say Timer", "say Volume", "say Status", "say More"), r.spoken())
        r.advance(1300) // More ends at 4800, pause, ding at 5600
        assertEquals(1, r.count("tone DING"))
    }

    @Test fun listLoopsByItself() {
        val r = Rig(); r.wake()
        r.advance(400 + 4 * 1300 + 800 + 600)
        assertEquals(1, r.count("tone DING"))
        assertEquals(listOf("say Timer", "say Volume", "say Status", "say More", "say Timer"), r.spoken())
        assertTrue(r.m.active)
    }

    @Test fun listEndFallsThenRisesBeforeItStartsOver() {
        val r = Rig(); r.wake()
        r.advance(400 + 4 * 1300 + 800 + 600)
        val d = r.log.indexOf("tone DING"); val u = r.log.indexOf("tone RESTART"); val again = r.log.lastIndexOf("say Timer")
        assertTrue("ding, then rise, then the first option", d in 0 until u && u < again)
        assertEquals(1, r.count("tone RESTART"))
    }

    @Test fun twoSilentLoopsLeaveWithLeaveTone() {
        val r = Rig(); r.wake(); r.advance(60_000)
        assertFalse(r.m.active)
        assertEquals(2, r.count("tone DING")); assertEquals(1, r.count("tone LEAVE")); assertEquals(8, r.spoken().size)
    }

    @Test fun aClickResetsTheSilentLoopCount() {
        val r = Rig(); r.wake(); r.advance(400 + 4 * 1300 + 900)   // one ding, list restarted
        r.pick("Volume")                                             // Volume screen: counter starts again
        r.advance(1300 * 4 + 400)                                    // a full loop and one ding there
        assertTrue(r.m.active)
    }

    // ---------- click binding ----------
    @Test fun clickPicksTheOptionSpokenLast() {
        val r = Rig(); r.wake(); r.advance(2300)   // Volume started at 1750; press 550 ms later
        r.click()
        assertEquals(ControllerMode.Screen.VOLUME, r.m.screen)
    }

    @Test fun clickWithin500msOfNextItemPicksThePrevious() {
        val r = Rig(); r.wake(); r.advance(1900)   // Volume started at 1750 (150 ms ago): the user meant Timer
        r.click()
        assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
        val q = Rig(); q.wake(); q.advance(2250)   // exactly 500 ms after the start: still the previous
        q.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, q.m.screen)
        val w = Rig(); w.wake(); w.advance(2260)   // 510 ms: Volume
        w.click(); assertEquals(ControllerMode.Screen.VOLUME, w.m.screen)
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

    @Test fun twoClicksStartTheListAgain() {
        val r = Rig(); r.wake(); r.advance(3100)   // Status is being spoken
        r.clearLog(); r.click(D)
        assertTrue(r.m.active); assertEquals(ControllerMode.Screen.HUB, r.m.screen)
        r.advance(300)
        assertEquals(listOf("say Timer"), r.spoken())
        // the first item counts as most recent: a click now picks Timer, not the Status heard before
        r.advance(600); r.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
    }

    @Test fun threeClicksGoBackOneLevelAndAtTheTopLeave() {
        val r = Rig(); r.wake(); r.advance(500); r.click()
        assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
        r.click(T); assertEquals(ControllerMode.Screen.HUB, r.m.screen); assertTrue(r.m.active)
        r.click(T); assertFalse(r.m.active); assertTrue(r.log.contains("tone LEAVE"))
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
        val r = Rig(rev); r.wake(); r.advance(400 + 1300 * 3 + 100)
        assertEquals(listOf("say Timer", "say Volume", "say Status", "say More"), r.spoken())   // the hub keeps the user's order
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

    @Test fun hubHasMessagesFirstOnlyWhenUnreadExist() {
        val a = Rig(); a.msgs = listOf(bob.copy(at = 1)); a.wake(); a.advance(450)
        assertEquals("say Messages, 1 unread", a.spoken().last())
        val b = Rig(); b.wake(); b.advance(450)
        assertEquals("say Timer", b.spoken().last())
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
        assertFalse(r.spoken().contains("say Playing"))
        val q = Rig(); q.song = "Blue, Ana"; q.wake(); q.pick("Playing")
        assertTrue(q.log.contains("say Blue, Ana"))
    }

    @Test fun tasksAreInTheHubOnlyWhenOpenAndDoneRemovesThemOneByOne() {
        val r0 = Rig(); r0.wake(); r0.advance(400 + 1300 * 6)
        assertFalse(r0.spoken().any { it.startsWith("say Tasks") })
        val r = Rig(); r.taskStore.add("Buy milk"); r.taskStore.add("Call mum")
        r.wake(); r.pick("Tasks, 2")
        r.advance(400 + 600)
        assertTrue(r.log.contains("say 1 of 2: Buy milk"))
        r.pick("Done")
        assertEquals(listOf("Call mum"), r.taskStore.all().map { it.text })
        r.advance(1000); assertTrue(r.log.contains("say Call mum"))
        r.pick("Done")
        assertTrue(r.taskStore.all().isEmpty()); assertTrue(r.log.contains("say All done"))
    }

    @Test fun hubOrderMatchesWhatIsSpoken() {
        val r = Rig(); r.m.settings = LauncherSettings(hub = listOf("timer", "volume", "more"), learnOrder = false)
        assertEquals(listOf("timer", "volume", "more"), r.m.hubOrder())
        r.m.settings = r.m.settings.copy(learnOrder = true)
        assertEquals(listOf("timer", "volume", "more"), r.m.hubOrder())   // learning never changes the user's menu order
    }

    @Test fun sharedNotesAreSplitIntoOpenTasks() {
        assertEquals(listOf("wait clio", "send exam"), TaskStore.parse("Papers\n[X] passport\n[ ] wait clio\n[x] nie\n[ ] send exam"))
        assertEquals(listOf("a b", "c"), TaskStore.parse("[ ] a\n b [X] z [ ] c"))   // joined lines too
        assertEquals(listOf("milk", "eggs"), TaskStore.parse("Shopping\n- milk\n2. eggs"))
        assertEquals(listOf("call mum tonight"), TaskStore.parse("call mum\ntonight"))
        assertTrue(TaskStore.parse("[X] only done").isEmpty())
    }

    @Test fun taskStoreSavesAndReloads() {
        var saved = listOf<String>()
        val a = TaskStore { saved = it }
        a.add("  Buy\n milk "); assertNull(a.add("   "))
        val b = TaskStore(saved); assertEquals(listOf("Buy milk"), b.all().map { it.text })
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
        val r = Rig(); r.wake(); r.pick("Volume")
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

    @Test fun volumeLevelIsSpokenInSpanishForASpanishMessageContext() {
        assertEquals("siete", Spoken.number(7, true)); assertEquals("seven", Spoken.number(7)); assertEquals("45", Spoken.number(45))
    }

    @Test fun volumeDoneGoesToTheHub() {
        val r = Rig(); r.wake(); r.pick("Volume"); r.pick("Done")
        assertEquals(ControllerMode.Screen.HUB, r.m.screen)
    }

    @Test fun statusSpeaksOneSentenceThenLeaves() {
        val r = Rig(); r.msgs = emptyList(); r.wake(); r.pick("Status")
        r.advance(10); assertTrue(r.spoken().single().startsWith("say 3 05 PM, battery 80 percent, no unread messages, next alarm 7 30 AM"))
        assertTrue(r.m.active)
        r.advance(600); assertFalse(r.m.active); assertTrue(r.log.contains("tone EXIT"))
    }

    @Test fun moreHubOptionsIncludeDndOnlyWithAccess() {
        val a = Rig(); a.wake(); a.pick("More"); a.advance(400 + 1300 * 3 + 100)
        assertEquals(listOf("say Read unread messages", "say Torch", "say Do not disturb", "say Exit"), a.spoken())
        val b = Rig(); b.dnd = false; b.wake(); b.pick("More"); b.advance(400 + 1300 * 2 + 100)
        assertEquals(listOf("say Read unread messages", "say Torch", "say Exit"), b.spoken())
    }

    @Test fun torchRunsAndTheListRestartsAfterItsSpeech() {
        val r = Rig(); r.wake(); r.pick("More"); r.pick("Torch")
        assertTrue(r.has("run Flashlight")); r.advance(1500)
        assertTrue(r.spoken().contains("say Read unread messages")); assertEquals(ControllerMode.Screen.MORE_HUB, r.m.screen)
    }

    @Test fun moreHubExitLeaves() {
        val r = Rig(); r.wake(); r.pick("More"); r.pick("Exit")
        assertFalse(r.m.active); assertTrue(r.log.contains("tone EXIT"))
    }

    @Test fun readUnreadMessagesFromMoreOpensTheMessage() {
        val r = Rig(); r.msgs = listOf(bob.copy(at = 1)); r.wake(); r.pick("More"); r.pick("Read unread messages")
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
        assertEquals(listOf("say Click when you hear it. Two clicks: start over. Three clicks: go back."), r.spoken())
        r.advance(500); assertEquals("say Timer", r.spoken().last())
        r.advance(60_000); assertEquals(1, r.spoken().count { it.contains("Click when") })
    }

    @Test fun noHintAfterFifteenPicks() {
        val r = Rig(); r.codes = 15; r.wake(); r.advance(450)
        assertFalse(r.spoken().any { it.contains("Click when") })
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
        assertTrue(r.spoken().single().contains("Click when"))
        r.advance(800); assertEquals("say[en] Bob: what time is it", r.spoken().last())
    }

    // ---------- choice log ----------
    @Test fun picksAreLoggedWithPositionAndIntent() {
        val r = Rig(); r.wake(); r.pick("Volume")
        val q = Rig(); q.wake(); q.advance(2300); q.click()
        assertTrue(q.log.contains("choice HUB 2 hub:volume"))
    }

    // ---------- item gap (default 1.6 s, from the end of each item's speech) ----------
    @Test fun defaultGapIsMeasuredFromTheEndOfSpeech() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake()
        r.advance(400); assertEquals(listOf("say Timer"), r.spoken())        // speech t=400..900
        r.advance(1899); assertEquals(1, r.spoken().size)                    // tick due at 900+1400=2300
        r.advance(1); assertEquals("tone TICK", r.log.last())
        r.advance(199); assertEquals(1, r.spoken().size)
        r.advance(1); assertEquals("say Volume", r.spoken().last())          // t=2500 = 1600 after done
    }

    @Test fun defaultGapClickDuringGapBindsToTheItemJustSpoken() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake(); r.advance(450 + 2100 + 1000)   // Volume spoken at 2500, now in its gap
        r.click(); assertEquals(ControllerMode.Screen.VOLUME, r.m.screen)
    }

    @Test fun defaultGapGraceStillPicksThePreviousJustAfterNextStarts() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake(); r.advance(2500 + 50 + 100)   // Volume started at 2550, 100 ms ago
        r.click(); assertEquals(ControllerMode.Screen.TIMER_PICK, r.m.screen)
    }

    @Test fun defaultGapTwoSilentLoopsLeave() {
        val r = Rig(gap = ControllerMode.ITEM_GAP_MS); r.wake()
        r.advance(400 + 4 * 2100 + 1600 + 1600 - 1)   // after the 1st ding's gap the list restarts; leave after the 2nd ding's gap
        assertTrue(r.m.active)
        r.advance(400 + 4 * 2100 + 5000)
        assertFalse(r.m.active); assertEquals(2, r.count("tone DING")); assertEquals(1, r.count("tone LEAVE"))
    }
}
