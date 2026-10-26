package com.faka.invisible.adapters

import com.faka.invisible.oskit.*

import com.faka.invisible.logickit.*

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The first native module: the chat apps (WhatsApp, Teams, WeChat, Claude) feed the Messages layer. It keeps the latest unread
 * message per conversation, announces new ones, votes one language per person, and replies (Claude has no reply action, so it is read-only).
 */
class ChatAppsModule : NotificationModule {
    companion object {
        /** Known chat and mail apps; any other app joins by [joinsByCategory]. */
        val APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.microsoft.teams", "com.tencent.mm", "com.anthropic.claude",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging",
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thoughtcrime.securesms", "com.google.android.gm",
            "com.instagram.android", "com.facebook.orca", "com.Slack", "com.discord",
        )

        /**
         * The generic rule for any other app: a conversation notification (message category) that offers a reply box.
         * An app whose notification is private (bank, password, secret) never joins.
         */
        fun joinsByCategory(category: String?, hasReplyAction: Boolean, private: Boolean) =
            !private && hasReplyAction && category == Notification.CATEGORY_MESSAGE
    }

    val store = MessageStore()
    @Volatile private var host: NotificationHost? = null
    @Volatile private var convLang = ConvLang()
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val replies = HashMap<String, Notification.Action>()

    override fun matches(pkg: String) = pkg in APPS
    override fun read(n: Notif) = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")

    override fun onConnected(ctx: android.content.Context, host: NotificationHost) {
        this.host = host
        val prefs = ctx.getSharedPreferences("conv_lang3", android.content.Context.MODE_PRIVATE)
        convLang = ConvLang(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()) { m ->
            prefs.edit().clear().apply { m.forEach { (k, v) -> putString(k, v) } }.apply()
        }
        store.clear(); synchronized(this) { replies.clear() }
    }

    override fun onRemoved(sbn: StatusBarNotification) { store.remove(sbn.key); synchronized(this) { replies.remove(sbn.key) } }

    override fun onMuted(pkg: String) { store.removeWhere { "|$pkg|" in it.key } }   // a status bar key reads "user|package|id|tag|uid"

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

    override fun onPosted(sbn: StatusBarNotification, loaded: Boolean) {
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
        synchronized(this) { if (reply != null) replies[sbn.key] = reply else replies.remove(sbn.key) }
        val key = sbn.key; val t = text!!
        val old = store.all().firstOrNull { it.key == key }
        val isNew = old == null || old.text != t
        val who = sbn.packageName + "|" + MessageLogic.stableName(sender!!)
        store.upsert(Msg(key, sender!!, t, convLang.get(who), if (isNew) sbn.postTime else old!!.at))
        if (isNew && loaded) host?.arrival(sender)
        // One language per person, Spanish unless English (or another) clearly wins the votes; detect only until settled.
        if (!convLang.settled(who)) worker.execute { try {
            val clean = MessageLogic.stripLinks(t)
            // very short texts ("ok", "jaja") and WhatsApp's own media text are too weak to vote
            if (MessageLogic.hasWords(t) && clean.words().size >= 3) convLang.learn(who, host?.detectLanguage(clean) ?: "")
            store.setLang(key, t, convLang.get(who))
        } catch (e: Exception) { Log.e("Invisible", "LANG failed", e) } }
        Log.d("Invisible", "MSG ${sbn.packageName} from $sender reply=${reply != null}")
    }
}
