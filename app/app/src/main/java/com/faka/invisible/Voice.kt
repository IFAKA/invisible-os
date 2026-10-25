package com.faka.invisible

/**
 * Audio Kit: every fixed sentence the phone says (English menu).
 * Sounds live in Earcons.kt (one Tone list); falling = list ended, rising = starts over.
 * Change a sentence here and it changes everywhere.
 */
object Voice {
    // Button rules, same on every screen (the one `when` in ControllerMode.onGesture):
    // idle:    1 = play/pause, 2 = next, 3 = previous, hold = wake
    // in menu: 1 = pick the option just spoken, 2 = one back, 3 = back, 4 = leave, hold = exit; any click cancels a pending send
    const val HINT = "Click when you hear it. Two clicks: one back. Three clicks: go back."
    const val GESTURE_HELP = "In the menu: click when you hear the option. 2 = one back, 3 = back, 4 = leave. Hold = exit."
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

    // ---- weather, calendar, find my phone ----
    const val NEED_LOCATION = "Open the app and allow location."
    const val WEATHER_FAILED = "No weather now."
    const val NEED_CALENDAR = "Open the app and allow the calendar."
    const val CALENDAR_FAILED = "Cannot read the calendar."
    const val NO_EVENTS = "No events today."
    const val EVENT_UNNAMED = "Event"
    const val FIND_PHONE = "Find my phone"
    const val NO_NAVIGATION = "No navigation."
    /** "Now 18 degrees, rain in 2 hours." [rainInHours]: 0 = raining now, null = none soon. */
    fun weather(degrees: Int, rainInHours: Int?): String {
        val t = if (degrees < 0) "minus ${-degrees}" else "$degrees"
        val rain = when (rainInHours) { null -> "no rain soon"; 0 -> "raining now"; 1 -> "rain in 1 hour"; else -> "rain in $rainInHours hours" }
        return "Now $t degrees, $rain."
    }
    fun eventNow(title: String) = "$title, now."
    /** "Dentist at 4 PM, in 40 minutes." */
    fun eventLater(title: String, hour24: Int, minute: Int, inMinutes: Int): String {
        val h = (hour24 % 12).let { if (it == 0) 12 else it }
        val at = "$h" + (if (minute == 0) "" else ":%02d".format(minute)) + if (hour24 < 12) " AM" else " PM"
        val h2 = inMinutes / 60; val m2 = inMinutes % 60
        val inText = buildList {
            if (h2 > 0) add(if (h2 == 1) "1 hour" else "$h2 hours")
            if (m2 > 0 || h2 == 0) add(if (m2 == 1) "1 minute" else "$m2 minutes")
        }.joinToString(" ")
        return "$title at $at, in $inText."
    }
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

    // ---- the levels: short teaching before each real match ----
    val L1_TAP = listOf("Level one. Jab and guard.", "To leave, hold three seconds.", "Tap to jab.")
    val L1_GUARD = listOf("Listen for a click.", CLICK1, "Hold to the click to guard.")
    val L1_RULE = listOf("Guard beats jab.")
    val L2_CHARGE = listOf("Level two. Charge.", "Hold longer.", "Two clicks.", CLICK2, "Let go after the second.")
    val L2_RULE = listOf("Charge beats guard.", "Jab beats charge.", "No charge twice in a row.")
    val L3_ENERGY = listOf("Level three. Energy.", "Charge costs three.", "Listen.", NOTES, "More energy, higher note.", "You get one back each round.")
    val DONE = listOf("Jab.", "Guard.", "Charge.")
    const val NOT_AGAIN = "Not again."
    const val SKIP = "Let's move on."
    const val LEAVE_HINT = "Hold three seconds to leave."
    fun hint(tapStep: Boolean) = if (tapStep) "Press to continue." else "Press the button."
    private val NAMES = listOf("jab", "guard", "charge")
    /** Every sentence of the practice (the markers are sounds). Each is at most 7 words. */
    val ALL: List<String> = (L1_TAP + L1_GUARD + L1_RULE + L2_CHARGE + L2_RULE + L3_ENERGY +
        DONE + listOf(SKIP, LEAVE_HINT, hint(true), hint(false), NOT_AGAIN, "Last life.", "Their last life.") +
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

    /** Level 1 (jab and guard): one word, and a warning at the last life. Empty = say nothing. */
    fun narrationL1(r: com.faka.invisible.tell.Round): String {
        val sb = StringBuilder(when {
            r.myParry -> "Parry."
            r.theirParry -> "Ouch."
            r.mine == r.theirs && r.damageToMe > 0 -> "Trade."
            else -> ""
        })
        if (r.after.myL == 1 && r.before.myL > 1) sb.append(" Last life.")
        if (r.after.oppL == 1 && r.before.oppL > 1) sb.append(" Their last life.")
        return sb.toString().trim()
    }

    /** Level 2 (charge): one to three words. */
    fun narrationL2(r: com.faka.invisible.tell.Round): String {
        val big = com.faka.invisible.tell.Move.CHARGE
        val sb = StringBuilder(when {
            r.myParry -> "Parry."
            r.theirParry -> "Ouch."
            r.myChargeFizzled -> "Charge broken."
            r.theirChargeFizzled -> "Their charge broken."
            r.mine == big && r.damageToOpp > 0 && r.damageToMe > 0 -> "Both hurt."
            r.mine == big && r.damageToOpp > 0 -> "Big hit."
            r.theirs == big && r.damageToMe > 0 -> "Big ouch."
            r.mine == r.theirs && r.damageToMe > 0 -> "Trade."
            else -> ""
        })
        if (r.after.myL == 1 && r.before.myL > 1) sb.append(" Last life.")
        return sb.toString().trim()
    }

    /** The coached narration (level 3, the full game): the result, a life change only when life changed, and two energy remarks. */
    fun narration(r: com.faka.invisible.tell.Round, lowEnergy: Boolean = false, chargeReady: Boolean = false): String {
        val sb = StringBuilder(result(r))
        if (r.damageToMe > 0) sb.append(" Your life ").append(n(r.after.myL)).append('.')
        if (r.damageToOpp > 0) sb.append(" Their life ").append(n(r.after.oppL)).append('.')
        if (chargeReady) sb.append(" Charge ready.") else if (lowEnergy) sb.append(" Low energy.")
        return sb.toString()
    }
}

/** Fixed sentences of the Write program (the stereo-pair keyboard, PLAN-OS.md). At most 7 words each. */
object TypeVoice {
    const val START = "Write. Click on the first word."
    const val START2 = "Hold on the second word."
    const val START3 = "Three clicks for more."
    const val MENU_DONE = "Done"
    const val MENU_DELETE = "Delete"
    const val NO_MODEL = "Write could not start"
    const val EMPTY = "Nothing written"
    const val DELETED = "Deleted"
    const val LETTERS = "Letters"
    const val WORDS = "Words"
    const val CHECKING = "Pick the left or the right"
    /** Pacing of the two words: ONE place, tune here. Slow, clear, a long gap (the ear needs time to take a word in). */
    const val WORD_RATE = 0.75f
    const val PAIR_GAP_MS = 800
}
