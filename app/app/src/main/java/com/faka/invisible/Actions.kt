package com.faka.invisible

import com.faka.invisible.logickit.*

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.AlarmClock
import android.content.ComponentName
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.faka.invisible.tell.TellAndroid
import com.faka.invisible.voicekit.*
import com.faka.invisible.tell.TellHost
import java.util.Calendar
import java.util.Locale

/** Shared by ButtonService (media session path) and ButtonAccessibilityService (raw key path). */
object Actions {
    private const val TAG = "Invisible"
    /** Speech rate at the start (1.0 = normal). It rises 0.1 per 20 successful picks, up to 1.8: blind users listen fast. */
    const val SPEECH_RATE = 0.9f
    private fun speechRate() = (mode.settings.speechRate + 0.1f * (prefs().getInt("codeCount", 0) / 20)).coerceAtMost(1.8f)
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
            override fun songLang(text: String) = ControllerNotificationListener.voiceFor(text)
            override fun pauseMusic() = pauseMusicNow()
            override fun resumeMusic() = resumeMusicNow()
            override fun volume(up: Boolean) = adjustVolume(up)
            override fun volumeLevel() = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            override fun startTimer(minutes: Int) = startTimerNow(minutes)
            override fun stopAlarm() = stopAlarmNow()
            override fun timerRinging() = ringing != null
            override fun messages() = ControllerNotificationListener.store.all()
            override fun reply(key: String, text: String) = ControllerNotificationListener.sendReply(key, text, app)
            override fun notifGroups() = ControllerNotificationListener.notifs.groups { ControllerNotificationListener.modules.forNotif(it).groupKey(it) }
            override fun notifRead(n: Notif) = ControllerNotificationListener.modules.forNotif(n).read(n)
            override fun notifSummary(n: Notif) = ControllerNotificationListener.modules.forNotif(n).summary(n)
            override fun notifFire(key: String, index: Int) = ControllerNotificationListener.fireAction(key, index, null, app)
            override fun notifDismiss(key: String) = ControllerNotificationListener.dismiss(key)
            override fun notifMute(pkg: String) = ControllerNotificationListener.mute(pkg)
            override fun notifAppHint(pkg: String) = try { app.packageManager.getApplicationInfo(pkg, 0).category } catch (e: Exception) { -1 }
            override fun installedApps() = DeviceApps.installed(app)
            override fun appUse() = DeviceApps.use(app)
            override fun appMuted(pkg: String) = ControllerNotificationListener.mutes.isMuted(pkg)
            override fun openApp(pkg: String) = DeviceApps.open(app, pkg)
            override fun notifMutedCount() = ControllerNotificationListener.mutes.all().size
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
            override fun weather(done: (Phrase) -> Unit) = PhoneInfo.weather(app) { t -> handler.post { done(t) } }
            override fun nextEvent(done: (Phrase) -> Unit) = PhoneInfo.nextEvent(app) { t -> handler.post { done(t) } }
            override fun wiredConnected() = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            override fun navigation() = ControllerNotificationListener.navigation
            override fun findPhone(on: Boolean) = narrator.findLoop(on)
            override fun dndAllowed() = this@Actions.dndAllowed()
            override fun codeCount() = prefs().getInt("codeCount", 0)
            override fun setCodeCount(n: Int) { prefs().edit().putInt("codeCount", n).apply() }
            override fun lastTimer() = prefs().getInt("lastTimer", 0)
            override fun setLastTimer(minutes: Int) { prefs().edit().putInt("lastTimer", minutes).apply() }
            override fun startGame(coach: Boolean) = startDuel(coach)
            override fun startWrite() = launchWrite()
            override fun pickCount(intent: String) = choices.intentCount(intent)
            override fun favourites() = choices.favourites()
            override fun suggest(candidates: Collection<String>, now: Long) = Suggester.top(choices.history(), candidates, now, tags = context)
            override fun setContext(tags: Set<String>) { context = tags }
            override fun choice(screen: String, code: Int, intent: String, sender: String) {
                choices.record(System.currentTimeMillis(), screen, code, intent, sender, context)
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

    fun saveSettings(s: LauncherSettings) { mode.settings = s; SettingsStore.save(prefs(), s) }

    private fun prefs() = app.getSharedPreferences("invisible", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val bf = java.io.File(app.filesDir, "buckets.txt")
        buckets = BucketStore(if (bf.exists()) bf.readLines() else emptyList()) { lines -> try { bf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "buckets.txt write failed", e) } }
        Thread({ wordModel() }, "write-warm").start()
        com.faka.invisible.tell.TellSynth.warmAsync()   // the Duel's sounds take seconds to build on a phone: do it now, quietly
        ControllerNotificationListener.loadMutes(app)
        TreeLeaves.store = prefs()
        mode.settings = SettingsStore.load(prefs())
        val f = java.io.File(app.filesDir, "choices.log")
        choices = ChoiceLog(if (f.exists()) f.readLines() else emptyList()) { line -> try { f.appendText(line + "\n") } catch (e: Exception) { Log.e(TAG, "choices.log write failed", e) } }
        val tf = java.io.File(app.filesDir, "tasks.txt")
        val mf = java.io.File(app.filesDir, "task_memory.txt")
        val rf = java.io.File(app.filesDir, "reply_memory.txt")
        replyMemory = PhraseMemory(if (rf.exists()) rf.readLines() else emptyList()) { lines -> try { rf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "reply_memory.txt write failed", e) } }
        taskMemory = PhraseMemory(if (mf.exists()) mf.readLines() else emptyList()) { lines -> try { mf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "task_memory.txt write failed", e) } }
        taskStore = TaskStore(if (tf.exists()) tf.readLines() else emptyList(), onAdd = { taskMemory.record(it.text, it.lang) }) { lines -> try { tf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "tasks.txt write failed", e) } }
        VoiceKit.init(app, handler, { speechRate() }, object : SpeechListener {
            override fun started() { mode.onSpeechStarted() }
            override fun ended() { mode.onSpeechDone(); duel?.takeIf { it.active }?.speechDone() }
        }, buttonTarget, ComponentName(app, ControllerNotificationListener::class.java))
        VoiceKit.narrator.swapEars = { prefs().getBoolean("swap_ears", true) }
    }

    private var duel: TellAndroid? = null

    // ---- Write: the stereo-pair keyboard (Typing.kt). The OS gives it the raw button and plays the close sound when it ends. ----
    private var typer: TypeProgram? = null
    private var nextWord: Ranker? = null
    @Volatile private var chatLang: String? = null   // the language of the place being written into; null = not known
    @Volatile private var phraseReq = 0
    private lateinit var buckets: BucketStore
    private val phraseWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val typeTimers = HashMap<String, Runnable>()

    /** Loads the English word model once (a few seconds on the phone, so it is also started when the app starts). */
    @Synchronized private fun wordModel(): Ranker? = nextWord ?: try {
        val t0 = System.currentTimeMillis()
        val m: Ranker = NextWord(app.assets.open("lm/lm.bin"), app.assets.open("lm/vocab.txt").bufferedReader().readText())   // you write in English; the chat gets the translation
        Log.d(TAG, "WRITE model loaded in ${System.currentTimeMillis() - t0} ms")
        m.also { nextWord = it }
    } catch (e: Throwable) { Log.e(TAG, "word model failed", e); null }

    /** Dev tool (AiTestReceiver): start Write into the field that has focus, as the hub item does. */
    fun startWriteDev() { handler.post { launchWrite() } }

    /** [sendTo] = Write for a notification reply: the written text goes to that action instead of the focused field.
     *  [replyTo] = the message being answered: the screen is off and shows no chat, so the suggestions and the reply language come from it. */
    private fun launchWrite(sendTo: ((String) -> Boolean)? = null, replyTo: Notif? = null) {
        typer?.stop()
        Thread({
            val lm = wordModel()
            handler.post {
                if (lm == null) { say(TypeVoice.NO_MODEL, false); gameEnded(); return@post }
                                val timers = object : Timers {
                    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { typeTimers.remove(key); task() }; typeTimers[key] = r; handler.postDelayed(r, delayMs) }
                    override fun clear(key: String) { typeTimers.remove(key)?.let { handler.removeCallbacks(it) } }
                }
                val frame = if (sendTo != null) Frame.replyTo(replyTo) else ButtonAccessibilityService.inst?.frame() ?: Frame()   // the place that has focus: app, kind of field, what is on the screen
                Log.d(TAG, "WRITE frame app=${frame.app} kind=${frame.kind} field=${frame.field} thread=${frame.thread} around=${frame.around.size} before=${frame.before.length}")
                chatLang = null
                Thread({   // the language of the place, from what is on the screen and in the field; unknown means nothing to translate
                    val seen = (frame.around + frame.before).joinToString(" ")
                    val lang = ChatLang.of(seen) { MlKitLangDetector().detect(it) }
                    handler.post { chatLang = lang }
                    if (lang != null && ChatLang.needsTranslation(lang) && MlKitTranslator.code(lang) != null && frame.kind == FieldKind.TEXT && !MlKitTranslator.hasModel(lang)) MlKitTranslator.download(lang)
                    Log.d(TAG, "WRITE chat language $lang")
                }, "write-lang").start()
                val out = object : TypeOut {
                    override fun suggestions() = buckets.suggestions(frame)
                    override fun nextWords(text: String, then: (List<String>) -> Unit) {
                        if (!mode.settings.ai || !LocalAi.available(app)) return
                        phraseWorker.execute {
                            val r = try { LocalAi.nextWords(app, text, "en", frame.brief()) } catch (e: Throwable) { Log.e(TAG, "next words failed", e); emptyList() }
                            handler.post { then(r) }
                        }
                    }
                    override fun pair(left: String, right: String?) {
                        Log.d(TAG, "WRITE pair $left | $right")
                        narrator.pair(left, right)
                    }
                    override fun hush() { narrator.hush() }
                    override fun prefetch(words: List<String>) { narrator.prefetch(words) }
                    override fun say(text: Phrase) { narrator.say(text, true) }
                    override fun cue(c: TypeCue) { narrator.cue(c) }
                    override fun phrases(text: String, then: (List<String>) -> Unit) {
                        if (!mode.settings.ai || !LocalAi.available(app)) return
                        val my = ++phraseReq; val frameNow = frame
                        phraseWorker.execute {   // one at a time; a request for an older text is dropped before it starts
                            if (my != phraseReq) return@execute
                            val r = try { LocalAi.continuations(app, text, "en", frameNow.brief()) } catch (e: Throwable) { Log.e(TAG, "endings failed", e); emptyList() }
                            if (r.isNotEmpty()) handler.post { then(r) }
                        }
                    }
                    override fun written(text: String) { buckets.learn(frame, text.split(' ').filter { it.isNotEmpty() }, text) }
                    override fun translate(text: String, then: (Translation) -> Unit) {
                        val target = chatLang
                        if (frame.kind != FieldKind.TEXT || target == null || !ChatLang.needsTranslation(target)) { then(Translation.Same); return }
                        phraseWorker.execute {
                            val r: Translation = when {
                                MlKitTranslator.code(target) == null -> Translation.Failed(TypeVoice.TRANSLATION_FAILED)
                                !MlKitTranslator.hasModel(target) || !MlKitTranslator.hasModel("en") -> { MlKitTranslator.download(target); Translation.Failed(TypeVoice.TRANSLATION_MISSING) }
                                else -> MlKitTranslator.translate(text, target)?.let { Translation.Done(it) } ?: Translation.Failed(TypeVoice.TRANSLATION_FAILED)
                            }
                            Log.d(TAG, "WRITE translate to $target: ${r::class.simpleName}")
                            handler.post { then(r) }
                        }
                    }
                    override fun readBack(text: String) { this@Actions.say(Phrase.content(text, chatLang), false) }
                    override fun finished(text: String) {
                        Log.d(TAG, "WRITE result [${frame.app} ${frame.kind}]: ${if (frame.kind == FieldKind.PASSWORD) "-" else text}"); EventLog.add("write: ${frame.app} ${text.length} chars")
                        if (text.isNotEmpty()) {
                            if (sendTo != null) { if (!sendTo(text)) Log.d(TAG, "WRITE reply was not sent") }
                            else if (frame.hasField) { val all = if (frame.before.isBlank()) text else frame.before.trimEnd() + (if (frame.digits) "" else " ") + text; if (!(ButtonAccessibilityService.inst?.setFieldText(all) ?: false)) Log.d(TAG, "WRITE could not set the field") }
                        }
                        handler.postDelayed({ if (text.isEmpty()) this@Actions.say(TypeVoice.EMPTY, false) else this@Actions.say(Phrase.content(text), false) }, 700)
                    }
                }
                typer = TypeProgram(BucketRanker(lm, buckets, frame), out, timers, frame) { typer = null; narrator.hush(); gameEnded() }.also { it.start() }
            }
        }, "write-load").start()
    }

    /** The OS rule for every game: when a game gives the phone back, the OS says goodbye with its own close sound. A game never plays it. */
    private fun gameEnded() { handler.post { narrator.beep(Tone.EXIT) } }

    private fun startDuel(coach: Boolean) {
        duel?.stop()
        duel = TellAndroid(prefs(), handler, object : TellHost {
            override fun speak(text: Phrase, slow: Boolean) = say(text, false, slow = slow)
            override fun stopSpeech() = narrator.stopSpeech()
            override fun pauseMusic() { pauseMusicNow() }
            override fun resumeMusic() = resumeMusicNow()
            override fun log(line: String) { Log.d(TAG, line); EventLog.add(line) }
        }, onEnded = { duel = null; gameEnded() }, coach = coach).also { it.start() }
    }

    /** What the button feeds: the programs that own the raw button, the menu, and the menu's own tones. */
    private val buttonTarget = object : ButtonTarget {
        override fun raw(): RawButton? = duel?.takeIf { it.active } ?: typer?.takeIf { it.active }   // the Duel and Write own the raw button
        override val menuActive get() = mode.active
        override fun gesture(g: Gesture, durationMs: Long) {
            EventLog.add("${g.name.lowercase()}  ${durationMs}ms")
            val inMode = mode.active && mode.screen != ControllerMode.Screen.SENT   // a click that cancels a send has its own tone
            val before = mode.tonesPlayed
            val scan = inMode && mode.scanning()   // while options are read: 1 click = choose (pick tone), 2 clicks = next (step tone)
            mode.onGesture(g, durationMs)
            if (inMode && mode.tonesPlayed == before) when (g) { Gesture.SINGLE -> narrator.beep(if (mode.consumeBackPick()) Tone.BACK else Tone.PICK); Gesture.DOUBLE -> narrator.beep(Tone.AGAIN); Gesture.TRIPLE -> narrator.beep(Tone.BACK); else -> {} }
        }
    }

    /** Long press launched the assistant role / hands-free. */
    fun assistHold() = VoiceKit.buttons.assistHold()

    /** New chat message arrived: short earcon, no speech. Favourite senders get a different tone. */
    fun arrival(sender: String) {
        if (!::app.isInitialized) return
        handler.post { narrator.beep(if (sender in choices.favourites()) Tone.ARRIVE_FAV else Tone.ARRIVE) }
    }

    private fun say(p: Phrase, queue: Boolean, fast: Boolean = false, slow: Boolean = false) = narrator.say(p, queue, fast, slow)

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

    private fun appVisible(): Boolean {
        val p = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(p)
        return p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun ring(n: Int) {
        if (n >= 20) { ringing = null; return }
        narrator.beep(Tone.ALARM, minOf(1f, 0.5f + 0.15f * (n / 3)))   // louder every 10 s
        if (n % 3 == 0) say(Voice.TIMER_DONE, true)
        val r = Runnable { ring(n + 1) }
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

    private var pausedPkg: String? = null

    private fun otherSessions() = try {
        app.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(app, ControllerNotificationListener::class.java))
            .filter { it.packageName != app.packageName }
    } catch (e: SecurityException) { emptyList() }

    private fun pauseMusicNow(): Boolean {
        val c = otherSessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (c == null) { Log.d(TAG, "MODE no music to pause"); return false }
        c.transportControls.pause()
        pausedPkg = c.packageName
        Log.d(TAG, "MODE pause music ${c.packageName}")
        return true
    }

    private fun resumeMusicNow() {
        val pkg = pausedPkg ?: return
        pausedPkg = null
        val all = otherSessions()
        val other = all.firstOrNull { it.packageName != pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (other != null) { Log.d(TAG, "MODE resume skipped, ${other.packageName} is playing"); return }
        val c = all.firstOrNull { it.packageName == pkg }
        if (c == null) { Log.d(TAG, "MODE resume skipped, $pkg session gone"); return }
        c.transportControls.play()
        Log.d(TAG, "MODE resume music $pkg")
    }

    private fun forwardToMusic(what: Forward) {
        try {
            val msm = app.getSystemService(MediaSessionManager::class.java)
            val c = msm.getActiveSessions(ComponentName(app, ControllerNotificationListener::class.java))
                .firstOrNull { it.packageName != app.packageName }
            if (c == null) { Log.d(TAG, "FORWARD none (no other media session)"); return }
            val t = c.transportControls
            when (what) {
                Forward.PLAY_PAUSE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                Forward.NEXT -> t.skipToNext()
                Forward.PREVIOUS -> t.skipToPrevious()
            }
            Log.d(TAG, "FORWARD ${what.name.lowercase()} to ${c.packageName}")
        } catch (e: SecurityException) {
            Log.d(TAG, "FORWARD none (notification access is OFF)")
        }
    }
}
