package com.faka.invisible

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var list: TextView
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var assist: TextView
    private lateinit var notif: TextView
    private lateinit var modeText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        button = Button(this)
        list = TextView(this).apply { textSize = 18f }
        status = TextView(this).apply { textSize = 18f }
        val a11y = Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        assist = TextView(this).apply { textSize = 18f }
        val setAssist = Button(this).apply {
            text = "Set as assistant"
            setOnClickListener { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        }
        notif = TextView(this).apply { textSize = 18f }
        modeText = TextView(this).apply { textSize = 18f }
        val notifBtn = Button(this).apply {
            text = "Notification access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(button); root.addView(modeText); root.addView(notif); root.addView(notifBtn); root.addView(status); root.addView(a11y); root.addView(assist); root.addView(setAssist); root.addView(list)
        setContentView(root)
        button.setOnClickListener {
            val svc = Intent(this, ButtonService::class.java)
            if (EventLog.running) stopService(svc) else startForegroundService(svc)
        }
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        EventLog.onChange = { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        render()
    }

    private fun render() {
        val n = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        notif.text = "Notification access: " + if (n.contains(packageName)) "ON" else "OFF"
        modeText.text = "Mode: " + if (Actions.mode.active) "controller" else "idle"
        val a = Settings.Secure.getString(contentResolver, "assistant") ?: ""
        assist.text = "Assistant: " + if (a.contains(packageName)) "ON" else "OFF"
        status.text = "Accessibility: " + if (Actions.accessibilityConnected) "ON" else "OFF"
        button.text = if (EventLog.running) "STOP" else "START"
        list.text = EventLog.items.joinToString("\n").ifEmpty { "No gestures yet" }
    }

    override fun onDestroy() { EventLog.onChange = null; super.onDestroy() }
}
