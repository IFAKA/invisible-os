package com.faka.invisible.oskit

import android.service.notification.StatusBarNotification

/** One action button of a notification. [index] is its place in the notification's action list. */
data class NotifAction(val index: Int, val label: String, val needsText: Boolean)

/**
 * One notification, as plain data. [hidden] = private: title and text were never kept, only the app is known.
 * [needsText]-actions are replies (they ask for typed text).
 */
data class Notif(
    val key: String, val pkg: String, val app: String, val title: String, val text: String,
    val at: Long, val actions: List<NotifAction> = emptyList(), val hidden: Boolean = false, val channel: String = "",
) {
    /** The action that wants typed text, if any. */
    val reply: NotifAction? get() = actions.firstOrNull { it.needsText }
}

/** What the OS offers an adapter (a module for one outside app). The adapter never reaches into the OS. */
interface NotificationHost {
    /** A new chat message arrived from [sender]: the OS plays its arrival tone. */
    fun arrival(sender: String)
    /** Blocking, off the main thread: the language code of [text] ("und" if unknown). */
    fun detectLanguage(text: String): String
}


/**
 * A native module for one app (or a few): how its notifications are grouped, read and answered.
 * The generic module is the default for every app; a registered module wins for the packages it matches.
 * The hooks run in the listener, for modules that keep their own state (like the chat apps).
 * To add an app: write one module and add one line to the adapter registry (Programs.kt, `Adapters`).
 */
interface NotificationModule {
    fun matches(pkg: String): Boolean
    /** Notifications with the same key are listed together. Default: one group per app. */
    fun groupKey(n: Notif): String = n.pkg
    /** What is read in full. Never called for a private notification. */
    fun read(n: Notif): String = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(". ")
    /** The one line in the list (the caller cuts it). */
    fun summary(n: Notif): String = read(n)

    /** True for a channel of this app that is only noise: it is not kept in the mailbox. */
    fun skips(channel: String): Boolean = false

    fun onConnected(ctx: android.content.Context, host: NotificationHost) {}
    fun onPosted(sbn: StatusBarNotification, loaded: Boolean) {}
    fun onRemoved(sbn: StatusBarNotification) {}
    /** The user muted this app: forget what the module holds for it. */
    fun onMuted(pkg: String) {}
}

/** Reads any app's notification the plain way. */
object GenericModule : NotificationModule {
    override fun matches(pkg: String) = true
}

/** Pure: finds the module for a package. */
class NotificationModules(private val modules: List<NotificationModule>, private val fallback: NotificationModule = GenericModule) {
    fun forPackage(pkg: String): NotificationModule = modules.firstOrNull { it.matches(pkg) } ?: fallback
    fun forNotif(n: Notif) = forPackage(n.pkg)
    fun all(): List<NotificationModule> = modules
}
