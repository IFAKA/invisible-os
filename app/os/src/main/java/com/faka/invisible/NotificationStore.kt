package com.faka.invisible

import com.faka.invisible.oskit.*

/** All the notifications of one group (by default one app), newest first. */
data class NotifGroup(val id: String, val app: String, val pkg: String, val items: List<Notif>)

/** Pure: which notifications must not be read aloud. */
object NotifPrivacy {
    const val VISIBILITY_SECRET = -1
    const val VISIBILITY_PRIVATE = 0

    /** Words in a package name that mark a bank, money or password app. */
    private val SENSITIVE = listOf(
        "bank", "banco", "finance", "fintech", "wallet", "password", "passwd", "authenticator", "otp", "vault",
        "paypal", "revolut", "mercadopago", "nubank", "binance", "coinbase", "bitwarden", "lastpass", "keepass", "1password", "dashlane", "keeper",
    )

    fun sensitivePackage(pkg: String): Boolean = pkg.lowercase().let { p -> SENSITIVE.any { it in p } }

    /**
     * True = say only "<App>, new notification". Secret always; private only while the phone is locked (its lock screen
     * would hide it too); a bank or password app always; a notification with no text of its own (the system hid it) also.
     */
    fun mustHide(pkg: String, visibility: Int, locked: Boolean, textHidden: Boolean): Boolean =
        visibility == VISIBILITY_SECRET || (visibility == VISIBILITY_PRIVATE && locked) || sensitivePackage(pkg) || textHidden
}

/** Pure: the apps the user muted. [save] persists the set. Muted apps are not counted and not announced. */
class MuteSet(initial: Set<String> = emptySet(), private val save: (Set<String>) -> Unit = {}) {
    private val set = HashSet(initial)
    @Synchronized fun isMuted(pkg: String) = pkg in set
    @Synchronized fun mute(pkg: String) { if (set.add(pkg)) save(set.toSet()) }
    @Synchronized fun unmuteAll() { if (set.isNotEmpty()) { set.clear(); save(emptySet()) } }
    @Synchronized fun all(): Set<String> = set.toSet()
}

/**
 * Pure: the "hotel mailbox". Every app's notifications, one per key (a new post with the same key replaces the old one),
 * capped. Reading is newest first; groups are ordered by their newest notification. Muted apps are left out of every read.
 */
class NotificationStore(private val max: Int = 100, private val muted: (String) -> Boolean = { false }) {
    private val list = ArrayList<Notif>()

    @Synchronized fun upsert(n: Notif) {
        list.removeAll { it.key == n.key }
        list.add(n)
        list.sortByDescending { it.at }
        while (list.size > max) list.removeAt(list.size - 1)
    }

    @Synchronized fun remove(key: String) { list.removeAll { it.key == key } }
    @Synchronized fun clear() = list.clear()
    @Synchronized fun get(key: String): Notif? = list.firstOrNull { it.key == key }

    /** All notifications of apps that are not muted, newest first. */
    @Synchronized fun all(): List<Notif> = list.filter { !muted(it.pkg) }

    @Synchronized fun count(): Int = all().size

    /** Groups by [groupOf] (a module may group differently from "one app"); newest group first, each group newest first. */
    @Synchronized fun groups(groupOf: (Notif) -> String = { it.pkg }): List<NotifGroup> =
        all().groupBy(groupOf).map { (id, items) -> NotifGroup(id, items.first().app, items.first().pkg, items) }
            .sortedByDescending { it.items.first().at }
}
