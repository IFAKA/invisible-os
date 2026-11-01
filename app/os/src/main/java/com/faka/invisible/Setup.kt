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
        add(SetupStep("button", "Button access", "Tap Turn on. In the list tap Invisible OS, switch it on, tap Allow. Then come back here.", s.button, false))
        add(SetupStep("test", "Hold your earbud button", "Hold your earbud button for one second. Nothing else opens yet.", s.buttonTested, false))
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

    /** First run: the main feature only, in this order: Button access, the button test, Notification access (skippable). Every other permission comes later, one at a time ([later]). */
    val FIRST_RUN = listOf("button", "test", "notif")
    fun firstRun(steps: List<SetupStep>) = FIRST_RUN.mapNotNull { id -> steps.firstOrNull { it.id == id } }

    /** What to say when the person comes back from a Settings page. */
    enum class Back { DONE, REPEAT_TAPS, HINT_ONLY }
    /** [passed] = the permission is on now; [failures] = how many times they already came back without it. Taps are repeated once, then only the skip hint. */
    fun afterReturn(passed: Boolean, failures: Int) = if (passed) Back.DONE else if (failures == 0) Back.REPEAT_TAPS else Back.HINT_ONLY

    /** Index of the step to do now, or -1 when all are passed. */
    fun current(steps: List<SetupStep>) = steps.indexOfFirst { !it.passed }

    /**
     * The one optional step offered on the app screen once the user is ready (never blocks): the Assistant after the practice
     * was heard, then [LATER] in order. A skipped or done step is not offered again. Null = nothing to offer.
     */
    val LATER = listOf("assist", "post", "calendar", "location", "usage")
    fun later(steps: List<SetupStep>, practiceDone: Boolean): SetupStep? {
        val order = if (practiceDone) LATER else emptyList()
        return order.mapNotNull { id -> steps.firstOrNull { it.id == id } }.firstOrNull { !it.passed }
    }

    /** What a step's one big button says (the voice and the screen say the same thing). The "test" step has no Settings page: its button confirms the hold was heard. */
    fun control(id: String) = when (id) {
        "test" -> "Next"
        in PERMISSION_STEPS -> "Allow"
        else -> "Open settings"
    }

    /** The step as the screen shows it: the line (same words as the voice) and the control under it. */
    data class StepView(val line: String, val control: String, val skippable: Boolean)
    fun view(step: SetupStep, line: String) = StepView(line, control(step.id), step.optional)

    /** True when [list] (a colon-separated setting such as enabled_notification_listeners) names a component of exactly [pkg]. */
    fun listed(list: String?, pkg: String) = (list ?: "").split(':').any { it.substringBefore('/') == pkg }

    /** The button test counts as passed once the button works (a hold was heard, or the practice already ran). */
    fun buttonTested(button: Boolean, heardHold: Boolean, practiceDone: Boolean) = button && (heardHold || practiceDone)

    fun label(index: Int, total: Int) = "Step ${index + 1} of $total"

    /** The permission asked by a runtime step, or null when the step opens a Settings page. */
    val PERMISSION_STEPS = setOf("post", "calendar", "location", "contacts", "calls", "calllog", "sms")

    /** Everything a new user must do is done (optional steps do not count). */
    fun ready(steps: List<SetupStep>) = steps.filter { !it.optional }.all { it.done }
}
