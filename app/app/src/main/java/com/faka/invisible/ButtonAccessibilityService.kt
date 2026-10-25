package com.faka.invisible

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class ButtonAccessibilityService : AccessibilityService() {
    companion object {
        var inst: ButtonAccessibilityService? = null
    }

    override fun onServiceConnected() {
        inst = this
        Actions.init(this)
        Actions.accessibilityConnected = true
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java)) // self-start, no screen needed
        Log.d("Invisible", "A11Y connected")
        EventLog.add("accessibility connected")
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        Log.d("Invisible", "AKEY action=${e.action} code=${e.keyCode} repeat=${e.repeatCount} down=${e.downTime} t=${e.eventTime}")
        Actions.key("a11y", e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.downTime, e.eventTime)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Actions.accessibilityConnected = false
        Log.d("Invisible", "A11Y disconnected")
        return super.onUnbind(intent)
    }
}
