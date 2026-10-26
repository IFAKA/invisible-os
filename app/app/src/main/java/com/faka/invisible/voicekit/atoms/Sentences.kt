@file:OptIn(KitOnly::class)

package com.faka.invisible.voicekit

import com.faka.invisible.logickit.Calculator

import com.faka.invisible.tell.Move
import com.faka.invisible.tell.Round

/** The only way to make a fixed sentence. Private to this file: fixed sentences exist only here. */
private fun f(text: String) = Phrase.fixed(text)

/** Raw numbers for the STATUS sentence. alarm* = next alarm (24 h) or null. */
data class Status(val hour: Int, val minute: Int, val battery: Int, val alarmHour: Int? = null, val alarmMinute: Int? = null)

/**
 * Pure spoken-text helpers (plain strings; the sentence objects below wrap them into [Phrase]).
 */
object Spoken {
    private val en = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "twenty one", "twenty two", "twenty three", "twenty four",
        "twenty five", "twenty six", "twenty seven", "twenty eight", "twenty nine", "thirty")
    private val es = listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
        "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
        "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve", "treinta")

    /** A Find range as it is read: "Sa to Sp", or just "W" when both ends are the same. */
    fun range(from: String, to: String): String = if (from == to) from else "$from to $to"

    /** "Five minutes", "One minute", "90 minutes". */
    fun minutes(n: Int): String = number(n).replaceFirstChar { it.uppercase() } + if (n == 1) " minute" else " minutes"

    /** A small number as a word (digits above 30). */
    fun number(n: Int, spanish: Boolean = false): String = (if (spanish) es else en).getOrNull(n) ?: n.toString()

    private fun clock(h24: Int, m: Int) = "${(h24 % 12).let { if (it == 0) 12 else it }} ${"%02d".format(m)} ${if (h24 < 12) "AM" else "PM"}"

    /** "5 03 PM" as the phone says the time. */
    fun time(h24: Int, m: Int) = clock(h24, m)

    /** ONE sentence: time, battery, unread messages, next alarm if any. */
    fun status(s: Status, unread: Int): String {
        val parts = mutableListOf(clock(s.hour, s.minute), "battery ${s.battery} percent",
            when (unread) { 0 -> "no unread messages"; 1 -> "1 unread message"; else -> "$unread unread messages" })
        if (s.alarmHour != null && s.alarmMinute != null) parts += "next alarm ${clock(s.alarmHour, s.alarmMinute)}"
        return parts.joinToString(", ")
    }
}

/**
 * Audio Kit: every fixed sentence the phone says (English menu).
 * Sounds live in Earcons.kt (one Tone list); falling = list ended, rising = starts over.
 * Change a sentence here and it changes everywhere.
 */
