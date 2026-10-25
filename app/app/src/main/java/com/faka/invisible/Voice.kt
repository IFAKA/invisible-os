package com.faka.invisible

/**
 * Audio Kit: every fixed sentence the phone says (English menu).
 * Sounds live in Earcons.kt (one Tone list); falling = list ended, rising = starts over.
 * Change a sentence here and it changes everywhere.
 */
object Voice {
    fun timerSet(minutes: Int) = "Timer $minutes minutes"
    const val TIMER_DONE = "Timer done"
    const val ALARM_STOPPED = "Alarm stopped"
    fun flashlight(on: Boolean) = "Flashlight " + if (on) "on" else "off"
    fun battery(percent: Int) = "Battery $percent percent"
    fun doNotDisturb(on: Boolean) = "Do not disturb " + if (on) "on" else "off"
    const val NO_TASKS = "No tasks yet"
    const val ALL_DONE = "All done"
    const val TASK_ADDED = "Task added"
    const val NO_MESSAGES = "No unread messages"
    const val CANNOT = "Cannot do it"
    const val CANCELLED = "Cancelled"
}
