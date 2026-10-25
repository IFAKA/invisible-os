package com.faka.invisible

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.AlarmClock
import android.content.ComponentName
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.faka.invisible.tell.TellAndroid
import com.faka.invisible.tell.TellHost
import java.util.Calendar
import java.util.Locale

/** Shared by ButtonService (media session path) and ButtonAccessibilityService (raw key path). */
object Actions {
    private const val TAG = "Invisible"
    /** Speech rate at the start (1.0 = normal). It rises 0.1 per 20 successful picks, up to 1.8: blind users listen fast. */
    const val SPEECH_RATE = 0.9f
    private val COACH_RATE = 0.75f
    private fun speechRate() = (mode.settings.speechRate + 0.1f * (prefs().getInt("codeCount", 0) / 20)).coerceAtMost(1.8f)
    @Volatile var accessibilityConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
    private val deduper = Deduper()
    private var lastHoldAt = 0L

    private var gestureTimer: Runnable? = null
    private val detector = GestureDetector(
        schedule = { delay, task -> val r = Runnable(task); gestureTimer = r; handler.postDelayed(r, delay) },
        cancel = { gestureTimer?.let { handler.removeCallbacks(it) }; gestureTimer = null },
        onGesture = { g, dur -> fire(g, dur) },
    )

    private val modeTimers = HashMap<String, Runnable>()
    var choices = ChoiceLog()
    private var context: Set<String> = emptySet()   // what the OS saw when the hub was last built (see Suggester.TAGS)
        private set
    val mode: ControllerMode = ControllerMode(
        out = object : ModeOutput {
            override fun speak(text: String, queue: Boolean, lang: String?, fast: Boolean) = say(text, queue, lang, fast)
            override fun stopSpeech() { tts?.stop(); spTrack?.let { try { it.stop() } catch (e: Exception) {} }; spTrack = null; lastUtt = "" }
            override fun tone(kind: Tone) = beep(kind)
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
            override fun status(): Status {
                val c = Calendar.getInstance()
                val b = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val t = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                val a = t?.let { Calendar.getInstance().apply { timeInMillis = it } }
                return Status(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), b, a?.get(Calendar.HOUR_OF_DAY), a?.get(Calendar.MINUTE))
            }
            override fun weather(done: (String) -> Unit) = PhoneInfo.weather(app) { t -> handler.post { done(t) } }
            override fun nextEvent(done: (String) -> Unit) = PhoneInfo.nextEvent(app) { t -> handler.post { done(t) } }
            override fun wiredConnected() = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            override fun navigation() = ControllerNotificationListener.navigation
            override fun findPhone(on: Boolean) = ringFind(on)
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

    fun saveSettings(s: LauncherSettings) { mode.settings = s; SettingsStore.save(prefs(), s) }

    private fun prefs() = app.getSharedPreferences("invisible", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val bf = java.io.File(app.filesDir, "buckets.txt")
        buckets = BucketStore(if (bf.exists()) bf.readLines() else emptyList()) { lines -> try { bf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "buckets.txt write failed", e) } }
        Thread({ wordModel() }, "write-warm").start()
        com.faka.invisible.tell.TellSynth.warmAsync()   // the Duel's sounds take seconds to build on a phone: do it now, quietly
        mode.settings = SettingsStore.load(prefs())
        val f = java.io.File(app.filesDir, "choices.log")
        choices = ChoiceLog(if (f.exists()) f.readLines() else emptyList()) { line -> try { f.appendText(line + "\n") } catch (e: Exception) { Log.e(TAG, "choices.log write failed", e) } }
        val tf = java.io.File(app.filesDir, "tasks.txt")
        val mf = java.io.File(app.filesDir, "task_memory.txt")
        val rf = java.io.File(app.filesDir, "reply_memory.txt")
        replyMemory = PhraseMemory(if (rf.exists()) rf.readLines() else emptyList()) { lines -> try { rf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "reply_memory.txt write failed", e) } }
        taskMemory = PhraseMemory(if (mf.exists()) mf.readLines() else emptyList()) { lines -> try { mf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "task_memory.txt write failed", e) } }
        taskStore = TaskStore(if (tf.exists()) tf.readLines() else emptyList(), onAdd = { taskMemory.record(it.text, it.lang) }) { lines -> try { tf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "tasks.txt write failed", e) } }
        tts = TextToSpeech(app) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US; ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (id == lastUtt) handler.post { mode.onSpeechStarted() } }
                    override fun onDone(id: String?) { if (id?.startsWith("wc_") == true) clips?.made(id) else if (id?.startsWith("sy_") == true) spearconMade(id.removePrefix("sy_")) else speechEnded(id) }
                    @Deprecated("old api") override fun onError(id: String?) { speechEnded(id) }
                    override fun onStop(id: String?, interrupted: Boolean) {}   // flushed by a newer utterance or a click
                })
            }
        }
    }

    private var duel: TellAndroid? = null

    // ---- Write: the stereo-pair keyboard (Typing.kt). The OS gives it the raw button and plays the close sound when it ends. ----
    private var typer: TypeProgram? = null
    private var nextWord: Ranker? = null
    @Volatile private var phraseReq = 0
    private lateinit var buckets: BucketStore
    private val phraseWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var clips: WordClips? = null
    private var pairTrack: AudioTrack? = null
    private var pairSeq = 0
    private val typeTimers = HashMap<String, Runnable>()

    private fun stopPair() { pairSeq++; pairTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }; pairTrack = null }

    private fun playPair(left: String, right: String?, me: Int) {
        if (me != pairSeq) return
        if (tts?.isSpeaking == true) { handler.postDelayed({ playPair(left, right, me) }, 300); return }   // the sentence first
        val (pcm, rate) = clips?.stereo(left, right, TypeVoice.PAIR_GAP_MS) ?: run { say(left, false); return }
        pairTrack = try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size); t.play()
            val frames = pcm.size / 2
            handler.postDelayed({ if (pairTrack === t) pairTrack = null; try { t.release() } catch (e: Exception) {} }, frames * 1000L / rate + 100)
            t
        } catch (e: Exception) { Log.e(TAG, "pair failed", e); null }
    }

    /** Loads your word model once (a few seconds on the phone, so it is also started when the app starts). */
    @Synchronized private fun wordModel(): Ranker? = nextWord ?: try {
        val t0 = System.currentTimeMillis()
        val mine = java.io.File(app.filesDir, "words_es.txt")   // your own model, pushed from the laptop; not in the app or in git
        val m: Ranker = if (mine.exists()) mine.bufferedReader().use { r -> NgramWords(r.lineSequence()) }
            else NextWord(app.assets.open("lm/lm.bin"), app.assets.open("lm/vocab.txt").bufferedReader().readText())
        Log.d(TAG, "WRITE model loaded in ${System.currentTimeMillis() - t0} ms")
        m.also { nextWord = it }
    } catch (e: Throwable) { Log.e(TAG, "word model failed", e); null }

    /** Dev tool (AiTestReceiver): start Write into the field that has focus, as the hub item does. */
    fun startWriteDev() { handler.post { launchWrite() } }

    private fun launchWrite() {
        typer?.stop()
        Thread({
            val lm = wordModel()
            handler.post {
                if (lm == null) { say(TypeVoice.NO_MODEL, false); gameEnded(); return@post }
                if (clips == null) clips = WordClips(java.io.File(app.cacheDir, "words"), { text, f, id -> setVoice(if (lm is NgramWords) "es" else null); tts?.setSpeechRate(TypeVoice.WORD_RATE); tts?.synthesizeToFile(text, null, f, id) == TextToSpeech.SUCCESS }, { handler.post(it) })
                val timers = object : Timers {
                    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { typeTimers.remove(key); task() }; typeTimers[key] = r; handler.postDelayed(r, delayMs) }
                    override fun clear(key: String) { typeTimers.remove(key)?.let { handler.removeCallbacks(it) } }
                }
                val frame = ButtonAccessibilityService.inst?.frame() ?: Frame()   // the place that has focus: app, kind of field, what is on the screen
                val out = object : TypeOut {
                    override fun suggestions() = buckets.suggestions(frame)
                    override fun pair(left: String, right: String?) {
                        Log.d(TAG, "WRITE pair $left | $right")
                        stopPair(); val me = pairSeq
                        var pending = if (right == null) 1 else 2
                        val one = { if (--pending == 0) playPair(left, right, me) }
                        clips?.ensure(left, one); right?.let { clips?.ensure(it, one) }
                    }
                    override fun hush() { stopPair() }
                    override fun prefetch(words: List<String>) { words.forEach { clips?.ensure(it) {} } }
                    override fun say(text: String) { this@Actions.say(text, true) }
                    override fun cue(c: TypeCue) { play(TypeSounds.pcm(c)) }
                    override fun phrases(text: String, then: (List<String>) -> Unit) {
                        if (!mode.settings.ai || !LocalAi.available(app)) return
                        val my = ++phraseReq; val es = lm is NgramWords; val frameNow = frame
                        phraseWorker.execute {   // one at a time; a request for an older text is dropped before it starts
                            if (my != phraseReq) return@execute
                            val r = try { LocalAi.continuations(app, text, if (es) "es" else "en", frameNow.brief()) } catch (e: Throwable) { Log.e(TAG, "endings failed", e); emptyList() }
                            if (r.isNotEmpty()) handler.post { then(r) }
                        }
                    }
                    override fun finished(text: String) {
                        Log.d(TAG, "WRITE result [${frame.app} ${frame.kind}]: ${if (frame.kind == FieldKind.PASSWORD) "-" else text}"); EventLog.add("write: ${frame.app} ${text.length} chars")
                        if (text.isNotEmpty()) {
                            buckets.learn(frame, text.split(' ').filter { it.isNotEmpty() }, text)
                            if (frame.hasField) { val all = if (frame.before.isBlank()) text else frame.before.trimEnd() + (if (frame.digits) "" else " ") + text; if (!(ButtonAccessibilityService.inst?.setFieldText(all) ?: false)) Log.d(TAG, "WRITE could not set the field") }
                        }
                        handler.postDelayed({ if (text.isEmpty()) this@Actions.say(TypeVoice.EMPTY, false) else this@Actions.say(text, false, if (lm is NgramWords) "es" else null) }, 700)
                    }
                }
                typer = TypeProgram(BucketRanker(lm, buckets, frame), out, timers, frame) { typer = null; stopPair(); gameEnded() }.also { it.start() }
            }
        }, "write-load").start()
    }

    /** The OS rule for every game: when a game gives the phone back, the OS says goodbye with its own close sound. A game never plays it. */
    private fun gameEnded() { handler.post { beep(Tone.EXIT) } }

    private fun startDuel(coach: Boolean) {
        duel?.stop()
        duel = TellAndroid(prefs(), handler, object : TellHost {
            override fun speak(text: String, slow: Boolean) = say(text, false, slow = slow)
            override fun stopSpeech() { tts?.stop(); lastUtt = "" }
            override fun pauseMusic() { pauseMusicNow() }
            override fun resumeMusic() = resumeMusicNow()
            override fun log(line: String) { Log.d(TAG, line); EventLog.add(line) }
        }, onEnded = { duel = null; gameEnded() }, coach = coach).also { it.start() }
    }

    /** Call on the main thread. repeatCount > 0 is ignored (the hold timer handles holds). */
    fun key(source: String, isDown: Boolean, repeat: Int, downTime: Long, time: Long) {
        if (repeat > 0) return
        if (!deduper.accept(source, isDown, downTime)) { Log.d(TAG, "DEDUPE dropped $source down=$downTime"); return }
        duel?.let { d -> if (d.active) { if (isDown) d.down(downTime) else d.up(time); return } }   // the Duel game owns the raw button
        typer?.let { w -> if (w.active) { if (isDown) w.down(downTime) else w.up(time); return } }   // so does Write
        if (isDown && mode.active) beep(Tone.TAP)   // blind: every press is answered at once
        if (isDown) detector.down(time) else detector.up(time)
    }

    /** Long press launched the assistant role / hands-free: act as HOLD unless a HOLD just fired from the key path. */
    fun assistHold() {
        if (duel?.active == true || typer?.active == true) return
        if (SystemClock.uptimeMillis() - lastHoldAt < 1500) { Log.d(TAG, "ASSIST ignored, hold just fired"); return }
        fire(Gesture.HOLD, 0)
    }

    private fun fire(g: Gesture, dur: Long) {
        if (g == Gesture.HOLD) lastHoldAt = SystemClock.uptimeMillis()
        Log.d(TAG, "GESTURE ${g.name.lowercase()} dur=$dur")
        EventLog.add("${g.name.lowercase()}  ${dur}ms")
        val inMode = mode.active && mode.screen != ControllerMode.Screen.SENT   // a click that cancels a send has its own tone
        val before = mode.tonesPlayed
        val scan = inMode && mode.scanning()   // while options are read: 1 click = next (step tone), 2 clicks = choose (pick tone)
        mode.onGesture(g, dur)
        if (inMode && mode.tonesPlayed == before) when (g) { Gesture.SINGLE -> beep(if (scan) Tone.AGAIN else if (mode.consumeBackPick()) Tone.BACK else Tone.PICK); Gesture.DOUBLE -> beep(if (scan) (if (mode.consumeBackPick()) Tone.BACK else Tone.PICK) else Tone.AGAIN); Gesture.TRIPLE -> beep(Tone.BACK); else -> {} }
    }

    private var spTrack: AudioTrack? = null

    /** Plays PCM on the accessibility stream: same level as speech, not tied to the music volume. */
    private fun play(pcm: ShortArray, rate: Int = Earcons.SR, vol: Float = 1f, onDone: (() -> Unit)? = null): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size); t.setVolume(vol); t.play()
        handler.postDelayed({ try { t.release() } catch (e: Exception) {}; onDone?.invoke() }, pcm.size * 1000L / rate + 80)
        t
    } catch (e: Exception) { Log.e(TAG, "play failed", e); onDone?.invoke(); null }

    /** Earcons: see SOUND-DESIGN.md. */
    private fun beep(k: Tone, vol: Float = 1f) { play(Earcons.pcm(k), vol = vol) }

    // ---- spearcons: an option picked often is scanned as its sped-up spoken word ----
    private val spMem = java.util.concurrent.ConcurrentHashMap<String, Spearcon.Pcm>()
    private val spWant = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()   // key -> (text, lang) being made
    private fun spDir() = java.io.File(app.cacheDir, "spearcons").apply { mkdirs() }

    private fun spearcon(key: String): Spearcon.Pcm? = spMem[key] ?: try {
        val f = java.io.File(spDir(), "$key.pcm")
        if (!f.exists()) null else {
            val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rate = bb.getInt(); Spearcon.Pcm(rate, ShortArray((bb.remaining()) / 2) { bb.getShort() }).also { spMem[key] = it }
        }
    } catch (e: Exception) { null }

    /** Called after the full word was spoken once: ask the TTS engine for a file of it (the next scan can use it). */
    private fun makeSpearcon(key: String, text: String, lang: String?) {
        if (spWant.putIfAbsent(key, text to lang) != null) return
        val r = tts?.synthesizeToFile(text, null, java.io.File(spDir(), "$key.wav"), "sy_$key")
        if (r != TextToSpeech.SUCCESS) spWant.remove(key)
    }

    /** Runs on a TTS thread: wav -> compressed PCM, kept in memory and on disk. */
    private fun spearconMade(key: String) {
        try {
            val w = java.io.File(spDir(), "$key.wav")
            val pcm = Spearcon.readWav(w.readBytes()) ?: return
            val sp = Spearcon.Pcm(pcm.rate, Spearcon.compress(pcm.samples, pcm.rate))
            val bb = java.nio.ByteBuffer.allocate(4 + sp.samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(sp.rate); sp.samples.forEach { bb.putShort(it) }
            java.io.File(spDir(), "$key.pcm").writeBytes(bb.array()); w.delete(); spMem[key] = sp
            Log.d(TAG, "SPEARCON made $key ${pcm.samples.size} -> ${sp.samples.size}")
        } catch (e: Exception) { Log.e(TAG, "spearcon failed", e) } finally { spWant.remove(key) }
    }

    /** New chat message arrived: short earcon, no speech. Favourite senders get a different tone. */
    fun arrival(sender: String) {
        if (!::app.isInitialized) return
        handler.post { beep(if (sender in choices.favourites()) Tone.ARRIVE_FAV else Tone.ARRIVE) }
    }

    /** Sets the voice language for the next utterance; falls back to the default locale if the voice is missing. */
    private fun setVoice(lang: String?) {
        val t = tts ?: return
        val want = if (lang == null) Locale.US else Locale(lang)
        val r = t.isLanguageAvailable(want)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(TAG, "TTS voice $lang missing, default"); t.language = Locale.getDefault()
        } else t.language = want
    }

    private var uttSeq = 0
    private var lastUtt = ""

    /** Only the newest utterance counts: older ones were flushed. Tells the mode the speech is over. */
    private fun speechEnded(id: String?) { if (id == lastUtt) handler.post { mode.onSpeechDone(); duel?.takeIf { it.active }?.speechDone() } }

    private fun say(text: String, queue: Boolean, lang: String? = null, fast: Boolean = false, slow: Boolean = false) {
        Log.d(TAG, "SPEAK[$lang] $text ready=$ttsReady fast=$fast")
        if (!ttsReady) { handler.post { mode.onSpeechDone() }; return }
        val spKey = Spearcon.key(text, lang)
        if (fast) spearcon(spKey)?.let { sp ->
            tts?.stop(); lastUtt = "inv${++uttSeq}"; val me = lastUtt
            handler.post { mode.onSpeechStarted() }
            spTrack = play(sp.samples, sp.rate) { if (lastUtt == me) { spTrack = null; mode.onSpeechDone() } }
            return
        }
        setVoice(lang)
        tts?.setSpeechRate(if (slow) COACH_RATE else speechRate())   // the Duel coach talks slowly; every other call sets its own rate again
        lastUtt = "inv${++uttSeq}"
        if (fast) { val k = spKey; val l = lang; handler.postDelayed({ makeSpearcon(k, text, l) }, 4000) }   // after the word, not during it
        tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, lastUtt)
    }

    private fun spokenTime(c: Calendar) =
        "${c.get(Calendar.HOUR).let { if (it == 0) 12 else it }} ${"%02d".format(c.get(Calendar.MINUTE))} ${if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"}"

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
        beep(Tone.ALARM, minOf(1f, 0.5f + 0.15f * (n / 3)))   // louder every 10 s
        if (n % 3 == 0) say(Voice.TIMER_DONE, true)
        val r = Runnable { ring(n + 1) }
        ringing = r
        handler.postDelayed(r, 3000)
    }

    // Find my phone: the alarm sound loops on the alarm stream at full volume; the old alarm volume comes back after.
    private var findTrack: AudioTrack? = null
    private var findOldVolume = -1

    private fun ringFind(on: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        findTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }; findTrack = null
        if (!on) { if (findOldVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, findOldVolume, 0); findOldVolume = -1; return }
        if (findOldVolume < 0) findOldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val pcm = Earcons.pcm(Tone.ALARM)
        findTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Earcons.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build().also {
                    it.write(pcm, 0, pcm.size); it.setLoopPoints(0, pcm.size, -1); it.setVolume(1f); it.play()
                }
        } catch (e: Exception) { Log.e(TAG, "find ring failed", e); null }
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
            "Time" -> say(spokenTime(Calendar.getInstance()), false)
            "Next alarm" -> {
                val t = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                say(if (t == null) "No alarm" else "Alarm " + spokenTime(Calendar.getInstance().apply { timeInMillis = t }), false)
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
    var reclaim: (() -> Unit)? = null
    private val watched = HashSet<String>()

    /** When another app starts playing it steals the media buttons: reclaim them (debounced). */
    fun watchOtherPlayers() {
        try {
            val msm = app.getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(app, ControllerNotificationListener::class.java)
            val attach = { list: List<android.media.session.MediaController>? ->
                list.orEmpty().filter { it.packageName != app.packageName }.forEach { c ->
                    if (watched.add(c.sessionToken.toString())) c.registerCallback(object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(st: PlaybackState?) {
                            if (st?.state == PlaybackState.STATE_PLAYING) {
                                handler.removeCallbacks(reclaimTask); handler.postDelayed(reclaimTask, 400)
                            }
                        }
                    }, handler)
                }
            }
            msm.addOnActiveSessionsChangedListener({ attach(it) }, me, handler)
            attach(msm.getActiveSessions(me))
        } catch (e: SecurityException) { Log.d(TAG, "WATCH off (notification access is OFF)") }
    }
    private val reclaimTask = Runnable { reclaim?.invoke() }

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
