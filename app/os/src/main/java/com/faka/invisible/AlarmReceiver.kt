package com.faka.invisible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes when our own alarm (set by "Set alarm" with the screen off) is due, and rings it. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Actions.alarmRang() }
}
