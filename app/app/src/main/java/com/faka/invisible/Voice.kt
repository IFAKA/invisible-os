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
    fun tasksAdded(n: Int) = when (n) { 0 -> "No new open tasks"; 1 -> "1 task added"; else -> "$n tasks added" }
    const val NO_TASKS = "No tasks yet"
    const val ALL_DONE = "All done"
    const val TASK_ADDED = "Task added"
    val TASK_STARTERS = listOf("Buy groceries", "Call someone", "Pay a bill", "Send an email", "Clean up")
    const val NO_MESSAGES = "No unread messages"
    const val CANNOT = "Cannot do it"
    const val CANCELLED = "Cancelled"
}

/** Spoken sentences of the Duel coach (practice drills and the narrated first match). Plain, short English. */
object TellVoice {
    const val INTRO = "Duel. You against a robot. One button. How long you press picks your move."
    val DRILLS = listOf(
        "Tap quickly. Let go before any click. That is a jab.",
        "Now press and hold. You hear a click. Let go right after the first click. That is a guard.",
        "Press and hold. Wait for the second click, then let go. That is a charge. It costs three energy.")
    val NAMES = listOf("jab", "guard", "charge")
    val DONE = listOf("Jab.", "Guard.", "Charge.")
    const val RULE = "Let go before a click: jab. After the first click: guard. After the second click: charge. " +
        "Guard beats jab. Jab beats charge. Charge beats guard. Same moves trade. Hold three seconds any time to leave."
    const val SKIP = "Let's move on."
    fun wrong(level: Int, drill: Int) = "That was a ${NAMES[level]}. " + when (drill) {
        0 -> "Tap quickly. Let go before any click."; 1 -> "Hold. Let go right after the first click."; else -> "Hold. Let go after the second click."
    }
    private val NUM = listOf("zero", "one", "two", "three", "four", "five")
    fun firstMatch(rung: Int) = "First match. Rung ${NUM[rung.coerceIn(0, 5)]}."
    fun duelRung(rung: Int) = "Duel. Rung ${NUM[rung.coerceIn(0, 5)]}."
    const val LOADING = "Loading the duel"
    const val COULD_NOT_START = "Duel could not start"

    private fun verb(m: com.faka.invisible.tell.Move) = when (m) {
        com.faka.invisible.tell.Move.JAB -> "jabbed"; com.faka.invisible.tell.Move.GUARD -> "guarded"; com.faka.invisible.tell.Move.CHARGE -> "charged"
    }
    private fun n(x: Int) = NUM[x.coerceIn(0, 5)]

    /** The result of one round in six words or fewer. */
    fun result(r: com.faka.invisible.tell.Round): String {
        if (r.mine == r.theirs) {
            val both = "Both ${verb(r.mine)}."
            return if (r.damageToMe > 0) "$both Both lose ${n(r.damageToMe)}." else "Nothing happens."
        }
        val head = "You ${verb(r.mine)}. They ${verb(r.theirs)}."
        val tail = when {
            r.myParry -> "Parry."
            r.theirParry -> "They parry."
            r.myChargeFizzled -> "Fizzle."
            r.theirChargeFizzled -> "They fizzle."
            r.damageToOpp > 0 -> "Hit, ${n(r.damageToOpp)}."
            r.damageToMe > 0 -> "Ouch, ${n(r.damageToMe)}."
            else -> "Nothing happens."
        }
        return "$head $tail"
    }

    /** Rough speaking time in ms (the speech engine does not tell the game when it is done). */
    fun speakMs(text: String) = 400L + 70L * text.length
}
