package com.faka.invisible

import android.content.Context
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.Computers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A stranger without a paired computer does not see the computer-only menu items; a saved menu is never rewritten. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubVisibilityTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("hubvis", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun freshInstallHasNoClaudeUntilPaired() {
        val p = prefs()
        assertFalse("claude" in SettingsStore.load(p).hub)
        assertTrue("messages" in SettingsStore.load(p).hub)
        Computers.save(p, listOf(Computer("Laptop", "http://x:1", "t")))
        assertTrue("claude" in SettingsStore.load(p).hub)
    }

    @Test fun savedMenuIsNotChangedOnDisk() {
        val p = prefs()
        SettingsStore.load(p)   // runs the one-time migrations
        val before = p.getString("s_hub", null)
        SettingsStore.load(p)
        assertEquals(before, p.getString("s_hub", null))
    }

    @Test fun visibleHubOnlyDropsComputerItems() {
        assertEquals(listOf("tasks", "music"), LauncherSettings.visibleHub(listOf("tasks", "claude", "music"), false))
        assertEquals(listOf("tasks", "claude"), LauncherSettings.visibleHub(listOf("tasks", "claude"), true))
    }
}
