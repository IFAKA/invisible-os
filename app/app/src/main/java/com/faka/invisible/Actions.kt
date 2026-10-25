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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var torchOn = false
    private var taskStore = TaskStore()
    fun tasks(): List<Task> = taskStore.all()
    fun addTask(text: String): Task? = taskStore.add(text)
    fun completeTask(id: Long) = taskStore.done(id)
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
            override fun completeTask(id: Long) = taskStore.done(id)
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
            override fun dndAllowed() = this@Actions.dndAllowed()
            override fun codeCount() = prefs().getInt("codeCount", 0)
            override fun setCodeCount(n: Int) { prefs().edit().putInt("codeCount", n).apply() }
            override fun lastTimer() = prefs().getInt("lastTimer", 0)
            override fun setLastTimer(minutes: Int) { prefs().edit().putInt("lastTimer", minutes).apply() }
            override fun pickCount(intent: String) = choices.intentCount(intent)
            override fun favourites() = choices.favourites()
            override fun choice(screen: String, code: Int, intent: String, sender: String) {
                choices.record(System.currentTimeMillis(), screen, code, intent, sender)
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
        mode.settings = SettingsStore.load(prefs())
        val f = java.io.File(app.filesDir, "choices.log")
        choices = ChoiceLog(if (f.exists()) f.readLines() else emptyList()) { line -> try { f.appendText(line + "\n") } catch (e: Exception) { Log.e(TAG, "choices.log write failed", e) } }
        val tf = java.io.File(app.filesDir, "tasks.txt")
        taskStore = TaskStore(if (tf.exists()) tf.readLines() else emptyList()) { lines -> try { tf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "tasks.txt write failed", e) } }
        tts = TextToSpeech(app) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US; ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (id == lastUtt) handler.post { mode.onSpeechStarted() } }
                    override fun onDone(id: String?) { if (id?.startsWith("sy_") == true) spearconMade(id.removePrefix("sy_")) else speechEnded(id) }
                    @Deprecated("old api") override fun onError(id: String?) { speechEnded(id) }
                    override fun onStop(id: String?, interrupted: Boolean) {}   // flushed by a newer utterance or a click
                })
            }
        }
    }

    /** Call on the main thread. repeatCount > 0 is ignored (the hold timer handles holds). */
    fun key(source: String, isDown: Boolean, repeat: Int, downTime: Long, time: Long) {
        if (repeat > 0) return
        if (!deduper.accept(source, isDown, downTime)) { Log.d(TAG, "DEDUPE dropped $source down=$downTime"); return }
        if (isDown && mode.active) beep(Tone.TAP)   // blind: every press is answered at once
        if (isDown) detector.down(time) else detector.up(time)
    }

    /** Long press launched the assistant role / hands-free: act as HOLD unless a HOLD just fired from the key path. */
    fun assistHold() {
        if (SystemClock.uptimeMillis() - lastHoldAt < 1500) { Log.d(TAG, "ASSIST ignored, hold just fired"); return }
        fire(Gesture.HOLD, 0)
    }

    private fun fire(g: Gesture, dur: Long) {
        if (g == Gesture.HOLD) lastHoldAt = SystemClock.uptimeMillis()
        Log.d(TAG, "GESTURE ${g.name.lowercase()} dur=$dur")
        EventLog.add("${g.name.lowercase()}  ${dur}ms")
        val inMode = mode.active && mode.screen != ControllerMode.Screen.SENT   // a click that cancels a send has its own tone
        mode.onGesture(g, dur)
        if (inMode) when (g) { Gesture.SINGLE -> beep(Tone.PICK); Gesture.DOUBLE -> beep(Tone.AGAIN); Gesture.TRIPLE -> beep(Tone.BACK); else -> {} }
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
    private fun speechEnded(id: String?) { if (id == lastUtt) handler.post { mode.onSpeechDone() } }

    private fun say(text: String, queue: Boolean, lang: String? = null, fast: Boolean = false) {
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
        tts?.setSpeechRate(speechRate())
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
        if (ok) { say("Timer $minutes minutes", false); return }
        ownTimer?.let { handler.removeCallbacks(it) }
        val r = Runnable { ownTimer = null; ring(0) }
        ownTimer = r
        handler.postDelayed(r, minutes * 60_000L)
        say("Timer $minutes minutes", false)
    }

    private fun appVisible(): Boolean {
        val p = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(p)
        return p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun ring(n: Int) {
        if (n >= 20) { ringing = null; return }
        beep(Tone.ALARM, minOf(1f, 0.5f + 0.15f * (n / 3)))   // louder every 10 s
        if (n % 3 == 0) say("Timer done", true)
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
        say("Alarm stopped", false)
    }

    private fun menuAction(name: String) {
        when (name) {
            "Flashlight" -> say("Flashlight " + if (toggleTorch()) "on" else "off", false)
            "Time" -> say(spokenTime(Calendar.getInstance()), false)
            "Next alarm" -> {
                val t = app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                say(if (t == null) "No alarm" else "Alarm " + spokenTime(Calendar.getInstance().apply { timeInMillis = t }), false)
            }
            "Battery" -> {
                val b = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("Battery $b percent", false)
            }
            "Do not disturb" -> {
                val nm = app.getSystemService(NotificationManager::class.java)
                val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.d(TAG, "DND ${if (on) "on" else "off"}")
                say("Do not disturb " + if (on) "on" else "off", false)
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
