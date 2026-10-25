package com.faka.invisible

import com.faka.invisible.voicekit.*

/** One unread conversation: key identifies it for replies. lang = detected language ("es", "en", ...) or "". at = arrival time (ms). */
data class Msg(val key: String, val sender: String, val text: String, val lang: String = "", val at: Long = 0)

enum class Forward { PLAY_PAUSE, NEXT, PREVIOUS }

interface ModeOutput {
    fun speak(text: Phrase, queue: Boolean = false, fast: Boolean = false)   // the voice language travels in the Phrase
    fun stopSpeech()                 // a click stops current speech
    fun tone(kind: Tone)
    fun runAction(name: String)      // "Flashlight", "Do not disturb"
    fun forward(what: Forward)       // to the real music app
    fun log(line: String)
    fun pauseMusic(): Boolean        // true if music was playing and we paused it
    fun resumeMusic()                // resume what pauseMusic paused (skips if user started something else)
    fun volume(up: Boolean)          // music volume step (the mode speaks the level)
    fun volumeLevel(): Int           // current media volume index, as is (0..device max)
    fun startTimer(minutes: Int)
    fun stopAlarm()
    fun timerRinging(): Boolean
    fun messages(): List<Msg>        // latest unread messages, newest first
    fun reply(key: String, text: String): Boolean   // true if the reply was sent
    fun status(): Status
    fun songLang(text: String): String? = null   // "es" or "en" for the voice
    fun tasks(): List<Task> = emptyList()   // open to-do items, oldest first
    fun completeTask(id: Long) {}
    fun taskOffers(): List<Task> = emptyList()   // the user's own past tasks, best first, not open now
    fun addTask(text: String, lang: String): Boolean = false
    fun replyOffers(): List<Task> = emptyList()   // the user's own past replies, most used first
    fun rememberReply(text: String, lang: String) {}
    fun prepareAiReplies(m: Msg) {}              // start writing reply sentences with the local model (background, cached)
    fun aiTasks(m: Msg): List<Task> = emptyList()     // clean to-do items the message asks for (cached, empty if none or not ready)
    fun aiReplies(m: Msg): List<Task> = emptyList()   // the cached sentences, empty if not ready
    fun nowPlaying(): String? = null   // "Title, Artist" of the music app, null if none
    fun dndAllowed(): Boolean
    fun lastTimer(): Int             // minutes, 0 = none yet
    fun setLastTimer(minutes: Int)
    fun favourites(): List<String>   // most frequent senders, best first
    fun choice(screen: String, code: Int, intent: String, sender: String)   // choice log; code 0 = wake, else the position of the option in the list
    fun codeCount(): Int             // successful code actions so far (persisted); learning mode while < 15
    fun setCodeCount(n: Int)
    fun startWrite() {}   // the Write keyboard (stereo pair); its text comes back through the app
    fun startGame(coach: Boolean) {}   // coach = teach first (the "How to play" item)
    fun weather(done: (Phrase) -> Unit) { done(Voice.WEATHER_FAILED) }   // off the main thread inside; [done] runs on the main thread with ONE sentence
    fun nextEvent(done: (Phrase) -> Unit) { done(Voice.CALENDAR_FAILED) }   // same: the next calendar event sentence
    fun wiredConnected(): Boolean = false   // a wired headset or USB audio is plugged in: then "Find my phone" is not offered
    fun navigation(): String? = null   // the latest Google Maps instruction as one sentence; null = not navigating
    fun findPhone(on: Boolean) {}   // ring loudly on the alarm stream (on) or stop (off)
    fun setContext(tags: Set<String>) {}   // the situation now (Suggester.TAGS); it is stored with every choice
    fun suggest(candidates: Collection<String>, now: Long): List<String> = emptyList()   // the OS's "Suggested" slot: up to 3 of these deep options, best first
    fun pickCount(intent: String): Int = 0   // how often this option was picked (spearcon after [ControllerMode.SPEARCON_AFTER] picks)
}

/**
 * Orders the options of one screen. HOOK: a language model can implement this later (for example rank the reply
 * options for one message text). [keys] are option intents in the default order; return the same keys, best first.
 */
interface OptionRanker {
    fun rank(screen: String, context: String, keys: List<String>): List<String>

    companion object { val DEFAULT: OptionRanker = object : OptionRanker { override fun rank(screen: String, context: String, keys: List<String>) = keys } }
}

/** Default ranker: most picked first (pick counts from the ChoiceLog); ties keep the default order. */
class CountRanker(private val count: (String) -> Int) : OptionRanker {
    override fun rank(screen: String, context: String, keys: List<String>) =
        keys.withIndex().sortedWith(compareBy({ -count(it.value) }, { it.index })).map { it.value }
}

/**
 * Pure state machine for the v4 auto-scan launcher.
 * Idle: clicks go to the music app, hold = WAKE. In the mode ONE rule on every screen: the phone speaks the
 * options one at a time (pause, tick between items, ding at the end) and loops. 1 click = the option spoken last,
 * 1 = next option now, 2 = choose the option spoken last, 3 = back one level (top: leave), 4 = leave. Two silent loops = leave. Hold = exit.
 * Click then hold (fast scroll, while options are read): steps through the options about 4 per second, first word + tick; on release the option is
 * spoken in full and the scan goes on from there. Anywhere else a click-then-hold is a plain hold (exit), so you can never get stuck.
 * Sends and timer starts wait [undoMs] after "Click to cancel"; any click inside cancels them.
 * Events in: onGesture, onSpeechStarted, onSpeechDone. Time and timers are injected.
 */
