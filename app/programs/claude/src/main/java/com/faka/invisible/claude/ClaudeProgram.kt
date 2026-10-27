package com.faka.invisible.claude

import android.os.Handler
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.logickit.squash
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.Voice
import com.faka.invisible.voicekit.VoiceKit
import java.io.IOException

/** What the Laptop tool asks of the OS besides the host: the Write keyboard (the program cannot reach another program). */
interface RoomsPort {
    /** The computers you added in the app (each has its own bridge). */
    fun computers(): List<Computer>
    /** The one computer the phone listens to; null = none set. */
    fun active(): Computer?
    /** Make [name] the computer the phone listens to (the OS stops the other watcher and starts this one). */
    fun switchTo(name: String)
    /** Leave this tool and open Write; [sendTo] gets the finished text (false = not sent). The OS comes back to this tool if nothing was written. */
    fun write(sendTo: (String) -> Boolean)
    /** Leave this tool and open Write [count] times in a row (each text is "" when nothing was written). If the first is empty the OS comes
     *  back to this tool; otherwise [finish] gets the texts, runs off the main thread, and the phrase it returns is spoken. */
    fun writeSeq(cues: List<Phrase>, finish: (List<String>) -> Phrase)
    /** Say "Test, done" the way the OS says a finished window, so you can check the voice and the volume. */
    fun testDone()
    /** Show on phone: post the notification "<window>: see changes" (one buzz); tapping it opens the Changes screen of [room] on [computer]. */
    fun showOnPhone(computer: Computer, room: Room) {}
}

/** Timers on the host's handler, one set per run. */
private class HandlerTimers(private val handler: Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}

/**
 * Laptop, a tool. Always the same walk: Home > Laptop > the WINDOWS of the laptop (tmux, GET /rooms; bridge/ROOMS-API.md) > one
 * window > the OPTIONS of that window (room.actions: Claude Code or shell) > an option runs. Last in the windows list: "New window"
 * (projects) and "Laptop settings" (switch computer, mute, test). Questions the laptop asks are not read on open: the need text is in the window.
 * The button is the OS list logic: 1 click = pick the item just spoken, 2 clicks = the one before, 3 clicks = back one level (from
 * the windows list: leave), hold = fast scroll (release speaks the item in full), hold 3 s = leave. The app screen shows the whole
 * list with the item being read ([ProgramHost.screen]) and a tap on a row picks it ([tap]). Only the button and the network; no microphone.
 */
class ClaudeAndroid(private val port: RoomsPort) : Program {
    private enum class Phase { NONE, INTRO, ITEM, STUCK, SENDING, SENT, LOADING, READING }

