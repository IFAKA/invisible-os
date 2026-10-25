package com.faka.invisible

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import java.util.Calendar
import java.util.Locale

class ButtonService : Service() {
    private val tag = "Invisible"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var track: AudioTrack? = null
    @Volatile private var playing = false

    private fun state(st: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        .setState(st, 0, 1f).build()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("inv", "Invisible", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "inv").setContentTitle("Invisible controller on")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        Actions.init(this)

        session = MediaSession(this, "Invisible").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    val e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                    if (e.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
                    Log.d(tag, "KEY action=${e.action} repeat=${e.repeatCount} down=${e.downTime} t=${e.eventTime} a11y=${Actions.accessibilityConnected}")
                    Actions.key("media", e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.downTime, e.eventTime)
                    return true
                }
            })
            setPlaybackState(state(PlaybackState.STATE_PLAYING))
            isActive = true
        }
        // Another app that starts playing takes the media buttons. Toggle our state to win them back.
        Actions.reclaim = {
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            Log.d(tag, "RECLAIM media buttons")
        }
        Actions.watchOtherPlayers()
        startSilence()
        EventLog.running = true
        EventLog.add("service on")
        Log.d(tag, "SERVICE started, session active")
    }

    private fun startSilence() {
        val rate = 8000
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        playing = true
        t.play()
        Thread {
            val zeros = ShortArray(size / 2)
            while (playing) t.write(zeros, 0, zeros.size)
        }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onDestroy() {
        EventLog.running = false
        Actions.reclaim = null
        EventLog.add("service off")
        playing = false
        track?.release()
        session.isActive = false
        session.release()
        Log.d(tag, "SERVICE stopped")
        super.onDestroy()
    }
}