class ControllerMode(
    private val out: ModeOutput,
    private val timers: Timers,
    private val clock: () -> Long,
    private val ranker: OptionRanker = OptionRanker.DEFAULT,
    private val learnActions: Int = 15,
    private val toneGapMs: Long = 400,         // earcon of a new screen, then speech
    private val pauseMs: Long? = null,  // silence after an option, measured from the END of its speech
    private val tickLeadMs: Long = 200,
    private val restartLeadMs: Long = 450,     // the rising start-over sound begins this long before item 1        // tick this long before the next option starts
    private val graceMs: Long = 500,           // a click this soon after an item started means the item before
    private val silentLoopsOverride: Int? = null,          // full loops without a click, then leave
    private val undoMsOverride: Long? = null,
    private val waitCapMs: Long = 4000,        // never wait longer than this for speech we did not start
    private val speechCapMs: Long = 20_000,    // watchdog if the platform never says "done"
    private val gapMs: Long = Pacing.GAP_MS,   // the gesture fires this long after its last press
    private val freshMs: Long = 120_000,       // a message this young goes first in the wake queue
    private val queueMs: Long = 300_000,       // the wake queue holds only messages this young
) {
    enum class Screen { HUB, MESSAGE, MORE_MSG, COMPOSE, TIMER_DONE, TIMER_PICK, VOLUME, TODAY, PHONE, TASK, TASK_ADD, MSG_TASK, GAMES, SENT }
    private enum class Phase { INTRO, SCAN, WAIT, OUTRO, PENDING }
    private class Intro(val text: Phrase, val msgId: String?, val hint: Boolean = false) { var started = false }
    private class Opt(val key: String, val label: Phrase, val run: () -> Unit)

    /** Set from the app's settings screen; read at use, so changes apply at once. */
    var settings = LauncherSettings()
    private val pause get() = pauseMs ?: settings.itemGapMs
    private val silentLoops get() = silentLoopsOverride ?: settings.silentLoops
    private val undoMs get() = undoMsOverride ?: settings.undoMs

    var active = false
        private set
    var screen = Screen.HUB
        private set
    private var phase = Phase.INTRO
    private var pausedByUs = false
    private var queue = mutableListOf<Msg?>()   // null = the finished timer
    private var qi = 0
    private var taskList = listOf<Task>()   // snapshot while the Tasks screen is open
    private var taskIdx = 0   // first task of the page being read
    private val TASK_PAGE = 5
    private val handled = HashSet<String>()   // wake queue: text spoken, answered or skipped (id = key + arrival time)
    private val replied = HashSet<String>()   // answered: no longer unread
    private val heard = HashSet<String>()     // text spoken to the end (or cut by the user's click after it started)
    private var fromHub = false
    private var hintPending = false
    private var lookupId = 0                 // a newer id cancels an answer still on its way
    private var finding = false               // the phone is ringing for "Find my phone"
    private var speechOpen = false            // we asked for speech and "done" has not come yet

    private val intro = ArrayDeque<Intro>()
    private var curIntro: Intro? = null
    private var list = ScanList<Opt>(emptyList())   // the options being read
    private var awaitStart = -1
    private val history = ArrayList<Pair<Int, Long>>()   // (option index, time its speech started), oldest first
    private var code = 0                       // position of the option being run
    private var waitNext: (() -> Unit)? = null

    private class Pending(val run: () -> Boolean, val onCancel: () -> Unit, val after: () -> Unit) { var armed = false }
    private var pending: Pending? = null

    private val cur: Msg? get() = queue.getOrNull(qi)
    private fun hid(m: Msg) = "${m.key}|${m.at}"
    private fun unread() = out.messages().filter { hid(it) !in replied }
    private fun isEs(m: Msg) = MessageLogic.wordingLang(m.lang) == "es"
    private fun vl(m: Msg) = MessageLogic.voiceLang(m.lang)

    /** [lastPressMs] = length of the last press; with the gesture gap it gives the time of the first press. */
    fun onGesture(g0: Gesture, lastPressMs: Long = 0) {
        if (g0 == Gesture.CLICK_HOLD_END) { endFast(); return }
        // click-then-hold scrolls only while the options are read; everywhere else it is a plain hold (exit)
        val g = if (g0 == Gesture.CLICK_HOLD && !(active && !finding && pending == null && phase == Phase.SCAN && !list.isEmpty)) Gesture.HOLD else g0
        val firstPress = clock() - gapMs - lastPressMs
        if (active && finding) { endFind(); return }   // ANY click or hold stops the ringing
        if (pending != null && g != Gesture.HOLD) { cancelPending(); return }   // ANY click cancels
        if (!active) {
            when (g) {
                Gesture.SINGLE -> out.forward(Forward.PLAY_PAUSE)
                Gesture.DOUBLE -> out.forward(Forward.NEXT)
                Gesture.TRIPLE -> out.forward(Forward.PREVIOUS)
                Gesture.HOLD -> wake()
                Gesture.CLICK_HOLD, Gesture.CLICK_HOLD_END -> {}   // mapped to HOLD above
                Gesture.QUAD -> {}   // no meaning when idle; hold wakes (the system sends it, also on the lock screen)
            }
            return
        }
        when (g) {
            Gesture.HOLD -> { stopAll(); out.choice(screen.name, 9, "exit", ""); exit(true) }
            Gesture.CLICK_HOLD -> startFast()
            Gesture.CLICK_HOLD_END -> {}
            Gesture.QUAD -> { stopAll(); out.choice(screen.name, 4, "leave", ""); leaveSilent() }
            Gesture.TRIPLE -> { stopAll(); out.choice(screen.name, 3, "back", ""); back() }
            Gesture.DOUBLE -> when (phase) {
                Phase.OUTRO -> { stopAll(); exit(true) }
                Phase.SCAN -> {   // two clicks choose the option spoken last
                    val i = bind(firstPress) ?: return    // nothing spoken yet: ignore
                    stopAll(); code = i + 1; list.items[i].run()
                }
                else -> again()
            }
            Gesture.SINGLE -> when (phase) {
                Phase.SCAN -> again()   // one click: the next option now
                Phase.INTRO -> skipIntro()
                Phase.WAIT -> { val n = waitNext; stopAll(); n?.invoke() }
                Phase.OUTRO -> { stopAll(); exit(true) }
                Phase.PENDING -> {}
            }
        }
    }

    /** Which option does a click at [pressAt] mean: the last one that started before it; the one before if it was within the grace. */
    private fun bind(pressAt: Long): Int? {
        var k = -1
        for (j in history.indices) if (history[j].second <= pressAt) k = j
        if (k < 0) return null
        if (k > 0 && pressAt - history[k].second <= graceMs) k--
        return history[k].first
    }

    /** Stop speech and every scan timer. */
    private fun stopAll() {
        timers.clear("scan"); timers.clear("tick"); timers.clear("dog"); timers.clear("fast")
        if (speechOpen) out.stopSpeech()
        speechOpen = false; awaitStart = -1
    }

    // ---- wake: choose the screen by context ----
    private fun wake() {
        active = true
        pausedByUs = out.pauseMusic()
        queue = buildQueue(); qi = 0
        fromHub = false; speechOpen = false
        hintPending = learning()
        out.log("MODE enter")
        out.choice(if (queue.isEmpty()) "HUB" else if (queue[0] == null) "TIMER_DONE" else "MESSAGE", 0, "wake", queue.getOrNull(0)?.sender ?: "")
        showQueueItemOrHub()
    }

    /** Wake opens the hub. Only urgent things go first: a finished timer, then a message that arrived in the last 2 min.
     *  All other unread messages wait behind the hub item "Messages, N unread". */
    private fun buildQueue(): MutableList<Msg?> {
        val now = clock()
        val ms = unread().filter { hid(it) !in handled && it.at > 0 && now - it.at <= queueMs }
        val recent = ms.filter { it.at > 0 && now - it.at <= freshMs }.maxByOrNull { it.at }
        val q = mutableListOf<Msg?>()
        if (out.timerRinging()) q += null
        if (recent != null) q += recent
        return q
    }

    private fun showQueueItemOrHub() {
        val m = cur
        when {
            queue.isEmpty() || qi >= queue.size -> show(Screen.HUB)
            m == null -> show(Screen.TIMER_DONE)
            else -> show(Screen.MESSAGE)
        }
    }

    private fun tone(s: Screen) = when (s) {
        Screen.HUB -> Tone.HUB; Screen.MESSAGE -> Tone.MESSAGE; Screen.TIMER_DONE, Screen.TIMER_PICK -> Tone.TIMER
        Screen.MORE_MSG, Screen.TODAY, Screen.PHONE, Screen.COMPOSE, Screen.TASK, Screen.TASK_ADD, Screen.MSG_TASK, Screen.GAMES -> Tone.MORE; Screen.VOLUME -> Tone.VOLUME; Screen.SENT -> Tone.SENT
    }

    private fun learning() = out.codeCount() < learnActions

    // ---- screens: earcon, then the intro (hint, message text), then the scan ----
    private fun show(s: Screen) {
        stopAll(); lookupId++
        screen = s; phase = Phase.INTRO
        if (!backNav) tn(tone(s)); backNav = false
        intro.clear()
        if (hintPending) { hintPending = false; intro += Intro(Voice.HINT, null, hint = true) }
        val m = cur
        if (s == Screen.MESSAGE && m != null) out.prepareAiReplies(m)
        if (s == Screen.MESSAGE && m != null && hid(m) !in heard) intro += Intro(Phrase.content("${m.sender}: ${shortText(m)}", vl(m)), hid(m))
        if (s == Screen.TIMER_DONE) intro += Intro(Voice.TIMER_DONE, null)
        curIntro = null
        timers.set("scan", toneGapMs) { nextIntro() }
    }

    private fun nextIntro() {
        val i = intro.removeFirstOrNull()
        curIntro = i
        if (i == null) { beginScan(); return }
        i.msgId?.let { handled.add(it) }
        say(i.text)
    }

    /** A click while the intro runs: the hint is skipped, a message text that has started is cut and the options start. */
    private fun skipIntro() {
        val i = curIntro
        stopAll()
        if (i == null || i.hint) { nextIntro(); return }
        if (i.msgId != null && !i.started) { intro.addFirst(i); nextIntro(); return }   // not started yet: read it
        i.msgId?.let { heard.add(it) }
        intro.clear(); curIntro = null
        beginScan()
    }

    /** Plain one-line text for speech, cut at [MAX_SPOKEN] chars. */
    private fun shortText(m: Msg): String =
        Voice.cut(MessageLogic.spoken(m.text, m.lang).replace(Regex("\\s+"), " ").trim(), MAX_SPOKEN, isEs(m), null).text

    private fun say(text: Phrase, fast: Boolean = false) {
        speechOpen = true
        timers.set("dog", speechCapMs) { onSpeechDone() }
        out.speak(text, false, fast)
    }

    // ---- the scan ----
    private fun beginScan() {
        stopAll()
        phase = Phase.SCAN; curIntro = null
        history.clear()
        list = ScanList(buildOptions())
        if (list.isEmpty) { leaveSilent(); return }
        speakItem(0)
    }

    private fun speakItem(i: Int) {
        list.moveTo(i); awaitStart = i
        val o = list.items[i]
        say(o.label, settings.spearcons && out.pickCount(o.key) >= SPEARCON_AFTER)
    }

    private fun recordStart() {
        if (awaitStart < 0) return
        history.add(awaitStart to clock()); awaitStart = -1
        if (history.size > 8) history.removeAt(0)
    }

    private fun itemDone() {
        recordStart()
        if (!list.isLast) {
            timers.set("tick", maxOf(0L, pause - tickLeadMs)) { tn(Tone.TICK) }
            timers.set("scan", pause) { speakItem(list.index + 1) }
        } else timers.set("scan", pause) {
            tn(Tone.DING)   // falls: the list ended
            if (list.endLoop() >= silentLoops) timers.set("scan", pause) { leaveSilent() }
            else {
                timers.set("tick", maxOf(0L, pause - restartLeadMs)) { tn(Tone.RESTART) }   // rises: it starts over
                timers.set("scan", pause) { speakItem(0) }
            }
        }
    }

    /** One click while scanning: the next option right now (after the last one, the first). In the other phases (two clicks): the list again from the first. */
    /** True while the options are being read: there one click moves on and two clicks choose (the tones follow). */
    fun scanning() = active && phase == Phase.SCAN

    // ---- fast scroll: click, then hold ----
    private var fast = false

    private fun startFast() {
        stopAll(); fast = true
        out.choice(screen.name, 8, "fast", "")
        fastTick()
    }

    /** One fast step: a tick (the rising sound at the wrap), then only the first word of the option. */
    private fun fastTick() {
        if (!fast) return
        val s = list.fastStep { it.label.text }
        tn(if (s.wrapped) Tone.RESTART else Tone.TICK)
        speechOpen = true
        out.speak(Phrase.content(s.text, list.current.label.lang), false, false)
        timers.set("fast", Pacing.FAST_MS) { fastTick() }
    }

    /** Released: stop on the option reached, speak it in full, and the normal scan goes on from there. */
    private fun endFast() {
        if (!fast) return
        fast = false
        stopAll()
        list.resetLoops(); history.clear()
        timers.set("scan", SKIP_MS) { speakItem(list.index) }
    }

    private fun again() {
        stopAll()
        out.choice(screen.name, 2, "again", "")
        when (phase) {
            Phase.INTRO -> if (curIntro?.msgId != null && curIntro?.started != true) { curIntro?.let { intro.addFirst(it) }; nextIntro() } else {
                curIntro?.msgId?.let { heard.add(it) }; intro.clear(); curIntro = null; beginScan()
            }
            Phase.WAIT -> { val n = waitNext; n?.invoke() }
            Phase.SCAN -> { phase = Phase.SCAN; list.resetLoops(); val j = list.nextIndex(); timers.set("scan", SKIP_MS) { speakItem(j) } }   // the next option now, at the user's own pace
            else -> { tn(Tone.RESTART); phase = Phase.SCAN; history.clear(); list.resetLoops(); timers.set("scan", toneGapMs - tickLeadMs) { speakItem(0) } }
        }
    }

    /** Speech we did not start (volume level, torch, alarm stopped): wait for its end, or for a click, then continue. */
    private fun afterSpeech(next: () -> Unit) {
        phase = Phase.WAIT; waitNext = next; speechOpen = true
        timers.set("dog", waitCapMs) { onSpeechDone() }
    }

    // ---- options per screen ----
    private fun buildOptions(): List<Opt> {
        val m = cur
        return when (screen) {
            Screen.HUB -> {
                val all = linkedMapOf<String, Opt>()
                val n = unread().size
                for (k in settings.hub) when (k) {
                    "messages" -> if (n > 0) all["hub:messages"] = Opt("hub:messages", Menu.messages(n)) { readUnread("hub:messages") }
                    "music" -> out.nowPlaying()?.let { song -> all["hub:music"] = Opt("hub:music", Menu.MUSIC) { pick("hub:music"); outro(Phrase.content(song, out.songLang(song))) } }   // only when the music app has a song
                    "tasks" -> out.tasks().let { ts -> all["hub:tasks"] = Opt("hub:tasks", Menu.tasks(ts.size)) {
                        pick("hub:tasks"); taskList = ts; taskIdx = 0
                        when {
                            ts.isNotEmpty() -> show(Screen.TASK)
                            else -> show(Screen.TASK_ADD)
                        }
                    } }
                    "timer" -> all["hub:timer"] = Opt("hub:timer", Menu.TIMER) { pick("hub:timer"); show(Screen.TIMER_PICK) }
                    "today" -> all["hub:today"] = Opt("hub:today", Menu.TODAY) { pick("hub:today"); show(Screen.TODAY) }
                    "phone" -> all["hub:phone"] = Opt("hub:phone", Menu.PHONE) { pick("hub:phone"); show(Screen.PHONE) }
                    "games" -> all["hub:games"] = Opt("hub:games", Menu.GAMES) { pick("hub:games"); show(Screen.GAMES) }
                }
                suggestedKeys = emptyList()
                out.setContext(buildSet { if (n > 0) add("unread"); if (out.nowPlaying() != null) add("playing"); if (out.navigation() != null) add("navigating"); if (out.wiredConnected()) add("wired") })
                val deep = deepOptions()
                val sug = out.suggest(deep.keys, clock()).mapNotNull { deep[it] }
                suggestedKeys = sug.map { it.key }
                sug + all.values.toList()   // the menu order is the user's own; only the small Suggested slot in front adapts
            }
            Screen.TASK -> {   // "Add task" first, then this page of tasks (a click right after hearing one = done), then "More tasks"
                val page = taskList.drop(taskIdx).take(TASK_PAGE)
                val opts = ArrayList<Opt>()
                opts += Opt("task:add", Menu.ADD_TASK) { pick("task:add"); show(Screen.TASK_ADD) }
                for (t in page) opts += Opt("task:done", Voice.cut(t.text, MAX_SPOKEN, t.lang != "en", t.lang.ifEmpty { out.songLang(t.text) ?: "es" })) {
                    pick("task:done")
                    startPending(Voice.TASK_DONE_UNDO, { out.completeTask(t.id); true }, onCancel = { show(Screen.TASK) }, after = {
                        taskList = taskList - t
                        if (taskList.isEmpty()) { say(Voice.ALL_DONE); phase = Phase.WAIT; waitNext = { show(Screen.HUB) } }
                        else { if (taskIdx >= taskList.size) taskIdx = 0; show(Screen.TASK) }
                    })
                }
                if (taskIdx + TASK_PAGE < taskList.size) opts += Opt("task:more", Menu.MORE_TASKS) { pick("task:more"); taskIdx += TASK_PAGE; show(Screen.TASK) }
                opts
            }
            Screen.TASK_ADD -> phraseOptions(taskChoices(), "taskadd") { t ->
                out.addTask(t.text, t.lang); say(Voice.TASK_ADDED); phase = Phase.WAIT; waitNext = { show(Screen.HUB) }
            }
            Screen.TIMER_PICK -> (settings.timers.map { it to Menu.minutes(it) }.let { t -> if (out.lastTimer() > 0) listOf(lastTimer() to Menu.LAST_USED) + t else t + (lastTimer() to Menu.LAST_USED) }).map { (min, label) ->
                Opt("timer:$min", label) { pick("timer:$min"); startTimerPending(min) }
            }
            Screen.VOLUME -> listOf(
                Opt("volume:up", Menu.LOUDER) { pick("volume:up"); out.volume(true); sayLevel() },
                Opt("volume:down", Menu.QUIETER) { pick("volume:down"); out.volume(false); sayLevel() },
                Opt("volume:done", Menu.DONE) { pick("volume:done"); show(Screen.PHONE) })
            Screen.TODAY -> settings.info.mapNotNull { k -> when (k) {
                "weather" -> Opt("info:weather", Menu.WEATHER) { pick("info:weather"); lookup(out::weather) }
                "calendar" -> Opt("info:calendar", Menu.NEXT_EVENT) { pick("info:calendar"); lookup(out::nextEvent) }
                "turn" -> Opt("info:turn", Menu.NEXT_TURN) { pick("info:turn"); outro(out.navigation()?.let { Phrase.content(it) } ?: Voice.NO_NAVIGATION) }
                else -> null
            } } + Opt("today:back", Menu.BACK) { pick("today:back"); show(Screen.HUB) }
            Screen.PHONE -> settings.phone.mapNotNull { k -> when (k) {
                "status" -> Opt("phone:status", Menu.STATUS) { pick("phone:status"); outro(Voice.status(out.status(), unread().size)) }
                "volume" -> Opt("phone:volume", Menu.VOLUME) { pick("phone:volume"); show(Screen.VOLUME) }
                "torch" -> Opt("phone:torch", Menu.TORCH) { pick("phone:torch"); out.runAction("Flashlight"); afterSpeech { beginScan() } }
                "dnd" -> if (out.dndAllowed()) Opt("phone:dnd", Menu.DND) { pick("phone:dnd"); out.runAction("Do not disturb"); afterSpeech { beginScan() } } else null
                "find" -> if (!out.wiredConnected()) Opt("phone:find", Voice.FIND_PHONE) { pick("phone:find"); find() } else null
                else -> null
            } } + Opt("phone:back", Menu.BACK) { pick("phone:back"); show(Screen.HUB) }
            Screen.GAMES -> listOf(   // every game lives here; a new game is one more line
                Opt("games:duel", Menu.DUEL) { pick("games:duel"); pausedByUs = false; exit(false); out.startGame(false) },   // music stays paused for the game, which resumes it
                Opt("games:write", Menu.WRITE) { pick("games:write"); pausedByUs = false; exit(false); out.startWrite() },   // a program of the OS, not a game; reachable here until it has its own place
                Opt("games:howto", Menu.HOW_TO_PLAY) { pick("games:howto"); pausedByUs = false; exit(false); out.startGame(true) },
                Opt("games:back", Menu.BACK) { pick("games:back"); show(Screen.HUB) })
            Screen.TIMER_DONE -> listOf(
                Opt("timer:stop", Menu.STOP) { pick("timer:stop"); out.stopAlarm(); afterSpeech { advance() } },
                Opt("timer:plus5", Menu.FIVE_MORE) { pick("timer:plus5"); out.stopAlarm(); queue.removeAt(qi); startTimerPending(5) },
                Opt("timer:ignore", Menu.IGNORE) { pick("timer:ignore"); skip() })
            Screen.MESSAGE -> if (m == null) emptyList() else messageOptions(m)
            Screen.MORE_MSG -> if (m == null) emptyList() else listOf(
                Opt("read", Menu.READ_ALL) { pick("read", m.sender); intro += Intro(Phrase.content(MessageLogic.spoken(m.text, m.lang).take(500), vl(m)), hid(m)); phase = Phase.INTRO; nextIntro() },
                Opt("send:later", Phrase.content(MessageLogic.word(Answer.LATER, "en"))) { sendPending(m, MessageLogic.compose(Answer.LATER, When.SKIP, Extra.NOTHING, m.lang), "send:later") },
                Opt("send:cant_now", Phrase.content(MessageLogic.word(Answer.CANT_NOW, "en"))) { sendPending(m, MessageLogic.compose(Answer.CANT_NOW, When.SKIP, Extra.NOTHING, m.lang), "send:cant_now") },
                Opt("compose", Menu.COMPOSE) { pick("compose", m.sender); show(Screen.COMPOSE) },
                Opt("task", Menu.TASK) {   // no typing needed: the task is made from the message
                    pick("task", m.sender)
                    if (out.aiTasks(m).isNotEmpty()) show(Screen.MSG_TASK) else addReplyTask(m)
                })
            Screen.MSG_TASK -> if (m == null) emptyList() else phraseOptions(out.aiTasks(m), "taskmsg") { t ->
                out.addTask(t.text, t.lang); say(Voice.TASK_ADDED); phase = Phase.WAIT; waitNext = { show(Screen.MESSAGE) }
            } + Opt("taskreply", Menu.replyTo(m.sender)) { pick("taskreply"); addReplyTask(m) }
            Screen.COMPOSE -> if (m == null) emptyList() else composeOptions(m)
            Screen.SENT -> emptyList()
        }
    }

    /** The options one level down that can be offered straight from the hub (never Back or Done). */
    private fun deepOptions(): Map<String, Opt> {
        val keep = screen
        try {
            return listOf(Screen.TODAY, Screen.PHONE, Screen.GAMES).flatMap { screen = it; buildOptions() }
                .filter { !it.key.endsWith(":back") }.associateBy { it.key }
        } finally { screen = keep }
    }

    private fun messageOptions(m: Msg): List<Opt> {
        val type = MessageLogic.classify(m.text, m.lang)
        val final = MessageLogic.isFinal(type)
        val all = linkedMapOf<String, Opt>()
        val yes = "send:yes:$type"; val no = "send:no:$type"; val skip = "skip:$type"; val more = "more:$type"
        all[yes] = Opt(yes, Menu.replyYes(final)) {
            sendPending(m, MessageLogic.quickReply(type, true, m.text, m.lang)!!, yes)
        }
        all[no] = Opt(no, Menu.NO) {
            val text = MessageLogic.quickReply(type, false, m.text, m.lang)
            if (text == null) { pick("nothing", m.sender); say(Voice.NOTHING); phase = Phase.WAIT; waitNext = { beginScan() } }
            else sendPending(m, text, no)
        }
        all[skip] = Opt(skip, Menu.SKIP) { pick(skip, m.sender); handled.add(hid(m)); skip() }
        all[more] = Opt(more, Menu.MORE) { pick(more, m.sender); show(Screen.MORE_MSG) }
        return if (!settings.learnOrder) all.values.toList() else ranker.rank("MESSAGE", type.name, all.keys.toList()).mapNotNull { all[it] }
    }

    /** One step: the answer. The time word of the message (today, tonight, tomorrow) is pre-filled in the sent text. */
    private fun addReplyTask(m: Msg) {
        out.addTask("Reply to ${m.sender}: ${MessageLogic.spoken(m.text, m.lang).replace(Regex("\\s+"), " ").trim().take(60)}", vl(m) ?: "")
        say(Voice.TASK_ADDED); phase = Phase.WAIT; waitNext = { show(Screen.MESSAGE) }
    }

    private fun composeOptions(m: Msg): List<Opt> =
        phraseOptions(out.replyOffers().take(3), "send:mine") { t -> sendPending(m, t.text, "send:mine") } +
        phraseOptions(out.aiReplies(m), "send:ai") { t -> sendPending(m, t.text, "send:ai") } +   // your own words first, then the model's sentences, then the fixed answers
        listOf(Answer.YES, Answer.NO, Answer.MAYBE).map { a ->
            Opt("send:compose:$a", Phrase.content(MessageLogic.word(a, "en"))) {
                sendPending(m, MessageLogic.compose(a, MessageLogic.timeWord(m.text), Extra.NOTHING, m.lang), "send:compose $a")
            }
        } + Opt("send:ask_details", Menu.ASK_DETAILS) { sendPending(m, MessageLogic.tellMeMore(m.lang), "send:ask_details") }

    /** Your own past tasks first, then starters (so "Add task" works on day one); open tasks are left out. */
    private fun taskChoices(): List<Task> {
        val own = out.taskOffers(); val seen = (own.map { it.text } + out.tasks().map { it.text }).map { it.lowercase() }.toSet()
        return (own + Voice.TASK_STARTERS.filter { it.lowercase() !in seen }.map { Task(0, it, "en") }).take(5)
    }

    /** The shared "pick a text" step (tasks and replies): the user's own past texts, spoken one by one; a pick calls [onPick]. */
    private fun phraseOptions(texts: List<Task>, key: String, onPick: (Task) -> Unit): List<Opt> = texts.map { t ->
        Opt(key, Phrase.content(t.text.take(MAX_SPOKEN), t.lang.ifEmpty { null })) { pick(key); onPick(t) }
    }

    /** Speak only the new volume level as a word, then keep scanning the volume list. */
    private fun sayLevel() {
        val n = out.volumeLevel()
        say(Voice.level(n))
        phase = Phase.WAIT; waitNext = { beginScan() }
    }

    /** Menu keys in the order the phone speaks them (the order the user set). Items with nothing to say right now are still listed. */
    fun hubOrder(): List<String> = settings.hub

    /** The end of the task list: your own past tasks to add, or the menu if there are none. */

    private fun lastTimer() = out.lastTimer().let { if (it > 0) it else 15 }

    private fun readUnread(key: String) {
        val all = unread().sortedByDescending { it.at }
        if (all.isEmpty()) { pick("none"); tn(Tone.UNKNOWN); say(Voice.NO_MESSAGES); phase = Phase.WAIT; waitNext = { beginScan() } }
        else { pick(key); queue = all.toMutableList(); qi = 0; fromHub = true; showQueueItemOrHub() }
    }

    /** Speak one sentence, then leave (Status). */
    /** Ask the phone for one sentence (it may take seconds, off the main thread); any click cancels and the list goes on. */
    private fun lookup(ask: ((Phrase) -> Unit) -> Unit) {
        val id = ++lookupId
        stopAll(); phase = Phase.WAIT; waitNext = { lookupId++; beginScan() }
        tn(Tone.TAP)
        ask { text -> if (id == lookupId && active && phase == Phase.WAIT) outro(text) }
    }

    /** Find my phone: it rings for [FIND_MS]; the first click of any kind stops it and leaves. It says nothing else. */
    private fun find() {
        stopAll(); phase = Phase.PENDING
        finding = true; out.findPhone(true)
        timers.set("find", FIND_MS) { endFind() }
    }

    private fun endFind() {
        if (!finding) return
        finding = false; timers.clear("find"); out.findPhone(false); exit(true)
    }

    private fun outro(text: Phrase) { phase = Phase.OUTRO; say(text) }

    // ---- back one level ----
    private fun back() {
        backNav = true
        try { backInner() } finally { backNav = false }
    }

    private fun backInner() {
        when (screen) {
            Screen.MORE_MSG -> show(Screen.MESSAGE)
            Screen.COMPOSE, Screen.MSG_TASK -> show(Screen.MORE_MSG)
            Screen.VOLUME -> show(Screen.PHONE)
            Screen.TIMER_PICK, Screen.TODAY, Screen.PHONE, Screen.TASK, Screen.TASK_ADD, Screen.GAMES -> show(Screen.HUB)
            Screen.MESSAGE -> if (fromHub) show(Screen.HUB) else leaveSilent()
            else -> leaveSilent()
        }
    }

    // ---- queue moves ----
    /** Item dealt with: drop it and show the next one, or the hub. */
    private fun advance() { if (qi < queue.size) queue.removeAt(qi); if (qi >= queue.size) qi = 0; showQueueItemOrHub() }
    /** Skip: keep the item for later wakes, show the next one; past the last one go to the hub. */
    private fun skip() { qi++; showQueueItemOrHub() }

    // ---- undo window ----
    private fun sendPending(m: Msg, text: String, intent: String) {
        pick(intent, m.sender)
        handled.add(hid(m)); replied.add(hid(m))
        startPending(Voice.SENDING, { out.reply(m.key, text).also { if (it) out.rememberReply(text, m.lang) } },
            onCancel = { handled.remove(hid(m)); replied.remove(hid(m)); show(Screen.MESSAGE) },
            after = { queue.remove(m); if (qi >= queue.size) qi = 0; showQueueItemOrHub() })
    }

    private fun startTimerPending(min: Int) {
        out.setLastTimer(min)
        startPending(Voice.STARTING, { out.startTimer(min); true }, onCancel = { show(Screen.HUB) }, after = { exit(false) })
    }

    private fun startPending(prompt: Phrase, run: () -> Boolean, onCancel: () -> Unit, after: () -> Unit) {
        flushPending()
        stopAll()
        val p = Pending(run, onCancel, after)
        pending = p
        screen = Screen.SENT; phase = Phase.PENDING
        tn(Tone.UNDO)
        say(prompt)
        timers.clear("dog")
        timers.set("undo", waitCapMs + undoMs) { fireUndo() }   // fallback if "done" never comes; real window starts at "done"
    }

    private fun fireUndo() {
        val p = pending ?: return
        pending = null
        if (p.run()) tn(Tone.SENT) else say(Voice.CANNOT)
        if (active && phase == Phase.PENDING) p.after()
    }

    /** A new send while one is waiting: the old one goes out now. */
    private fun flushPending() {
        val p = pending ?: return
        timers.clear("undo"); pending = null
        p.run()
    }

    private fun cancelPending() {
        val p = pending ?: return
        timers.clear("undo"); pending = null
        out.choice(screen.name, 1, "undo", "")
        stopAll()
        tn(Tone.CANCEL)
        say(Voice.CANCELLED)
        if (active) { phase = Phase.WAIT; waitNext = { p.onCancel() } }
    }

    /** Tones the mode has played; Actions.fire skips its own gesture tone when this moved (one tone per navigation event). */
    var tonesPlayed = 0; private set
    private fun tn(t: Tone) { tonesPlayed++; out.tone(t) }

    private var backPicked = false
    private var suggestedKeys = listOf<String>()   // what the hub offered first (for the hit log)
    private var backNav = false   // going back: only the falling Back tone sounds; the screen's own (rising) arrival tone is skipped
    /** True once after the user picked a "Back" item: the click should sound like leaving (falling), not like entering. */
    fun consumeBackPick(): Boolean = backPicked.also { backPicked = false }

    private fun pick(intent: String, sender: String = "") {
        if (suggestedKeys.isNotEmpty() && screen == Screen.HUB) out.choice(screen.name, -1, if (intent in suggestedKeys) "suggest:hit" else "suggest:miss", intent)   // into the choice log, for tools/menu_report.py
        backPicked = intent.endsWith(":back") || intent == "volume:done"
        if (backPicked) backNav = true
        out.choice(screen.name, code, intent, sender)
        if (intent != "none" && intent != "nothing") out.setCodeCount(out.codeCount() + 1)
    }

    fun exit(withTone: Boolean = true) {
        if (!active) return
        stopAll(); lookupId++
        if (finding) { finding = false; timers.clear("find"); out.findPhone(false) }
        active = false
        if (pausedByUs) { pausedByUs = false; out.resumeMusic() }
        out.log("MODE exit")
        if (withTone) tn(Tone.EXIT)
    }

    /** Two silent loops or 4 clicks: leave, and say so with the leaving tone (silence is never ambiguous). */
    private fun leaveSilent() { if (!active) return; exit(false); tn(Tone.LEAVE) }

    /** The platform started speaking the newest utterance. */
    fun onSpeechStarted() {
        if (!active) return
        when (phase) {
            Phase.INTRO -> curIntro?.started = true
            Phase.SCAN -> recordStart()
            else -> {}
        }
    }

    /** The platform finished speaking (or the watchdog fired). */
    fun onSpeechDone() {
        if (!speechOpen) return
        if (fast) { speechOpen = false; return }   // a first word during fast scroll: nothing follows it
        speechOpen = false
        timers.clear("dog")
        val p = pending
        if (p != null && !p.armed) { p.armed = true; timers.set("undo", undoMs) { fireUndo() }; return }   // "Click to cancel" is spoken: the window starts now
        if (!active) return
        when (phase) {
            Phase.INTRO -> { curIntro?.msgId?.let { heard.add(it) }; nextIntro() }
            Phase.SCAN -> itemDone()
            Phase.WAIT -> { val n = waitNext; waitNext = null; n?.invoke() }
            Phase.OUTRO -> exit(true)
            Phase.PENDING -> {}
        }
    }

    companion object {
        /** Pause between scanned items, from the end of one item's speech to the start of the next. Easy to change. */
        const val SKIP_MS = Pacing.SKIP_MS   // one click while scanning: how soon the next option starts
        const val ITEM_GAP_MS = Pacing.ITEM_GAP_MS
        const val FIND_MS = 20_000L   // "Find my phone" rings this long
        const val SPEARCON_AFTER = 30   // picks of one option, then it is scanned as a sped-up spearcon
        private const val MAX_SPOKEN = 160
    }
}
