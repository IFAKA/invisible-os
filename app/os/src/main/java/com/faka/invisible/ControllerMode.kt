package com.faka.invisible

import com.faka.invisible.oskit.*

import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.ProgramEntry

import com.faka.invisible.voicekit.*

/** A launchable app: [hint] = ApplicationInfo.category, -1 = none. */
data class InstalledApp(val pkg: String, val label: String, val hint: Int = -1)

/** How often an app was opened in the last month and the last week. */
data class AppUse(val month: Int, val week: Int)


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
    fun volumeMax(): Int = 15        // the device's top index
    fun startTimer(minutes: Int)
    fun setAlarm(hour: Int, minute: Int): Boolean = false   // a wake-up alarm at this time; false = it could not be set
    fun stopAlarm()
    fun timerRinging(): Boolean
    fun messages(): List<Msg>        // latest unread messages, newest first
    fun reply(key: String, text: String): Boolean   // true if the reply was sent
    fun notifGroups(): List<NotifGroup> = emptyList()   // the mailbox: every app's notifications, grouped, muted apps left out
    fun roomsWaiting(): Int = 0                   // Claude windows on the computer that wait for you (the last answer; renewed in the background)
    fun liveApps(): List<LiveApp> = emptyList()   // apps that are live now (playing, a workout...), most recently updated first, for the front of home
    fun liveNotif(key: String): Notif? = null     // an ongoing notification by key (live entries open it)
    fun notifRead(n: Notif): String = GenericModule.read(n)         // what is read in full (the app's module decides)
    fun notifSummary(n: Notif): String = GenericModule.summary(n)   // the one line in the list
    fun notifFire(key: String, index: Int): Boolean = false   // press action [index] of a notification
    fun notifDismiss(key: String): Boolean = false
    fun notifDismissAll(pkg: String?): Int = 0   // null = every app; returns how many were cleared (ongoing ones are kept)
    fun notifMute(pkg: String) {}
    fun installedApps(): List<InstalledApp> = emptyList()   // every launchable app (the leaves of the tree), this app left out
    fun resetLearning(pkg: String?): Int = 0   // forget the picks and launches of one app or program (null = all); returns how many picks went
    fun appUse(): Map<String, AppUse> = emptyMap()   // launches per app in the last month and week
    fun appMuted(pkg: String): Boolean = false   // a muted app is not in the tree
    fun openApp(pkg: String): Boolean = false   // start the app (its launch intent)
    fun notifAppHint(pkg: String): Int = -1   // ApplicationInfo.category of the app, -1 = unknown (a hint for its group)
    fun notifMutedCount(): Int = 0
    fun notifAccessOff(): Boolean = false   // the notification listener is not allowed: music control and messages fail silently
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
    fun hasMusicApp(): Boolean = false  // a music app is installed, so Music can start it with no song loaded
    fun musicToggles(): List<String> = emptyList()  // the music app's switches now, e.g. "Shuffle off", "Repeat off"
    fun musicToggle(kind: String, then: (String) -> Unit) {}   // press "shuffle" or "repeat"; then() gets the switch's new name
    fun musicRadio() {}                            // start a radio from the song that plays now
    fun searchMusic() {}                           // the Write keyboard; the text is looked up on the web, said back and played in the music app
    fun isMusicApp(pkg: String): Boolean = false   // YT Music (also the patched one): its page gets Play options
    fun playMusic(liked: Boolean = false) {}                 // open the music app and press Play all on its home (Quick picks); if that fails, start Liked music
    fun dndAllowed(): Boolean
    fun lastTimer(): Int             // minutes, 0 = none yet
    fun setLastTimer(minutes: Int)
    fun favourites(): List<String>   // most frequent senders, best first
    fun choice(screen: String, code: Int, intent: String, sender: String, pickMs: Long = -1)   // pickMs = time from the start of the list to the press (-1 = not a list pick); choice log; code 0 = wake, else the position of the option in the list
    fun codeCount(): Int             // successful code actions so far (persisted); learning mode while < 15
    fun setCodeCount(n: Int)
    fun background(work: () -> Unit, done: () -> Unit) { work(); done() }   // heavy work off the main thread; [done] runs on the main thread
    fun findHintSeen(): Boolean = true   // "Find. Pick the first letter." is said only once
    fun setFindHintSeen() {}
    fun loadReactions(): String = ""   // saved reaction samples (see ReactionGap.encode)
    fun saveReactions(text: String) {}
    fun programs(): List<ProgramEntry> = emptyList()   // the programs the OS lists (its registry), in menu order
    fun startProgram(id: String) {}   // start the program with this menu id (a ProgramEntry.id); the program gets the raw button
    fun weather(done: (Phrase) -> Unit) { done(Voice.WEATHER_FAILED) }   // off the main thread inside; [done] runs on the main thread with ONE sentence
    fun nextEvent(done: (Phrase) -> Unit) { done(Voice.CALENDAR_FAILED) }   // same: the next calendar event sentence
    fun wiredConnected(): Boolean = false   // a wired headset or USB audio is plugged in: then "Find my phone" is not offered
    fun navigation(): String? = null   // the latest Google Maps instruction as one sentence; null = not navigating
    fun findPhone(on: Boolean) {}
    fun callCaller(): String? = null   // the name (or number) of the ringing call, null = nothing rings
    fun answerCall(): Boolean = false
    fun rejectCall(): Boolean = false
    fun endCall(): Boolean = false
    fun lacks(need: String): Boolean = false   // a permission the feature needs is off (Needs ids: notif, contacts, calls)
    fun needed(need: String) {}                // remember it: the app screen then shows one card with the button to the right Settings page
    fun callContacts(): List<Contact> = emptyList()   // starred and most called first; slow: call it inside [background]
    fun dial(c: Contact): Boolean = false             // start a call to this contact
    fun emergency(): Boolean = false                  // call the emergency number of the phone's country and text the emergency contact (if set)   // ring loudly on the alarm stream (on) or stop (off)
    fun setContext(tags: Set<String>) {}   // the situation now (Suggester.TAGS); it is stored with every choice
    fun suggest(candidates: Collection<String>, now: Long, others: Collection<String> = emptyList(), accept: (String, Double, Int) -> Boolean = { _, _, _ -> true }): List<String> = emptyList()   // others = home entries that compete (never offered); accept = see SuggestGate; the OS's "Suggested" slot: up to 3 of these deep options, best first
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
 * 1 = choose the option spoken last, 2 = next option now, 3 = back one level (top: leave), 4 = leave. Two silent loops = leave. Click then hold = exit.
 * Hold (fast scroll, while options are read): steps through the options one word every 0.65 s or more (a word is never cut off), first word + tick; on release the option is
 * spoken in full and the scan goes on from there. Anywhere else a plain hold does nothing; click then hold always exits, so you can never get stuck.
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
    enum class Screen { HUB, MESSAGE, MORE_MSG, COMPOSE, TIMER_DONE, TIMER_PICK, VOLUME, TODAY, PHONE, TASK, TASK_ADD, MSG_TASK, GAMES, CALC, ALARM_HOUR, ALARM_MIN, SENT, TREE, APP_OPEN, NOTIF_APPS, NOTIF_LIST, NOTIF_ACTIONS, FIND, MUSIC, MUSIC_PLAY, VOLUME_DIAL, CALL_IN, CALL_ACTIVE, CONTACTS, EMERGENCY, RESET_ASK }
    private enum class Phase { INTRO, SCAN, WAIT, OUTRO, PENDING }
    private class Intro(val text: Phrase, val msgId: String?, val hint: Boolean = false) { var started = false }
    private class Opt(val key: String, val label: Phrase, val run: () -> Unit)

    /** Set from the app's settings screen; read at use, so changes apply at once. */
    var settings = LauncherSettings()
    private val reactions by lazy { ReactionGap(ReactionGap.decode(out.loadReactions())) }   // how fast the user clicks (saved across restarts)
    /** Dev only (e2e hook `pace`): scales the silences of the menu (gap between items, the screen-tone gap). Never touches button timing. 1 = real. */
    var devPace = 1f
    private val toneGap get() = (toneGapMs * devPace).toLong()
    private val pause get() = ((pauseMs ?: reactions.gap(settings.itemGapMs)) * devPace).toLong()   // the gap between items adapts: never under the user setting, longer for a slow user
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
    private var notifFromAll = false          // the app's notifications were reached from the Notifications list (back goes there), not from the tree
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
        override fun sayTitle(title: Phrase) { history.clear(); awaitStart = -1; say(title) }   // a click while the title is read picks nothing
    }
    private val ALARM_HOURS = listOf(7, 6, 8, 9, 5, 10, 4, 11) + (0..23).filter { it !in listOf(7, 6, 8, 9, 5, 10, 4, 11) }
    private val ALARM_MINUTES = listOf(0, 30, 15, 45, 10, 20, 40, 50, 5, 25, 35, 55)

    private val scanner = Scanner<Opt>(timers, { it.label }, scanOut,
        ScanCadence(pauseMs = { pause }, loops = { silentLoops }, tickLeadMs = tickLeadMs, restartLeadMs = restartLeadMs),
        spearcon = { settings.spearcons && out.pickCount(it.key) >= SPEARCON_AFTER })
    private val list get() = scanner.list   // the options being read
    private var awaitStart = -1
    private val history = ArrayList<Pair<Int, Long>>()   // (option index, time its speech started), oldest first
    private var scanStartedAt = 0L             // when beginScan started the list (for the time-to-pick in the choice log)
    private val itemEnds = HashMap<Int, Long>()   // option index -> time its speech ended (for the reaction time)
    private var pickMs = -1L                   // set while a click-pick runs: ms from list start to the press
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
        if (g0 == Gesture.CLICK_HOLD_END) { if (dialHolding) return else endFast(); return }   // the dial: the earbud ends a hold after ~0.7 s even if you keep pressing, so the release does not stop it; a press does
        if (active && screen == Screen.VOLUME_DIAL && phase == Phase.WAIT && g0 != Gesture.HOLD) { dial(g0); return }   // click then hold (HOLD) still closes the OS
        // The detector sends a plain hold as CLICK_HOLD (fast scroll, only while options are read) and click-then-hold as HOLD (exit):
        // exit needs the harder gesture so it is never an accident. Idle: a plain hold wakes. Active outside a list: a plain hold does nothing.
        val canFast = active && !finding && pending == null && phase == Phase.SCAN && !list.isEmpty
        val g = if (g0 == Gesture.CLICK_HOLD && !active) Gesture.HOLD else g0
        val firstPress = clock() - gapMs - lastPressMs
        if (active && finding) { endFind(); if (g != Gesture.HOLD) return }   // ANY click or hold stops the ringing; click then hold also closes
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
            Gesture.CLICK_HOLD -> if (canFast) { if (screen == Screen.HUB) startFind() else startFast() }   // on home a hold is Find (fast scroll over a few items is little use)
            Gesture.CLICK_HOLD_END -> {}
            Gesture.QUAD -> { stopAll(); out.choice(screen.name, 4, "leave", ""); leaveSilent() }
            Gesture.TRIPLE -> { stopAll(); out.choice(screen.name, 3, "back", ""); back() }
            Gesture.DOUBLE -> when (phase) {
                Phase.OUTRO -> { stopAll(); exit(true) }
                else -> again()   // two clicks while scanning: the previous option now
            }
            Gesture.SINGLE -> when (phase) {
                Phase.SCAN -> {   // one click chooses the option spoken last
                    val i = bind(firstPress) ?: return    // nothing spoken yet: ignore
                    noteReaction(i, firstPress)
                    stopAll(); code = i + 1; pickMs = firstPress - scanStartedAt
                    com.faka.invisible.oskit.Trace.ev("item", "screen=${screen.name} item=${i + 1}/${list.items.size} text=${com.faka.invisible.voicekit.ScreenMirror.items.getOrNull(i) ?: "?"} reactionMs=$pickMs")
                    list.items[i].run(); pickMs = -1
                }
                Phase.INTRO -> skipIntro()
                Phase.WAIT -> { val n = waitNext; stopAll(); n?.invoke() }
                Phase.OUTRO -> { stopAll(); exit(true) }
                Phase.PENDING -> {}
            }
        }
    }

    /** The app screen was tapped on item [i] named [text]: choose it as if it had just been spoken and the button clicked. False if the list moved on. */
    fun pickAt(i: Int, text: String): Boolean {
        if (active && screen == Screen.VOLUME_DIAL && i in 1..2 && com.faka.invisible.voicekit.ScreenMirror.items.getOrNull(i) == text) { dial(if (i == 1) Gesture.SINGLE else Gesture.DOUBLE); return true }
        if (!active || finding || pending != null || phase != Phase.SCAN || i !in list.items.indices) return false
        if (com.faka.invisible.voicekit.ScreenMirror.items.getOrNull(i) != text) return false
        stopAll(); code = i + 1; pickMs = -1
        com.faka.invisible.oskit.Trace.ev("item", "screen=${screen.name} item=${i + 1}/${list.items.size} text=$text via=app-screen")
        list.items[i].run()
        return true
    }

    /** Which option does a click at [pressAt] mean: the last one that started before it; the one before if it was within the grace. */
    private fun bind(pressAt: Long): Int? {
        var k = -1
        for (j in history.indices) if (history[j].second <= pressAt) k = j
        if (k < 0) return null
        if (k > 0 && pressAt - history[k].second <= graceMs) k--
        return history[k].first
    }

    /** Learn how fast the user clicks: ms from the end of item [i] speech to the press (negative = still speaking). */
    private fun noteReaction(i: Int, pressAt: Long) {
        val end = itemEnds[i]
        reactions.add(if (end == null) -1L else pressAt - end)
        out.saveReactions(reactions.encode())
    }

    /** Stop speech and every scan timer. */
    private fun stopAll() {
        scanner.stop(); timers.clear("dog"); timers.clear("findwait")
        if (speechOpen) out.stopSpeech()
        speechOpen = false; awaitStart = -1
    }

    // ---- wake: choose the screen by context ----
    /** Back from a program that was started from this screen without a result: open the same list again and read that item first. */
    fun resumeAt(s: Screen, key: String) {
        if (active) return
        active = true; pausedByUs = false; queue = mutableListOf(); qi = 0; fromHub = false; speechOpen = false; hintPending = false
        musicBack = Screen.HUB; firstKey = key
        out.log("MODE resume ${s.name}")
        show(s)
    }
    private var firstKey: String? = null

    private var accessWarn = false   // said once, before the first screen of this wake

    private var wakeT0 = 0L
    /** Wake timing marks (trace kind `wake`): ms since the wake started, for every step. */
    private fun wmark(step: String) { if (wakeT0 > 0) com.faka.invisible.oskit.Trace.ev("wake", "t+${System.currentTimeMillis() - wakeT0}ms $step") }

    private fun wake() {
        wakeT0 = System.currentTimeMillis(); wmark("start")
        active = true
        pausedByUs = out.pauseMusic(); wmark("pauseMusic")
        queue = buildQueue(); qi = 0; wmark("buildQueue")
        fromHub = false; speechOpen = false
        hintPending = learning(); wmark("learning")
        accessWarn = out.notifAccessOff(); wmark("notifAccessOff")
        out.log("MODE enter")
        com.faka.invisible.oskit.Trace.ev("wake", "menu on pausedMusic=$pausedByUs queue=${queue.size}")
        out.choice(if (queue.isEmpty()) "HUB" else if (queue[0] == null) "TIMER_DONE" else "MESSAGE", 0, "wake", queue.getOrNull(0)?.sender ?: "")
        showQueueItemOrHub(); wmark("shown")
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
        Screen.MORE_MSG, Screen.TODAY, Screen.PHONE, Screen.COMPOSE, Screen.TASK, Screen.TASK_ADD, Screen.MSG_TASK, Screen.GAMES, Screen.CALC, Screen.ALARM_HOUR, Screen.ALARM_MIN, Screen.TREE, Screen.APP_OPEN, Screen.NOTIF_APPS, Screen.NOTIF_LIST, Screen.NOTIF_ACTIONS, Screen.FIND, Screen.MUSIC, Screen.MUSIC_PLAY, Screen.CONTACTS -> Tone.MORE; Screen.CALL_IN, Screen.CALL_ACTIVE, Screen.EMERGENCY, Screen.RESET_ASK -> Tone.TIMER; Screen.VOLUME, Screen.VOLUME_DIAL -> Tone.VOLUME; Screen.SENT -> Tone.SENT
    }

    private fun learning() = out.codeCount() < learnActions

    // ---- screens: earcon, then the intro (hint, message text), then the scan ----
    /** The place for the app screen: the tree path (Talk > Mail), or the screen's name. */
    private fun mirrorTitle(s: Screen): String =
        if (s == Screen.HUB) "" else if (s == Screen.TREE) treePath.joinToString(" > ") { it.label } else s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun show(s0: Screen) {
        val s = notifGuard(s0)
        stopAll(); lookupId++
        screen = s; phase = Phase.INTRO; prebuilt = null; timers.clear("prebuild")
        com.faka.invisible.oskit.Trace.ev("screen", "${s.name} title=${mirrorTitle(s)}")
        com.faka.invisible.voicekit.ScreenMirror.setTitle(mirrorTitle(s))
        com.faka.invisible.voicekit.ScreenMirror.show(emptyList(), 0)   // the list of the last screen is gone: what is said now fills the picture
        if (!backNav) tn(tone(s)); backNav = false
        intro.clear()
        if (accessWarn) { accessWarn = false; com.faka.invisible.oskit.Trace.ev("wake", "notification access off: warned"); intro += Intro(Voice.NOTIF_ACCESS_OFF, null) }
        if (hintPending) { hintPending = false; intro += Intro(Voice.HINT, null, hint = true) }
        val m = cur
        if (s == Screen.MESSAGE && m != null) out.prepareAiReplies(m)
        if (s == Screen.MESSAGE && m != null && hid(m) !in heard) intro += Intro(Phrase.content("${m.sender}: ${shortText(m)}", vl(m)), hid(m))
        if (s == Screen.FIND && findStack.size == 1 && !out.findHintSeen()) { out.setFindHintSeen(); intro += Intro(Voice.FIND_HINT, null, hint = true) }
        if (s == Screen.TIMER_DONE) intro += Intro(Voice.TIMER_DONE, null)
        if (s == Screen.CALL_IN) intro += Intro(Voice.callFrom(out.callCaller() ?: "unknown"), null)
        if (s == Screen.CALL_ACTIVE) intro += Intro(Voice.CALL_ANSWERED, null)
        if (s == Screen.EMERGENCY) intro += Intro(Voice.EMERGENCY_ASK, null)
        if (s == Screen.RESET_ASK) intro += Intro(resetName?.let { Voice.resetAsk(it) } ?: Voice.RESET_ASK_ALL, null)
        if (s == Screen.NOTIF_ACTIONS) openNotif()?.let { intro += Intro(notifRead(it), null) }
        curIntro = null
        if (s == Screen.VOLUME_DIAL) {   // no list: the song plays on and the button is the dial
            phase = Phase.WAIT; waitNext = null; dialScreen()
            timers.set("scan", toneGap) { say(Voice.level(out.volumeLevel())); timers.set("scan", 900) { say(Voice.DIAL_HINT) } }   // the level, then what to do
            return
        }
        timers.set("scan", toneGap) { nextIntro() }
        if (s == Screen.HUB) { val me = lookupId; timers.set("prebuild", 15) { if (screen == Screen.HUB && lookupId == me && prebuilt == null) prebuilt = buildOptions() } }   // the home list is built while the screen tone plays, not after it
    }

    /** The home list built during the screen tone (see [show]); [beginScan] takes it once. */
    private var prebuilt: List<Opt>? = null

    private fun nextIntro() {
        val i = intro.removeFirstOrNull()
        curIntro = i
        if (i == null) { beginScan(); return }
        i.msgId?.let { handled.add(it) }
        if (!i.hint && screen in setOf(Screen.MESSAGE, Screen.CALL_IN, Screen.CALL_ACTIVE, Screen.NOTIF_ACTIONS)) com.faka.invisible.voicekit.ScreenMirror.setTitle(i.text.text.take(90))   // who calls, what the message says: stays on screen above the options
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
        Voice.cut(MessageLogic.spoken(m.text, m.lang).squash(), MAX_SPOKEN, isEs(m), null).text

    private fun say(text: Phrase, fast: Boolean = false) {
        if (phase != Phase.SCAN && screen != Screen.VOLUME_DIAL) com.faka.invisible.voicekit.ScreenMirror.said(text.text)
        speechOpen = true
        timers.set("dog", speechCapMs) { onSpeechDone() }
        out.speak(text, false, fast)
    }

    // ---- the scan ----
    private fun beginScan() {
        stopAll()
        phase = Phase.SCAN; curIntro = null
        history.clear(); itemEnds.clear()
        scanStartedAt = clock()
        wmark("beginScan"); val opts = prebuilt?.takeIf { screen == Screen.HUB } ?: buildOptions(); prebuilt = null; wmark("buildOptions")
        scanner.load(opts, listTitle())
        if (scanner.isEmpty) { leaveSilent(); return }
        val first = firstKey?.let { k -> opts.indexOfFirst { it.key == k } }?.takeIf { it > 0 } ?: 0
        firstKey = null
        scanner.speakItem(first); wmark("speakItem called"); wakeT0 = 0
    }

    /** The title of the list on screen, read again each time the list starts over: a single notification is its own title. */
    private fun listTitle(): Phrase? = if (screen == Screen.NOTIF_ACTIONS) openNotif()?.let { notifRead(it) } else null

    private fun recordStart() {
        if (awaitStart < 0) return
        history.add(awaitStart to clock()); awaitStart = -1
        if (history.size > 8) history.removeAt(0)
    }

    private fun itemDone() {
        recordStart()
        history.lastOrNull()?.let { itemEnds[it.first] = clock() }
        scanner.itemDone()
    }

    /** Two clicks while scanning: the previous option right now (before the first one, the last). In the other phases (two clicks): the list again from the first. */
    /** True while the options are being read: there one click chooses and two clicks go one back (the tones follow). */
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
            Phase.SCAN -> { phase = Phase.SCAN; scanner.prevIn(SKIP_MS) }   // the previous option now, at the user's own pace
            else -> { tn(Tone.RESTART); phase = Phase.SCAN; history.clear(); scanner.restartIn(toneGap - tickLeadMs) }
        }
    }

    /** Speech we did not start (volume level, torch, alarm stopped): wait for its end, or for a click, then continue. */
    private fun afterSpeech(next: () -> Unit) {
        phase = Phase.WAIT; waitNext = next; speechOpen = true
        timers.set("dog", waitCapMs) { onSpeechDone() }
    }

    /** The menu lines of the registered programs whose id starts with [prefix]. Picking one starts it; the music stays paused for it, and it resumes the music. */
    private fun programOpts(prefix: String): Array<Opt> = out.programs().filter { it.id.startsWith(prefix) }
        .map { e -> Opt(e.id, e.title) { pick(e.id); pausedByUs = false; exit(false); out.startProgram(e.id) } }.toTypedArray()

    // ---- options per screen ----
    private fun buildOptions(): List<Opt> {
        val m = cur
        return when (screen) {
            Screen.HUB -> {
                val all = linkedMapOf<String, Opt>()
                val n = unread().size
                for (k in settings.hub) when (k) {
                    "messages" -> if (n > 0) all["hub:messages"] = Opt("hub:messages", Menu.messages(n)) { readUnread("hub:messages") }
                        else if (out.lacks("notif")) all["hub:messages"] = Opt("hub:messages", Menu.MESSAGES) { pick("hub:messages"); out.needed("notif"); outro(Voice.NEED_NOTIFICATIONS) }   // no access yet: say why, the app shows the button
                    "music" -> if (out.nowPlaying() != null || out.hasMusicApp()) all["hub:music"] = Opt("hub:music", Menu.MUSIC) { pick("hub:music"); musicBack = Screen.HUB; show(Screen.MUSIC) }   // the Music page: what is playing, pause, next, play something
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
                    "alarm" -> all["hub:alarm"] = Opt("hub:alarm", Menu.SET_ALARM) { pick("hub:alarm"); show(Screen.ALARM_HOUR) }
                    "calculator" -> all["hub:calculator"] = Opt("hub:calculator", Menu.CALCULATOR) { pick("hub:calculator"); calc.clear(); show(Screen.CALC) }
                    "reset" -> all["hub:reset"] = Opt("hub:reset", Menu.RESET_LEARNING) { pick("hub:reset"); startFind(reset = true) }
                    "games" -> all["hub:games"] = Opt("hub:games", Menu.GAMES) { pick("hub:games"); show(Screen.GAMES) }
                    else -> out.programs().firstOrNull { it.id == "hub:$k" }?.let { e -> all[e.id] = Opt(e.id, if (k == "claude") out.roomsWaiting().let { w -> if (w > 0) Menu.roomsNeed(e.title.text, w) else e.title } else e.title) { pick(e.id); pausedByUs = false; exit(false); out.startProgram(e.id) } }   // a tool program of the registry; its place in the tree comes from its tag in AppCategories
                }
                suggestedKeys = emptyList()
                out.setContext(buildSet { if (n > 0) add("unread"); if (out.nowPlaying() != null) add("playing"); if (out.navigation() != null) add("navigating"); if (out.wiredConnected()) add("wired") })
                hubOpts = all; wmark("hub items+context")
                val leaves = treeLeaves(all); wmark("treeLeaves")
                val live = out.liveApps().filter { l -> l.pkg == AppWeights.SELF || leaves.any { it.pkg == l.pkg } }   // a live app must be a leaf (installed, not hidden or muted); this app's own is only the e2e fake
                homeLeaves = leaves.map { if (live.any { l -> l.pkg == it.pkg }) it.copy(live = true) else it }
                wmark("liveApps"); planFind(); wmark("planFind")
                val root = cachedTree(homeLeaves); treeRoot = root; treePath.clear(); wmark("cachedTree")
                val deep = deepOptions(); wmark("deepOptions")
                val far = suggestableLeaves(root)   // apps and programs that are not direct entries of home: offered too, when worth it
                val home = root.children.map { c -> c.app?.let { leafKey(it.pkg) } ?: "group:${c.label}" }
                val cost = far.mapValues { it.value.second }
                val sug = out.suggest((deep.keys - "phone:emergency") + far.keys, clock(), home) { key, share, pos -> key !in cost || SuggestGate.worth(share, cost.getValue(key), pos) }   // Emergency is never suggested: it is the last item of home
                    .mapNotNull { deep[it] ?: far[it]?.first }; wmark("suggest")
                com.faka.invisible.oskit.Trace.ev("live", "home live=" + live.joinToString(",") { "${it.pkg}:${it.state}" })
                val liveOpts = live.map { liveOption(it) }   // live apps (playing music, a running workout) come first, most recently updated first
                val liveKeys = live.map { "app:${it.pkg}" }.toSet() + (if (live.any { out.isMusicApp(it.pkg) }) setOf("hub:music") else emptySet())
                val playing = if (out.nowPlaying() != null && "hub:music" !in liveKeys) all["hub:music"] else null   // something is playing and is not already a live entry: its controls come first
                val front = (if (playing == null) sug else listOf(playing) + sug.filter { it.key != playing.key }).filter { it.key !in liveKeys }
                suggestedKeys = front.map { it.key }
                val notifs = out.notifGroups().sumOf { it.items.size }
                val notifOpt = if (notifs > 0) listOf(Opt("notifs:all", Menu.notifications(notifs)) { pick("notifs:all"); show(Screen.NOTIF_APPS) }) else emptyList()   // all waiting notifications, right after the live apps
                val (pinned, rest) = treeOptions(root).partition { o -> AppTree.PINNED.any { o.key == it } }   // the pinned Laptop tool comes right after Notifications, before the suggestions
                liveOpts + notifOpt + pinned + front.filter { f -> pinned.none { it.key == f.key } } + rest + unmuteOption() + Opt("phone:emergency", Menu.EMERGENCY) { pick("phone:emergency"); show(Screen.EMERGENCY) }   // the home list is the tree's top; only the small Suggested slot in front adapts; Emergency is always the last item
            }
            Screen.TASK -> {   // "Add task" first, then this page of tasks (a click right after hearing one = done), then "More tasks"
                val page = taskList.drop(taskIdx).take(TASK_PAGE)
                val opts = ArrayList<Opt>()
                opts += Opt("task:add", Menu.ADD_TASK) { pick("task:add"); show(Screen.TASK_ADD) }
                for (t in page) opts += Opt("task:done", Voice.cut(t.text, MAX_SPOKEN, t.lang == "es", t.lang.ifEmpty { out.songLang(t.text) ?: "en" })) {
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
            } }
            Screen.CALL_IN -> listOf(
                Opt("call:answer", Menu.ANSWER) { pick("call:answer"); if (out.answerCall()) show(Screen.CALL_ACTIVE) else callFailed() },
                Opt("call:reject", Menu.REJECT) { pick("call:reject"); if (out.rejectCall()) exit(false) else callFailed() })
            Screen.CALL_ACTIVE -> listOf(Opt("call:end", Menu.END_CALL) { pick("call:end"); out.endCall(); exit(false) })
            Screen.CONTACTS -> contacts.map { c -> Opt("call:contact", Phrase.content(c.name, null)) { pick("call:contact"); exit(false); if (out.dial(c)) out.speak(Voice.calling(c.name)) else out.speak(callFailure()) } }
            Screen.EMERGENCY -> listOf(   // the safe answer is read first: a click right after hearing it picks it
                Opt("emergency:no", Menu.EMERGENCY_NO) { pick("emergency:no"); show(Screen.PHONE) },
                Opt("emergency:yes", Menu.EMERGENCY_YES) { pick("emergency:yes"); exit(false); out.speak(if (out.emergency()) Voice.EMERGENCY_CALLING else callFailure()) })
            Screen.RESET_ASK -> listOf(   // the safe answer is read first
                Opt("reset:no", Menu.EMERGENCY_NO) { pick("reset:no"); show(Screen.FIND) },
                Opt("reset:yes", Menu.RESET_YES) { pick("reset:yes"); out.resetLearning(resetPkg); treeKey = null; treeCache = null; resetPkg = null; resetName = null; resetting = false; say(Voice.RESET_DONE); phase = Phase.WAIT; waitNext = { show(Screen.HUB) } })
            Screen.PHONE -> listOf(   // Call a contact first; Emergency is always last (one triple click back puts you on it) and cannot be switched off
                Opt("phone:call", Menu.CALL_CONTACT) { pick("phone:call"); out.background({ contacts = out.callContacts() }, { if (contacts.isEmpty()) outro(if (out.lacks("contacts")) { out.needed("contacts"); Voice.NEED_CONTACTS } else Voice.NO_CONTACTS) else show(Screen.CONTACTS) }) }
            ) + settings.phone.mapNotNull { k -> when (k) {
                "status" -> Opt("phone:status", Menu.STATUS) { pick("phone:status"); outro(Voice.status(out.status(), unread().size)) }
                "volume" -> Opt("phone:volume", Menu.VOLUME) { pick("phone:volume"); show(Screen.VOLUME) }
                "torch" -> Opt("phone:torch", Menu.TORCH) { pick("phone:torch"); out.runAction("Flashlight"); afterSpeech { beginScan() } }
                "dnd" -> if (out.dndAllowed()) Opt("phone:dnd", Menu.DND) { pick("phone:dnd"); out.runAction("Do not disturb"); afterSpeech { beginScan() } } else null
                "find" -> if (!out.wiredConnected()) Opt("phone:find", Voice.FIND_PHONE) { pick("phone:find"); find() } else null
                else -> null
            } } + Opt("phone:emergency", Menu.EMERGENCY) { pick("phone:emergency"); show(Screen.EMERGENCY) }
            Screen.GAMES -> programOpts("games:").toList()   // every game lives here, in the order of the registry (Programs.kt); a new game is one more line there
            Screen.CALC -> calcOptions()
            Screen.ALARM_HOUR -> ALARM_HOURS.map { h -> Opt("alarm:h$h", Menu.hour(h)) { pick("alarm:h$h"); alarmHour = h; show(Screen.ALARM_MIN) } }
            Screen.ALARM_MIN -> ALARM_MINUTES.map { m -> Opt("alarm:m$m", Menu.minute(m)) { pick("alarm:m$m"); setAlarmPending(alarmHour, m) } }
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
            Screen.FIND -> findOptions()
            Screen.APP_OPEN -> appOpenOptions()
            Screen.MUSIC -> musicOptions()
            Screen.MUSIC_PLAY -> musicPlayOptions()
            Screen.VOLUME_DIAL -> emptyList()
            Screen.NOTIF_APPS -> notifAppsOptions()
            Screen.NOTIF_LIST -> notifListOptions()
            Screen.NOTIF_ACTIONS -> notifActionOptions()
        }
    }

    // ---- The tree: home -> group -> (subgroup) -> app -> its notifications -> one in full with its actions ----
    private fun openItems() = out.notifGroups().filter { it.pkg == notifPkg }.flatMap { it.items }.sortedByDescending { it.at }
    private fun openNotif() = openItems().firstOrNull { it.key == notifKey } ?: notifKey?.let { out.liveNotif(it) }
    private fun liveOpen() = notifKey?.let { out.liveNotif(it) } != null   // the open notification is a live (ongoing) one: it is not in an app's list

    /** A live app on home: "<App>, playing" / "<App>, live" / "<App>, <its short line>". It opens its notification with the actions (pause, next, lap...), or the Music page for a music app. */
    private fun liveLabel(l: LiveApp): Phrase =
        if (l.line.isNotEmpty()) Phrase.content(l.app + ", " + l.line, out.songLang(l.line)) else Menu.liveState(l.app, l.state)
    private fun liveOption(l: LiveApp) = Opt("live:${l.pkg}", liveLabel(l)) {
        pick("app:${l.pkg}"); notifPkg = l.pkg; notifFromAll = false
        val key = l.notifKey
        when {
            out.isMusicApp(l.pkg) -> { musicBack = Screen.HUB; show(Screen.MUSIC) }
            key != null -> { notifKey = key; show(Screen.NOTIF_ACTIONS) }
            else -> show(Screen.APP_OPEN)
        }
    }

    /** A screen whose app or notification is gone (dismissed, removed by the app) falls back one level (an empty app: home, which is rebuilt). */
    private fun notifGuard(s: Screen): Screen = when {
        s == Screen.NOTIF_ACTIONS && openNotif() == null -> notifGuard(Screen.NOTIF_LIST)
        s == Screen.NOTIF_LIST && openItems().isEmpty() -> if (notifFromAll) notifGuard(Screen.NOTIF_APPS) else Screen.HUB
        s == Screen.NOTIF_LIST && openItems().size == 1 -> { notifKey = openItems().first().key; Screen.NOTIF_ACTIONS }   // one notification: no list, read it
        s == Screen.NOTIF_APPS && out.notifGroups().none { it.items.isNotEmpty() } -> Screen.HUB
        else -> s
    }

    /** The list one level above the app: the group it was picked in, or home. */
    /** One level above an app's notifications: the Notifications list it was picked in, or the tree. */
    private fun appUp() = if (notifFromAll) show(Screen.NOTIF_APPS) else treeUp()

    private fun treeUp() = show(if (treePath.isEmpty()) Screen.HUB else Screen.TREE)

    /**
     * The leaves of the tree: the OS programs that are switched on and every installed app (muted ones and this app left out), so a place never moves
     * because of a notification. Pin, hide and move edits are applied ([TreeLeaves.edit]). Weight = launches and picks ([AppWeights]); unread is only for the label.
     */
    private fun treeLeaves(builtins: Map<String, Opt>): List<AppLeaf> {
        val programs = builtins.map { (k, o) -> AppLeaf(k, o.label.text, AppCategories.categoryOf(k), if (k == "hub:messages") unread().size else 0, AppWeights.weight(k, 0, 0, out.pickCount(k)), rank = settings.hub.indexOf(k.removePrefix("hub:"))) }
        val unread = out.notifGroups().groupBy { it.pkg }.mapValues { (_, gs) -> gs.sumOf { it.items.size } }
        val apps = TreeLeaves.apps(out.installedApps(), out.appUse(), { out.appMuted(it) }, unread) { out.pickCount(it) }
        return TreeLeaves.edit(programs + apps, settings)
    }

    private var resetting = false               // Find is walked to pick an app to forget (Reset learning), not to open it
    private var resetPkg: String? = null; private var resetName: String? = null   // the app picked there (null = everything)
    private var treeKey: List<Any>? = null; private var treeCache: TreeNode? = null
    /** The search is exact but not free: when no weight, label or place changed since the last home list, the same tree is reused (unread changes only labels, so they are left out of the key). */
    private fun cachedTree(leaves: List<AppLeaf>): TreeNode {
        val key = leaves.map { listOf(it.pkg, it.label, it.category, it.weight, it.live) }
        val old = treeCache
        if (key == treeKey && old != null) return withUnread(old, leaves.associate { it.pkg to it.unread })
        return TreeLeaves.tree(leaves).also { treeKey = key; treeCache = it }
    }

    private fun withUnread(n: TreeNode, u: Map<String, Int>): TreeNode {
        val a = n.app?.let { it.copy(unread = u[it.pkg] ?: 0) }
        val kids = n.children.map { withUnread(it, u) }
        return TreeNode(n.label, kids, a, a?.unread ?: kids.sumOf { it.unread }, n.weight, n.rank)
    }

    private fun treeLabel(c: TreeNode): Phrase {
        val a = c.app
        val lang = if (a != null && !a.pkg.startsWith("hub:")) NameLang.voice(a.pkg, a.label) else null   // app names in their language; groups and OS programs stay English
        val counted = c.unread > 0 && !isMusic(c) && a?.pkg?.startsWith("hub:") == true   // app and group notifications are counted only inside Notifications
        return if (lang == null) { if (counted) Menu.treeCount(c.label, c.unread) else Menu.treeName(c.label) }
        else Phrase.content(if (counted) c.label + ", " + Menu.countText(c.unread) else c.label, lang)
    }

    /** One list of the tree: a group goes down, an OS program runs as before, an app opens its notifications. */
    private fun treeOptions(n: TreeNode): List<Opt> = n.children.map { c ->
        val a = c.app
        when {
            a != null && a.pkg in hubOpts -> hubOpts.getValue(a.pkg)
            a != null -> treeAppOption(c)
            else -> Opt("tree:group", treeLabel(c)) { pick("group:${c.label}"); treePath.add(c); show(Screen.TREE) }
        }
    }

    /** The history key of a leaf: an OS program is its own key ("hub:phone"), an app is "app:<package>" (what [pick] logs from the tree). */
    private fun leafKey(pkg: String) = if (pkg in hubOpts) pkg else "app:$pkg"

    /** Leaves below home (not its direct entries): key -> (an option that does what picking it in the tree does, cost of its path in the tree). */
    private fun suggestableLeaves(root: TreeNode): Map<String, Pair<Opt, Double>> {
        val out = LinkedHashMap<String, Pair<Opt, Double>>()
        for (p in AppTree.paths(root)) {
            if (p.levels == 0) continue
            val a = p.app; val key = leafKey(a.pkg)
            val opt = leafOption(a)
            out[key] = opt to SuggestGate.treeCost(p.heard, p.levels)
        }
        return out
    }

    /** What picking a leaf does: an OS program runs as before, an app opens its notifications or itself. The same from the tree, the Suggested slot and Find. */
    private fun leafOption(a: AppLeaf): Opt = hubOpts[a.pkg] ?: treeAppOption(TreeNode(a.label, app = a, unread = a.unread))

    // ---- Find: reach any app or program by the letters of its name (a hold on home). The steps come from NameFinder, computed from the leaves of the home build. ----
    private var homeLeaves: List<AppLeaf> = emptyList()
    private var findPlan: NameFinder.Node? = null          // the Find lists for the leaves of the last home build (built off the main thread)
    private var findPlanKey: List<Any>? = null
    private val findStack = ArrayList<NameFinder.Node>()   // the lists walked down; the last one is on screen

    /** Called when the home list is built: plan Find in the background if the leaves changed. */
    private fun planFind() {
        val key = homeLeaves.map { listOf(it.pkg, it.label, it.weight) }
        if (key == findPlanKey) return
        findPlanKey = key; findPlan = null
        val ls = homeLeaves; val box = arrayOfNulls<NameFinder.Node>(1)
        out.background({ box[0] = NameFinder.plan(ls) }) { if (findPlanKey === key) { findPlan = box[0]; out.log("FINDPLAN\n" + NameFinder.dump(box[0]!!)) } }
    }

    private fun startFind(reset: Boolean = false) {
        val plan = findPlan
        if (plan == null) { stopAll(); tn(Tone.TICK); timers.set("findwait", 300) { if (active && screen == Screen.HUB) startFind(reset) }; return }   // not ready yet: a tick, then again
        stopAll()
        out.choice(screen.name, 7, "find", "")   // for tools/menu_report.py: how often Find is used
        resetting = reset
        findStack.clear(); findStack.add(plan)
        show(Screen.FIND)
    }

    /** A range is read in Spanish when one of its end names is a Spanish app name (Camera to Mercado Pago). */
    private fun rangeLang(label: String): String? =
        if (homeLeaves.any { !it.pkg.startsWith("hub:") && NameLang.voice(it.pkg, it.label) != null && label.contains(it.label.substringBefore(",")) }) "es" else null

    private fun findOptions(): List<Opt> = (findStack.lastOrNull()?.parts ?: emptyList()).map { p ->
        val sub = p.sub
        if (sub != null) Opt("find:range", Phrase.content(p.label, rangeLang(p.label))) { pick("find:step", p.label); findStack.add(sub); show(Screen.FIND) }
        else if (resetting) p.leaf!!.let { l -> Opt("reset:app", Phrase.content(l.label, rangeLang(l.label))) { resetPkg = l.pkg; resetName = l.label; show(Screen.RESET_ASK) } }   // an app is only picked, never opened or counted as a pick
        else leafOption(p.leaf!!).let { o ->
            Opt(o.key, o.label) { out.choice(screen.name, code, "find:${findStack.size}", "", -1); o.run() }   // then exactly what the tree does (same pick intent)
        }
    } + (if (resetting && findStack.size == 1) listOf(Opt("reset:all", Menu.RESET_EVERYTHING) { resetPkg = null; resetName = null; show(Screen.RESET_ASK) }) else emptyList())   // the last item of the first list

    private fun treeAppOption(c: TreeNode): Opt { val a = c.app!!; return Opt("app:${a.pkg}", treeLabel(c)) { pick("app:${a.pkg}"); notifPkg = a.pkg; notifFromAll = false; show(if (c.unread > 0 && !isMusic(c)) Screen.NOTIF_LIST else Screen.APP_OPEN) } }
    /** A music app's only notification is its player: it never opens the notification list, it opens the Music page. */
    private fun isMusic(c: TreeNode) = c.app?.pkg?.let { out.isMusicApp(it) } == true

    private fun unmuteOption(): List<Opt> = if (out.notifMutedCount() == 0) emptyList() else listOf(Opt("notif:unmute", Menu.UNMUTE_ALL) {
        pick("notif:unmute"); out.notifUnmuteAll(); say(Voice.APPS_UNMUTED); phase = Phase.WAIT; waitNext = { show(Screen.HUB) }
    })

    /** What is read for a notification: a private one gives only the app. */
    private fun notifLine(n: Notif, max: Int, full: Boolean): Phrase {
        if (n.hidden) return Voice.PRIVATE_NOTIF   // inside an app the name was already said by the apps list
        val t = if (full) out.notifRead(n) else out.notifSummary(n)
        val lang = out.songLang(t)
        return Voice.cut(t.squash(), max, lang == "es", lang)
    }
    private fun notifRead(n: Notif) = notifLine(n, 500, true)

    /** An app without notifications: open it, or go back. */
    private fun appOpenOptions(): List<Opt> {
        val music = if (notifPkg?.let { out.isMusicApp(it) } == true) listOf(Opt("app:music", Menu.MUSIC) { pick("app:music"); musicBack = Screen.APP_OPEN; show(Screen.MUSIC) }) else emptyList()
        val weather = if (notifPkg?.contains("weather", true) == true) listOf(Opt("app:weather", Menu.SAY_WEATHER) { pick("app:weather"); lookup(out::weather) }) else emptyList()
        return weather + music + listOf(Opt("app:open", Menu.OPEN_APP) { pick("app:open"); openApp() })
    }

    private var musicBack = Screen.HUB
    private var contacts = listOf<Contact>()   // snapshot while the contact list is open

    private fun callFailed() { phase = Phase.OUTRO; say(Voice.CALL_FAILED) }

    /** A call rings: open the answer list now, over whatever is open. */
    fun callIncoming() {
        if (!active) { active = true; pausedByUs = out.pauseMusic(); queue = mutableListOf(); qi = 0; fromHub = false; speechOpen = false; hintPending = false; musicBack = Screen.HUB }
        out.log("MODE call")
        show(Screen.CALL_IN)
    }

    /** The call was answered on the phone itself while the list rings: the list becomes End call. */
    fun callOngoing() { if (active && screen == Screen.CALL_IN) show(Screen.CALL_ACTIVE) }

    /** The call is over (the other side hung up, or it was answered elsewhere): leave the call lists. */
    fun callEnded() {
        if (!active || (screen != Screen.CALL_IN && screen != Screen.CALL_ACTIVE)) return
        exit(false); out.speak(Voice.CALL_ENDED)
    }

    /** The Music page: what is playing, shuffle, repeat, radio from this song, play something (6 at most). Pause, next and previous are the earbud's own job. */
    private fun musicOptions(): List<Opt> {
        val song = out.nowPlaying()
        val sw = out.musicToggles()
        fun toggle(kind: String, word: String) = sw.firstOrNull { it.startsWith(word) }?.let { name ->
            Opt("music:$kind", Phrase.content(name, "en")) { pick("music:$kind"); out.musicToggle(kind) { now -> outro(Phrase.content(now, "en")) } } }
        val ctl = if (song == null) emptyList() else listOfNotNull(
            Opt("music:now", Menu.WHATS_PLAYING) { pick("music:now"); outro(Phrase.content(song, out.songLang(song))) },
            toggle("shuffle", "Shuffle"), toggle("repeat", "Repeat"),
            Opt("music:radio", Menu.RADIO) { pick("music:radio"); out.musicRadio(); outro(Menu.RADIO) })
        val volume = Opt("music:volume", Menu.VOLUME) { pick("music:volume"); show(Screen.VOLUME_DIAL) }
        val play = Opt("music:play", Menu.PLAY_SOMETHING) { pick("music:play"); show(Screen.MUSIC_PLAY) }
        return if (song == null) listOf(play, volume) else listOf(volume) + ctl + play   // nothing playing: you want music first; playing: you hear it and want the volume first
    }

    private fun musicPlayOptions(): List<Opt> = listOf(
        Opt("music:mix", Menu.PLAY_MIX) { pick("music:mix"); out.playMusic(false); outro(Menu.PLAYING_MUSIC) },
        Opt("music:liked", Menu.PLAY_LIKED) { pick("music:liked"); out.playMusic(true); outro(Menu.PLAYING_MUSIC) },
        Opt("music:search", Menu.SEARCH_SONG) { pick("music:search"); pausedByUs = false; exit(false); out.searchMusic() })

    private fun openApp() { val p = notifPkg ?: return; pausedByUs = false; exit(false); out.openApp(p) }

    /** Every app that has notifications ("WhatsApp, 3 notifications"), newest first; "Clear all" comes first. */
    private fun notifAppsOptions(): List<Opt> {
        val byApp = out.notifGroups().filter { it.items.isNotEmpty() }.groupBy { it.pkg }
        val apps = byApp.map { (pkg, gs) -> Triple(pkg, gs.first().app, gs.flatMap { it.items }) }.sortedByDescending { t -> t.third.maxOf { it.at } }
        return apps.map { (pkg, name, items) ->
            val lang = NameLang.voice(pkg, name)
            val label = if (lang == null) Menu.treeCount(name, items.size) else Phrase.content(name + ", " + Menu.countText(items.size), lang)
            Opt("notifs:app", label) { pick("notifs:app"); notifPkg = pkg; notifFromAll = true; show(Screen.NOTIF_LIST) }   // one notification: the guard skips the list
        } + Opt("notifs:clearall", Menu.CLEAR_ALL) { pick("notifs:clearall"); clearedAll(out.notifDismissAll(null)) { show(Screen.NOTIF_APPS) } }
    }

    /** The notifications of one app (it has 2 or more here; one goes straight to its actions): "Clear all from this app" last. */
    private fun notifListOptions(): List<Opt> =
        openItems().take(MAX_NOTIFS).map { n ->
            Opt("notif:item", notifLine(n, MAX_SPOKEN, false)) { pick("notif:item"); notifKey = n.key; show(Screen.NOTIF_ACTIONS) }
        } + Opt("notif:clearapp", Menu.CLEAR_APP) { pick("notif:clearapp"); clearedAll(out.notifDismissAll(notifPkg)) { appUp() } }

    /** Says how many went, then goes back one level ([back]): the cleared ones are gone from that list. Ones the app keeps (cannot be swiped) stay in it. */
    private fun clearedAll(n: Int, back: () -> Unit) { say(if (n > 0) Voice.cleared(n) else Voice.CANT_CLEAR); phase = Phase.WAIT; waitNext = back }

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
            Opt("notif:open", Menu.OPEN_APP) { pick("notif:open"); openApp() })
    }

    private fun notifDone(ok: Boolean) { say(if (ok) Voice.NOTIF_DONE else Voice.NOTIF_FAILED); phase = Phase.WAIT; waitNext = { show(if (liveOpen()) Screen.NOTIF_ACTIONS else Screen.NOTIF_LIST) } }

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
        out.addTask("Reply to ${m.sender}: ${MessageLogic.spoken(m.text, m.lang).squash().take(60)}", vl(m) ?: "")
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

    // ---- the volume dial (Music > Volume): no list, the song plays on. Hold = quieter, step after step until a press or the bottom, 1 click = one step louder, 2 clicks = one step quieter, 3 clicks = back.
    // Louder is only ever one step per click (never repeats), so a loud mistake costs one step and the next gesture fixes it.
    private var dialHolding = false
    private fun dial(g: Gesture) {
        if (dialHolding && g != Gesture.CLICK_HOLD) { stopDial(); return }   // any press stops the descent (and does nothing else)
        when (g) {
            Gesture.SINGLE -> dialStep(true)
            Gesture.DOUBLE -> dialStep(false)
            Gesture.CLICK_HOLD -> { stopAll(); dialHolding = true; dialDown() }
            Gesture.TRIPLE -> { stopAll(); out.choice(screen.name, 3, "back", ""); back() }
            Gesture.QUAD -> { stopAll(); out.choice(screen.name, 4, "leave", ""); leaveSilent() }
            else -> {}
        }
    }

    private fun dialStep(up: Boolean) {
        stopAll()
        val before = out.volumeLevel()
        out.volume(up)
        val n = out.volumeLevel()
        if (n == before) tn(Tone.CANCEL) else say(Voice.level(n))
        dialScreen()
    }

    /** The volume dial has no list: the app screen shows the level and the two things the button does; a tap does the same. */
    private fun dialScreen() {
        com.faka.invisible.voicekit.ScreenMirror.show(listOf("Volume ${out.volumeLevel()} of ${out.volumeMax()}", "Louder (1 click)", "Quieter (2 clicks)"), 0)
    }

    private fun dialDown() {
        if (!dialHolding) return
        val before = out.volumeLevel()
        out.volume(false)
        if (out.volumeLevel() == before) { dialHolding = false; tn(Tone.CANCEL); say(Voice.level(out.volumeLevel())); return }   // the bottom: stop and say where
        tn(levelTone(out.volumeLevel())); dialScreen()
        timers.set("dial", DIAL_STEP_MS) { dialDown() }
    }

    /** One of five notes: low pitch = quiet, high = loud. */
    private fun levelTone(n: Int) = Tone.valueOf("LVL" + (1 + n * 5 / (out.volumeMax() + 1)).coerceIn(1, 5))

    private fun stopDial() {
        stopAll(); timers.clear("dial"); dialHolding = false
        say(Voice.level(out.volumeLevel())); dialScreen()
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

    private fun callFailure(): Phrase = if (out.lacks("calls")) { out.needed("calls"); Voice.NEED_CALLS } else Voice.CALL_FAILED
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
            Screen.NOTIF_ACTIONS -> if (liveOpen()) show(Screen.HUB) else if (openItems().size >= 2) show(Screen.NOTIF_LIST) else appUp()   // a lone notification has no list above it
            Screen.NOTIF_LIST -> appUp()
            Screen.NOTIF_APPS -> show(Screen.HUB)
            Screen.APP_OPEN -> treeUp()
            Screen.MUSIC -> show(musicBack)
            Screen.MUSIC_PLAY, Screen.VOLUME_DIAL -> show(Screen.MUSIC)
            Screen.TREE -> { treePath.removeLastOrNull(); treeUp() }
            Screen.FIND -> { findStack.removeLastOrNull(); if (findStack.isEmpty()) show(Screen.HUB) else show(Screen.FIND) }
            Screen.TIMER_PICK, Screen.TODAY, Screen.PHONE, Screen.TASK, Screen.TASK_ADD, Screen.GAMES, Screen.CALC, Screen.ALARM_HOUR -> show(Screen.HUB)
            Screen.ALARM_MIN -> show(Screen.ALARM_HOUR)
            Screen.CONTACTS, Screen.EMERGENCY -> show(Screen.PHONE)
            Screen.RESET_ASK -> show(Screen.FIND)
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

    // ---- calculator and alarm programs ----
    private val calc = Calculator()
    private var alarmHour = 7

    /** The list adapts: typing a number puts Equals and the operators first, waiting for a number puts the digits first. */
    private fun calcOptions(): List<Opt> {
        fun then(key: String, label: Phrase, act: () -> Unit) = Opt("calc:$key", label) { pick("calc:$key"); act(); calcSay() }
        val digits = (1..9).plus(0).map { d -> then("d$d", Menu.digit(d)) { calc.digit(d) } }
        val point = then("point", Menu.POINT) { calc.point() }
        val ops = "+-*/%".map { o -> then("op$o", Menu.op(o)) { calc.operate(o) } }
        val equals = Opt("calc:equals", Menu.EQUALS) { pick("calc:equals"); calc.equals(); calcSay() }
        val tail = listOf(then("undo", Menu.UNDO) { calc.undo() }, then("clear", Menu.CLEAR) { calc.clear() })
        return when {
            calc.typing -> listOfNotNull(equals.takeIf { calc.canEquals }) + (if (calc.canOperate) ops else emptyList()) + digits + point + tail
            calc.canOperate && !calc.typing && calc.isResult() -> ops + digits + point + tail
            else -> digits + point + (if (calc.canOperate) ops else emptyList()) + tail
        }
    }

    /** Say what the calculator holds now, then read the (changed) list again from the top. */
    private fun calcSay() {
        say(Voice.calc(calc.spoken()), true)
        phase = Phase.WAIT; waitNext = { beginScan() }
    }

    private fun setAlarmPending(h: Int, m: Int) =
        startPending(Voice.settingAlarm(h, m), { out.setAlarm(h, m).also { if (!it) say(Voice.ALARM_FAILED) } }, onCancel = { show(Screen.HUB) }, after = { exit(false) })

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
        out.choice(screen.name, code, intent, sender, pickMs)
        if (intent != "none" && intent != "nothing") out.setCodeCount(out.codeCount() + 1)
    }

    /** Dev (e2e): show a screen by name, waking the menu silently first. */
    fun devShow(name: String) { if (!active) { active = true; queue = mutableListOf(); qi = 0; fromHub = false; speechOpen = false; hintPending = false; musicBack = Screen.HUB }; show(Screen.valueOf(name)) }
    /** Dev (e2e): "screen phase" of the menu, for the test log. */
    fun devState() = "active=$active screen=$screen"

    fun exit(withTone: Boolean = true) {
        if (!active) return
        stopAll(); lookupId++
        if (finding) { finding = false; timers.clear("find"); out.findPhone(false) }
        active = false
        com.faka.invisible.voicekit.ScreenMirror.clear()
        if (pausedByUs) { pausedByUs = false; out.resumeMusic() }
        out.log("MODE exit")
        com.faka.invisible.oskit.Trace.ev("exit", "menu off from ${screen.name} tone=$withTone")
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
        const val SKIP_MS = Pacing.SKIP_MS   // two clicks while scanning: how soon the previous option starts
        const val DIAL_STEP_MS = 450L   // volume dial: one step quieter every this long after a hold, until a press
        const val ITEM_GAP_MS = Pacing.ITEM_GAP_MS
        const val FIND_MS = 20_000L   // "Find my phone" rings this long
        const val SPEARCON_AFTER = 30   // picks of one option, then it is scanned as a sped-up spearcon
        private const val MAX_SPOKEN = 160
        private const val MAX_NOTIFS = 20   // notifications read in one app list
    }
}
