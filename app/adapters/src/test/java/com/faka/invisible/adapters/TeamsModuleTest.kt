package com.faka.invisible.adapters

import com.faka.invisible.oskit.Notif

import org.junit.Assert.*
import org.junit.Test

class TeamsModuleTest {
    private val m = TeamsModule(ChatAppsModule())
    private fun n(ch: String, title: String = "Ana", text: String = "hola") =
        Notif("k", TeamsModule.PKG, "Teams", title, text, 0L, channel = "com.microsoft.teams.$ch")

    @Test fun callSaysWhoCalls() = assertEquals("Teams call from Ana", m.read(n("Calls", text = "Incoming call")))
    @Test fun mentionComesFirst() = assertEquals("Mention. Ana: hola", m.read(n("PersonalMentions")))
    @Test fun meetingIsLabelled() = assertEquals("Teams meeting. Ana: hola", m.read(n("MeetingNotifications")))
    @Test fun chatIsPlain() = assertEquals("Ana: hola", m.read(n("Chats")))
    @Test fun noiseIsSkipped() {
        listOf("Reactions", "Files", "MediaController").forEach { assertTrue(m.skips("com.microsoft.teams.$it")) }
        listOf("Chats", "Calls", "MeetingNotifications", "PersonalMentions").forEach { assertFalse(m.skips("com.microsoft.teams.$it")) }
    }
    @Test fun onlyTeams() { assertTrue(m.matches(TeamsModule.PKG)); assertFalse(m.matches("com.whatsapp")) }
}