object Voice {
    // Button rules, same on every screen (the one `when` in ControllerMode.onGesture):
    // idle:    1 = play/pause, 2 = next, 3 = previous, hold = wake
    // in a list: hold = fast scroll (first words), release = that option in full; elsewhere a plain hold does nothing
    // in menu: 1 = pick the option just spoken, 2 = one back, 3 = back, 4 = leave, click then hold = exit; any click cancels a pending send
    val HINT = f("Click when you hear it. Two clicks: next. Three clicks: go back.")
    val FIND_HINT = f("Find. Pick the first letter.")   // said the first time only
    val GESTURE_HELP = f("In the menu: click when you hear the option. 2 = one back, 3 = back, 4 = leave. Click then hold = exit.")
    fun timerSet(minutes: Int) = f("Timer $minutes minutes")
    val TIMER_DONE = f("Timer done")
    val ALARM_STOPPED = f("Alarm stopped")
    fun flashlight(on: Boolean) = f("Flashlight " + if (on) "on" else "off")
    fun battery(percent: Int) = f("Battery $percent percent")
    fun doNotDisturb(on: Boolean) = f("Do not disturb " + if (on) "on" else "off")
    fun tasksAdded(n: Int) = f(when (n) { 0 -> "No new open tasks"; 1 -> "1 task added"; else -> "$n tasks added" })
    val ALL_DONE = f("All done")
    val TASK_ADDED = f("Task added")
    /** A private notification: the app only, never the content. */
    fun newNotification(app: String) = f("$app, new notification")
    val NOTIF_DONE = f("Done")
    val NOTIF_FAILED = f("It did not work")
    val APP_MUTED = f("App muted")
    val APPS_UNMUTED = f("Apps unmuted")
    val TASK_STARTERS = listOf("Buy groceries", "Call someone", "Pay a bill", "Send an email", "Clean up")   // stored as tasks, not spoken here
    val NO_MESSAGES = f("No unread messages")
    val CANNOT = f("Cannot do it")
    val CANCELLED = f("Cancelled")
    val SENDING = f("Sending. Click to cancel")
    val STARTING = f("Starting. Click to cancel")
    val TASK_DONE_UNDO = f("Done. Click to cancel")
    val NOTHING = f("Nothing")
    val NO_ALARM = f("No alarm")
    fun calc(spoken: String) = f(spoken)
    val ALARM_HOUR = f("Which hour?")
    val ALARM_MINUTE = f("Which minute?")
    fun settingAlarm(h24: Int, minute: Int) = f("Alarm " + Spoken.time(h24, minute) + ". Click to cancel")
    val ALARM_RINGING = f("Alarm. Wake up")
    val ALARM_FAILED = f("Alarm not set")
    fun alarmAt(h24: Int, minute: Int) = f("Alarm " + Spoken.time(h24, minute))
    fun time(h24: Int, minute: Int) = f(Spoken.time(h24, minute))
    /** The volume level as a word. */
    fun level(n: Int) = f(Spoken.number(n, false))
    fun status(s: Status, unread: Int) = f(Spoken.status(s, unread))

    // ---- weather, calendar, find my phone ----
    val NEED_LOCATION = f("Open the app and allow location.")
    val WEATHER_FAILED = f("No weather now.")
    val NEED_CALENDAR = f("Open the app and allow the calendar.")
    val CALENDAR_FAILED = f("Cannot read the calendar.")
    val NO_EVENTS = f("No events today.")
    val EVENT_UNNAMED = "Event"   // the title of an event that has none (content, spoken inside eventNow/eventLater)
    val FIND_PHONE = f("Find my phone")
    val NO_NAVIGATION = f("No navigation.")
    /** "Now 18 degrees, rain in 2 hours." [rainInHours]: 0 = raining now, null = none soon. */
    fun weather(degrees: Int, rainInHours: Int?): Phrase {
        val t = if (degrees < 0) "minus ${-degrees}" else "$degrees"
        val rain = when (rainInHours) { null -> "no rain soon"; 0 -> "raining now"; 1 -> "rain in 1 hour"; else -> "rain in $rainInHours hours" }
        return f("Now $t degrees, $rain.")
    }
    fun eventNow(title: String) = f("$title, now.")
    /** "Dentist at 4 PM, in 40 minutes." */
    fun eventLater(title: String, hour24: Int, minute: Int, inMinutes: Int): Phrase {
        val h = (hour24 % 12).let { if (it == 0) 12 else it }
        val at = "$h" + (if (minute == 0) "" else ":%02d".format(minute)) + if (hour24 < 12) " AM" else " PM"
        val h2 = inMinutes / 60; val m2 = inMinutes % 60
        val inText = buildList {
            if (h2 > 0) add(if (h2 == 1) "1 hour" else "$h2 hours")
            if (m2 > 0 || h2 == 0) add(if (m2 == 1) "1 minute" else "$m2 minutes")
        }.joinToString(" ")
        return f("$title at $at, in $inText.")
    }

    /** The end of a long text: cut it and say there is more, in the text's own language. */
    fun cut(text: String, max: Int, spanish: Boolean, lang: String?): Phrase {
        if (text.length <= max) return Phrase.content(text, lang)
        return Phrase.content(text.take(max).trimEnd() + (if (spanish) " y más" else " and more"), lang)
    }
}

