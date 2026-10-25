package com.faka.invisible

/**
 * Audio Kit: every fixed sentence the phone says (English menu).
 * Sounds live in Earcons.kt (one Tone list); falling = list ended, rising = starts over.
 * Change a sentence here and it changes everywhere.
 */
object Voice {
    // Button rules, same on every screen (the one `when` in ControllerMode.onGesture):
    // idle:    1 = play/pause, 2 = next, 3 = previous, hold = wake
    // in menu: 1 = pick the option just spoken, 2 = list again, 3 = back, 4 = leave, hold = exit; any click cancels a pending send
    const val HINT = "Click when you hear it. Two clicks: start over. Three clicks: go back."
    const val GESTURE_HELP = "In the menu: click when you hear the option. 2 = start over, 3 = back, 4 = leave. Hold = exit."
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
