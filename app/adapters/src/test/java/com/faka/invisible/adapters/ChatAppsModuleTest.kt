package com.faka.invisible.adapters

import org.junit.Assert.*
import org.junit.Test

class ChatAppsModuleTest {
    private val msg = "msg"
    @Test fun knownAppsMatch() {
        val m = ChatAppsModule()
        listOf("org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.google.android.gm",
            "com.Slack", "com.discord", "com.facebook.orca", "com.instagram.android", "com.samsung.android.messaging").forEach { assertTrue(it, m.matches(it)) }
        assertFalse(m.matches("com.chase.sig.android"))
    }
    @Test fun genericRuleNeedsCategoryAndReplyAndNotPrivate() {
        // CATEGORY_MESSAGE is "msg"
        assertTrue(ChatAppsModule.joinsByCategory(msg, true, false))
        assertFalse(ChatAppsModule.joinsByCategory(msg, false, false))
        assertFalse(ChatAppsModule.joinsByCategory("email", true, false))
        assertFalse(ChatAppsModule.joinsByCategory(null, true, false))
        assertFalse(ChatAppsModule.joinsByCategory(msg, true, true))
    }
}