/** Labels of the menu: what the phone says for each option. English, short. */
object Menu {
    fun messages(unread: Int) = f("Messages, $unread unread")
    val DISMISS = f("Dismiss")
    val MUTE_APP = f("Mute this app")
    val UNMUTE_ALL = f("Unmute all apps")
    val MUSIC = f("Music")
    val PLAYING_MUSIC = f("Playing music")
    fun tasks(n: Int) = f(if (n == 0) "Tasks, none" else "Tasks, $n")
    val TIMER = f("Timer")
    val TODAY = f("Today")
    val PHONE = f("Phone")
    val GAMES = f("Games")
    val ADD_TASK = f("Add task")
    val MORE_TASKS = f("More tasks")
    val LAST_USED = f("Last used")
    fun minutes(n: Int) = f(Spoken.minutes(n))
    val LOUDER = f("Louder")
    val QUIETER = f("Quieter")
    val DONE = f("Done")
    val WEATHER = f("Weather")
    val NEXT_EVENT = f("Next event")
    val NEXT_TURN = f("Next turn")
    val BACK = f("Back")
    val OPEN_APP = f("Open app")
    val STATUS = f("Status")
    val VOLUME = f("Volume")
    val TORCH = f("Torch")
    val DND = f("Do not disturb")
    val CALCULATOR = f("Calculator")
    val SET_ALARM = f("Set alarm")
    fun digit(n: Int) = f(Spoken.number(n).replaceFirstChar { it.uppercase() })
    fun op(o: Char) = f(Calculator.WORDS.getValue(o).replaceFirstChar { it.uppercase() })
    val POINT = f("Point")
    val EQUALS = f("Equals")
    val UNDO = f("Undo")
    val CLEAR = f("Clear")
    /** "7 AM", "12 PM", "12 AM". */
    fun hour(h24: Int) = f("${(h24 % 12).let { if (it == 0) 12 else it }} ${if (h24 < 12) "AM" else "PM"}")
    fun minute(m: Int) = f(if (m == 0) "Sharp" else Spoken.number(m).replaceFirstChar { it.uppercase() })
    val DUEL = f("Duel")
    val ECHO = f("Echo")
    val WRITE = f("Write")
    val COMPASS = f("Compass")
    val HOW_TO_PLAY = f("How to play")
    val STOP = f("Stop")
    val FIVE_MORE = f("Five more minutes")
    val IGNORE = f("Ignore")
    val READ_ALL = f("Read all")
    val COMPOSE = f("Compose")
    val TASK = f("Task")
    val ASK_DETAILS = f("Ask details")
    val NO = f("No")
    val SKIP = f("Skip")
    val MORE = f("More")
    fun replyYes(final: Boolean) = f(if (final) "Reply okay" else "Reply yes")
    /** "Reply to Ana" (the name is content). */
    fun replyTo(sender: String) = f("Reply to $sender")
}

/**
 * Spoken sentences of the Duel coach. Plain English, at most 7 words per sentence, one idea each.
 * Pacing (how long a person needs to take a sentence in) is in Pacing.kt.
 */
object EchoVoice {
    val INTRO = f("Echo. Listen, then copy.")
    val LEAVE = f("Hold three seconds to leave.")
    val AGAIN = f("Again.")
    val LIFE_BACK = f("Life back.")
    private val WORDS = listOf("No", "One", "Two", "Three", "Four", "Five")
    fun lives(n: Int) = f(if (n == 1) "Last life." else "${WORDS[n.coerceIn(0, 5)]} lives.")
    val LOADING = f("One moment.")
    fun level(n: Int) = f("Level $n.")
    fun over(score: Int, best: Int) = f(if (score >= best && score > 0) "Game over. New best, $score." else "Game over. Score $score. Best $best.")
    val ALL: List<Phrase> = listOf(INTRO, LEAVE, AGAIN, LOADING, LIFE_BACK) + (1..5).map { lives(it) }
}

