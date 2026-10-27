package com.faka.invisible

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.faka.invisible.adapters.ChatAppsModule
import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.*

/**
 * Its enabled "notification access" lets us read other apps' media sessions, keeps every app's notifications in the mailbox
 * ([notifs]), and hands each one to the native module of its app ([modules]; the chat apps feed the Messages layer).
 */
class ControllerNotificationListener : NotificationListenerService() {
    companion object {
        /** The native modules, one line each; every other app is read by the generic module. */
        val modules get() = Adapters.modules
        private val chat get() = Adapters.chat
        val store get() = chat.store
        /** What the adapters may ask of the OS. */
        private val adapterHost = object : NotificationHost {
            override fun arrival(pkg: String, sender: String, text: String, lang: String, visibility: Int) = Actions.arrival(pkg, sender, text, lang, visibility)
            override fun detectLanguage(text: String) = detector.detect(text)
        }
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
        /** A call of the dialer: the ringing one (name and answer / decline button) and the running one. */
        class Call(val key: String, val name: String, val answer: Int, val decline: Int)
        @Volatile var ringing: Call? = null
            private set
        @Volatile private var ongoingKey: String? = null
        /** A call rings or runs. */
        val inCall get() = ringing != null || ongoingKey != null
        private val callHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Presses the dialer's own Answer or Decline button; used when the phone permission is not given. */
        fun fireCall(ctx: android.content.Context, answer: Boolean): Boolean {
            val c = ringing ?: return false
            val i = if (answer) c.answer else c.decline
            return i >= 0 && fireAction(c.key, i, null, ctx)
        }
        @Volatile private var navKey: String? = null

        /** An ongoing notification (a player, a workout, a stopwatch...) and what says whether it is live; see [LiveApps]. */
        class Ongoing(val notif: Notif, val category: String, val chronometer: Boolean, val mediaStyle: Boolean)
        private val ongoing = java.util.concurrent.ConcurrentHashMap<String, Ongoing>()
        /** The ongoing notifications now, for the live entries on home. */
        fun ongoingNow(): List<Ongoing> = ongoing.values.filter { !mutes.isMuted(it.notif.pkg) }
        /** One ongoing notification by key (for its actions screen). */
        fun ongoingNotif(key: String): Notif? = ongoing[key]?.notif?.takeIf { !mutes.isMuted(it.pkg) }
        /** Voice for a song name: Spanish only when the detector clearly says Spanish, otherwise English (same rule as messages). */
        fun voiceFor(text: String): String {
            val d = try { detector.detect(text) } catch (e: Exception) { "" }
            Log.d("Invisible", "SONG lang detect=$d for $text")
            return if (d == "es") "es" else "en"
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

        /** Swipes away every mailbox notification of [pkg] (null = every app, muted ones left alone); ongoing ones are not in the mailbox, so they stay. Returns how many. */
        fun dismissAll(pkg: String?): Int = notifs.all().filter { pkg == null || it.pkg == pkg }.count { dismiss(it.key) }

        /** Swipes the notification away, as the user would. */
        fun dismiss(key: String): Boolean {
            val l = live ?: return false
            if (l.activeNotifications?.firstOrNull { it.key == key }?.isClearable == false) return false   // the app keeps it: swiping does nothing, so do not say it went
            return try { l.cancelNotification(key); notifs.remove(key); true } catch (e: Exception) { Log.e("Invisible", "DISMISS failed", e); false }
        }
    }

    @Volatile private var loaded = false

    override fun onListenerConnected() {
        loaded = false; live = this; loadMutes(applicationContext)
        modules.all().forEach { it.onConnected(applicationContext, adapterHost) }
        notifs.clear(); ongoing.clear(); synchronized(Companion) { notifActions.clear() }; navigation = null; navKey = null
        reloadActive()
    }

    private fun reloadActive() {
        loaded = false
        try { activeNotifications?.forEach { take(it) } } catch (e: Exception) { Log.e("Invisible", "NL load failed", e) }
        loaded = true
    }

    override fun onListenerDisconnected() { live = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) { traceNotif("posted", sbn); take(sbn) }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        traceNotif("removed", sbn)
        if (sbn.key == ringing?.key || sbn.key == ongoingKey) {
            if (sbn.key == ringing?.key) ringing = null
            if (sbn.key == ongoingKey) ongoingKey = null
            callHandler.postDelayed({ if (ringing == null && ongoingKey == null) Actions.callOver() }, 700)   // an answered call swaps its notification: wait a moment
        }
        if (sbn.packageName == Navigation.PACKAGE && sbn.key == navKey) { navigation = null; navKey = null }
        notifs.remove(sbn.key); ongoing.remove(sbn.key); synchronized(Companion) { notifActions.remove(sbn.key) }
        modules.all().forEach { it.onRemoved(sbn) }
    }

