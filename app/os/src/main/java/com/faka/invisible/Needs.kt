package com.faka.invisible

/**
 * A permission asked at the moment of need: the feature says one short sentence, and the app screen shows one card with a
 * button to the right place. [id] is also the Setup step id that knows how to ask (MainActivity.doStep).
 */
data class Need(val id: String, val title: String, val reason: String, val action: String)

object Needs {
    const val PREF = "need_permission"

    private val all = listOf(
        Need("notif", "Messages needs notification access", "So it can read your new messages to you.", "Open settings"),
        Need("calendar", "Today needs the calendar", "So it can tell you your next event.", "Allow"),
        Need("location", "Weather needs your location", "So it can tell you the weather where you are.", "Allow"),
        Need("contacts", "Phone needs your contacts", "So you can call a contact and hear who is calling.", "Allow"),
        Need("calls", "Phone needs call access", "So you can start, answer and end calls with the button.", "Allow"),
    )

    fun info(id: String?) = all.firstOrNull { it.id == id }

    /** The need to show now: the stored one, unless it is already met (then null and it should be cleared). */
    fun pending(stored: String?, stillMissing: (String) -> Boolean): Need? = info(stored)?.takeIf { stillMissing(it.id) }

    /** Remember that a feature just found [id] missing (the card shows it next time the app screen is drawn). */
    fun record(ctx: android.content.Context, id: String) {
        ctx.getSharedPreferences("invisible", android.content.Context.MODE_PRIVATE).edit().putString(PREF, id).apply()
    }

    /** True while [id] is missing. */
    fun missing(ctx: android.content.Context, id: String): Boolean {
        fun g(p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when (id) {
            "notif" -> !(android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)
            "calendar" -> !g(android.Manifest.permission.READ_CALENDAR)
            "location" -> !g(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            "contacts" -> !g(android.Manifest.permission.READ_CONTACTS)
            "calls" -> !g(android.Manifest.permission.CALL_PHONE) || !g(android.Manifest.permission.ANSWER_PHONE_CALLS)
            else -> false
        }
    }
}
