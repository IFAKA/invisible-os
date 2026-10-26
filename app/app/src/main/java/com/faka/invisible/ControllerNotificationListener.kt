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
 * Its enabled "notification access" lets us read other apps' media sessions, and keeps the latest
 * unread chat notifications (WhatsApp, Teams, WeChat, Claude; Claude has no reply action, so it is read-only) for the Messages layer.
 */
class ControllerNotificationListener : NotificationListenerService() {
    companion object {
        val apps = setOf("com.whatsapp", "com.microsoft.teams", "com.tencent.mm", "com.anthropic.claude")
        val store = MessageStore()
        /** The mailbox: every app's notifications (not ongoing, not group summaries), see [NotificationStore]. */
        val notifs = NotificationStore()
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
        private val detector: LangDetector by lazy { MlKitLangDetector() }
        @Volatile var convLang = ConvLang()
        private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
        private val replies = HashMap<String, Notification.Action>()

        @Synchronized fun sendReply(key: String, text: String, ctx: android.content.Context): Boolean {
            val a = replies[key] ?: return false
            val inputs = a.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val b = Bundle()
                inputs.forEach { b.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(inputs, intent, b)
                a.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: Exception) { Log.e("Invisible", "REPLY failed", e); false }
        }

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

        /** Swipes the notification away, as the user would. */
        fun dismiss(key: String): Boolean {
            val l = live ?: return false
            return try { l.cancelNotification(key); true } catch (e: Exception) { Log.e("Invisible", "DISMISS failed", e); false }
        }
    }

    @Volatile private var loaded = false

    override fun onListenerConnected() {
        loaded = false; live = this
        val prefs = getSharedPreferences("conv_lang3", MODE_PRIVATE)
        convLang = ConvLang(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()) { m ->
            prefs.edit().clear().apply { m.forEach { (k, v) -> putString(k, v) } }.apply()
        }
        store.clear(); notifs.clear(); synchronized(Companion) { replies.clear(); notifActions.clear() }; navigation = null; navKey = null
        try { activeNotifications?.forEach { take(it) } } catch (e: Exception) { Log.e("Invisible", "NL load failed", e) }
        loaded = true
    }

    override fun onListenerDisconnected() { live = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == Navigation.PACKAGE && sbn.key == navKey) { navigation = null; navKey = null }
        notifs.remove(sbn.key); synchronized(Companion) { notifActions.remove(sbn.key) }
        if (sbn.packageName !in apps) return
        store.remove(sbn.key); synchronized(Companion) { replies.remove(sbn.key) }
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
        if (sbn.packageName !in apps) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val x = n.extras
        var sender = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        var text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val last = x.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)?.lastOrNull()
        if (last != null) {
            last.getCharSequence("text")?.toString()?.let { text = it }
            if (sender == null) sender = last.getCharSequence("sender")?.toString()
        }
        if (sender.isNullOrBlank() || text.isNullOrBlank()) return
        val reply = n.actions?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        synchronized(Companion) { if (reply != null) replies[sbn.key] = reply else replies.remove(sbn.key) }
        val key = sbn.key; val t = text!!
        val old = store.all().firstOrNull { it.key == key }
        val isNew = old == null || old.text != t
        val who = sbn.packageName + "|" + MessageLogic.stableName(sender!!)
        store.upsert(Msg(key, sender!!, t, convLang.get(who), if (isNew) sbn.postTime else old!!.at))
        if (isNew && loaded) Actions.arrival(sender)
        // One language per person, Spanish unless English (or another) clearly wins the votes; detect only until settled.
        if (!convLang.settled(who)) worker.execute { try {
            val clean = MessageLogic.stripLinks(t)
            // very short texts ("ok", "jaja") and WhatsApp's own media text are too weak to vote
            if (MessageLogic.hasWords(t) && clean.split(Regex("\\s+")).size >= 3) convLang.learn(who, detector.detect(clean))
            store.setLang(key, t, convLang.get(who))
        } catch (e: Exception) { Log.e("Invisible", "LANG failed", e) } }
        Log.d("Invisible", "MSG ${sbn.packageName} from $sender reply=${reply != null}")
    }
}
