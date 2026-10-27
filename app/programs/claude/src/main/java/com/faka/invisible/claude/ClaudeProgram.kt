package com.faka.invisible.claude

import android.os.Handler
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.VoiceKit
import java.io.IOException

/** Where the bridge address and token are saved (the app home screen writes them). */
object ClaudeSettings {
    const val URL_KEY = "claude_url"
    const val TOKEN_KEY = "claude_token"
}

/** What the Laptop tool asks of the OS besides the host: the Write keyboard (the program cannot reach another program). */
interface RoomsPort {
    /** Leave this tool and open Write; [sendTo] gets the finished text (false = not sent). The OS comes back to this tool if nothing was written. */
    fun write(sendTo: (String) -> Boolean)
    /** Leave this tool and open Write [count] times in a row (each text is "" when nothing was written). If the first is empty the OS comes
     *  back to this tool; otherwise [finish] gets the texts, runs off the main thread, and the phrase it returns is spoken. */
    fun writeSeq(cues: List<Phrase>, finish: (List<String>) -> Phrase)
    /** Say "Test, done" the way the OS says a finished window, so you can check the voice and the volume. */
    fun testDone()
}

/** Timers on the host's handler, one set per run. */
private class HandlerTimers(private val handler: Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}

/**
 * Laptop (Claude bridge and Rooms), a tool. With nothing to answer it reads the rooms (tmux windows) of the laptop; see bridge/ROOMS-API.md.
 * Claude bridge part: It long-polls the laptop bridge on a background thread and speaks what comes: "<project> asks: <what>",
 * then the options one by one (the kit Scanner); a click picks the option just spoken. Notes (done, needs you) are one short line.
 * 1 click = pick the option just spoken, 2 clicks = the one before, 3 clicks = skip this one (answer it on the laptop), hold 3 s = leave.
 * Only the button and the network; no microphone.
 */
class ClaudeAndroid(private val port: RoomsPort) : Program {
    private enum class Phase { NONE, LINE, INTRO, ITEM, STUCK, SENDING, SENT, LOADING, READING }