object CompassVoice {
    val INTRO = f("Compass. Click: heading. Two clicks: find north.")
    val LEAVE = f("Hold three seconds to leave.")
    val FINDING = f("Finding north.")
    val STOPPED = f("Stopped.")
    val WAIT = f("One moment.")
    val NO_SENSOR = f("This phone has no compass.")
    val UNSURE = f("Not sure. Move the phone in a figure eight.")
    fun heading(name: String, degrees: Int) = f("$name, $degrees degrees.")
}

object TellVoice {
    // ---- markers inside a step's lines: a sound instead of a sentence ----
    val CLICK1 = f("<click1>")
    val CLICK2 = f("<click2>")
    val NOTES = f("<notes>")

    // ---- the levels: short teaching before each real match ----
    val L1_TAP = listOf(f("Level one. Jab and guard."), f("To leave, hold three seconds."), f("Tap to jab."))
    val L1_GUARD = listOf(f("Listen for a click."), CLICK1, f("Hold to the click to guard."))
    val L1_RULE = listOf(f("Guard beats jab."))
    val L2_CHARGE = listOf(f("Level two. Charge."), f("Hold longer."), f("Two clicks."), CLICK2, f("Let go after the second."))
    val L2_RULE = listOf(f("Charge beats guard."), f("Jab beats charge."), f("No charge twice in a row."))
    val L3_ENERGY = listOf(f("Level three. Energy."), f("Charge costs three."), f("Listen."), NOTES, f("More energy, higher note."), f("You get one back each round."))
    val DONE = listOf(f("Jab."), f("Guard."), f("Charge."))
    val NOT_AGAIN = f("Not again.")
    val SKIP = f("Let's move on.")
    val LEAVE_HINT = f("Hold three seconds to leave.")
    fun hint(tapStep: Boolean) = f(if (tapStep) "Press to continue." else "Press the button.")
    private val NAMES = listOf("jab", "guard", "charge")
    /** Every sentence of the practice (the markers are sounds). Each is at most 7 words. */
    val ALL: List<Phrase> = (L1_TAP + L1_GUARD + L1_RULE + L2_CHARGE + L2_RULE + L3_ENERGY +
        DONE + listOf(SKIP, LEAVE_HINT, hint(true), hint(false), NOT_AGAIN, f("Last life."), f("Their last life.")) +
        (0..2).flatMap { w -> (0..2).map { l -> wrong(l, w) } }.flatMap { it }).filter { it != CLICK1 && it != CLICK2 && it != NOTES }

    /** Feedback for a wrong hold: what it was, then what to do. Two sentences, spoken with a pause between. */
    fun wrong(level: Int, want: Int): List<Phrase> = listOf(f("That was a ${NAMES[level]}."), f(when (want) {
        0 -> "Tap quickly."; 1 -> "Hold for the first click."; else -> "Wait for the second click."
    }))

    private val NUM = listOf("zero", "one", "two", "three", "four", "five")
    fun duelRung(rung: Int) = f("Duel. Rung ${NUM[rung.coerceIn(0, 5)]}.")
    val LOADING = f("Loading the duel")
    val COULD_NOT_START = f("Duel could not start")

    private fun base(m: Move) = when (m) { Move.JAB -> "jab"; Move.GUARD -> "guard"; Move.CHARGE -> "charge" }
    private fun n(x: Int) = NUM[x.coerceIn(0, 5)]

    /** The result of one round, at most 5 words. */
    fun result(r: Round): Phrase {
        if (r.mine == r.theirs) return f("Both ${base(r.mine)}. " + if (r.damageToMe > 0) "Both hurt." else "Nothing.")
        val tail = when {
            r.myParry -> "Parry."
            r.theirParry -> "Parried."
            r.myChargeFizzled -> "Fizzle."
            r.theirChargeFizzled -> "Fizzle."
            r.damageToOpp > 0 -> "Hit."
            r.damageToMe > 0 -> "Ouch."
            else -> "Nothing."
        }
        return f("They ${base(r.theirs)}. You ${base(r.mine)}. $tail")
    }

