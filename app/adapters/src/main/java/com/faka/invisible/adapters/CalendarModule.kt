package com.faka.invisible.adapters

import com.faka.invisible.oskit.*

/**
 * Google Calendar: an event notification is read as "<title>, in 20 min, <place>". The text looks like
 * "In 20 minutes · Room 2", "En 20 minutos • Sala 2" or "10:00 - 11:00 · Room 2". Dismiss and Open app are the
 * OS's own actions (the adapter has no actions of its own, so there is no snooze here).
 */
class CalendarModule : NotificationModule {
    companion object {
        const val PKG = "com.google.android.calendar"
        private val SPLIT = Regex("\\s*[·•\\n|]\\s*")
        private val IN = Regex("^(?:in|en|dentro de)\\s+(?:about\\s+|unos\\s+|aprox\\.?\\s+)?(\\d+)\\s*(min\\w*|h\\w*|hours?|horas?)\\b.*$", RegexOption.IGNORE_CASE)
        private val NOW = Regex("^(now|starting now|ahora|ya)$", RegexOption.IGNORE_CASE)
        private val CLOCK = Regex("^\\d{1,2}[:.]\\d{2}.*$")

        /** Pure: "<title>, in 20 min, <place>" from a title and the notification text. */
        fun phrase(title: String, text: String): String {
            val parts = text.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            var `when`: String? = null
            val rest = ArrayList<String>()
            for ((i, p) in parts.withIndex()) {
                val m = IN.matchEntire(p)
                when {
                    i == 0 && m != null -> {
                        val unit = m.groupValues[2].lowercase()
                        `when` = "in ${m.groupValues[1]} " + if (unit.startsWith("h")) "h" else "min"
                    }
                    i == 0 && NOW.matches(p) -> `when` = "now"
                    i == 0 && CLOCK.matches(p) -> `when` = "at " + p.substringBefore(" ").substringBefore("-").trim()
                    else -> rest.add(p)
                }
            }
            return (listOf(title.trim()) + listOfNotNull(`when`) + rest).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }

    override fun matches(pkg: String) = pkg == PKG
    override fun read(n: Notif): String = phrase(n.title, n.text)
}
