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

/**
 * Spoken sentences of the Duel coach and the pacing of the practice. Plain English, at most 7 words per sentence,
 * one idea each. Pacing follows how long a person needs to take a sentence in, not how long the voice needs to say it.
 */
object TellVoice {
    // ---- pacing: ONE place, tune here ----
    /** The coach voice runs at rate 0.75, about 110 words a minute = 550 ms a word, plus 400 ms to start. Used when no done-callback comes. */
    const val SPEAK_BASE_MS = 400L
    const val SPEAK_PER_WORD_MS = 550L
    /** Time to take a sentence in: 600 ms to settle plus 250 ms for each word just heard (a 5-word sentence = 1.85 s). */
    const val DIGEST_BASE_MS = 600L
    const val DIGEST_PER_WORD_MS = 250L
    /** After a sound the player has not heard yet (cue, click, notes, result): at least this much quiet before speaking. */
    const val SOUND_QUIET_MS = 1500L
    /** After the instruction of a step that needs the thumb: time to get ready. */
    const val READY_MS = 800L
    /** Silence without a press: a hint every this long. */
    const val HINT_MS = 10000L
    /** Space between the six rising energy notes. */
    const val NOTE_SPACING_MS = 600L

    fun words(text: String) = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    fun speakMs(text: String) = SPEAK_BASE_MS + SPEAK_PER_WORD_MS * words(text)
    /** The quiet after a spoken sentence. */
    fun pauseAfter(text: String) = DIGEST_BASE_MS + DIGEST_PER_WORD_MS * words(text)
    /** The quiet after a sound of [soundMs] length. */
    fun quietAfterSound(soundMs: Long) = soundMs + SOUND_QUIET_MS

    // ---- markers inside a step's lines: a sound instead of a sentence ----
    const val CLICK1 = "<click1>"
    const val CLICK2 = "<click2>"
    const val NOTES = "<notes>"

    // ---- the curriculum ----
    val S_TAP = listOf("Duel. One button.", "To leave, hold three seconds.", "Tap the button.")
    val S_GUARD = listOf("Now press and hold.", "Listen for a click.", CLICK1, "Let go after the click.")
    val S_CHARGE = listOf("Hold longer.", "Two clicks.", CLICK2, "Let go after the second.")
    val S_ROUND_A = listOf("The robot will jab.", "You guard.")
    val S_ROUND_B = listOf("The robot will charge.", "You jab.")
    val S_ROUND_C = listOf("The robot will guard.", "You charge.")
    val S_ENERGY = listOf("Charge costs energy.", "Three.", "Listen.", NOTES, "More energy, higher note.", "You get one back each round.")
    val S_LIFE = listOf("You each have four life.", "Take all of theirs.", "The robot is on your left.")
    val S_FIGHT = listOf("Now a real fight.", "Tap to start.")
    val DONE = listOf("Jab.", "Guard.", "Charge.")
    const val RULE_A = "Guard beats jab."
    const val RULE_B = "Jab beats charge."
    const val RULE_C = "Charge beats guard."
    const val SKIP = "Let's move on."
    const val LEAVE_HINT = "Hold three seconds to leave."
    fun hint(tapStep: Boolean) = if (tapStep) "Press to continue." else "Press the button."
    private val NAMES = listOf("jab", "guard", "charge")
    /** Every sentence of the practice (the markers are sounds). Each is at most 7 words. */
    val ALL: List<String> = (S_TAP + S_GUARD + S_CHARGE + S_ROUND_A + S_ROUND_B + S_ROUND_C + S_ENERGY + S_LIFE + S_FIGHT +
        DONE + listOf(RULE_A, RULE_B, RULE_C, SKIP, LEAVE_HINT, hint(true), hint(false)) +
        (0..2).flatMap { w -> (0..2).map { l -> wrong(l, w) } }.flatMap { it }).filter { it != CLICK1 && it != CLICK2 && it != NOTES }

    /** Feedback for a wrong hold: what it was, then what to do. Two sentences, spoken with a pause between. */
    fun wrong(level: Int, want: Int): List<String> = listOf("That was a ${NAMES[level]}.", when (want) {
        0 -> "Tap quickly."; 1 -> "Hold for the first click."; else -> "Wait for the second click."
    })

    private val NUM = listOf("zero", "one", "two", "three", "four", "five")
    fun firstMatch(rung: Int) = "First match. Rung ${NUM[rung.coerceIn(0, 5)]}."
    fun duelRung(rung: Int) = "Duel. Rung ${NUM[rung.coerceIn(0, 5)]}."
    const val LOADING = "Loading the duel"
    const val COULD_NOT_START = "Duel could not start"

    private fun base(m: com.faka.invisible.tell.Move) = when (m) {
        com.faka.invisible.tell.Move.JAB -> "jab"; com.faka.invisible.tell.Move.GUARD -> "guard"; com.faka.invisible.tell.Move.CHARGE -> "charge"
    }
    private fun n(x: Int) = NUM[x.coerceIn(0, 5)]

    /** The result of one round, at most 5 words. */
    fun result(r: com.faka.invisible.tell.Round): String {
        if (r.mine == r.theirs) return "Both ${base(r.mine)}. " + if (r.damageToMe > 0) "Both hurt." else "Nothing."
        val tail = when {
            r.myParry -> "Parry."
            r.theirParry -> "Parried."
            r.myChargeFizzled -> "Fizzle."
            r.theirChargeFizzled -> "Fizzle."
            r.damageToOpp > 0 -> "Hit."
            r.damageToMe > 0 -> "Ouch."
            else -> "Nothing."
        }
        return "They ${base(r.theirs)}. You ${base(r.mine)}. $tail"
    }

    /** The coached narration: the result, a life change only when life changed, and two energy remarks. */
    fun narration(r: com.faka.invisible.tell.Round, lowEnergy: Boolean = false, chargeReady: Boolean = false): String {
        val sb = StringBuilder(result(r))
        if (r.damageToMe > 0) sb.append(" Your life ").append(n(r.after.myL)).append('.')
        if (r.damageToOpp > 0) sb.append(" Their life ").append(n(r.after.oppL)).append('.')
        if (chargeReady) sb.append(" Charge ready.") else if (lowEnergy) sb.append(" Low energy.")
        return sb.toString()
    }
}