    /** Level 1 (jab and guard): one word, and a warning at the last life. Empty = say nothing. */
    fun narrationL1(r: Round): Phrase {
        val sb = StringBuilder(when {
            r.myParry -> "Parry."
            r.theirParry -> "Ouch."
            r.mine == r.theirs && r.damageToMe > 0 -> "Trade."
            else -> ""
        })
        if (r.after.myL == 1 && r.before.myL > 1) sb.append(" Last life.")
        if (r.after.oppL == 1 && r.before.oppL > 1) sb.append(" Their last life.")
        return f(sb.toString().trim())
    }

    /** Level 2 (charge): one to three words. */
    fun narrationL2(r: Round): Phrase {
        val big = Move.CHARGE
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
        return f(sb.toString().trim())
    }

    /** The coached narration (level 3, the full game): the result, a life change only when life changed, and two energy remarks. */
    fun narration(r: Round, lowEnergy: Boolean = false, chargeReady: Boolean = false): Phrase {
        val sb = StringBuilder(result(r).text)
        if (r.damageToMe > 0) sb.append(" Your life ").append(n(r.after.myL)).append('.')
        if (r.damageToOpp > 0) sb.append(" Their life ").append(n(r.after.oppL)).append('.')
        if (chargeReady) sb.append(" Charge ready.") else if (lowEnergy) sb.append(" Low energy.")
        return f(sb.toString())
    }

    // ---- the end of a match ----
    val WIN = f("You win.")
    val LOSE = f("You lose.")
    val DRAW = f("Draw.")
    val PLAY_AGAIN = f("Tap to play again. Hold to leave.")
    /** After a match: the result word, what the mistake cost (content computed by the game), and the way on. Empty parts are left out. */
    fun matchEnd(word: Phrase, mistake: Phrase): Phrase =
        f(listOf(word.text, mistake.text, PLAY_AGAIN.text).filter { it.isNotEmpty() }.joinToString(" "))
    /** "Not again." in front of a narration. */
    fun notAgainThen(text: Phrase): Phrase = f((NOT_AGAIN.text + " " + text.text).trim())
    /** Three taps: the score and your moves. */
    fun summary(rounds: Int, myLife: Int?, oppLife: Int?, jabs: Int, guards: Int, charges: Int): Phrase {
        val life = if (myLife != null && oppLife != null) " Life $myLife to $oppLife." else ""
        return f("$rounds rounds.$life Jab $jabs, guard $guards, charge $charges.")
    }
}

/** Fixed sentences of the Write program (the stereo-pair keyboard, PLAN-OS.md). At most 7 words each. */
object TypeVoice {
    val START = f("Write. Click on the first word.")
    val START2 = f("Hold on the second word.")
    val START_NEXT = f("Two clicks for the next two.")
    val START3 = f("Three clicks for more.")
    val MENU_DONE = f("Done")
    val MENU_DELETE = f("Delete")
    val PHRASES = f("Phrases")
    val DIGITS = f("Digits")
    val PASSWORD = f("Password field. Not written.")
    val NO_MODEL = f("Write could not start")
    val EMPTY = f("Nothing written")
    val CONFIRM = f("Click to send. Hold to edit.")
    val TRANSLATION_MISSING = f("Translation not ready. Nothing sent.")
    val TRANSLATION_FAILED = f("Translation failed. Nothing sent.")
    val LETTERS = f("Letters")
    val WORDS = f("Words")
    val WORDS_WITH = f("Words with")
    /** "Words with a b c": the letters chosen so far, spoken one by one. */
    fun wordsWith(prefix: String) = f(WORDS_WITH.text + " " + prefix.toCharArray().joinToString(" "))
}
