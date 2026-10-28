package com.faka.invisible.now

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.provider.Settings
import com.faka.invisible.now.SwitchesCore.Item
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.ScanCadence
import com.faka.invisible.voicekit.ScanOut
import com.faka.invisible.voicekit.Scanner
import com.faka.invisible.voicekit.Timers
import com.faka.invisible.voicekit.Tone
import com.faka.invisible.voicekit.Voice
import com.faka.invisible.voicekit.VoiceKit

/**
 * Quick switches, a tool: a spoken list. Click picks the item just spoken, two clicks the one before, three clicks leave, a hold is
 * fast scroll, hold 3 s leaves. Items: volume up, volume down, Do not disturb (only with notification-policy access; else it says
 * where to allow it), flashlight, ring my phone (the default ringtone, loud, 10 s; any press stops it), and the Wi-Fi and
 * Bluetooth Settings pages (Android 10 and later do not let an app flip those two). No microphone.
 */
class SwitchesProgram : Program {
    private lateinit var host: ProgramHost
    private lateinit var scanner: Scanner<Item>
    @Volatile private var running = false
    private var awaitItem = false
    private var resumeAfter = false   // an action was spoken: the list goes on at the same item when the speech is over
    private var ringtone: Ringtone? = null
    private var savedAlarmVolume = -1
    private var clicks = 0
    private var downAt = 0L
    private var down = false
    private var fastOn = false
    private var torchOn = false
    private var torchId: String? = null
    private var torchCb: CameraManager.TorchCallback? = null
    private val leave = Runnable { finish() }
    private val holdFast = Runnable { startFast() }
    private val stopRing = Runnable { endRing(spoken = false) }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }

    override val active get() = running

    private fun label(i: Item): Phrase = when (i) {
        Item.VOLUME_UP -> SwitchesVoice.VOLUME_UP
        Item.VOLUME_DOWN -> SwitchesVoice.VOLUME_DOWN
        Item.DND -> SwitchesVoice.DND
        Item.TORCH -> SwitchesVoice.TORCH
        Item.RING -> SwitchesVoice.RING
        Item.WIFI -> SwitchesVoice.WIFI
        Item.BLUETOOTH -> SwitchesVoice.BLUETOOTH
    }

    private class HostTimers(private val host: ProgramHost) : Timers {
        private val pending = HashMap<String, Runnable>()
        override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; host.later(r, delayMs) }
        override fun clear(key: String) { pending.remove(key)?.let { host.cancel(it) } }
    }

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true
        scanner = Scanner(HostTimers(host), ::label, object : ScanOut {
            override fun say(index: Int, label: Phrase, spearcon: Boolean) { awaitItem = true; show(index); host.speak(label) }
            override fun sayFast(word: Phrase) { host.speak(word) }
            override fun tone(t: Tone) { VoiceKit.narrator.beep(t) }
            override fun leave() { finish() }
        }, ScanCadence(pauseMs = { PAUSE_MS }, loops = { LOOPS }, ceremony = false), scanKey = "switches:scan")
        scanner.load(SwitchesCore.ITEMS)
        watchTorch()
        Trace.ev("program", "switches start")
        host.pauseMusic()
        scanner.speakItem(0)
    }

    private fun show(mark: Int) { host.screen(scanner.list.items.map { label(it).text }, mark) }

    override fun speechDone() {
        if (!running) return
        if (resumeAfter) { resumeAfter = false; scanner.resumeIn(PAUSE_MS); return }
        if (awaitItem) { awaitItem = false; scanner.itemDone() }
    }

    // ---- the button ----
    override fun down(time: Long) {
        down = true; downAt = time; host.cancel(resolve)
        if (ringtone != null) { host.cancel(leave); endRing(spoken = true); down = false; return }   // any press stops the ring
        host.later(holdFast, Pacing.HOLD_MS); host.later(leave, Pacing.LEAVE_MS)
    }

    override fun up(time: Long) {
        host.cancel(leave); host.cancel(holdFast)
        if (fastOn) {
            fastOn = false; down = false
            if (scanner.endFast()) { host.stopSpeech(); awaitItem = false; scanner.resumeIn(0) }
            return
        }
        if (down && time - downAt < Pacing.HOLD_MS) { clicks++; host.later(resolve, Pacing.GAP_MS) }
        down = false
    }

    private fun startFast() {
        if (!running || ringtone != null) return
        clicks = 0; host.cancel(resolve)
        host.stopSpeech(); awaitItem = false; resumeAfter = false; scanner.stop()
        fastOn = true
        scanner.startFast()
    }

    private fun onClicks(n: Int) {
        host.stopSpeech(); awaitItem = false; resumeAfter = false
        when {
            n >= 3 -> finish()
            n == 2 -> scanner.prevIn(0)
            else -> { val item = scanner.list.current; scanner.stop(); pick(item) }
        }
    }

    // ---- the switches ----
    private fun pick(item: Item) {
        Trace.ev("pick", "switches ${item.name.lowercase()}")
        val ctx = host.context
        when (item) {
            Item.VOLUME_UP, Item.VOLUME_DOWN -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, SwitchesCore.stepped(am.getStreamVolume(AudioManager.STREAM_MUSIC), max, item == Item.VOLUME_UP), 0)
                reply(SwitchesVoice.volume(SwitchesCore.percent(am.getStreamVolume(AudioManager.STREAM_MUSIC), max)))
            }
            Item.DND -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) { reply(SwitchesVoice.DND_NEEDS); return }
                val on = SwitchesCore.dndNowOn(nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL)
                reply(Voice.doNotDisturb(on))
            }
            Item.TORCH -> toggleTorch()
            Item.RING -> startRing()
            Item.WIFI -> openSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, SwitchesVoice.OPENING_WIFI)
            Item.BLUETOOTH -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, SwitchesVoice.OPENING_BLUETOOTH)
        }
    }

    /** Speaks the result, then the list goes on at the same item. */
    private fun reply(p: Phrase) { resumeAfter = true; host.speak(p) }

    private fun openSettings(action: String, said: Phrase) {
        try { host.context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); reply(said) }
        catch (e: Exception) { Trace.err("switches open $action", e); reply(SwitchesVoice.OPEN_FAILED) }
    }

    private fun watchTorch() {
        try {
            val cm = host.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchId = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) torchOn = enabled }
            }
            torchCb = cb
            cm.registerTorchCallback(cb, host.handler)
        } catch (e: Exception) { Trace.err("switches torch watch", e) }
    }

    private fun toggleTorch() {
        val id = torchId
        if (id == null) { reply(SwitchesVoice.TORCH_FAILED); return }
        try {
            val cm = host.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val on = !torchOn
            cm.setTorchMode(id, on); torchOn = on
            reply(Voice.flashlight(on))
        } catch (e: Exception) { Trace.err("switches torch", e); reply(SwitchesVoice.TORCH_FAILED) }
    }

    private fun startRing() {
        try {
            val ctx = host.context
            val am = ctx.getSystemService(AudioManager::class.java)
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.isLooping = true; r.volume = 1f
            r.play(); ringtone = r
            host.later(stopRing, RING_MS)
            host.speak(SwitchesVoice.RINGING)
        } catch (e: Exception) { Trace.err("switches ring", e); endRing(spoken = false); reply(SwitchesVoice.OPEN_FAILED) }
    }

    /** The ring is over (10 s passed or a press). [spoken] = say that it stopped. */
    private fun endRing(spoken: Boolean) {
        host.cancel(stopRing)
        val r = ringtone ?: return
        ringtone = null
        try { r.stop() } catch (e: Exception) { Trace.err("switches ring stop", e) }
        if (savedAlarmVolume >= 0) { host.context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0); savedAlarmVolume = -1 }
        Trace.ev("program", "switches ring end")
        if (running) { host.stopSpeech(); if (spoken) reply(SwitchesVoice.RING_STOPPED) else scanner.resumeIn(0) }
    }

    override fun stop() = finish()

    private fun finish() {
        if (!running) return
        running = false
        endRing(spoken = false)
        host.cancel(leave); host.cancel(resolve); host.cancel(holdFast)
        scanner.stop()
        torchCb?.let { try { (host.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).unregisterTorchCallback(it) } catch (e: Exception) {} }
        host.stopSpeech()
        host.resumeMusic()
        host.ended()
    }

    private companion object { const val PAUSE_MS = 1200L; const val LOOPS = 2; const val RING_MS = 10_000L }
}

object SwitchesEntry {
    val entry = ProgramEntry("hub:switches", SwitchesVoice.TITLE) { SwitchesProgram() }
}
