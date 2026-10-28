package com.faka.invisible.logickit

import com.faka.invisible.oskit.Notif

/**
 * Pure rules for the two jobs the laptop's model does for notifications (bridge/ROOMS-API.md /ai/digest, /ai/short).
 * The model is optional and slow: the phone asks in the background, caches the answer, and uses it only when it still fits.
 */
object AiAssist {
    /** A single message longer than this many words gets a short version first. */
    const val LONG_WORDS = 40
    /** The bridge reads at most this many items for a digest. */
    const val MAX_DIGEST_ITEMS = 30

    fun isLong(text: String): Boolean = text.words().size > LONG_WORDS

    /** The cache key of a set of notifications: the same set (apps, titles, texts, and how many are private) gives the same key. */
    fun digestKey(items: List<Triple<String, String, String>>, hidden: Int): String =
        (items.map { "${it.first}\u0001${it.second}\u0001${it.third}" }.sorted() + "hidden=$hidden").joinToString("\u0002").hashCode().toString() + ":" + items.size + ":" + hidden

    /** True when a digest can be asked for: something to sort, and not more than the bridge reads. */
    fun digestable(items: Int): Boolean = items in 1..MAX_DIGEST_ITEMS

    /** The digest line with the private notifications added, so the numbers add up to all of them ("3 chats, 2 junk, 2 private"). */
    fun withPrivate(line: String, hidden: Int): String {
        val l = line.squash()
        if (l.isEmpty()) return ""
        return if (hidden > 0) "$l, $hidden private" else l
    }

    /**
     * The earlier messages of the conversation [current] belongs to (same app and same title or sender, older, newest [max]), oldest first,
     * one per line, about [chars] in all. Private notifications are never used, as context or as the message: "" when [current] is private.
     */
    fun earlier(current: Notif, all: List<Notif>, max: Int = 3, chars: Int = 1000): String {
        if (current.hidden) return ""
        val prior = all.filter { !it.hidden && it.key != current.key && it.pkg == current.pkg && it.title == current.title && it.at <= current.at && it.text.isNotBlank() }
            .sortedByDescending { it.at }.take(max)
        val lines = ArrayList<String>(); var left = chars
        for (n in prior) {
            if (left <= 0) break
            val t = n.text.squash()
            val line = if (current.title.isBlank()) t else current.title + ": " + t
            lines += line.take(left); left -= line.length + 1
        }
        return lines.reversed().joinToString("\n")
    }
}
