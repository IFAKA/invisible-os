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
    val HINT get() = f("Click when you hear it. Two clicks: back one. Three clicks: go up.")
    val DIAL_HINT get() = f("Hold: quieter, any press stops. Click: louder.")   // said while learning
    val FIND_HINT get() = f("Find. Pick the first letter.")   // said the first time only
    val GESTURE_HELP get() = f("Click when you hear the option. 2 clicks: back one. 3 clicks: go up. 4 clicks: leave. Click, then hold: exit.")
    fun timerSet(minutes: Int) = f("Timer $minutes minutes")
    val TIMER_DONE get() = f("Timer done")
    // calls
    fun callFrom(name: String) = Phrase.mixed("Call from $name")
    val CALL_ENDED get() = f("Call ended")
    val CALL_ANSWERED get() = f("In call")
    val CALL_FAILED get() = f("The call did not work")
    val NO_CONTACTS get() = f("No contacts to call")
    fun calling(name: String) = Phrase.mixed("Calling $name")
    val EMERGENCY_ASK get() = f("Call the emergency number?")
    val EMERGENCY_CALLING get() = f("Calling the emergency number")
    val EMERGENCY_TEXTED get() = f("Your emergency contact got a message")
    val ALARM_STOPPED get() = f("Alarm stopped")
    fun flashlight(on: Boolean) = f("Flashlight " + if (on) "on" else "off")
    fun battery(percent: Int) = f("Battery $percent percent")
    fun doNotDisturb(on: Boolean) = f("Do not disturb " + if (on) "on" else "off")
    fun tasksAdded(n: Int) = f(when (n) { 0 -> "No new open tasks"; 1 -> "1 task added"; else -> "$n tasks added" })
    val ALL_DONE get() = f("All done")
    val TASK_ADDED get() = f("Task added")
    /** A private notification: the app only, never the content. */
    fun newNotification(app: String) = f("$app, new notification")
    /** A private chat message: the app only, never the content. */
    fun newMessage(app: String) = f("$app, new message")
    /** A chat message arrives: who wrote, in which app (the text is read after it, in its own language). */
    fun arrivalFrom(app: String, sender: String) = Phrase.mixed("$app, $sender")
    /** A laptop window finished while the phone was idle: its name only; the full message is read when the room is opened. */
    fun roomDone(name: String) = f("$name, done")
    fun roomsDone(name: String, more: Int) = f("$name, done. $more more windows done")
    /** The phone now listens to this computer (after a switch). */
    fun listening(computer: String) = f("$computer, listening")
    val NOTIF_DONE get() = f("Done")
    val NOTIF_FAILED get() = f("It did not work")
    val APP_MUTED get() = f("App muted")
    val APPS_UNMUTED get() = f("Apps unmuted")
    val TASK_STARTERS get() = listOf("Buy groceries", "Call someone", "Pay a bill", "Send an email", "Clean up")   // stored as tasks, not spoken here
    val NO_MESSAGES get() = f("No unread messages")
    val CANNOT get() = f("Cannot do that")
    val CANCELLED get() = f("Cancelled")
    val SENDING get() = f("Sending. Click to cancel")
    val STARTING get() = f("Starting. Click to cancel")
    val TASK_DONE_UNDO get() = f("Done. Click to cancel")
    val NOTHING get() = f("Nothing")
    val NO_ALARM get() = f("No alarm")
    fun calc(spoken: String) = f(spoken)
    val ALARM_HOUR get() = f("Which hour?")
    val ALARM_MINUTE get() = f("Which minute?")
    fun settingAlarm(h24: Int, minute: Int) = f("Alarm " + Spoken.time(h24, minute) + ". Click to cancel")
    val ALARM_RINGING get() = f("Alarm. Wake up")
    val ALARM_FAILED get() = f("Alarm not set")
    fun alarmAt(h24: Int, minute: Int) = f("Alarm " + Spoken.time(h24, minute))
    fun time(h24: Int, minute: Int) = f(Spoken.time(h24, minute))
    /** The volume level as a word. */
    fun level(n: Int) = f(Spoken.number(n, false))
    fun status(s: Status, unread: Int) = f(Spoken.status(s, unread))

    // ---- setup (said when a step appears; the screen shows the same words) ----
    /** "Step 2 of 12. Notification access. Double tap Turn on." [action] = the button of the step, or null when the step only waits for the button. */
    fun setupStep(n: Int, total: Int, title: String, action: String?) =
        f("Step $n of $total. $title. " + if (action != null) "Double tap $action." else "Hold it for one second.")
    val BUTTON_ACCESS_OFF get() = f("Button access is off.")

    // ---- weather, calendar, find my phone ----
    val NEED_LOCATION get() = f("Weather needs location. Open the app.")
    val WEATHER_FAILED get() = f("No weather now.")
    val STILL_WORKING get() = f("Still working.")   // said once by Narrator.waiting after 10 s
    val NEED_CALENDAR get() = f("Today needs the calendar. Open the app.")
    val NEED_NOTIFICATIONS get() = f("Messages needs notification access. Open the app.")
    val NEED_CONTACTS get() = f("Phone needs contacts access. Open the app.")
    val NEED_CALLS get() = f("Phone needs call access. Open the app.")
    val CALENDAR_FAILED get() = f("Cannot read the calendar.")
    val NO_EVENTS get() = f("No events today.")
    val EVENT_UNNAMED = "Event"   // the title of an event that has none (content, spoken inside eventNow/eventLater)
    val FIND_PHONE get() = f("Find my phone")
    val NO_NAVIGATION get() = f("No navigation.")
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
    /** A group or OS program name in the tree (an English label, possibly with the unread count): said in the menu language. */
    fun treeName(label: String) = Phrase.mixed(label)
    /** What the number after a name counts, in words ("WhatsApp, 2 notifications"): a bare number never says what it counts. */
    fun countText(n: Int) = if (n == 1) "1 notification" else "$n notifications"
    fun treeCount(label: String, n: Int) = Phrase.mixed("$label, ${countText(n)}")
    /** A live app at the front of home: "Music, playing", "Music, paused", "Workout, live". */
    fun liveState(app: String, state: String) = Phrase.mixed("$app, $state")
    val MESSAGES get() = f("Messages")
    fun messages(unread: Int) = f("Messages, $unread unread")
    val DISMISS get() = f("Dismiss")
    val MUTE_APP get() = f("Mute this app")
    val UNMUTE_ALL get() = f("Unmute all apps")
    val MUSIC get() = f("Music")
    val WHATS_PLAYING get() = f("What's playing")
    val PLAY_SOMETHING get() = f("Play something")
    val RADIO get() = f("Radio from this song")
    val SEARCH_SONG get() = f("Search a song")
    val NO_SONG get() = f("No song found")
    val PLAY_MIX get() = f("Play my mix")
    val PLAY_LIKED get() = f("Play liked music")
    val PLAYING_MUSIC get() = f("Playing music")
    fun tasks(n: Int) = f(if (n == 0) "Tasks, none" else "Tasks, $n")
    val TIMER get() = f("Timer")
    val TODAY get() = f("Today")
    val PHONE get() = f("Phone")
    val ANSWER get() = f("Answer")
    val REJECT get() = f("Reject")
    val END_CALL get() = f("End call")
    val CALL_CONTACT get() = f("Call a contact")
    val EMERGENCY get() = f("Emergency")
    val EMERGENCY_NO get() = f("No, go back")
    val EMERGENCY_YES get() = f("Yes, call now")
    val GAMES get() = f("Games")
    val ADD_TASK get() = f("Add task")
    val MORE_TASKS get() = f("More tasks")
    val LAST_USED get() = f("Last used")
    fun minutes(n: Int) = f(Spoken.minutes(n))
    val LOUDER get() = f("Louder")
    val QUIETER get() = f("Quieter")
    val DONE get() = f("Done")
    val WEATHER get() = f("Weather")
    val SAY_WEATHER get() = f("Say the weather")
    val NEXT_EVENT get() = f("Next event")
    val NEXT_TURN get() = f("Next turn")
    val BACK get() = f("Back")
    val OPEN_APP get() = f("Open app")
    val STATUS get() = f("Status")
    val VOLUME get() = f("Volume")
    val TORCH get() = f("Flashlight")
    val DND get() = f("Do not disturb")
    val CALCULATOR get() = f("Calculator")
    val SET_ALARM get() = f("Set alarm")
    fun digit(n: Int) = f(Spoken.number(n).replaceFirstChar { it.uppercase() })
    fun op(o: Char) = f(Calculator.WORDS.getValue(o).replaceFirstChar { it.uppercase() })
    val POINT get() = f("Point")
    val EQUALS get() = f("Equals")
    val UNDO get() = f("Undo")
    val CLEAR get() = f("Clear")
    /** "7 AM", "12 PM", "12 AM". */
    fun hour(h24: Int) = f("${(h24 % 12).let { if (it == 0) 12 else it }} ${if (h24 < 12) "AM" else "PM"}")
    fun minute(m: Int) = f(if (m == 0) "Sharp" else Spoken.number(m).replaceFirstChar { it.uppercase() })
    val STOP get() = f("Stop")
    val FIVE_MORE get() = f("Five more minutes")
    val IGNORE get() = f("Ignore")
    val READ_ALL get() = f("Read all")
    val COMPOSE get() = f("Compose")
    val TASK get() = f("Task")
    val ASK_DETAILS get() = f("Ask details")
    val NO get() = f("No")
    val SKIP get() = f("Skip")
    val MORE get() = f("More")
    fun replyYes(final: Boolean) = f(if (final) "Reply okay" else "Reply yes")
    /** "Reply to Ana" (the name is content). */
    fun replyTo(sender: String) = f("Reply to $sender")
}
