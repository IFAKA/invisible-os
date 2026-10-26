package com.faka.invisible

import android.service.notification.StatusBarNotification

/**
 * A native module for one app (or a few): how its notifications are grouped, read and answered.
 * The generic module is the default for every app; a registered module wins for the packages it matches.
 * The hooks run in the listener, for modules that keep their own state (like the chat apps).
 * To add an app: write one module and add one line to [NotificationModules.registered].
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

    fun onConnected(ctx: android.content.Context) {}
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
