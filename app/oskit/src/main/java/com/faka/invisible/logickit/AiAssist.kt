package com.faka.invisible.logickit

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
}