    private lateinit var host: ProgramHost
    private val handler get() = host.handler
    private lateinit var timers: Timers
    private lateinit var client: BridgeClient
    private var computers: List<Computer> = emptyList()
    private var switchIntro: Phrase? = null
    private lateinit var listenTo: Computer
    private var phase = Phase.NONE
    private var awaitItem = false
    @Volatile private var running = false
    private var clicks = 0
    private var downAt = 0L
    private var stopWaiting: (() -> Unit)? = null
    private var down = false
    private var fastOn = false
    private val leave = Runnable { shutdown() }
    private val holdFast = Runnable { startFast() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    private var reopen: () -> Unit = {}
    private var onBack: () -> Unit = { shutdown() }
    private var room: Room? = null
    private var projects: List<Project> = emptyList()
    private var project: Project? = null
    private var areas: List<Area> = emptyList()
    private var pending: RoomAction? = null
    private var storyIntro: Phrase? = null

    private fun label(c: Choice): Phrase = when (c) {
        is Choice.Option -> Phrase.content(c.text, "en")
        Choice.Again -> ClaudeVoice.READ_AGAIN
        is Choice.SwitchPick -> ClaudeVoice.switchTo(c.computer.name)
        Choice.SwitchMenu -> ClaudeVoice.SWITCH_COMPUTER
        is Choice.RoomPick -> ClaudeVoice.window(c.room)
        Choice.ProjectsPick -> ClaudeVoice.PROJECTS
        Choice.SettingsPick -> ClaudeVoice.SETTINGS
        Choice.MutePick -> ClaudeVoice.MUTE
        Choice.TestDonePick -> ClaudeVoice.TEST_DONE
        is Choice.ActPick -> ClaudeVoice.label(c.action, room?.go.orEmpty())
        Choice.No -> ClaudeVoice.NO_BACK
        Choice.Yes -> ClaudeVoice.YES
        is Choice.Approve -> when (c.opt.kind) { Approval.Kind.NO -> ClaudeVoice.NO; Approval.Kind.YES -> ClaudeVoice.YES; Approval.Kind.ALWAYS -> ClaudeVoice.YES_ALWAYS }
        Choice.Details -> ClaudeVoice.DETAILS
        is Choice.ProjectPick -> Phrase.content(c.project.name, "en")
        Choice.ContinueChat -> ClaudeVoice.CONTINUE_CHAT
        Choice.NewChat -> ClaudeVoice.NEW_CHAT
        Choice.NewProjectPick -> ClaudeVoice.NEW_PROJECT
        is Choice.AreaPick -> Phrase.content(c.area.name, "en")
    }

    private val scanner: Scanner<Choice> by lazy {
        Scanner(timers, { c -> label(c) }, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) { awaitItem = true; showRows(index); host.speak(label) }
            override fun sayFast(word: Phrase) { host.speak(word) }
            override fun tone(t: Tone) { VoiceKit.narrator.beep(t) }
            override fun leave() { phase = Phase.STUCK; reopen = { restartList() }; host.speak(ClaudeVoice.WAITING_YOU) }
        }, ScanCadence(pauseMs = { PAUSE_MS }, loops = { LOOPS }, ceremony = false), scanKey = "claude:scan")
    }

    /** The app screen: every item of the list, [mark] = the one being read. */
    private fun showRows(mark: Int) { host.screen(scanner.list.items.map { label(it).text }, mark) }

    /** A line spoken and shown (the app screen shows only what the program puts there). */
    private fun say(p: Phrase) { host.screen(listOf(p.text), 0); host.speak(p) }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        computers = port.computers().filter { it.url.isNotBlank() && it.token.isNotBlank() }
        val active = port.active()?.takeIf { it.url.isNotBlank() && it.token.isNotBlank() }
        if (active == null) { host.speak(ClaudeVoice.NOT_SET); host.ended(); return }
        timers = HandlerTimers(host.handler)
        running = true
        host.pauseMusic()
        connect(active)
    }

    /** Work with one computer: its windows are the first list. */
    private fun connect(c: Computer) {
        listenTo = c
        client = BridgeClient(c.url, c.token)
        loadRooms()
    }

    /** The list of the other computers (3 or more computers): pick one to listen to. */
    private fun showSwitchList() {
        onBack = { showSettings() }
        showList(Computers.others(computers, listenTo).map { Choice.SwitchPick(it) }, null)
    }

    /** Listen to [c] from now on: the OS moves its watcher, this tool lists its windows and says "<name>, listening" first. */
    private fun switchTo(c: Computer) {
        host.stopSpeech(); scanner.stop(); awaitItem = false
        port.switchTo(c.name)
        switchIntro = Voice.listening(c.name)
        connect(c)
    }

    override fun stop() = shutdown()

    private fun endWaiting() { stopWaiting?.invoke(); stopWaiting = null }

    /** Runs [work] off the main thread; [done] gets its result on the main thread, or null if the laptop did not answer. */
    private fun <T> bg(work: () -> T, done: (T?) -> Unit) {
        endWaiting(); stopWaiting = host.waiting()
        Thread({
            val r = try { work() } catch (_: IOException) { null }
            handler.post { endWaiting(); if (running) done(r) }
        }, "claude-rooms").start()
    }

    /** The laptop did not answer: say so (plainly, with the Tailscale hint for 100.x addresses). A click or a tap says "Trying again" at once, waits a moment, then runs [retry]. */
    private fun unreachable(retry: () -> Unit) {
        scanner.stop(); awaitItem = false; endWaiting()
        phase = Phase.STUCK
        reopen = {
            host.stopSpeech(); phase = Phase.LOADING
            say(ClaudeVoice.TRYING)
            timers.set("claude:retry", RETRY_MS) { if (running) retry() }
        }
        say(ClaudeVoice.notReachable(listenTo.url))
    }

    private fun restartList() { if (!scanner.isEmpty) { phase = Phase.ITEM; scanner.restartIn(0) } }

    private fun showList(items: List<Choice>, intro: Phrase?) {
        scanner.stop(); awaitItem = false; host.stopSpeech(); timers.clear("claude:next")
        scanner.load(items)
        reopen = { showList(items, intro) }
        showRows(0)
        if (intro != null) { phase = Phase.INTRO; host.speak(intro) } else { phase = Phase.ITEM; scanner.speakItem(0) }
    }

    private fun loadRooms() {
        scanner.stop(); awaitItem = false; room = null
        phase = Phase.LOADING
        bg({ client.rooms() }) { list -> if (list == null) unreachable { loadRooms() } else showRooms(list) }
    }

    /** The windows list: the windows (urgent first, at most [MAX_ROOMS]), then "New window" and "Laptop settings". */
    private fun showRooms(list: List<Room>) {
        onBack = { shutdown() }
        val picks = list.take(MAX_ROOMS).map { Choice.RoomPick(it) } + Choice.ProjectsPick + Choice.SettingsPick
        val intro = switchIntro ?: if (list.isEmpty()) ClaudeVoice.NO_ROOMS else null
        switchIntro = null
        showList(picks, intro)
    }

    /** Laptop settings: switch computer (when there is another), mute, test. */
    private fun showSettings() {
        onBack = { loadRooms() }
        val others = Computers.others(computers, listenTo)
        val switch: List<Choice> = when (others.size) { 0 -> emptyList(); 1 -> listOf(Choice.SwitchPick(others[0])); else -> listOf(Choice.SwitchMenu) }
        showList(switch + Choice.MutePick + Choice.TestDonePick, null)
    }

    private fun showActions(r0: Room, intro: Phrase?) {
        val old = room
        val r = if (r0.story.isBlank() && old != null && old.id == r0.id) r0.copy(story = old.story, go = old.go) else r0   // keep the story fetched on entry
        room = r; pending = null
        onBack = { loadRooms() }
        val ask = r.ask
        val items: List<Choice> = if (ask == null) RoomAction.earbudList(r).map { Choice.ActPick(it) }
            else listOf<Choice>(Choice.ActPick(RoomAction(RoomAction.HEAR))) + Approval.options(ask.options).map { Choice.Approve(it) } + Choice.Details   // No is always read before Yes; no Write here
        showList(items, intro)
    }

    /** What is said first in a window: its story, else its need line, else what it is about. */
    private fun intro(r: Room): Phrase =
        r.ask?.let { ClaudeVoice.wants(it.what, it.plain) } ?: r.story.takeIf { it.isNotBlank() }?.let { Phrase.content(it, "en") } ?: r.need.takeIf { it.isNotBlank() }?.let { Phrase.content(it, "en") } ?: ClaudeVoice.window(r)

    /** A window: its story first (asked from the laptop once if the list had none), then its options. */
    private fun openRoom(r: Room) {
        if (r.ask != null) { storyIntro = intro(r); showActions(r, storyIntro); return }
        if (r.story.isNotBlank()) { storyIntro = intro(r); showActions(r, storyIntro); return }
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.LOADING
        bg({ client.story(r.id) }) { got ->
            if (got == null) unreachable { openRoom(r) }
            else { val r2 = r.copy(story = got.first.squash(), go = got.second.squash()); storyIntro = intro(r2); showActions(r2, storyIntro) }
        }
    }

    private fun doAction(a: RoomAction) {
        val r = room ?: return
        when {
            a.id == RoomAction.HEAR -> showActions(r, storyIntro ?: intro(r))
            a.id == "readall" -> readAll(r)
            a.id == "write" -> write(r)
            a.needsConfirm -> { pending = a; onBack = { showActions(r, null) }; showList(listOf(Choice.No, Choice.Yes), if (a.id == "preview") ChangesVoice.START_PREVIEW else ClaudeVoice.sure(RoomText.label(a))) }
            else -> act(r, a)
        }
    }

    private fun act(r: Room, a: RoomAction) {
        if (a.id == "show" || a.id == "preview") { showOnPhone(r, a.id == "preview"); return }
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        bg({ client.act(r.id, a.id) }) { ok ->
            if (ok == true) { phase = Phase.SENT; say(ClaudeVoice.sentTo(RoomState.nameOf(r))) }
            else if (ok == null) unreachable { showActions(r, null) }
            else { reopen = { showActions(r, null) }; phase = Phase.STUCK; say(ClaudeVoice.FAILED) }
        }
    }

    /** Show on phone: ([start] = Start preview first, which waits for the dev server) then the OS posts the notification. */
    private fun showOnPhone(r: Room, start: Boolean) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        bg({ !start || client.startPreview(r.id) }) { ok ->
            when (ok) {
                true -> { port.showOnPhone(listenTo, r); phase = Phase.SENT; say(ChangesVoice.SENT_TO_PHONE) }
                null -> unreachable { showActions(r, null) }
                else -> { reopen = { showActions(r, null) }; phase = Phase.STUCK; say(ChangesVoice.NOT_STARTED) }
            }
        }
    }

    /** Send the pick of an approval through the bridge (POST /answer); then "Sent to <window>" and the windows list. */
    private fun approve(r: Room, o: Approval.Opt) {
        val ask = r.ask ?: return
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        bg({ client.answer(ask.id, o.label) }) { code ->
            when (code) {
                200 -> { phase = Phase.SENT; say(ClaudeVoice.sentTo(RoomState.nameOf(r))) }
                null -> unreachable { showActions(r, null) }
                else -> { reopen = { loadRooms() }; phase = Phase.STUCK; say(ClaudeVoice.TOO_LATE) }
            }
        }
    }

    private fun readDetails(r: Room) {
        val what = r.ask?.what.orEmpty()
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.READING
        say(Phrase.content(what, "en"))
    }

    private fun readAll(r: Room) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.LOADING
        bg({ client.last(r.id) }) { text ->
            when {
                text == null -> unreachable { showActions(r, null) }
                text.isBlank() -> { reopen = { showActions(r, null) }; phase = Phase.STUCK; say(ClaudeVoice.NOTHING_TO_READ) }
                else -> { phase = Phase.READING; say(Phrase.content(text, "en")) }
            }
        }
    }

    private fun write(r: Room) {
        val c = client
        releaseQuietly()
        port.write { text -> Thread({ try { c.send(r.id, text) } catch (_: IOException) {} }, "claude-send").start(); true }
    }

    private fun loadProjects() {
        scanner.stop(); awaitItem = false
        phase = Phase.LOADING
        bg({ client.projects() }) { list -> if (list == null) unreachable { loadProjects() } else { projects = list; showProjects() } }
    }

    private fun showProjects() {
        onBack = { loadRooms() }
        showList(listOf<Choice>(Choice.NewProjectPick) + projects.map { Choice.ProjectPick(it) }, null)
    }

    private fun loadAreas() {
        scanner.stop(); awaitItem = false
        phase = Phase.LOADING
        bg({ client.areas() }) { list ->
            if (list == null) unreachable { loadAreas() }
            else { areas = list; onBack = { showProjects() }; showList(list.map { Choice.AreaPick(it) }, if (list.isEmpty()) ClaudeVoice.NO_AREAS else null) }
        }
    }

    /** Ask for the name, then the first message, with Write; then make the project. */
    private fun newProject(a: Area) {
        val c = client
        releaseQuietly()
        port.writeSeq(listOf(ClaudeVoice.WRITE_NAME, ClaudeVoice.WRITE_ASK)) { texts ->
            val name = texts[0]
            try { if (c.newProject(a.path, name, texts[1])) ClaudeVoice.started(name) else ClaudeVoice.FAILED } catch (_: IOException) { ClaudeVoice.NOT_REACHABLE }
        }
    }

    private fun showModes(p: Project) {
        project = p
        onBack = { showProjects() }
        showList(listOf(Choice.ContinueChat, Choice.NewChat), null)
    }

    private fun openProject(mode: String) {
        val p = project ?: return
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        bg({ client.openProject(p.path, mode) }) { ok ->
            if (ok == true) { phase = Phase.SENT; say(ClaudeVoice.opened(p.name)) }
            else if (ok == null) unreachable { showModes(p) }
            else { reopen = { showModes(p) }; phase = Phase.STUCK; say(ClaudeVoice.FAILED) }
        }
    }

    private fun mute() {
        host.prefs.edit().putLong(RoomMute.KEY, RoomMute.nextMorning(System.currentTimeMillis())).apply()
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENT
        say(ClaudeVoice.MUTED)
    }

    /** Run the item [c] (the one just spoken or the one tapped). */
    private fun pick(c: Choice) {
        when (c) {
            is Choice.SwitchPick -> switchTo(c.computer)
            Choice.SwitchMenu -> showSwitchList()
            is Choice.RoomPick -> openRoom(c.room)
            Choice.ProjectsPick -> loadProjects()
            Choice.SettingsPick -> showSettings()
            Choice.MutePick -> mute()
            Choice.TestDonePick -> port.testDone()
            is Choice.ActPick -> doAction(c.action)
            is Choice.Approve -> room?.let { approve(it, c.opt) }
            Choice.Details -> room?.let { readDetails(it) }
            Choice.No -> room?.let { showActions(it, null) }
            Choice.Yes -> { val r = room; val a = pending; if (r != null && a != null) act(r, a) }
            is Choice.ProjectPick -> showModes(c.project)
            Choice.NewProjectPick -> loadAreas()
            is Choice.AreaPick -> newProject(c.area)
            Choice.ContinueChat -> openProject("continue")
            Choice.NewChat -> openProject("new")
            else -> {}
        }
    }

    /** The app screen was tapped on [row]: the same as the button picking it. */
    override fun tap(row: Int) {
        if (!running) return
        when (phase) {
            Phase.STUCK -> { reopen(); return }
            Phase.READING -> { host.stopSpeech(); room?.let { showActions(it, null) }; return }
            Phase.ITEM, Phase.INTRO -> {}
            else -> return
        }
        if (row !in scanner.list.items.indices) return
        scanner.stop(); awaitItem = false; host.stopSpeech()
        scanner.list.moveTo(row)
        pick(scanner.list.items[row])
    }

    private fun roomsClick(n: Int) {
        when {
            phase == Phase.READING -> { host.stopSpeech(); room?.let { showActions(it, null) } }
            n >= 4 -> shutdown()
            n == 3 -> { scanner.stop(); awaitItem = false; host.stopSpeech(); timers.clear("claude:retry"); onBack() }
            phase == Phase.STUCK -> { host.stopSpeech(); reopen() }
            phase == Phase.INTRO -> { host.stopSpeech(); phase = Phase.ITEM; scanner.speakItem(0) }
            scanner.isEmpty -> {}
            n == 2 -> { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.prevIn(0) }
            else -> { val c = scanner.list.current; scanner.stop(); awaitItem = false; host.stopSpeech(); pick(c) }
        }
    }

    override fun speechDone() {
        if (!running) return
        when (phase) {
            Phase.INTRO -> { phase = Phase.ITEM; scanner.speakItem(0) }
            Phase.ITEM -> if (awaitItem) { awaitItem = false; scanner.itemDone() }
            Phase.SENT -> timers.set("claude:next", NEXT_MS) { loadRooms() }
            Phase.READING -> room?.let { showActions(it, null) }
            else -> {}
        }
    }

    /** A hold reached 0.5 s: fast scroll (only while a list is read). */
    private fun startFast() {
        if (!running || phase != Phase.ITEM || scanner.isEmpty) return
        clicks = 0; handler.removeCallbacks(resolve)
        host.stopSpeech(); awaitItem = false; scanner.stop()
        fastOn = true
        scanner.startFast()
    }

    override fun down(time: Long) {
        downAt = time; down = true; handler.removeCallbacks(resolve)
        handler.postDelayed(holdFast, Pacing.HOLD_MS); handler.postDelayed(leave, Pacing.LEAVE_MS)
    }

    override fun up(time: Long) {
        handler.removeCallbacks(leave); handler.removeCallbacks(holdFast)
        if (fastOn) {   // released: stop on the item reached and speak it in full
            fastOn = false; down = false
            if (scanner.endFast()) { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.resumeIn(0) }
            return
        }
        if (down && time - downAt < Pacing.HOLD_MS) { clicks++; handler.postDelayed(resolve, Pacing.GAP_MS) }
        down = false
    }

    private fun onClicks(n: Int) {
        if (phase == Phase.SENDING || phase == Phase.LOADING) return
        roomsClick(n)
    }

    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve); handler.removeCallbacks(holdFast)
        timers.clear("claude:next"); timers.clear("claude:retry"); scanner.stop()
        stopWaiting?.invoke(); stopWaiting = null
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    /** Stops without telling the OS the program ended: the OS is about to start Write. */
    private fun releaseQuietly() {
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve); handler.removeCallbacks(holdFast)
        timers.clear("claude:next"); timers.clear("claude:retry"); scanner.stop()
        stopWaiting?.invoke(); stopWaiting = null
        host.stopSpeech()
        host.resumeMusic()
    }

    private companion object { const val RETRY_MS = 700L; const val PAUSE_MS = 1200L; const val LOOPS = 2; const val NEXT_MS = 700L; const val MAX_ROOMS = 8 }
}

/** What the OS registry lists for the Claude bridge (a tool of the home hub). */
object ClaudeProgram {
    fun entry(port: RoomsPort) = ProgramEntry("hub:claude", ClaudeVoice.TITLE) { ClaudeAndroid(port) }
}
