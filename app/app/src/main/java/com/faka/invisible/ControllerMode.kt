package com.faka.invisible

import com.faka.invisible.voicekit.*

/** A launchable app: [hint] = ApplicationInfo.category, -1 = none. */
data class InstalledApp(val pkg: String, val label: String, val hint: Int = -1)

/** How often an app was opened in the last month and the last week. */
data class AppUse(val month: Int, val week: Int)

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
    fun notifGroups(): List<NotifGroup> = emptyList()   // the mailbox: every app's notifications, grouped, muted apps left out
    fun notifRead(n: Notif): String = GenericModule.read(n)         // what is read in full (the app's module decides)
    fun notifSummary(n: Notif): String = GenericModule.summary(n)   // the one line in the list
    fun notifFire(key: String, index: Int): Boolean = false   // press action [index] of a notification
    fun notifDismiss(key: String): Boolean = false
    fun notifMute(pkg: String) {}
    fun installedApps(): List<InstalledApp> = emptyList()   // every launchable app (the leaves of the tree), this app left out
    fun appUse(): Map<String, AppUse> = emptyMap()   // launches per app in the last month and week
    fun appMuted(pkg: String): Boolean = false   // a muted app is not in the tree
    fun openApp(pkg: String): Boolean = false   // start the app (its launch intent)
    fun notifAppHint(pkg: String): Int = -1   // ApplicationInfo.category of the app, -1 = unknown (a hint for its group)
    fun notifMutedCount(): Int = 0
    fun notifUnmuteAll() {}
    fun startNotifReply(key: String, index: Int) {}   // Write keyboard; its text goes into that action's reply box
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
 * 1 = choose the option spoken last, 2 = next option now, 3 = back one level (top: leave), 4 = leave. Two silent loops = leave. Hold = exit.
 * Click then hold (fast scroll, while options are read): steps through the options one word every 0.65 s or more (a word is never cut off), first word + tick; on release the option is
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
    enum class Screen { HUB, MESSAGE, MORE_MSG, COMPOSE, TIMER_DONE, TIMER_PICK, VOLUME, TODAY, PHONE, TASK, TASK_ADD, MSG_TASK, GAMES, SENT, TREE, APP_OPEN, NOTIF_LIST, NOTIF_ACTIONS }
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
    private var notifPkg: String? = null     // the app open in the notification screens
    private var treeRoot: TreeNode? = null   // the group tree, built when the home list is built (fixed while the user walks it)
    private val treePath = ArrayList<TreeNode>()   // the groups walked down from home; the last one is the list shown in TREE
    private var hubOpts: Map<String, Opt> = emptyMap()   // the built-in programs of the OS, as options (leaves of the tree)
    private var notifKey: String? = null     // the notification open in NOTIF_ACTIONS
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
    private val scanOut = object : ScanOut {
        override fun say(index: Int, label: Phrase, spearcon: Boolean) { awaitStart = index; say(label, spearcon) }
        override fun sayFast(word: Phrase) { speechOpen = true; out.speak(word, false, false) }
        override fun tone(t: Tone) { tn(t) }
        override fun leave() { leaveSilent() }
    }
    private val scanner = Scanner<Opt>(timers, { it.label }, scanOut,
        ScanCadence(pauseMs = { pause }, loops = { silentLoops }, tickLeadMs = tickLeadMs, restartLeadMs = restartLeadMs),
        spearcon = { settings.spearcons && out.pickCount(it.key) >= SPEARCON_AFTER })
    private val list get() = scanner.list   // the options being read
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
                else -> again()   // two clicks while scanning: the next option now
            }
            Gesture.SINGLE -> when (phase) {
                Phase.SCAN -> {   // one click chooses the option spoken last
                    val i = bind(firstPress) ?: return    // nothing spoken yet: ignore
                    stopAll(); code = i + 1; list.items[i].run()
                }
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
        scanner.stop(); timers.clear("dog")
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
        Screen.MORE_MSG, Screen.TODAY, Screen.PHONE, Screen.COMPOSE, Screen.TASK, Screen.TASK_ADD, Screen.MSG_TASK, Screen.GAMES, Screen.TREE, Screen.APP_OPEN, Screen.NOTIF_LIST, Screen.NOTIF_ACTIONS -> Tone.MORE; Screen.VOLUME -> Tone.VOLUME; Screen.SENT -> Tone.SENT
    }

    private fun learning() = out.codeCount() < learnActions

    // ---- screens: earcon, then the intro (hint, message text), then the scan ----
    private fun show(s0: Screen) {
        val s = notifGuard(s0)
        stopAll(); lookupId++
        screen = s; phase = Phase.INTRO
        if (!backNav) tn(tone(s)); backNav = false
        intro.clear()
        if (hintPending) { hintPending = false; intro += Intro(Voice.HINT, null, hint = true) }
        val m = cur
        if (s == Screen.MESSAGE && m != null) out.prepareAiReplies(m)
        if (s == Screen.MESSAGE && m != null && hid(m) !in heard) intro += Intro(Phrase.content("${m.sender}: ${shortText(m)}", vl(m)), hid(m))
        if (s == Screen.TIMER_DONE) intro += Intro(Voice.TIMER_DONE, null)
        if (s == Screen.NOTIF_ACTIONS) openNotif()?.let { intro += Intro(notifRead(it), null) }
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
        scanner.load(buildOptions())
        if (scanner.isEmpty) { leaveSilent(); return }
        scanner.speakItem(0)
    }

    private fun recordStart() {
        if (awaitStart < 0) return
        history.add(awaitStart to clock()); awaitStart = -1
        if (history.size > 8) history.removeAt(0)
    }

    private fun itemDone() {
        recordStart()
        scanner.itemDone()
    }

    /** Two clicks while scanning: the next option right now (after the last one, the first). In the other phases (two clicks): the list again from the first. */
    /** True while the options are being read: there one click chooses and two clicks move on (the tones follow). */
    fun scanning() = active && phase == Phase.SCAN

    // ---- fast scroll: click, then hold ----
    private fun startFast() {
        stopAll()
        out.choice(screen.name, 8, "fast", "")
        scanner.startFast()
    }

    /** Released: stop on the option reached, speak it in full, and the normal scan goes on from there. */
    private fun endFast() {
        if (!scanner.endFast()) return
        stopAll()
        history.clear()
        scanner.resumeIn(SKIP_MS)
    }

    private fun again() {
        stopAll()
        out.choice(screen.name, 2, "again", "")
        when (phase) {
            Phase.INTRO -> if (curIntro?.msgId != null && curIntro?.started != true) { curIntro?.let { intro.addFirst(it) }; nextIntro() } else {
                curIntro?.msgId?.let { heard.add(it) }; intro.clear(); curIntro = null; beginScan()
            }
            Phase.WAIT -> { val n = waitNext; n?.invoke() }
            Phase.SCAN -> { phase = Phase.SCAN; scanner.nextIn(SKIP_MS) }   // the next option now, at the user's own pace
            else -> { tn(Tone.RESTART); phase = Phase.SCAN; history.clear(); scanner.restartIn(toneGapMs - tickLeadMs) }
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
                hubOpts = all
                val root = cachedTree(treeLeaves(all)); treeRoot = root; treePath.clear()
                sug + treeOptions(root) + unmuteOption()   // the home list is the tree's top; only the small Suggested slot in front adapts
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
            Screen.TREE -> treeOptions(treePath.lastOrNull() ?: treeRoot ?: AppTree.build(emptyList()))
            Screen.APP_OPEN -> appOpenOptions()
            Screen.NOTIF_LIST -> notifListOptions()
            Screen.NOTIF_ACTIONS -> notifActionOptions()
        }
    }

    // ---- The tree: home -> group -> (subgroup) -> app -> its notifications -> one in full with its actions ----
    private fun openItems() = out.notifGroups().filter { it.pkg == notifPkg }.flatMap { it.items }.sortedByDescending { it.at }
    private fun openNotif() = openItems().firstOrNull { it.key == notifKey }

    /** A screen whose app or notification is gone (dismissed, removed by the app) falls back one level (an empty app: home, which is rebuilt). */
    private fun notifGuard(s: Screen): Screen = when {
        s == Screen.NOTIF_ACTIONS && openNotif() == null -> notifGuard(Screen.NOTIF_LIST)
        s == Screen.NOTIF_LIST && openItems().isEmpty() -> Screen.HUB
        else -> s
    }

    /** The list one level above the app: the group it was picked in, or home. */
    private fun treeUp() = show(if (treePath.isEmpty()) Screen.HUB else Screen.TREE)

    /**
     * The leaves of the tree: the OS programs that are switched on and every installed app (muted ones and this app left out), so a place never moves
     * because of a notification. Weight = launches and picks ([AppWeights]); unread is only for the label.
     */
    private fun treeLeaves(builtins: Map<String, Opt>): List<AppLeaf> {
        val programs = builtins.map { (k, o) -> AppLeaf(k, o.label.text, AppCategories.categoryOf(k), if (k == "hub:messages") unread().size else 0, AppWeights.weight(k, 0, 0, out.pickCount(k)), native = true, rank = settings.hub.indexOf(k.removePrefix("hub:"))) }
        val unread = out.notifGroups().groupBy { it.pkg }.mapValues { (_, gs) -> gs.sumOf { it.items.size } }
        val use = out.appUse()
        val apps = out.installedApps().filter { it.pkg != AppWeights.SELF && !out.appMuted(it.pkg) }.map { a ->
            val u = use[a.pkg] ?: AppUse(0, 0)
            AppLeaf(a.pkg, a.label, AppCategories.categoryOf(a.pkg, a.hint), unread[a.pkg] ?: 0, AppWeights.weight(a.pkg, u.month, u.week, out.pickCount("app:${a.pkg}")), rank = APP_RANK)
        }
        return programs + apps
    }

    private var treeKey: List<Any>? = null; private var treeCache: TreeNode? = null
    /** The search is exact but not free: when no weight, label or place changed since the last home list, the same tree is reused (unread changes only labels, so they are left out of the key). */
    private fun cachedTree(leaves: List<AppLeaf>): TreeNode {
        val key = leaves.map { listOf(it.pkg, it.label, it.category, it.weight) }
        val old = treeCache
        if (key == treeKey && old != null) return withUnread(old, leaves.associate { it.pkg to it.unread })
        return AppTree.build(leaves).also { treeKey = key; treeCache = it }
    }

    private fun withUnread(n: TreeNode, u: Map<String, Int>): TreeNode {
        val a = n.app?.let { it.copy(unread = u[it.pkg] ?: 0) }
        val kids = n.children.map { withUnread(it, u) }
        return TreeNode(n.label, kids, a, a?.unread ?: kids.sumOf { it.unread }, n.weight, n.isMore, n.rank)
    }

    private fun treeLabel(c: TreeNode): Phrase = when {
        c.isMore -> Menu.MORE
        c.unread > 0 -> Phrase.content(c.label + ", " + c.unread)
        else -> Phrase.content(c.label)
    }

    /** One list of the tree: a group goes down, an OS program runs as before, an app opens its notifications. */
    private fun treeOptions(n: TreeNode): List<Opt> = n.children.map { c ->
        val a = c.app
        when {
            a != null && a.pkg in hubOpts -> hubOpts.getValue(a.pkg)
            a != null -> Opt("tree:app", treeLabel(c)) { pick("app:${a.pkg}"); notifPkg = a.pkg; show(if (c.unread > 0) Screen.NOTIF_LIST else Screen.APP_OPEN) }
            else -> Opt("tree:group", treeLabel(c)) { pick("group:${c.label}"); treePath.add(c); show(Screen.TREE) }
        }
    }

    private fun unmuteOption(): List<Opt> = if (out.notifMutedCount() == 0) emptyList() else listOf(Opt("notif:unmute", Menu.UNMUTE_ALL) {
        pick("notif:unmute"); out.notifUnmuteAll(); say(Voice.APPS_UNMUTED); phase = Phase.WAIT; waitNext = { show(Screen.HUB) }
    })

    /** What is read for a notification: a private one gives only the app. */
    private fun notifLine(n: Notif, max: Int, full: Boolean): Phrase {
        if (n.hidden) return Voice.newNotification(n.app)
        val t = if (full) out.notifRead(n) else out.notifSummary(n)
        val lang = out.songLang(t)
        return Voice.cut(t.replace(Regex("\\s+"), " ").trim(), max, lang == "es", lang)
    }
    private fun notifRead(n: Notif) = notifLine(n, 500, true)

    /** An app without notifications: open it, or go back. */
    private fun appOpenOptions(): List<Opt> = listOf(Opt("app:open", Menu.OPEN_APP) { pick("app:open"); openApp() }, Opt("app:back", Menu.BACK) { pick("app:back"); treeUp() })

    private fun openApp() { val p = notifPkg ?: return; pausedByUs = false; exit(false); out.openApp(p) }

    private fun notifListOptions(): List<Opt> = openItems().take(MAX_NOTIFS).map { n ->
        Opt("notif:item", notifLine(n, MAX_SPOKEN, false)) { pick("notif:item"); notifKey = n.key; show(Screen.NOTIF_ACTIONS) }
    } + Opt("notif:back", Menu.BACK) { pick("notif:back"); treeUp() }

    private fun notifActionOptions(): List<Opt> {
        val n = openNotif() ?: return emptyList()
        val acts = if (n.hidden) emptyList() else n.actions.map { a ->   // a private notification offers no action labels either
            Opt("notif:action", Phrase.content(a.label, out.songLang(a.label))) {
                pick("notif:action")
                if (a.needsText) { pausedByUs = false; exit(false); out.startNotifReply(n.key, a.index) }
                else notifDone(out.notifFire(n.key, a.index))
            }
        }
        return acts + listOf(
            Opt("notif:dismiss", Menu.DISMISS) { pick("notif:dismiss"); notifDone(out.notifDismiss(n.key)) },
            Opt("notif:mute", Menu.MUTE_APP) {
                pick("notif:mute"); out.notifMute(n.pkg); say(Voice.APP_MUTED); phase = Phase.WAIT; waitNext = { show(Screen.HUB) }
            },
            Opt("notif:open", Menu.OPEN_APP) { pick("notif:open"); openApp() },
            Opt("notif:back", Menu.BACK) { pick("notif:back"); show(Screen.NOTIF_LIST) })
    }

    private fun notifDone(ok: Boolean) { say(if (ok) Voice.NOTIF_DONE else Voice.NOTIF_FAILED); phase = Phase.WAIT; waitNext = { show(Screen.NOTIF_LIST) } }

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
            Screen.NOTIF_ACTIONS -> show(Screen.NOTIF_LIST)
            Screen.NOTIF_LIST, Screen.APP_OPEN -> treeUp()
            Screen.TREE -> { treePath.removeLastOrNull(); treeUp() }
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
        if (scanner.fasting) { speechOpen = false; return }   // a first word during fast scroll: nothing follows it
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
        const val SKIP_MS = Pacing.SKIP_MS   // two clicks while scanning: how soon the next option starts
        const val ITEM_GAP_MS = Pacing.ITEM_GAP_MS
        const val FIND_MS = 20_000L   // "Find my phone" rings this long
        const val SPEARCON_AFTER = 30   // picks of one option, then it is scanned as a sped-up spearcon
        private const val MAX_SPOKEN = 160
        private const val APP_RANK = 1000   // apps come after the OS programs when weights tie
        private const val MAX_NOTIFS = 20   // notifications read in one app list
    }
}
