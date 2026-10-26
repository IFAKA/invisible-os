@file:OptIn(KitOnly::class)

package com.faka.invisible.voicekit

import com.faka.invisible.logickit.Calculator


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

    /** A Find range as it is read: "A to C", or just "W" when both ends are the same. */
    fun range(from: String, to: String): String = if (from == to) from else "$from to $to"

    /** The rest of one Find letter after its most used apps: "Other C". */
    fun otherLetter(letter: String): String = "Other $letter"

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
    val STILL_WORKING = f("Still working.")   // said once by Narrator.waiting after 10 s
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
    val WHATS_PLAYING = f("What's playing")
    val PLAY_SOMETHING = f("Play something")
    val RADIO = f("Radio from this song")
    val SEARCH_SONG = f("Search a song")
    val NO_SONG = f("No song found")
    val PLAY_MIX = f("Play my mix")
    val PLAY_LIKED = f("Play liked music")
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