    /** The mailbox: keeps any app's notification. A private one (see [NotifPrivacy]) keeps no title and no text at all. */
    private fun keep(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) trackOngoing(sbn)
        if (sbn.packageName == packageName || n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        if (modules.forPackage(sbn.packageName).skips(n.channelId.orEmpty())) return
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
        val hidden = NotifPrivacy.mustHide(sbn.packageName, n.visibility, locked, title.isBlank() && text.isBlank())
        val actions = n.actions.orEmpty().mapIndexedNotNull { i, a ->
            a.title?.toString()?.takeIf { it.isNotBlank() }?.let { NotifAction(i, it, !a.remoteInputs.isNullOrEmpty()) }
        }
        synchronized(Companion) { if (n.actions != null) notifActions[sbn.key] = n.actions else notifActions.remove(sbn.key) }
        notifs.upsert(Notif(sbn.key, sbn.packageName, appLabel(sbn.packageName), if (hidden) "" else title, if (hidden) "" else text, sbn.postTime, actions, hidden, n.channelId.orEmpty()))
    }

    /** An ongoing notification of another app (this app's own only in the e2e test): kept apart from the mailbox, with its actions, so a live one can open on home. */
    private fun trackOngoing(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName && !fake(sbn)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
        val hidden = NotifPrivacy.mustHide(sbn.packageName, n.visibility, locked, false)
        val actions = n.actions.orEmpty().mapIndexedNotNull { i, a ->
            a.title?.toString()?.takeIf { it.isNotBlank() }?.let { NotifAction(i, it, !a.remoteInputs.isNullOrEmpty()) }
        }
        if (n.actions != null) synchronized(Companion) { notifActions[sbn.key] = n.actions }
        val chrono = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || n.extras.getBoolean("android.chronometerCountDown")
        val media = x.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true
        ongoing[sbn.key] = Ongoing(
            Notif(sbn.key, sbn.packageName, appLabel(sbn.packageName), if (hidden) "" else title, if (hidden) "" else text, sbn.postTime, actions, hidden, n.channelId.orEmpty()),
            n.category.orEmpty(), chrono, media)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    /** A dialer notification: a ringing call opens the Answer / Reject list, a running one is remembered. True = handled here. */
    private fun call(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName && !fake(sbn)) return false
        val titles = n.actions.orEmpty().map { it.title?.toString().orEmpty() }
        when (Calls.kind(n.category, n.extras.getInt("android.callType", 0), titles.size)) {
            CallKind.NONE -> return false
            CallKind.ONGOING -> { ongoingKey = sbn.key; if (ringing?.key == sbn.key) ringing = null; Actions.callAnswered() }
            CallKind.INCOMING -> {
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val resolved = if (title.any { it.isLetter() }) null else PhoneCalls.nameFor(this, title)
                val first = ringing?.key != sbn.key
                ringing = Call(sbn.key, Calls.callerName(title, resolved), Calls.answerIndex(titles), Calls.declineIndex(titles))
                n.actions?.let { a -> synchronized(Companion) { notifActions[sbn.key] = a } }
                if (first) Actions.callRinging()
            }
        }
        return true
    }

    /** Debug builds only: a notification the e2e test posts from this app stands in for a call or chat message of another app. */
    private fun fake(sbn: StatusBarNotification) = BuildConfig.DEBUG && sbn.packageName == packageName && sbn.notification.extras.getBoolean("e2e")

    private fun take(sbn: StatusBarNotification) {
        if (call(sbn)) return
        if (sbn.packageName == Navigation.PACKAGE) {
            val x = sbn.notification.extras
            fun s(k: String) = x.getCharSequence(k)?.toString()
            val t = Navigation.sentence(s(Notification.EXTRA_TITLE), s(Notification.EXTRA_TEXT), s(Notification.EXTRA_SUB_TEXT))
            if (t != null && sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) { navigation = t; navKey = sbn.key }
            return
        }
        keep(sbn)
        if (mutes.isMuted(sbn.packageName)) return   // muted: kept in the mailbox but hidden from every read; the module neither counts nor announces it
        val m = modules.forPackage(sbn.packageName)
        if (m === GenericModule) {
            // Generic rule: any conversation notification with a reply box feeds the Messages layer (never a private one).
            val n = sbn.notification
            val x = n.extras
            val empty = x.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() && x.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
            val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
            val priv = NotifPrivacy.mustHide(sbn.packageName, n.visibility, locked, empty)
            if (ChatAppsModule.joinsByCategory(n.category, n.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() }, priv)) chat.onPosted(sbn, loaded)
        } else m.onPosted(sbn, loaded)
    }
}