    private lateinit var host: ProgramHost
    private val handler get() = host.handler
    private lateinit var timers: Timers
    private lateinit var client: BridgeClient
    private val queue = ClaudeQueue()
    private var current: ClaudeEvent? = null
    private var phase = Phase.NONE
    private var awaitItem = false
    @Volatile private var running = false
    private var clicks = 0
    private var downAt = 0L
    private var stopWaiting: (() -> Unit)? = null
    private var down = false
    private val leave = Runnable { shutdown() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    private var asking = false
    private var reopen: () -> Unit = {}
    private var onBack: () -> Unit = { shutdown() }
    private var room: Room? = null
    private var projects: List<Project> = emptyList()
    private var project: Project? = null
    private var areas: List<Area> = emptyList()
    private var pending: RoomAction? = null

    private fun label(c: Choice): Phrase = when (c) {
        is Choice.Option -> Phrase.content(c.text, "en")
        Choice.Again -> ClaudeVoice.READ_AGAIN
        is Choice.RoomPick -> ClaudeVoice.room(RoomState.nameOf(c.room), c.room.state)
        Choice.ProjectsPick -> ClaudeVoice.PROJECTS
        Choice.MutePick -> ClaudeVoice.MUTE
        Choice.TestDonePick -> ClaudeVoice.TEST_DONE
        is Choice.ActPick -> ClaudeVoice.label(c.action)
        Choice.No -> ClaudeVoice.NO_BACK
        Choice.Yes -> ClaudeVoice.YES
        is Choice.ProjectPick -> Phrase.content(c.project.name, "en")
        Choice.ContinueChat -> ClaudeVoice.CONTINUE_CHAT
        Choice.NewChat -> ClaudeVoice.NEW_CHAT
        Choice.NewProjectPick -> ClaudeVoice.NEW_PROJECT
        is Choice.AreaPick -> Phrase.content(c.area.name, "en")
    }

    private val scanner: Scanner<Choice> by lazy {
        Scanner(timers, { c -> label(c) }, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) { awaitItem = true; host.speak(label) }
            override fun sayFast(word: Phrase) {}
            override fun tone(t: Tone) {}
            override fun leave() { phase = Phase.STUCK; host.speak(ClaudeVoice.WAITING_YOU) }
        }, ScanCadence(pauseMs = { PAUSE_MS }, loops = { LOOPS }, ceremony = false), scanKey = "claude:scan")
    }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host
        val url = host.prefs.getString(ClaudeSettings.URL_KEY, "").orEmpty()
        val token = host.prefs.getString(ClaudeSettings.TOKEN_KEY, "").orEmpty()
        if (url.isBlank() || token.isBlank()) { host.speak(ClaudeVoice.NOT_SET); host.ended(); return }
        client = BridgeClient(url, token)
        timers = HandlerTimers(host.handler)
        running = true
        host.pauseMusic()
        phase = Phase.LOADING
        stopWaiting = host.waiting()
        Thread({ pollLoop() }, "claude-poll").apply { isDaemon = true }.start()
    }

    override fun stop() = shutdown()

    /** Background thread: the only place that waits for the network. */
    private fun pollLoop() {
        var since = 0
        var up = true
        var first = true
        while (running) {
            try {
                val r = client.poll(since, if (first) 1 else POLL_SEC)
                since = r.seq
                if (first) { first = false; up = true; handler.post { if (running) onFirst(r.events) } }
                else {
                    if (!up) { up = true; handler.post { if (running && phase == Phase.NONE) host.speak(ClaudeVoice.BACK_UP) } }
                    if (r.events.isNotEmpty()) handler.post { if (running) onEvents(r.events) }
                }
            } catch (e: IOException) {
                if (first && up) handler.post { if (running) unreachable { loadRooms() } }
                else if (up) handler.post { if (running && phase == Phase.NONE) host.speak(ClaudeVoice.DOWN) }
                up = false
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** The first answer of the laptop: a question waiting is read first, otherwise the rooms. */
    private fun onFirst(events: List<ClaudeEvent>) {
        endWaiting()
        for (e in events) queue.add(e)
        presentNext()
    }

    private fun endWaiting() { stopWaiting?.invoke(); stopWaiting = null }

    /** Runs [work] off the main thread; [done] gets its result on the main thread, or null if the laptop did not answer. */
    private fun <T> bg(work: () -> T, done: (T?) -> Unit) {
        endWaiting(); stopWaiting = host.waiting()
        Thread({
            val r = try { work() } catch (_: IOException) { null }
            handler.post { endWaiting(); if (running) done(r) }
        }, "claude-rooms").start()
    }

    private fun unreachable(retry: () -> Unit) {
        scanner.stop(); awaitItem = false; endWaiting()
        phase = Phase.STUCK; reopen = retry
        host.speak(ClaudeVoice.NOT_REACHABLE)
    }

    private fun showList(items: List<Choice>, intro: Phrase?) {
        scanner.stop(); awaitItem = false; host.stopSpeech(); timers.clear("claude:next")
        asking = false
        scanner.load(items)
        reopen = { showList(items, intro) }
        if (intro != null) { phase = Phase.INTRO; host.speak(intro) } else { phase = Phase.ITEM; scanner.speakItem(0) }
    }

    private fun loadRooms() {
        scanner.stop(); awaitItem = false; asking = false; room = null
        phase = Phase.LOADING
        bg({ client.rooms() }) { list -> if (list == null) unreachable { loadRooms() } else showRooms(list) }
    }

    private fun showRooms(list: List<Room>) {
        onBack = { shutdown() }
        val picks = list.take(MAX_ROOMS).map { Choice.RoomPick(it) } + Choice.ProjectsPick + Choice.MutePick + Choice.TestDonePick
        showList(picks, if (list.isEmpty()) ClaudeVoice.NO_ROOMS else null)
    }

    private fun showActions(r: Room, intro: Phrase?) {
        room = r; pending = null
        onBack = { loadRooms() }
        showList(RoomAction.earbudList(r).map { Choice.ActPick(it) }, intro)
    }

    private fun openRoom(r: Room) = showActions(r, r.need.takeIf { it.isNotBlank() }?.let { Phrase.content(it, "en") })

    private fun doAction(a: RoomAction) {
        val r = room ?: return
        when {
            a.id == "readall" -> readAll(r)
            a.id == "write" -> write(r)
            a.needsConfirm -> { pending = a; onBack = { showActions(r, null) }; showList(listOf(Choice.No, Choice.Yes), ClaudeVoice.sure(RoomText.label(a))) }
            else -> act(r, a)
        }
    }

    private fun act(r: Room, a: RoomAction) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        bg({ client.act(r.id, a.id) }) { ok ->
            if (ok == true) { phase = Phase.SENT; host.speak(ClaudeVoice.sentTo(RoomState.nameOf(r))) }
            else { reopen = { showActions(r, null) }; phase = Phase.STUCK; host.speak(if (ok == null) ClaudeVoice.NOT_REACHABLE else ClaudeVoice.FAILED) }
        }
    }

    private fun readAll(r: Room) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.LOADING
        bg({ client.last(r.id) }) { text ->
            when {
                text == null -> unreachable { showActions(r, null) }
                text.isBlank() -> { reopen = { showActions(r, null) }; phase = Phase.STUCK; host.speak(ClaudeVoice.NOTHING_TO_READ) }
                else -> { phase = Phase.READING; host.speak(Phrase.content(text, "en")) }
            }
        }
    }

    private fun write(r: Room) {
        val c = client
        releaseQuietly()
        port.write { text -> Thread({ try { c.send(r.id, text) } catch (_: IOException) {} }, "claude-send").start(); true }
    }

    private fun loadProjects() {
        scanner.stop(); awaitItem = false; asking = false
        phase = Phase.LOADING
        bg({ client.projects() }) { list -> if (list == null) unreachable { loadProjects() } else { projects = list; showProjects() } }
    }

    private fun showProjects() {
        onBack = { loadRooms() }
        showList(listOf<Choice>(Choice.NewProjectPick) + projects.map { Choice.ProjectPick(it) }, null)
    }

    private fun loadAreas() {
        scanner.stop(); awaitItem = false; asking = false
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
            if (ok == true) { phase = Phase.SENT; host.speak(ClaudeVoice.opened(p.name)) }
            else { reopen = { showModes(p) }; phase = Phase.STUCK; host.speak(if (ok == null) ClaudeVoice.NOT_REACHABLE else ClaudeVoice.FAILED) }
        }
    }

    private fun mute() {
        host.prefs.edit().putLong(RoomMute.KEY, RoomMute.nextMorning(System.currentTimeMillis())).apply()
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENT
        host.speak(ClaudeVoice.MUTED)
    }

    private fun roomsClick(n: Int) {
        when {
            phase == Phase.READING -> { host.stopSpeech(); room?.let { showActions(it, null) } }
            n >= 3 -> { scanner.stop(); awaitItem = false; host.stopSpeech(); onBack() }
            phase == Phase.STUCK -> { host.stopSpeech(); reopen() }
            phase == Phase.INTRO -> { host.stopSpeech(); phase = Phase.ITEM; scanner.speakItem(0) }
            n == 2 -> { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.prevIn(0) }
            else -> when (val c = scanner.list.current) {
                is Choice.RoomPick -> openRoom(c.room)
                Choice.ProjectsPick -> loadProjects()
                Choice.MutePick -> mute()
                Choice.TestDonePick -> port.testDone()
                is Choice.ActPick -> doAction(c.action)
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
    }

    private fun onEvents(events: List<ClaudeEvent>) {
        var fresh = false
        for (e in events) if (queue.add(e)) fresh = true
        if (fresh && (phase == Phase.NONE || (!asking && room == null && (phase == Phase.ITEM || phase == Phase.INTRO || phase == Phase.STUCK)))) presentNext()
    }

    private fun presentNext() {
        val e = queue.nextByUrgency()
        if (e == null) { current = null; loadRooms(); return }
        current = e
        present(e)
    }

    /** Speak [e] from the start: the line, then (for a question) the options. */
    private fun present(e: ClaudeEvent) {
        scanner.stop(); awaitItem = false; asking = true
        if (e.needsAnswer) {
            VoiceKit.narrator.beep(Tone.CLAUDE)
            scanner.load(Choice.of(e))
            phase = Phase.INTRO
            host.speak(ClaudeVoice.asks(e.project, e.summary))
        } else {
            phase = Phase.LINE
            host.speak(if (e.kind == ClaudeEvent.KIND_DONE) ClaudeVoice.done(e.project, e.summary) else ClaudeVoice.needs(e.project, e.summary))
        }
    }

    override fun speechDone() {
        if (!running) return
        when (phase) {
            Phase.INTRO -> { phase = Phase.ITEM; scanner.speakItem(0) }
            Phase.ITEM -> if (awaitItem) { awaitItem = false; scanner.itemDone() }
            Phase.LINE, Phase.SENT -> timers.set("claude:next", NEXT_MS) { presentNext() }
            Phase.READING -> room?.let { showActions(it, null) }
            else -> {}
        }
    }

    override fun down(time: Long) { downAt = time; down = true; handler.removeCallbacks(resolve); handler.postDelayed(leave, Pacing.LEAVE_MS) }

    override fun up(time: Long) {
        handler.removeCallbacks(leave)
        if (down && time - downAt < Pacing.LEAVE_MS) { clicks++; handler.postDelayed(resolve, Pacing.GAP_MS) }
        down = false
    }

    private fun onClicks(n: Int) {
        if (phase == Phase.SENDING || phase == Phase.LOADING) return
        if (!asking) { roomsClick(n); return }
        val e = current
        if (e == null || phase == Phase.NONE) { host.stopSpeech(); host.speak(ClaudeVoice.NOTHING); return }
        if (!e.needsAnswer) { host.stopSpeech(); timers.clear("claude:next"); presentNext(); return }   // a click skips a note
        when {
            n >= 3 -> { scanner.stop(); awaitItem = false; host.stopSpeech(); host.speak(ClaudeVoice.SKIPPED); phase = Phase.SENT }
            phase == Phase.INTRO || phase == Phase.STUCK -> { host.stopSpeech(); present(e) }
            n == 2 -> { host.stopSpeech(); awaitItem = false; phase = Phase.ITEM; scanner.prevIn(0) }
            else -> when (val c = scanner.list.current) {
                is Choice.Option -> send(e, c.text)
                Choice.Again -> { host.stopSpeech(); present(e) }
                else -> {}
            }
        }
    }

    private fun send(e: ClaudeEvent, choice: String) {
        scanner.stop(); awaitItem = false; host.stopSpeech()
        phase = Phase.SENDING
        stopWaiting = host.waiting()
        Thread({
            val code = try { client.answer(e.id, choice) } catch (_: IOException) { -1 }
            handler.post {
                stopWaiting?.invoke(); stopWaiting = null
                if (!running) return@post
                when (code) {
                    200 -> { phase = Phase.SENT; host.speak(ClaudeVoice.sent(choice)) }
                    409 -> { phase = Phase.SENT; host.speak(ClaudeVoice.TOO_LATE) }
                    else -> { host.speak(ClaudeVoice.FAILED); timers.set("claude:retry", NEXT_MS) { present(e) } }
                }
            }
        }, "claude-answer").start()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve)
        timers.clear("claude:next"); timers.clear("claude:retry"); scanner.stop()
        stopWaiting?.invoke(); stopWaiting = null
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    /** Stops without telling the OS the program ended: the OS is about to start Write. */
    private fun releaseQuietly() {
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve)
        timers.clear("claude:next"); timers.clear("claude:retry"); scanner.stop()
        stopWaiting?.invoke(); stopWaiting = null
        host.stopSpeech()
        host.resumeMusic()
    }

    private companion object { const val POLL_SEC = 25; const val RETRY_MS = 5000L; const val PAUSE_MS = 1200L; const val LOOPS = 2; const val NEXT_MS = 700L; const val MAX_ROOMS = 8 }
}

/** What the OS registry lists for the Claude bridge (a tool of the home hub). */
object ClaudeProgram {
    fun entry(port: RoomsPort) = ProgramEntry("hub:claude", ClaudeVoice.TITLE) { ClaudeAndroid(port) }
}
