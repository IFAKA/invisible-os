@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.faka.invisible.oskit.Trace

/**
 * ENGINE. The only place that takes the earbud button from the system through a media session. It keeps the session
 * active with a silent stream, wins the buttons back when another app starts playing, and passes every key to the [ButtonPort].
 */
class MediaKeyIntake(
    private val app: Context,
    private val handler: Handler,
    private val port: ButtonPort,
    private val watcher: ComponentName,   // the notification listener: it grants the right to see other media sessions
) {
    private val tag = "Invisible"
    private lateinit var session: MediaSession
    private var track: AudioTrack? = null
    @Volatile private var playing = false
    @Volatile private var stopped = false
    private val retrySilence = Runnable { if (!stopped && track == null) startSilence() }
    private val watched = HashSet<String>()
    private var lastTraced = 0L
    private val reclaimTask = Runnable { reclaim() }

    private fun state(st: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        .setState(st, 0, 1f).build()

    /** [n] quick presses made out of one key: each press lasts 50 ms, one every 140 ms (well inside the click gap). */
    private fun clicks(n: Int) {
        val base = SystemClock.uptimeMillis()
        for (i in 0 until n) {
            val t = base + i * 140L
            handler.postDelayed({ port.key("media", true, 0, t, t) }, i * 140L)
            handler.postDelayed({ port.key("media", false, 0, t, t + 50) }, i * 140L + 50)
        }
    }

    fun start() {
        session = MediaSession(app, "Invisible").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    val e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                    // Our session says PLAYING, so an earbud tap (Bluetooth AVRCP) arrives as PAUSE (or PLAY): it is the same button.
                    // Earbuds count double and triple taps themselves and send one key: Next = 2 clicks, Previous = 3.
                    val multi = when (e.keyCode) { KeyEvent.KEYCODE_MEDIA_NEXT -> 2; KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 3; else -> 0 }
                    if (multi > 0) {
                        if (e.action == KeyEvent.ACTION_DOWN) { Trace.ev("media", "key code=${e.keyCode} = $multi clicks"); clicks(multi) }
                        return true
                    }
                    val tap = e.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    if (!ButtonInput.isButton(e) && !tap) return false
                    Trace.ev("media", "key action=${e.action} code=${e.keyCode} repeat=${e.repeatCount}")
                    Log.d(tag, "KEY action=${e.action} repeat=${e.repeatCount} down=${e.downTime} t=${e.eventTime}")
                    port.key("media", e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.downTime, e.eventTime)
                    return true
                }
            })
            setPlaybackState(state(PlaybackState.STATE_PLAYING))
            isActive = true
        }
        watchOtherPlayers()
        startSilence()
    }

    fun stop() {
        stopped = true; handler.removeCallbacks(retrySilence)
        playing = false
        track?.release()
        session.isActive = false
        session.release()
    }

    /** Another app that starts playing takes the media buttons. Toggle our state to win them back. */
    private fun reclaim() {
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
        Log.d(tag, "RECLAIM media buttons")
        Trace.ev("media", "reclaim buttons (another app played)")
    }

    /** When another app starts playing it steals the media buttons: reclaim them (debounced). */
    private fun watchOtherPlayers() {
        try {
            val msm = app.getSystemService(MediaSessionManager::class.java)
            val attach = { list: List<MediaController>? ->
                list.orEmpty().filter { it.packageName != app.packageName }.forEach { c ->
                    if (watched.add(c.sessionToken.toString())) c.registerCallback(object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(st: PlaybackState?) {
                            if (st?.state == PlaybackState.STATE_PLAYING) {
                                if (SystemClock.uptimeMillis() - lastTraced > 2000) { lastTraced = SystemClock.uptimeMillis(); Trace.ev("media", "other player playing pkg=${c.packageName}") }
                                handler.removeCallbacks(reclaimTask); handler.postDelayed(reclaimTask, 400)
                            }
                        }
                    }, handler)
                }
            }
            msm.addOnActiveSessionsChangedListener({ attach(it) }, watcher, handler)
            attach(msm.getActiveSessions(watcher))
        } catch (e: SecurityException) { Log.d(tag, "WATCH off (notification access is OFF)") }
    }

    private fun startSilence() {
        val rate = 8000
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) {   // right after boot the audio system may not be ready: try again soon instead of crashing the service
            Log.d(tag, "SILENCE track failed, retry in 5 s: $e"); handler.postDelayed(retrySilence, 5000); return
        }
        track = t
        playing = true
        t.play()
        Thread {
            val zeros = ShortArray(size / 2)
            while (playing) t.write(zeros, 0, zeros.size)
        }.start()
    }
}
