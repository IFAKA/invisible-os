package com.faka.invisible

import com.faka.invisible.practice.PracticeEntry
import com.faka.invisible.practice.PracticeProgram
import com.faka.invisible.oskit.*

import com.faka.invisible.logickit.*

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.AlarmClock
import android.content.ComponentName
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.faka.invisible.claude.ClaudeEvent
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.Computers
import com.faka.invisible.claude.DoneBatch
import com.faka.invisible.claude.RoomMute
import com.faka.invisible.claude.RoomsPort
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.write.Frame
import com.faka.invisible.write.WriteProgram
import com.faka.invisible.write.WriteServices
import com.faka.invisible.voicekit.*
import java.util.Calendar
import java.util.Locale

/** Shared by ButtonService (media session path) and ButtonAccessibilityService (raw key path). */
object Actions {
    private const val TAG = "Invisible"
    private const val BACK_DELAY_MS = 2200L   // after "Nothing written" has been said
    /** Speech rate at the start (1.0 = normal). It rises 0.1 per 20 successful picks, up to 1.8: blind users listen fast. */
    const val SPEECH_RATE = 0.9f
    private fun speechRate() = com.faka.invisible.logickit.SpeechRate.effective(mode.settings.speechRate, prefs().getInt("codeCount", 0))   // use speeds the voice up a little, never more than +0.2x: the slider is what the user hears
    @Volatile var accessibilityConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val narrator get() = VoiceKit.narrator
    private var torchOn = false
    private var taskStore = TaskStore()
    private var taskMemory = PhraseMemory()
    private var replyMemory = PhraseMemory()
    private val aiCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()   // reply sentences from the local model, per message
    private val aiTaskCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val aiBusy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun tasks(): List<Task> = taskStore.all()
    /** Splits the note, detects each task's language (off the main thread), saves, then calls [done] on the main thread with the count. */
    fun addTasks(text: String, done: (Int) -> Unit) {
        Thread {
            val n = try { taskStore.addAll(text) { try { ControllerNotificationListener.voiceFor(it) } catch (e: Throwable) { "" } } }
                catch (e: Throwable) { Log.e(TAG, "addTasks failed", e); 0 }
            handler.post { done(n) }
        }.start()
    }
    fun completeTask(id: Long) = taskStore.done(id)
    fun moveTask(from: Int, to: Int) = taskStore.move(from, to)
    private val modeTimers = HashMap<String, Runnable>()
    var choices = ChoiceLog()
    private var context: Set<String> = emptySet()   // what the OS saw when the hub was last built (see Suggester.TAGS)
        private set
    val mode: ControllerMode = ControllerMode(
        out = object : ModeOutput {
            override fun speak(text: Phrase, queue: Boolean, fast: Boolean) = say(text, queue, fast)
            override fun stopSpeech() = narrator.stopSpeech()
            override fun tone(kind: Tone) = narrator.beep(kind)
            override fun runAction(name: String) = menuAction(name)
            override fun forward(what: Forward) = forwardToMusic(what)
            override fun log(line: String) { Log.d(TAG, line); if (line.startsWith("MODE")) EventLog.add(line) }
            override fun tasks() = taskStore.all()
            override fun prepareAiReplies(m: Msg) {
                val k = "${m.key}|${m.at}"
                if (!mode.settings.ai || !LocalAi.available(app) || aiCache.containsKey(k) || !aiBusy.add(k)) return
                Thread {
                    val msg = "${m.sender}: ${m.text.take(300)}"; val l = if (m.lang.startsWith("en")) "en" else "es"
                    try { aiCache[k] = LocalAi.replies(app, msg, l); aiTaskCache[k] = LocalAi.tasks(app, msg, l) }
                    catch (e: Throwable) { Log.e(TAG, "ai failed", e); aiCache.putIfAbsent(k, emptyList()); aiTaskCache.putIfAbsent(k, emptyList()) } finally { aiBusy.remove(k) }
                }.start()
            }
            override fun aiTasks(m: Msg) = aiTaskCache["${m.key}|${m.at}"].orEmpty().map { Task(0, it, if (m.lang.startsWith("en")) "en" else "es") }
            override fun aiReplies(m: Msg) = aiCache["${m.key}|${m.at}"].orEmpty().map { Task(0, it, if (m.lang.startsWith("en")) "en" else "es") }
            override fun replyOffers() = replyMemory.offers(3, emptySet())
            override fun rememberReply(text: String, lang: String) = replyMemory.record(text, lang)
            override fun taskOffers() = taskMemory.offers(5, taskStore.all().map { it.text.lowercase() }.toSet())
            override fun addTask(text: String, lang: String) = (taskStore.add(text, lang) != null).also { EventLog.refresh() }
            override fun completeTask(id: Long) { taskStore.done(id); EventLog.refresh() }
            override fun nowPlaying(): String? = otherSessions().firstNotNullOfOrNull { c ->
                c.metadata?.let { md ->
                    val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    if (title.isNullOrBlank()) null else if (artist.isNullOrBlank()) title else "$title, $artist"
                }
            }
            override fun hasMusicApp() = musicApps.any { app.packageManager.getLaunchIntentForPackage(it) != null }
            override fun musicToggles() = otherSessions().firstOrNull { it.packageName in musicApps }?.playbackState?.customActions?.map { it.name.toString() } ?: emptyList()
            override fun musicToggle(kind: String, then: (String) -> Unit) = toggleNow(kind, then)
            override fun musicRadio() = radioNow()
            override fun searchMusic() = launchWrite({ q -> searchAndPlay(q); true }, null, backTo = { mode.resumeAt(ControllerMode.Screen.MUSIC_PLAY, "music:search") })
            override fun isMusicApp(pkg: String) = pkg in musicApps
            override fun playMusic(liked: Boolean) = playQuickPicks(liked)
            override fun songLang(text: String) = ControllerNotificationListener.voiceFor(text)
            override fun pauseMusic() = pauseMusicNow()
            override fun resumeMusic() = resumeMusicNow()
            override fun volume(up: Boolean) = adjustVolume(up)
            override fun volumeMax() = app.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            override fun volumeLevel() = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            override fun startTimer(minutes: Int) = startTimerNow(minutes)
            override fun setAlarm(hour: Int, minute: Int) = setAlarmNow(hour, minute)
            override fun stopAlarm() = stopAlarmNow()
            override fun timerRinging() = ringing != null
            override fun messages() = ControllerNotificationListener.store.all()
            override fun reply(key: String, text: String) = ControllerNotificationListener.sendReply(key, text, app)
            override fun notifGroups() = ControllerNotificationListener.notifs.groups { ControllerNotificationListener.modules.forNotif(it).groupKey(it) }
            override fun liveApps() = liveAppsNow()
            override fun roomsWaiting() = roomsWaitingNow()
            override fun liveNotif(key: String) = ControllerNotificationListener.ongoingNotif(key)
            override fun notifRead(n: Notif) = ControllerNotificationListener.modules.forNotif(n).read(n)
            override fun notifSummary(n: Notif) = ControllerNotificationListener.modules.forNotif(n).summary(n)
            override fun notifFire(key: String, index: Int) = ControllerNotificationListener.fireAction(key, index, null, app)
            override fun notifDismiss(key: String) = ControllerNotificationListener.dismiss(key)
            override fun notifDismissAll(pkg: String?) = ControllerNotificationListener.dismissAll(pkg)
            override fun notifMute(pkg: String) = ControllerNotificationListener.mute(pkg)
            override fun notifAppHint(pkg: String) = try { app.packageManager.getApplicationInfo(pkg, 0).category } catch (e: Exception) { -1 }
            override fun installedApps() = DeviceApps.installedNow(app)
            override fun appUse() = DeviceApps.useNow(app)
            override fun resetLearning(pkg: String?): Int {
                val gone = choices.forget { LearningReset.matches(pkg, it) }
                val installed = DeviceApps.installedNow(app).map { it.pkg }
                LearningReset.saveBaseline(prefs(), DeviceApps.useNow(app), if (pkg == null) installed else listOf(pkg))
                Trace.ev("learning", "reset ${pkg ?: "everything"}: $gone picks")
                return gone
            }
            override fun appMuted(pkg: String) = ControllerNotificationListener.mutes.isMuted(pkg)
            override fun openApp(pkg: String) = DeviceApps.open(app, pkg)
            override fun notifMutedCount() = ControllerNotificationListener.mutes.all().size
            override fun notifAccessOff() = Needs.missing(app, "notif").also { if (it) Needs.record(app, "notif") }
            override fun notifUnmuteAll() = ControllerNotificationListener.unmuteAll()
            override fun startNotifReply(key: String, index: Int) = launchWrite({ text -> ControllerNotificationListener.fireAction(key, index, text, app) },
                ControllerNotificationListener.notifs.get(key))
            override fun status(): Status {
                val c = Calendar.getInstance()
                val b = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val t = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                val a = t?.let { Calendar.getInstance().apply { timeInMillis = it } }
                return Status(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), b, a?.get(Calendar.HOUR_OF_DAY), a?.get(Calendar.MINUTE))
            }
            override fun weather(done: (Phrase) -> Unit) { val w = narrator.waiting(); PhoneInfo.weather(app) { t -> w(); handler.post { done(t) } } }
            override fun nextEvent(done: (Phrase) -> Unit) { val w = narrator.waiting(); PhoneInfo.nextEvent(app) { t -> w(); handler.post { done(t) } } }
            override fun wiredConnected() = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            override fun navigation() = ControllerNotificationListener.navigation
            override fun findPhone(on: Boolean) = narrator.findLoop(on)
            override fun callCaller() = ControllerNotificationListener.ringing?.name
            override fun answerCall() = PhoneCalls.answer(app) || ControllerNotificationListener.fireCall(app, answer = true)
            override fun rejectCall() = PhoneCalls.end(app) || ControllerNotificationListener.fireCall(app, answer = false)
            override fun endCall() = PhoneCalls.end(app) || ControllerNotificationListener.fireCall(app, answer = false)
            override fun callContacts() = PhoneCalls.contacts(app)
            override fun lacks(need: String) = Needs.missing(app, need)
            override fun needed(need: String) = Needs.record(app, need)
            override fun dial(c: com.faka.invisible.logickit.Contact) = PhoneCalls.place(app, c.number)
            override fun emergency() = PhoneCalls.emergency(app, emergencyContact())
            override fun dndAllowed() = this@Actions.dndAllowed()
            override fun waiting() = narrator.waiting()
            override fun background(work: () -> Unit, done: () -> Unit) { Thread { work(); handler.post { done() } }.start() }
            override fun findHintSeen() = prefs().getBoolean("find_hint_seen", false)
            override fun setFindHintSeen() { prefs().edit().putBoolean("find_hint_seen", true).apply() }
            override fun loadReactions() = prefs().getString("reactions", "") ?: ""
            override fun saveReactions(text: String) { prefs().edit().putString("reactions", text).apply() }
            override fun codeCount() = prefs().getInt("codeCount", 0)
            override fun setCodeCount(n: Int) { prefs().edit().putInt("codeCount", n).apply() }
            override fun lastTimer() = prefs().getInt("lastTimer", 0)
            override fun setLastTimer(minutes: Int) { prefs().edit().putInt("lastTimer", minutes).apply() }
            override fun programs() = Programs.all
            override fun startProgram(id: String) { Programs.byId(id)?.let { startProgramEntry(it) } }
            override fun pickCount(intent: String) = choices.intentCount(intent)
            override fun favourites() = choices.favourites()
            override fun suggest(candidates: Collection<String>, now: Long, others: Collection<String>, accept: (String, Double, Int) -> Boolean) = Suggester.top(choices.history(), candidates, now, tags = context, others = others, accept = accept)
            override fun setContext(tags: Set<String>) { context = tags }
            override fun choice(screen: String, code: Int, intent: String, sender: String, pickMs: Long) {
                com.faka.invisible.oskit.Trace.ev("pick", "screen=$screen code=$code intent=$intent${if (sender.isNotBlank()) " sender=$sender" else ""} reactionMs=$pickMs")
                choices.record(System.currentTimeMillis(), screen, code, intent, sender, context, pickMs)
            }
        },
        timers = object : Timers {
            override fun set(key: String, delayMs: Long, task: () -> Unit) {
                clear(key)
                val r = Runnable { modeTimers.remove(key); task() }
                modeTimers[key] = r; handler.postDelayed(r, delayMs)
            }
            override fun clear(key: String) { modeTimers.remove(key)?.let { handler.removeCallbacks(it) } }
        },
        clock = { System.currentTimeMillis() },
        ranker = CountRanker { choices.intentCount(it) },   // HOOK: replace with a language-model ranker later
    )

    /** Dev tool: play the pair "left" then "right" as Write does, to hear which ear is which. */
    fun pairTest() { handler.post { narrator.pair("left", "right") } }

    fun setSwapEars(on: Boolean) { prefs().edit().putBoolean("swap_ears", on).apply() }

    fun saveSettings(s: LauncherSettings) { mode.settings = s; SettingsStore.save(prefs(), s); applyLanguage(s) }

    /** The menu is English; it changes only when the owner picks another language. */
    private fun applyLanguage(s: LauncherSettings) = com.faka.invisible.voicekit.MenuLanguage.apply(s.menuLanguage)

    private fun prefs() = app.getSharedPreferences("invisible", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Programs.install(writeServices, roomsPort, app, object : com.faka.invisible.whereami.WhereServices { override fun navigation() = ControllerNotificationListener.navigation })
        Programs.all.forEach { it.warm() }   // some programs build their sounds in seconds on a phone: do it now, quietly
        ControllerNotificationListener.loadMutes(app)
        TreeLeaves.store = prefs()
        mode.settings = SettingsStore.load(prefs())
        applyLanguage(mode.settings)
        val f = java.io.File(app.filesDir, "choices.log")
        choices = ChoiceLog(if (f.exists()) f.readLines() else emptyList(), { line -> try { f.appendText(line + "\n") } catch (e: Exception) { Log.e(TAG, "choices.log write failed", e) } },
            { keep -> try { f.writeText(keep.joinToString("") { it + "\n" }) } catch (e: Exception) { Log.e(TAG, "choices.log rewrite failed", e) } })
        val tf = java.io.File(app.filesDir, "tasks.txt")
        val mf = java.io.File(app.filesDir, "task_memory.txt")
        val rf = java.io.File(app.filesDir, "reply_memory.txt")
        replyMemory = PhraseMemory(if (rf.exists()) rf.readLines() else emptyList()) { lines -> try { rf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "reply_memory.txt write failed", e) } }
        taskMemory = PhraseMemory(if (mf.exists()) mf.readLines() else emptyList()) { lines -> try { mf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "task_memory.txt write failed", e) } }
        taskStore = TaskStore(if (tf.exists()) tf.readLines() else emptyList(), onAdd = { taskMemory.record(it.text, it.lang) }) { lines -> try { tf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "tasks.txt write failed", e) } }
        VoiceKit.init(app, handler, { speechRate() }, object : SpeechListener {
            override fun started() { mode.onSpeechStarted(); Log.d("E2E", "speech started") }
            override fun ended() { mode.onSpeechDone(); running?.takeIf { it.active }?.speechDone(); Log.d("E2E", "speech ended") }
        }, buttonTarget, ComponentName(app, ControllerNotificationListener::class.java))
        VoiceKit.narrator.swapEars = { prefs().getBoolean("swap_ears", true) }
        rooms.restart()
    }

    /** The program of the registry that runs now (at most one). */
    private var running: Program? = null

    /** What Write asks of the OS (the program never reaches into it). */
    private val writeServices = object : WriteServices {
        override fun focusFrame() = ButtonAccessibilityService.inst?.frame() ?: Frame()
        override fun setFieldText(text: String) = ButtonAccessibilityService.inst?.setFieldText(text) ?: false
        override fun aiEnabled() = mode.settings.ai && LocalAi.available(app)
        override fun nextWords(text: String, brief: String) = LocalAi.nextWords(app, text, "en", brief)
        override fun continuations(text: String, brief: String) = LocalAi.continuations(app, text, "en", brief)
        override fun detectLanguage(text: String) = MlKitLangDetector().detect(text)
    }

    /** Write into a place that is not the focused field: [sendTo] gets the text (a notification reply, a music search). */
    private fun launchWrite(sendTo: (String) -> Boolean, replyTo: Notif?, backTo: (() -> Unit)? = null) {
        val frame = if (replyTo == null) Frame() else Frame.reply(replyTo.pkg, replyTo.title, replyTo.text, replyTo.hidden)
        var sent = false
        val p = WriteProgram(writeServices, { t -> sent = true; sendTo(t) }, frame)
        if (backTo == null) { startProgram(p); return }
        // Left with nothing written: go back to the list it came from ("Nothing written" is said first), reading that item.
        startProgram(p)
        backAfterEnd = { if (sent) false else { handler.postDelayed({ backTo() }, BACK_DELAY_MS); true } }
    }
    private var backAfterEnd: (() -> Boolean)? = null

    /** One Write per cue in [cues] (the cue is said first). Nothing written in the first = back to the Laptop tool. After the last, [finish] runs off the main thread and its phrase is said. */
    private fun writeChain(cues: List<Phrase>, got: List<String>, finish: (List<String>) -> Phrase) {
        val cue = cues[got.size]
        say(cue, false)
        handler.postDelayed({ writeStep(cues, got, finish) }, 600L + 250L * cue.text.split(' ').size)
    }
    private fun writeStep(cues: List<Phrase>, got: List<String>, finish: (List<String>) -> Phrase) {
        val count = cues.size
        var text = ""
        val p = WriteProgram(writeServices, { t -> text = t; true }, Frame())
        startProgram(p)
        backAfterEnd = {
            val all = got + text
            when {
                got.isEmpty() && text.isEmpty() -> { handler.postDelayed({ Programs.byId("hub:claude")?.let { startProgramEntry(it) } }, BACK_DELAY_MS); true }
                all.size < count -> { handler.postDelayed({ writeChain(cues, all, finish) }, BACK_DELAY_MS); true }
                else -> { val w = narrator.waiting(); Thread({ val said = try { finish(all) } finally { handler.post { w() } }; handler.post { say(said, true) } }, "rooms-finish").start(); false }
            }
        }
    }

    /** Dev tool (AiTestReceiver): start Write into the field that has focus, as the hub item does. */
    /** Dev: connect to the music app's browser service and log its tree two levels deep. */
    fun browseMusicDev() = handler.post {
        val pkg = musicApps.first { app.packageManager.getLaunchIntentForPackage(it) != null }
        lateinit var b: android.media.browse.MediaBrowser
        fun dump(id: String, depth: Int) {
            if (depth > 2) return
            b.subscribe(id, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, kids: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                    for (k in kids) { Log.d("BROWSE", "$depth $parentId > ${k.mediaId} | ${k.description.title} | browsable=${k.isBrowsable} playable=${k.isPlayable}"); if (k.isBrowsable) dump(k.mediaId!!, depth + 1) }
                }
                override fun onError(parentId: String) { Log.d("BROWSE", "error $parentId") }
            })
        }
        b = android.media.browse.MediaBrowser(app, android.content.ComponentName(pkg, "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"), object : android.media.browse.MediaBrowser.ConnectionCallback() {
            override fun onConnected() { Log.d("BROWSE", "connected root=${b.root}"); dump(b.root, 0) }
            override fun onConnectionFailed() { Log.d("BROWSE", "connection failed") }
        }, null)
        b.connect()
    }

    /** The app screen was tapped: item [i] named [text] is chosen, the same as a click on it; the voice goes on from there. */
    fun tapItem(i: Int, text: String) = handler.post { val p = running; if (p != null) p.tap(i) else if (mode.pickAt(i, text)) EventLog.pressed(com.faka.invisible.voicekit.Gesture.SINGLE) }
    /** An on-screen button does what the earbud button does (Back = 3 clicks, Close = click then hold, Wake = hold). */
    fun screenPress(g: com.faka.invisible.voicekit.Gesture) = handler.post { buttonTarget.gesture(g, 0) }

    /** Dev: press the button without the earbud (adb cannot inject key events on this phone): SINGLE, DOUBLE, TRIPLE, HOLD, CLICK_HOLD... */
    fun gestureDev(name: String) = handler.post { buttonTarget.gesture(Gesture.valueOf(name.uppercase()), 0) }
    /** Dev: "120,150,900" = press 120 ms, wait 150 ms, press 900 ms; fed to the real button input (programs, holds, gestures). */
    fun pressDev(ms: String) = handler.post {
        var t = android.os.SystemClock.uptimeMillis(); var down = -1L
        ms.split(",").map { it.trim().toLong() }.forEachIndexed { i, d ->
            val at = t
            if (i % 2 == 0) handler.postAtTime({ down = at; VoiceKit.buttons.key("dev", true, 0, at, at) }, at)
            t += d
            val end = t
            if (i % 2 == 0) handler.postAtTime({ VoiceKit.buttons.key("dev", false, 0, down, end) }, end)
        }
    }
    /** The first-run practice (runs by itself the first time the OS is woken; the Help page replays it). */
    fun startPractice() {
        handler.post {
            startProgramEntry(PracticeEntry.entry)
            // When the practice ends, go straight to the home menu (no second hold needed).
            backAfterEnd = { gameEnded(); handler.postDelayed({ if (running == null && !mode.active) mode.onGesture(Gesture.HOLD, 0) }, 1500); true }
        }
    }
    /** Dev (e2e): back to a known state: no program, menu closed, nothing spoken, practice marked done (or not), fake notifications gone. */
    fun resetDev(practiceDone: Boolean) = handler.post {
        backAfterEnd = null
        running?.stop(); running = null
        mode.exit(false); com.faka.invisible.voicekit.ScreenMirror.clear(); narrator.stopSpeech()
        prefs().edit().putBoolean(PracticeProgram.DONE_KEY, practiceDone).apply()
        Log.d("E2E", "reset done")
    }
    /** Dev (e2e): swap the speech engine for the instant stand-in (debug builds only; the class exists only there). */
    fun voiceDev(instant: Boolean) = handler.post {
        val sw = com.faka.invisible.voicekit.VoiceKit.speechSwitch
        sw.alt = if (instant) Class.forName("com.faka.invisible.InstantSpeech").getConstructors()[0].newInstance(sw.words, { speechRate() }, com.faka.invisible.voicekit.VoiceKit.speechEvents) as com.faka.invisible.voicekit.SpeechPort else null
        Log.d("E2E", "voice ${if (instant) "instant" else "real"}")
    }
    fun paceDev(f: Float) = handler.post { mode.devPace = f; rooms.devBatchScale = f; Log.d("E2E", "pace $f") }
    fun showDev(name: String, at: Int = -1) = handler.post { mode.devShow(name, at); Log.d("E2E", "show $name") }
    fun stateDev() = handler.post { Log.d("E2E", "state ${mode.devState()} running=${running != null} rate=${mode.settings.speechRate} mirror=${com.faka.invisible.voicekit.ScreenMirror.active}:${com.faka.invisible.voicekit.ScreenMirror.items.size}:${com.faka.invisible.voicekit.ScreenMirror.lastPress?.replace(" ", "_")}") }
    fun rateDev(r: Float) = handler.post { saveSettings(mode.settings.copy(speechRate = r)); Log.d("E2E", "rate set $r") }
    fun startDev(name: String) = handler.post { Programs.byDevName(name)?.let { startProgramEntry(it) } }

    /** Dev: drive the music session directly: "uri:<url>" or "search:<query>". */
    fun waitDev(ms: Long) = handler.post { val w = narrator.waiting(); handler.postDelayed({ w() }, ms) }
    fun ctlDev(cmd: String) = handler.post {
        val c = otherSessions().firstOrNull { it.packageName in musicApps } ?: run { Log.d("BROWSE", "no music session"); return@post }
        Log.d("BROWSE", "ctl $cmd on ${c.packageName}")
        if (cmd.startsWith("uri:")) c.transportControls.playFromUri(android.net.Uri.parse(cmd.removePrefix("uri:")), null)
        else if (cmd.startsWith("searchx:")) { val (f, q) = cmd.removePrefix("searchx:").split("|", limit = 2); c.transportControls.playFromSearch(q, android.os.Bundle().apply { putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, f); if (f.endsWith("audio")) putString(android.provider.MediaStore.EXTRA_MEDIA_TITLE, q) }) }
        else c.transportControls.playFromSearch(cmd.removePrefix("search:"), null)
        handler.postDelayed({ Log.d("BROWSE", "queue: " + (c.queue ?: emptyList()).joinToString(" / ") { it.description.title.toString() }) }, 5000)
    }

    fun toggleDev(kind: String) = handler.post { toggleNow(kind) { Log.d("BROWSE", "toggle -> $it") } }
    fun radioDev() = handler.post { radioNow() }
    fun searchDev(q: String) { handler.post { searchAndPlay(q) } }

    fun playMusicDev() { handler.post { playQuickPicks() } }
    fun startWriteDev() { startDev("write") }

    /** The OS rule for every game: when a game gives the phone back, the OS says goodbye with its own close sound. A game never plays it. */
    private fun gameEnded() { com.faka.invisible.voicekit.ScreenMirror.clear(); handler.post { narrator.beep(Tone.EXIT) } }

    /** Starts a program of the registry. The host is what it may ask of the OS; when it ends, the OS plays the close sound. */
    private fun startProgramEntry(e: ProgramEntry) { com.faka.invisible.voicekit.ScreenMirror.program(e.title.text); startProgram(e.create()) }

    /** A program that draws no picture of its own: the app screen shows the last lines it said, so a sighted person sees what is going on. */
    private var ownScreen = false
    private val spoken = ArrayList<String>()
    private fun mirrorSaid(t: String) {
        if (ownScreen || running == null || t.isBlank()) return
        spoken.add(t); while (spoken.size > 5) spoken.removeAt(0)
        com.faka.invisible.voicekit.ScreenMirror.show(spoken.toList(), spoken.size - 1)
    }

    private fun startProgram(p: Program) {
        running?.stop()
        running = p
        ownScreen = false; spoken.clear()
        com.faka.invisible.oskit.Trace.ev("program", "start ${p.javaClass.simpleName}")
        p.start(object : ProgramHost {
            override val context get() = app
            override val handler get() = this@Actions.handler
            override val prefs get() = prefs()
            override fun waiting() = narrator.waiting()
            override fun speak(text: Phrase, slow: Boolean) { mirrorSaid(text.text); say(text, false, slow = slow) }
            override fun screen(rows: List<String>, mark: Int) { ownScreen = true; com.faka.invisible.voicekit.ScreenMirror.show(rows, mark) }
            override fun stopSpeech() = narrator.stopSpeech()
            override fun pauseMusic() { pauseMusicNow() }
            override fun resumeMusic() = resumeMusicNow()
            override fun log(line: String) { Log.d(TAG, line); EventLog.add(line) }
            override fun outputDevice() = outputDeviceKey()
            override fun ended() { com.faka.invisible.oskit.Trace.ev("program", "end ${p.javaClass.simpleName}"); if (running === p) running = null; com.faka.invisible.voicekit.ScreenMirror.clear(); val back = backAfterEnd; backAfterEnd = null; if (back == null || !back()) gameEnded() }
        })
    }

    /** Which sound output is in use (earbuds, wired, phone speaker): the click delay is saved per output. */
    private fun outputDeviceKey(): String {
        val am = app.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wanted = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val d = wanted.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
        return if (d == null) "speaker" else "${d.type}:${d.productName}"
    }

    /** What the button feeds: the programs that own the raw button, the menu, and the menu's own tones. */
    private val buttonTarget = object : ButtonTarget {
        override fun raw(): RawButton? = running?.takeIf { it.active }   // the running program owns the raw button
        override val menuActive get() = mode.active
        override fun exitProgram() {   // each stop() ends with the program's onEnded, which plays the OS close sound. Write is not here: its holds pick words; it leaves from its menu
            running?.takeIf { it.active && it.exitOnClickHold }?.let { it.stop(); return }
        }
        override fun gesture(g: Gesture, durationMs: Long) {
            EventLog.add("${g.name.lowercase()}  ${durationMs}ms")
            EventLog.pressed(g)
            if ((g == Gesture.HOLD || g == Gesture.CLICK_HOLD) && !mode.active && running == null && !prefs().getBoolean(PracticeProgram.DONE_KEY, false)) { startPractice(); return }   // the first wake: the practice comes first, once
            val inMode = mode.active && mode.screen != ControllerMode.Screen.SENT   // a click that cancels a send has its own tone
            val before = mode.tonesPlayed
            val scan = inMode && mode.scanning()   // while options are read: 1 click = choose (pick tone), 2 clicks = previous item (step-down tone)
            mode.onGesture(g, durationMs)
            if (inMode && mode.tonesPlayed == before) when (g) { Gesture.SINGLE -> narrator.beep(if (mode.consumeBackPick()) Tone.BACK else Tone.PICK); Gesture.DOUBLE -> narrator.beep(if (scan) Tone.STEP_BACK else Tone.AGAIN); Gesture.TRIPLE -> narrator.beep(Tone.BACK); else -> {} }
        }
    }

    /** Long press launched the assistant role / hands-free. */
    fun assistHold() = VoiceKit.buttons.assistHold()

    /** The emergency contact's phone number (empty = none); set on the app's home screen. */
    fun emergencyContact(): String = prefs().getString("emergency_contact", "").orEmpty()
    fun setEmergencyContact(n: String) { prefs().edit().putString("emergency_contact", Calls.digits(n)).apply() }

    /** A call rings: open the Answer / Reject list. */
    fun callRinging() { if (::app.isInitialized) handler.post { mode.callIncoming() } }
    /** The call was answered elsewhere (on the phone itself). */
    fun callAnswered() { if (::app.isInitialized) handler.post { mode.callOngoing() } }
    /** No call is left. */
    fun callOver() { if (::app.isInitialized) handler.post { mode.callEnded() } }

    /** The computers (each runs its own bridge), set on the app's Computers page; one watcher listens to the active one while the OS is idle. */
    fun computers(): List<Computer> = Computers.load(prefs())
    /** The one computer the phone listens to (prefs `claude_active`; the first if unset or gone). */
    fun activeComputer(): Computer? = Computers.active(computers(), prefs().getString(Computers.ACTIVE_KEY, null))
    fun setComputers(list: List<Computer>) {
        val before = computers(); val active = prefs().getString(Computers.ACTIVE_KEY, null)
        Computers.save(prefs(), list)
        val keep = Computers.activeAfter(before, list, active)
        prefs().edit().apply { if (keep == null) remove(Computers.ACTIVE_KEY) else putString(Computers.ACTIVE_KEY, keep) }.apply()
        rooms.restart()
    }
    /** Listen to this computer only: save it, move the watcher. */
    fun setActive(name: String) {
        val c = Computers.find(computers(), name) ?: return
        prefs().edit().putString(Computers.ACTIVE_KEY, c.name).apply()
        rooms.restart()
    }
    /** Dev hook: one address replaces the whole list (a blank address clears it). */
    fun setClaudeBridge(url: String, token: String) = setComputers(Computers.single(url, token))
    private val rooms by lazy {
        RoomEvents(handler, { activeComputer() }, { running != null }, { RoomMute.muted(prefs().getLong(RoomMute.KEY, 0L), System.currentTimeMillis()) },
            { narrator.beep(Tone.CLAUDE) }, { say(it, true) }, { e -> handler.post { claudeAsked(e) } })
    }
    private fun roomsWaitingNow(): Int = rooms.waitingNow()
    /** What the Laptop tool asks of the OS: the Write keyboard, and the way back to the tool when nothing was written. */
    val roomsPort = object : RoomsPort {
        override fun computers() = this@Actions.computers()
        override fun active() = activeComputer()
        override fun switchTo(name: String) = setActive(name)
        override fun testDone() { handler.post { say(Voice.roomDone("Test"), false) } }
        override fun write(sendTo: (String) -> Boolean) {
            handler.post { launchWrite(sendTo, null) { Programs.byId("hub:claude")?.let { startProgramEntry(it) } } }
        }
        override fun writeSeq(cues: List<Phrase>, finish: (List<String>) -> Phrase) {
            handler.post { writeChain(cues, emptyList(), finish) }
        }
    }
    /** A question came while no program was running: tell the user with the Claude sound; if the menu is not in use, open the tool so the next click answers. */
    private fun claudeAsked(e: ClaudeEvent) {
        if (running != null) return
        narrator.beep(Tone.CLAUDE)
        if (!mode.active) Programs.byId("hub:claude")?.let { startProgramEntry(it) }
    }

    private val arrivalBurst = ArrivalBurst<Arriving>()
    private class Arriving(val pkg: String, val sender: String, val text: String, val lang: String, val hidden: Boolean)

    /**
     * New chat message arrived: the tone (once per burst), then, 1.5 s later, "<App>, <Sender>" and the latest text of that chat
     * in its own language. A private message says only "<App>, new message". Not during a call or in a program (tone only).
     * An open menu is not cut: the reading is queued after the current phrase.
     */
    fun arrival(pkg: String, sender: String, text: String, lang: String, visibility: Int) {
        if (!::app.isInitialized) return
        val locked = app.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked ?: true
        val hidden = NotifPrivacy.mustHide(pkg, visibility, locked, false)
        handler.post {
            val key = "$pkg|$sender"
            if (arrivalBurst.add(key, Arriving(pkg, sender, text, lang, hidden), System.currentTimeMillis())) {
                narrator.beep(if (sender in choices.favourites()) Tone.ARRIVE_FAV else Tone.ARRIVE)
                handler.postDelayed({ arrivalBurst.take(key)?.let { readArrival(it) } }, arrivalBurst.windowMs)
            }
        }
    }

    private fun readArrival(a: Arriving) {
        if (!ArrivalPlan.mayRead(ControllerNotificationListener.inCall, running != null)) { Log.d(TAG, "ARRIVAL not read (call or program)"); com.faka.invisible.oskit.Trace.ev("arrival", "not read pkg=${a.pkg} call=${ControllerNotificationListener.inCall} program=${running != null}"); return }
        val label = appName(a.pkg)
        val lines = ArrivalPlan.lines(label, a.sender, a.text, a.lang, a.hidden)
        val duck = !mode.active && pauseMusicNow()   // an open menu already handles the music
        com.faka.invisible.oskit.Trace.ev("arrival", "read pkg=${a.pkg} private=${a.hidden} menuOpen=${mode.active} duck=$duck lines=${lines.size}")
        lines.forEach { say(it, true) }
        if (duck) {
            fun waitEnd(n: Int) { handler.postDelayed({ if (narrator.speaking && n < 200) waitEnd(n + 1) else resumeMusicNow() }, 400) }
            waitEnd(0)
        }
    }

    /** A screen of the app speaks with the app's own voice (the setup steps, the lost-access warning). */
    fun announce(p: Phrase, queue: Boolean) { handler.post { say(p, queue) } }

    private fun say(p: Phrase, queue: Boolean, fast: Boolean = false, slow: Boolean = false) { Log.d("SAY", p.text); narrator.say(p, queue, fast, slow) }   // SAY log: lets a dev check what the menu speaks

    private fun adjustVolume(up: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    // Timer: try the clock app (no UI). If Android refuses, run our own timer that rings with tone + voice.
    private var ownTimer: Runnable? = null
    private var ringing: Runnable? = null

    private fun startTimerNow(minutes: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Invisible")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android blocks starting activities from the background (screen off), silently. So only try the clock app
        // when our app is visible; otherwise use our own timer.
        val ok = if (!appVisible()) { Log.d(TAG, "TIMER background start blocked, own timer"); false } else try { app.startActivity(intent); true } catch (e: Exception) { Log.d(TAG, "TIMER clock app failed: $e"); false }
        Log.d(TAG, "TIMER $minutes min clockApp=$ok")
        if (ok) { say(Voice.timerSet(minutes), false); return }
        ownTimer?.let { handler.removeCallbacks(it) }
        val r = Runnable { ownTimer = null; ring(0) }
        ownTimer = r
        handler.postDelayed(r, minutes * 60_000L)
        say(Voice.timerSet(minutes), false)
    }

    /** A wake-up alarm at the next [hour]:[minute]. The system alarm keeps it and rings it in the clock app; when Android blocks the clock app (screen off) the alarm manager wakes [AlarmReceiver], which rings with tone and voice. */
    private fun setAlarmNow(hour: Int, minute: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Invisible").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appVisible()) try { app.startActivity(intent); Log.d(TAG, "ALARM $hour:$minute clockApp"); return true } catch (e: Exception) { Log.d(TAG, "ALARM clock app failed: $e") }
        val at = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        val fire = android.app.PendingIntent.getBroadcast(app, 7, Intent(app, AlarmReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        return try { app.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.timeInMillis, fire), fire); Log.d(TAG, "ALARM $hour:$minute own"); true } catch (e: Exception) { Log.d(TAG, "ALARM own failed: $e"); false }
    }

    /** Called by [AlarmReceiver] when our own alarm fires. */
    fun alarmRang() { handler.post { ring(0, Voice.ALARM_RINGING) } }

    private fun appVisible(): Boolean {
        val p = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(p)
        return p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun ring(n: Int, line: Phrase = Voice.TIMER_DONE) {
        if (n >= 20) { ringing = null; return }
        narrator.beep(Tone.ALARM, minOf(1f, 0.5f + 0.15f * (n / 3)))   // louder every 10 s
        if (n % 3 == 0) say(line, true)
        val r = Runnable { ring(n + 1, line) }
        ringing = r
        handler.postDelayed(r, 3000)
    }

    private fun stopAlarmNow() {
        ownTimer?.let { handler.removeCallbacks(it) }; ownTimer = null
        ringing?.let { handler.removeCallbacks(it) }; ringing = null
        if (appVisible()) try {
            app.startActivity(Intent(AlarmClock.ACTION_DISMISS_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.d(TAG, "DISMISS timer failed: $e") }
        if (appVisible()) try {
            app.startActivity(Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.d(TAG, "DISMISS alarm failed: $e") }
        say(Voice.ALARM_STOPPED, false)
    }

    private fun menuAction(name: String) {
        when (name) {
            "Flashlight" -> say(Voice.flashlight(toggleTorch()), false)
            "Time" -> Calendar.getInstance().let { c -> say(Voice.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), false) }
            "Next alarm" -> {
                val t = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                say(if (t == null) Voice.NO_ALARM else Calendar.getInstance().apply { timeInMillis = t }.let { Voice.alarmAt(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }, false)
            }
            "Battery" -> {
                val b = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say(Voice.battery(b), false)
            }
            "Do not disturb" -> {
                val nm = app.getSystemService(NotificationManager::class.java)
                val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.d(TAG, "DND ${if (on) "on" else "off"}")
                say(Voice.doNotDisturb(on), false)
            }
        }
    }

    fun dndAllowed() = app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun toggleTorch(): Boolean {
        try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            Log.d(TAG, "TORCH ${if (torchOn) "on" else "off"}")
        } catch (e: Exception) { Log.e(TAG, "TORCH failed", e) }
        return torchOn
    }

    private var pausedPkgs: List<String> = emptyList()

    /** The live apps for the front of home: media sessions that play or were paused lately, and ongoing notifications that are not static. */
    private fun liveAppsNow(): List<LiveApp> {
        val now = System.currentTimeMillis(); val elapsed = android.os.SystemClock.elapsedRealtime()
        val media = otherSessions().mapNotNull { c ->
            val st = c.playbackState ?: return@mapNotNull null
            val playing = st.state == PlaybackState.STATE_PLAYING; val paused = st.state == PlaybackState.STATE_PAUSED
            if (!playing && !paused) return@mapNotNull null
            LiveCandidate(c.packageName, appName(c.packageName), now - (elapsed - st.lastPositionUpdateTime), mediaPlaying = playing, mediaPaused = paused)
        }
        val notes = ControllerNotificationListener.ongoingNow().map { o ->
            val n = o.notif
            LiveCandidate(n.pkg, n.app, n.at, notifKey = n.key, hasActions = n.actions.isNotEmpty(), chronometer = o.chronometer, category = o.category,
                mediaStyle = o.mediaStyle, line = listOf(n.text, n.title).firstOrNull { it.isNotBlank() }.orEmpty(), hidden = n.hidden)
        }
        return LiveApps.pick(media + notes, now, "", { ControllerNotificationListener.mutes.isMuted(it) })
    }

    private fun appName(pkg: String): String = try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg.substringAfterLast('.') }

    private fun otherSessions() = try {
        app.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(app, ControllerNotificationListener::class.java))
            .filter { it.packageName != app.packageName }
    } catch (e: SecurityException) { emptyList() }

    private const val LIKED_LINK = "https://music.youtube.com/watch?list=LM"
    private val musicApps = listOf("app.morphe.android.apps.youtube.music", "com.google.android.apps.youtube.music")

    /** Start music with no screen: if the music app has a session, ask it to play something (its own mix of your taste); else open it by link and ask once it has a session. */
    private fun playQuickPicks(liked: Boolean = false, link: String? = null) {
        fun ask(c: android.media.session.MediaController) = (link ?: if (liked) LIKED_LINK else null)?.let { c.transportControls.playFromUri(android.net.Uri.parse(it), null) } ?: c.transportControls.playFromSearch("", null)
        otherSessions().firstOrNull { it.packageName in musicApps }?.let { ask(it); return }
        val pkg = musicApps.firstOrNull { app.packageManager.getLaunchIntentForPackage(it) != null } ?: run { Log.d(TAG, "MODE no music app"); return }
        // no session: a media-play key (like the earbud) makes the system start the app's playback service, which the memory manager keeps alive; the link only opens an activity, which it kills with the screen off
        Log.d(TAG, "MODE music: no session, media key")
        val am = app.getSystemService(AudioManager::class.java)
        for (a in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) am.dispatchMediaKeyEvent(android.view.KeyEvent(a, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        var tries = 0
        val w = narrator.waiting()   // a cold start takes seconds: the heartbeat says it is on its way
        val poll = object : Runnable {
            override fun run() {
                val c = otherSessions().firstOrNull { it.packageName in musicApps }
                if (c != null) { Log.d(TAG, "MODE music: session found, play"); w(); ask(c); return }
                if (++tries in listOf(12, 44, 76)) { Log.d(TAG, "MODE music: no session, open by link ($tries)"); playLiked(pkg, link ?: LIKED_LINK) }   // the first open sometimes leaves the session asleep: try again
                if (tries < 110) handler.postDelayed(this, 500) else w()
            }
        }
        handler.postDelayed(poll, 1000)
    }

    private fun toggleNow(kind: String, then: (String) -> Unit) {
                val c = otherSessions().firstOrNull { it.packageName in musicApps } ?: return
                val word = if (kind == "shuffle") "Shuffle" else "Repeat"
                val id = c.playbackState?.customActions?.firstOrNull { it.name.startsWith(word) }?.action ?: return
                c.transportControls.sendCustomAction(id, null)
                val before = c.playbackState?.customActions?.firstOrNull { it.name.startsWith(word) }?.name?.toString()
        var tries = 0
        val wait = object : Runnable {   // the app renames the switch a moment after the press: wait for the new name (3 s at most)
            override fun run() {
                val now = c.playbackState?.customActions?.firstOrNull { it.name.startsWith(word) }?.name?.toString() ?: ""
                if (now != before || ++tries >= 10) then(now) else handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(wait, 300)
            }
    private fun radioNow() {
                val md = otherSessions().firstOrNull { it.packageName in musicApps }?.metadata ?: return
                val q = listOf(md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)).filter { !it.isNullOrBlank() }.joinToString(" ")
                val w = narrator.waiting()
                Thread({ val hit = lookupSong(q); handler.post { w(); if (hit == null) { say(Menu.NO_SONG, false); return@post }; playQuickPicks(false, "https://music.youtube.com/watch?v=${hit.videoId}&list=RDAMVM${hit.videoId}") } }, "music-radio").start()
            }

    private fun lookupSong(query: String): com.faka.invisible.logickit.MusicSearch.Hit? = try {
        val c = java.net.URL(com.faka.invisible.logickit.MusicSearch.URL).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Origin", "https://music.youtube.com")
        c.outputStream.use { it.write(com.faka.invisible.logickit.MusicSearch.body(query).toByteArray()) }
        com.faka.invisible.logickit.MusicSearch.first(c.inputStream.bufferedReader().use { it.readText() })
    } catch (e: Throwable) { Log.e(TAG, "music search failed", e); null }

    /** Look the typed text up on the web (off the main thread), say which song was found, play it. */
    private fun searchAndPlay(query: String) {
        val w = narrator.waiting()
        Thread({
            val hit = lookupSong(query)
            handler.post {
                w()
                if (hit == null) { say(Menu.NO_SONG, false); gameEnded(); return@post }
                Log.d(TAG, "MODE music search '$query' -> ${hit.title} / ${hit.artist}")
                say(Phrase.content(listOf(hit.title, hit.artist).filter { it.isNotBlank() }.joinToString(", "), ControllerNotificationListener.voiceFor(hit.title + " " + hit.artist)), false)
                playQuickPicks(false, "https://music.youtube.com/watch?v=${hit.videoId}")
            }
        }, "music-search").start()
    }

    private fun playLiked(pkg: String, link: String) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))   // reuse the window it already has: the retries must not stack copies
        } catch (e: Throwable) { Log.e(TAG, "play liked failed", e) }
    }

    /** Every media session, newest first, ours included; null = notification access is off (the system refuses). */
    private fun sessionsOrNull(): List<android.media.session.MediaController>? = try {
        app.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(app, ControllerNotificationListener::class.java))
    } catch (e: SecurityException) { null }

    private fun playersOf(cs: List<android.media.session.MediaController>) =
        cs.map { MediaReplay.Player(it.packageName, it.playbackState?.state ?: 0, it.packageName == app.packageName) }

    private fun mediaKey(code: Int) = listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).map { android.view.KeyEvent(it, code) }

    /** The OS opens: pause every player that is playing (not ours), remember which, and check a moment later that each one really stopped. */
    private fun pauseMusicNow(): Boolean {
        val cs = sessionsOrNull() ?: run { Trace.ev("music", "pause: NOTIFICATION ACCESS IS OFF, the player list cannot be read (turn it on in setup)"); return false }
        val playing = MediaReplay.playing(playersOf(cs))
        if (playing.isEmpty()) { Trace.ev("music", "pause: nothing playing (sessions: ${playersOf(cs).joinToString { it.pkg + ":" + it.state }})"); return false }
        val ctl = cs.filter { c -> playing.any { it.pkg == c.packageName } }
        ctl.forEach { it.transportControls.pause() }
        pausedPkgs = ctl.map { it.packageName }
        Trace.ev("music", "pause sent pkg=${pausedPkgs.joinToString()}")
        handler.postDelayed({
            if (pausedPkgs.isEmpty()) return@postDelayed   // already resumed: nothing to check
            ctl.forEach { c ->
                val st = c.playbackState?.state ?: 0
                if (MediaReplay.isPlaying(st)) {   // the player ignored pause(): send the real pause key to it
                    mediaKey(MediaReplay.KEY_PAUSE).forEach { c.dispatchMediaButtonEvent(it) }
                    Trace.ev("music", "pause ignored by ${c.packageName} (state=$st), sent the pause key")
                } else Trace.ev("music", "pause confirmed ${c.packageName} state=$st")
            }
        }, 600)
        return true
    }

    /** The OS closes: play again only what we paused, and only if nothing else started meanwhile. */
    private fun resumeMusicNow() {
        val pkgs = pausedPkgs; pausedPkgs = emptyList()
        if (pkgs.isEmpty()) return
        val cs = sessionsOrNull() ?: run { Trace.ev("music", "resume skipped: notification access is off"); return }
        val others = cs.filter { it.packageName != app.packageName }
        val intruder = others.firstOrNull { it.packageName !in pkgs && MediaReplay.isPlaying(it.playbackState?.state ?: 0) }
        if (intruder != null) { Trace.ev("music", "resume skipped: ${intruder.packageName} is playing"); return }
        pkgs.forEach { pkg ->
            val c = others.firstOrNull { it.packageName == pkg }
            when {
                c == null -> Trace.ev("music", "resume skipped: $pkg session gone")
                MediaReplay.isPlaying(c.playbackState?.state ?: 0) -> Trace.ev("music", "resume skipped: $pkg already playing")
                else -> { c.transportControls.play(); Trace.ev("music", "resume sent pkg=$pkg") }
            }
        }
    }

    /**
     * An earbud click count while the OS is closed: do what a headset does, to the player that really plays (or played last).
     * Never our own session. Without notification access (no player list) or with no session, the key goes straight to the music app.
     */
    /** Dev (e2e): replay clicks are only traced, never sent to the real player. */
    @Volatile var replayDry = false

    private fun forwardToMusic(what: Forward) {
        val a = when (what) { Forward.PLAY_PAUSE -> MediaReplay.Action.PLAY_PAUSE; Forward.NEXT -> MediaReplay.Action.NEXT; Forward.PREVIOUS -> MediaReplay.Action.PREVIOUS }
        val clicks = a.ordinal + 1
        val cs = sessionsOrNull()
        if (cs == null) { Trace.ev("media", "replay $clicks: NOTIFICATION ACCESS IS OFF, no player list"); keyToMusicApp(a, clicks); return }
        val pick = MediaReplay.choose(playersOf(cs))
        val c = pick?.let { p -> cs.firstOrNull { it.packageName == p.pkg } }
        if (pick == null || c == null) { Trace.ev("media", "replay $clicks: no other player session"); keyToMusicApp(a, clicks); return }
        val t = c.transportControls
        val cmd = MediaReplay.command(a, pick)
        if (!replayDry) when (cmd) { "pause" -> t.pause(); "play" -> t.play(); "next" -> t.skipToNext(); else -> t.skipToPrevious() }
        Trace.ev("media", "replay $clicks -> $cmd pkg=${pick.pkg} state=${pick.state}${if (replayDry) " (dry)" else ""}")
    }

    /** The fallback: send the headset key to the first music app that is installed (its own media button receiver wakes it). */
    private fun keyToMusicApp(a: MediaReplay.Action, clicks: Int) {
        val pkg = musicApps.firstOrNull { app.packageManager.getLaunchIntentForPackage(it) != null } ?: run { Trace.ev("media", "replay $clicks: no music app installed"); return }
        if (replayDry) { Trace.ev("media", "replay $clicks -> ${a.name.lowercase()} key to $pkg (dry)"); return }
        mediaKey(MediaReplay.keyFor(a)).forEach { app.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, it)) }
        Trace.ev("media", "replay $clicks -> ${a.name.lowercase()} key sent to $pkg")
    }
}
