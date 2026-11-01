package com.faka.invisible

/** The app's home, zone 1: ONE sentence for the state of the OS, and the ONE fix when something is missing. Pure. */
object HomeStatus {
    /** [fixId] is the setup step id (or "on") the single button runs; null = nothing to fix. */
    data class Line(val text: String, val fixLabel: String?, val fixId: String?, val problem: Boolean)

    const val WORKING = "Working. Hold your earbud button."

    /** [running] = the button service is on; [buttonAccess] = the accessibility service is connected; [notifications] = notification access; [need] = a feature asked for a permission (sentence, button, id). */
    fun line(running: Boolean, buttonAccess: Boolean, notifications: Boolean, need: Triple<String, String, String>? = null): Line = when {
        !buttonAccess -> Line("Button access is off.", "Turn on", "button", true)
        !running -> Line("Invisible OS is off.", "Turn on", "on", true)
        !notifications -> Line("Notification access is off.", "Turn on", "notif", true)
        need != null -> Line(need.first, need.second, need.third, true)
        else -> Line(WORKING, null, null, false)
    }
}
