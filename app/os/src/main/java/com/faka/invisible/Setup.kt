package com.faka.invisible

/** What the phone says is on, collected by the setup screen. Plain values so the checklist logic is testable. */
data class SetupState(
    val button: Boolean, val buttonTested: Boolean, val notifications: Boolean, val assistant: Boolean,
    val postNotifications: Boolean, val calendar: Boolean, val location: Boolean, val usage: Boolean,
    val sdk: Int, val skipped: Set<String> = emptySet(),
    val contacts: Boolean = false, val calls: Boolean = false, val sms: Boolean = false, val callLog: Boolean = false,
)

/** One line of the setup checklist. [optional] steps can be skipped; a skipped step counts as passed. */
data class SetupStep(val id: String, val title: String, val reason: String, val done: Boolean, val optional: Boolean, val skipped: Boolean = false) {
    val passed get() = done || skipped
}

/** The first-run checklist: pure, in the order a new user should do it. */
object Setup {
    fun steps(s: SetupState): List<SetupStep> = buildList {
        add(SetupStep("button", "Button access", "So the app can hear your earbud button.", s.button, false))
        add(SetupStep("test", "Hold your earbud button", "Hold your earbud button. This screen will say when it hears you.", s.buttonTested, false))
        add(SetupStep("notif", "Notification access", "So it can read your new messages to you.", s.notifications, true))
        add(SetupStep("assist", "Assistant", "So your earbud hold opens it. In the earbud app set the right earbud: one tap Play/Pause, two taps Next song, three taps Previous song, hold Voice assistant.", s.assistant, true))
        if (s.sdk >= 33) add(SetupStep("post", "Show notifications", "So the app can show that it is listening.", s.postNotifications, true))
        add(SetupStep("calendar", "Calendar", "So it can tell you your next event.", s.calendar, true))
        add(SetupStep("location", "Location", "So it can tell you the weather where you are.", s.location, true))
        add(SetupStep("contacts", "Contacts", "So it can say who is calling and let you call a contact.", s.contacts, true))
        add(SetupStep("calls", "Phone calls", "So you can answer, reject, end and start calls with the button.", s.calls, true))
        add(SetupStep("calllog", "Call history", "So your most-called people come first.", s.callLog, true))
        add(SetupStep("sms", "Text messages", "So Emergency can text your emergency contact.", s.sms, true))
        add(SetupStep("usage", "Usage access", "Better order of apps: the ones you use most come first.", s.usage, true))
    }.map { it.copy(skipped = !it.done && it.optional && it.id in s.skipped) }

    /** First run: ONLY the required steps (Button access, then holding the button). Every other permission is asked when a feature first needs it. */
    fun firstRun(steps: List<SetupStep>) = steps.filter { !it.optional }

    /** Index of the step to do now, or -1 when all are passed. */
    fun current(steps: List<SetupStep>) = steps.indexOfFirst { !it.passed }

    /**
     * The one optional step offered on the app screen once the user is ready (never blocks): the Assistant after the practice
     * was heard, then Usage access. A skipped or done step is not offered again. Null = nothing to offer.
     */
    fun later(steps: List<SetupStep>, practiceDone: Boolean): SetupStep? {
        val order = if (practiceDone) listOf("assist", "usage") else emptyList()
        return order.mapNotNull { id -> steps.firstOrNull { it.id == id } }.firstOrNull { !it.passed }
    }

    fun label(index: Int, total: Int) = "Step ${index + 1} of $total"

    /** The permission asked by a runtime step, or null when the step opens a Settings page. */
    val PERMISSION_STEPS = setOf("post", "calendar", "location", "contacts", "calls", "calllog", "sms")

    /** Everything a new user must do is done (optional steps do not count). */
    fun ready(steps: List<SetupStep>) = steps.filter { !it.optional }.all { it.done }
}
