package com.faka.invisible

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Its enabled "notification access" lets us read other apps' media sessions, keeps every app's notifications in the mailbox
 * ([notifs]), and hands each one to the native module of its app ([modules]; the chat apps feed the Messages layer).
 */
class ControllerNotificationListener : NotificationListenerService() {
    companion object {
        /** The native modules, one line each; every other app is read by the generic module. */
        private val chat = ChatAppsModule()
        val modules = NotificationModules(listOf(chat))
        val store get() = chat.store
        /** The mailbox: every app's notifications (not ongoing, not group summaries), see [NotificationStore]. */
        val notifs = NotificationStore(muted = { mutes.isMuted(it) })
        /** Apps the user muted from the Notifications screen: not counted, not announced. Saved in SharedPreferences. */
        @Volatile var mutes = MuteSet()
            private set
        private var mutesLoaded = false
        private val notifActions = HashMap<String, Array<Notification.Action>>()
        @Volatile private var live: ControllerNotificationListener? = null
        /** The latest Google Maps navigation sentence while it is active, else null. */
        @Volatile var navigation: String? = null
        @Volatile private var navKey: String? = null
        /** Voice for a song name: English only when the detector clearly says English, otherwise Spanish (same rule as messages). */
        fun voiceFor(text: String): String {
            val d = try { detector.detect(text) } catch (e: Exception) { "" }
            Log.d("Invisible", "SONG lang detect=$d for $text")
            return if (d == "en") "en" else "es"
        }
        val detector: LangDetector by lazy { MlKitLangDetector() }

        fun sendReply(key: String, text: String, ctx: android.content.Context): Boolean = chat.sendReply(key, text, ctx)

        /** Fires action [index] of the notification [key]. [text] fills the action's reply box when it has one. */
        @Synchronized fun fireAction(key: String, index: Int, text: String?, ctx: android.content.Context): Boolean {
            val a = notifActions[key]?.getOrNull(index) ?: return false
            return try {
                val intent = Intent()
                val inputs = a.remoteInputs
                if (text != null && !inputs.isNullOrEmpty()) {
                    val b = Bundle()
                    inputs.forEach { b.putCharSequence(it.resultKey, text) }
                    RemoteInput.addResultsToIntent(inputs, intent, b)
                }
                a.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: Exception) { Log.e("Invisible", "ACTION failed", e); false }
        }

        @Synchronized fun loadMutes(ctx: android.content.Context) {
            if (mutesLoaded) return
            mutesLoaded = true
            val prefs = ctx.getSharedPreferences("notif_mute", android.content.Context.MODE_PRIVATE)
            mutes = MuteSet(prefs.getStringSet("pkgs", emptySet()).orEmpty()) { prefs.edit().putStringSet("pkgs", it).apply() }
        }

        fun mute(pkg: String) { mutes.mute(pkg); modules.all().forEach { it.onMuted(pkg) } }

        /** Unmutes every app and reads the notifications that are showing again (silently: no arrival announcements). */
        fun unmuteAll() { mutes.unmuteAll(); live?.reloadActive() }

        /** Swipes the notification away, as the user would. */
        fun dismiss(key: String): Boolean {
            val l = live ?: return false
            return try { l.cancelNotification(key); notifs.remove(key); true } catch (e: Exception) { Log.e("Invisible", "DISMISS failed", e); false }
        }
    }

    @Volatile private var loaded = false

    override fun onListenerConnected() {
        loaded = false; live = this; loadMutes(applicationContext)
        modules.all().forEach { it.onConnected(applicationContext) }
        notifs.clear(); synchronized(Companion) { notifActions.clear() }; navigation = null; navKey = null
        reloadActive()
    }

    private fun reloadActive() {
        loaded = false
        try { activeNotifications?.forEach { take(it) } } catch (e: Exception) { Log.e("Invisible", "NL load failed", e) }
        loaded = true
    }

    override fun onListenerDisconnected() { live = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == Navigation.PACKAGE && sbn.key == navKey) { navigation = null; navKey = null }
        notifs.remove(sbn.key); synchronized(Companion) { notifActions.remove(sbn.key) }
        modules.all().forEach { it.onRemoved(sbn) }
    }

    /** The mailbox: keeps any app's notification. A private one (see [NotifPrivacy]) keeps no title and no text at all. */
    private fun keep(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
        val hidden = NotifPrivacy.mustHide(sbn.packageName, n.visibility, locked, title.isBlank() && text.isBlank())
        val actions = n.actions.orEmpty().mapIndexedNotNull { i, a ->
            a.title?.toString()?.takeIf { it.isNotBlank() }?.let { NotifAction(i, it, !a.remoteInputs.isNullOrEmpty()) }
        }
        synchronized(Companion) { if (n.actions != null) notifActions[sbn.key] = n.actions else notifActions.remove(sbn.key) }
        notifs.upsert(Notif(sbn.key, sbn.packageName, appLabel(sbn.packageName), if (hidden) "" else title, if (hidden) "" else text, sbn.postTime, actions, hidden))
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    private fun take(sbn: StatusBarNotification) {
        if (sbn.packageName == Navigation.PACKAGE) {
            val x = sbn.notification.extras
            fun s(k: String) = x.getCharSequence(k)?.toString()
            val t = Navigation.sentence(s(Notification.EXTRA_TITLE), s(Notification.EXTRA_TEXT), s(Notification.EXTRA_SUB_TEXT))
            if (t != null && sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) { navigation = t; navKey = sbn.key }
            return
        }
        keep(sbn)
        if (mutes.isMuted(sbn.packageName)) return   // muted: kept in the mailbox but hidden from every read; the module neither counts nor announces it
        modules.forPackage(sbn.packageName).onPosted(sbn, loaded)
    }
}
