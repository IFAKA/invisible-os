package com.faka.invisible

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class ButtonService : Service() {
    private val tag = "Invisible"
    private val handler = Handler(Looper.getMainLooper())
    private var keys: com.faka.invisible.voicekit.MediaKeyIntake? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("inv", "Invisible", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "inv").setContentTitle("Invisible controller on")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        Actions.init(this)

        keys = com.faka.invisible.voicekit.VoiceKit.mediaKeys().also { it.start() }
        EventLog.running = true
        EventLog.add("service on")
        Log.d(tag, "SERVICE started, session active")
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onDestroy() {
        EventLog.running = false
        EventLog.add("service off")
        keys?.stop(); keys = null
        Log.d(tag, "SERVICE stopped")
        super.onDestroy()
    }
}
