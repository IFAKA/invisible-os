package com.faka.invisible

import android.service.notification.StatusBarNotification

/**
 * Microsoft Teams, read by its notification channel (the ids Teams creates): calls say who calls and offer Answer / Decline,
 * mentions come first, reactions and file or media noise are dropped. Chats and personal mentions still feed the Messages
 * layer through [chat] (the chat module sees them, so replies keep working).
 */
class TeamsModule(private val chat: ChatAppsModule) : NotificationModule {
    companion object {
        const val PKG = "com.microsoft.teams"
        private const val P = "com.microsoft.teams."
        private val NOISE = setOf("Reactions", "Files", "MediaController", "Suggested", "CrossDeviceEvent", "Other").map { P + it }.toSet()
        private val MENTIONS = setOf("PersonalMentions", "TagMentions", "EveryoneMentions", "ChannelTeamMentions").map { P + it }.toSet()
        private val FEED = setOf(P + "Chats", P + "PersonalMentions")
        fun kind(channel: String) = when {
            channel == P + "Calls" -> "call"
            channel == P + "MeetingNotifications" -> "meeting"
            channel in MENTIONS -> "mention"
            else -> "chat"
        }
    }

    override fun matches(pkg: String) = pkg == PKG
    override fun skips(channel: String) = channel in NOISE

    override fun read(n: Notif): String {
        val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
        return when (kind(n.channel)) {
            "call" -> "Teams call from " + n.title.ifBlank { "someone" }
            "meeting" -> "Teams meeting. $body"
            "mention" -> "Mention. $body"
            else -> body
        }
    }

    override fun onPosted(sbn: StatusBarNotification, loaded: Boolean) {
        if (sbn.notification.channelId in FEED) chat.onPosted(sbn, loaded)
    }
    override fun onRemoved(sbn: StatusBarNotification) = chat.onRemoved(sbn)
}
