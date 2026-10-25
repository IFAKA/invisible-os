package com.faka.invisible

import android.content.SharedPreferences

/**
 * What the user can set in the app. [hub] = the first-menu items that are ON, in order (keys of [HUB_ITEMS]; "more" is always kept).
 * [timers] = the three timer lengths in minutes ("Last used" is always added). [learnOrder] = reorder options by how often you pick them.
 */
data class LauncherSettings(
    val hub: List<String> = DEFAULT_HUB,
    val timers: List<Int> = listOf(5, 10, 25),
    val itemGapMs: Long = ControllerMode.ITEM_GAP_MS,
    val silentLoops: Int = 2,
    val undoMs: Long = 3000,
    val speechRate: Float = 0.9f,
    val learnOrder: Boolean = true,
) {
    companion object {
        val HUB_ITEMS = linkedMapOf("messages" to "Messages", "timer" to "Timer", "volume" to "Volume", "status" to "Status", "more" to "More")
        val DEFAULT_HUB = HUB_ITEMS.keys.toList()

        /** Keep known keys once each, in order; "more" is always present (it holds Exit, Torch, Read). */
        fun cleanHub(keys: List<String>): List<String> =
            keys.filter { it in HUB_ITEMS }.distinct().let { if ("more" in it) it else it + "more" }

        fun cleanTimers(t: List<Int>): List<Int> {
            val d = listOf(5, 10, 25)
            return List(3) { (t.getOrNull(it) ?: d[it]).coerceIn(1, 180) }
        }
    }
}

object SettingsStore {
    fun load(p: SharedPreferences): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            hub = p.getString("s_hub", null)?.split(',')?.let { LauncherSettings.cleanHub(it) } ?: d.hub,
            timers = LauncherSettings.cleanTimers(p.getString("s_timers", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: d.timers),
            itemGapMs = p.getLong("s_gap", d.itemGapMs).coerceIn(600, 4000),
            silentLoops = p.getInt("s_loops", d.silentLoops).coerceIn(1, 5),
            undoMs = p.getLong("s_undo", d.undoMs).coerceIn(1500, 8000),
            speechRate = p.getFloat("s_rate", d.speechRate).coerceIn(0.6f, 1.8f),
            learnOrder = p.getBoolean("s_learn", d.learnOrder),
        )
    }

    fun save(p: SharedPreferences, s: LauncherSettings) {
        p.edit().putString("s_hub", s.hub.joinToString(",")).putString("s_timers", s.timers.joinToString(","))
            .putLong("s_gap", s.itemGapMs).putInt("s_loops", s.silentLoops).putLong("s_undo", s.undoMs)
            .putFloat("s_rate", s.speechRate).putBoolean("s_learn", s.learnOrder).apply()
    }
}
