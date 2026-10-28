package com.faka.invisible.adapters

import com.faka.invisible.logickit.*
import com.faka.invisible.oskit.*

import android.service.notification.StatusBarNotification

/**
 * Gmail: read as "<sender>, <subject>" and a short start of the body. Gmail puts the sender in the title and the subject
 * (then maybe the body) in the text. Mail still feeds the Messages layer through [chat], as before. Privacy is the OS's:
 * a notification the generic rule marks private never reaches [read] and so never reaches any AI.
 */
class GmailModule(private val chat: ChatAppsModule) : NotificationModule {
    companion object {
        const val PKG = "com.google.android.gm"
        const val BODY_WORDS = 12

        /** Pure: "<sender>, <subject>. <first words of the body>". */
        fun phrase(title: String, text: String): String {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val subject = lines.firstOrNull().orEmpty()
            val body = lines.drop(1).joinToString(" ").words()
            val head = listOf(title.trim(), subject).filter { it.isNotEmpty() }.joinToString(", ")
            if (body.isEmpty()) return head
            return head + ". " + body.take(BODY_WORDS).joinToString(" ") + if (body.size > BODY_WORDS) "..." else ""
        }
    }

    override fun matches(pkg: String) = pkg == PKG
    override fun read(n: Notif) = phrase(n.title, n.text)
    override fun onConnected(ctx: android.content.Context, host: NotificationHost) = chat.onConnected(ctx, host)
    override fun onPosted(sbn: StatusBarNotification, loaded: Boolean) = chat.onPosted(sbn, loaded)
    override fun onRemoved(sbn: StatusBarNotification) = chat.onRemoved(sbn)
    override fun onMuted(pkg: String) = chat.onMuted(pkg)
}
