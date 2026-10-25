package com.faka.invisible

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
    }

    @Volatile private var loaded = false

    override fun onListenerConnected() {
        loaded = false
        val prefs = getSharedPreferences("conv_lang3", MODE_PRIVATE)
        convLang = ConvLang(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()) { m ->
            prefs.edit().clear().apply { m.forEach { (k, v) -> putString(k, v) } }.apply()
        }
        store.clear(); synchronized(Companion) { replies.clear() }
        try { activeNotifications?.forEach { take(it) } } catch (e: Exception) { Log.e("Invisible", "NL load failed", e) }
        loaded = true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in apps) return
        store.remove(sbn.key); synchronized(Companion) { replies.remove(sbn.key) }
    }

    private fun take(sbn: StatusBarNotification) {
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
