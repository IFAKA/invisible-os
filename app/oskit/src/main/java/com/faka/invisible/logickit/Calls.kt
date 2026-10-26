package com.faka.invisible.logickit

enum class CallKind { INCOMING, ONGOING, NONE }

/** One person you can call. [times] = how often you called or were called (Android's times-contacted count). */
data class Contact(val name: String, val number: String, val starred: Boolean = false, val times: Int = 0)

/** Pure call logic: what a dialer notification means, who to list first, which number is the emergency number. */
object Calls {
    /** CallStyle values of `android.callType` (Android 12+). */
    const val TYPE_INCOMING = 1
    const val TYPE_ONGOING = 2

    /**
     * Reads a notification of a dialer. [category] = Notification.category, [callType] = extra `android.callType` (0 if missing),
     * [actions] = how many buttons it has (a ringing call has Answer and Decline, a running call has Hang up).
     */
    fun kind(category: String?, callType: Int, actions: Int): CallKind = when {
        category != "call" -> CallKind.NONE
        callType == TYPE_INCOMING -> CallKind.INCOMING
        callType == TYPE_ONGOING -> CallKind.ONGOING
        actions >= 2 -> CallKind.INCOMING
        else -> CallKind.ONGOING
    }

    /** The name to say: the dialer's title when it is a name, else the contact found for the number, else the number digit by digit. */
    fun callerName(title: String, resolved: String?): String {
        val t = title.squash()
        val r = resolved?.squash().orEmpty()
        return when {
            r.isNotEmpty() -> r
            t.isNotEmpty() && t.any { it.isLetter() } -> t
            t.isNotEmpty() -> spokenNumber(t)
            else -> "Unknown number"
        }
    }

    /** "+54 11 5555" -> "5 4 1 1 5 5 5 5": digits one by one, so the voice does not read a big number. */
    fun spokenNumber(n: String): String = n.filter { it.isDigit() }.toList().joinToString(" ")

    fun digits(n: String): String = n.filter { it.isDigit() || it == '+' }

    /** Starred first, then most called, then by name; one entry per number, only people with a number. */
    fun rank(all: List<Contact>, max: Int = 8): List<Contact> =
        all.filter { it.number.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { digits(it.number) }
            .sortedWith(compareByDescending<Contact> { it.starred }.thenByDescending { it.times }.thenBy { it.name.lowercase() })
            .take(max)

    private fun find(titles: List<String>, words: List<String>) = titles.indexOfFirst { t -> words.any { t.lowercase().contains(it) } }

    /** Which button of the ringing-call notification answers it (by its title, English or Spanish); -1 = none. */
    fun answerIndex(titles: List<String>): Int = find(titles, listOf("answer", "accept", "contestar", "responder", "aceptar"))

    /** Which button declines it; -1 = none. */
    fun declineIndex(titles: List<String>): Int = find(titles, listOf("decline", "reject", "dismiss", "rechazar", "declinar", "cancel"))

    private val EMERGENCY = mapOf(
        "US" to "911", "CA" to "911", "MX" to "911", "AR" to "911", "CL" to "133", "CO" to "123", "PE" to "105", "UY" to "911", "VE" to "171",
        "BR" to "190", "GB" to "999", "IE" to "999", "AU" to "000", "NZ" to "111", "JP" to "110", "CN" to "110", "KR" to "112", "IN" to "112",
        "ZA" to "112", "RU" to "112", "CH" to "112", "TR" to "112",
    )

    /** The emergency number for a two-letter country (any case); the EU number 112 works on most phones, so it is the fallback. */
    fun emergencyNumber(country: String?): String = EMERGENCY[country?.trim()?.uppercase()] ?: "112"

    /** The SMS to the emergency contact. */
    fun emergencyText(): String = "Emergency. I pressed the emergency button on my phone and called the emergency number. Please call me."
}
