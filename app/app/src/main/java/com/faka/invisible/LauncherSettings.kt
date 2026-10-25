package com.faka.invisible

import android.content.SharedPreferences

/**
 * What the user can set in the app. [hub] = the first-menu items that are ON, in order (keys of [HUB_ITEMS]; "more" is always kept).
 * [timers] = the three timer lengths in minutes ("Last used" is always added). [learnOrder] = reorder options by how often you pick them.
 */
data class LauncherSettings(
    val hub: List<String> = DEFAULT_HUB,
    val more: List<String> = DEFAULT_MORE,
    val timers: List<Int> = listOf(5, 10, 25),
    val itemGapMs: Long = ControllerMode.ITEM_GAP_MS,
    val silentLoops: Int = 2,
    val undoMs: Long = 3000,
    val speechRate: Float = 0.9f,
    val learnOrder: Boolean = true,
    val spearcons: Boolean = false,   // a well-known option is scanned as its sped-up word (off: it sounded odd)
) {
    companion object {
        val HUB_ITEMS = linkedMapOf("playing" to "Playing", "messages" to "Messages", "tasks" to "Tasks", "timer" to "Timer", "volume" to "Volume", "status" to "Status", "more" to "More")
        val DEFAULT_HUB = HUB_ITEMS.keys.toList()

        /** What is inside "More", in the order spoken. "exit" is always kept. */
        val MORE_ITEMS = linkedMapOf("read" to "Read unread messages", "torch" to "Torch", "dnd" to "Do not disturb", "duel" to "Duel", "exit" to "Exit")
        val DEFAULT_MORE = MORE_ITEMS.keys.toList()
        fun cleanMore(keys: List<String>): List<String> = keys.filter { it in MORE_ITEMS }.distinct().let { if ("exit" in it) it else it + "exit" }

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
            hub = p.getString("s_hub", null)?.split(',')?.let { LauncherSettings.cleanHub(withTasks(p, withPlaying(p, it))) } ?: d.hub,
            more = p.getString("s_more", null)?.split(',')?.let { LauncherSettings.cleanMore(withDuel(p, it)) } ?: d.more,
            timers = LauncherSettings.cleanTimers(p.getString("s_timers", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: d.timers),
            itemGapMs = p.getLong("s_gap", d.itemGapMs).coerceIn(600, 4000),
            silentLoops = p.getInt("s_loops", d.silentLoops).coerceIn(1, 5),
            undoMs = p.getLong("s_undo", d.undoMs).coerceIn(1500, 8000),
            speechRate = p.getFloat("s_rate", d.speechRate).coerceIn(0.6f, 1.8f),
            learnOrder = p.getBoolean("s_learn", d.learnOrder),
            spearcons = p.getBoolean("s_spearcons", d.spearcons),
        )
    }

    /** A saved menu from before "Playing" existed (or from when it was last) gets it once, as the first item; the user can move or switch it off in settings. */
    private fun withPlaying(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_playing_saved", false)) return keys
        val moved = listOf("playing") + keys.filter { it != "playing" }
        p.edit().putBoolean("s_playing_saved", true).putString("s_hub", moved.joinToString(",")).apply()   // saved now, so the settings screen shows it too
        return moved
    }

    /** A saved menu from before "Tasks" existed gets it once, after "Messages" (or first if there is none). */
    private fun withTasks(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_tasks_saved", false)) return keys
        val rest = keys.filter { it != "tasks" }
        val at = rest.indexOf("messages") + 1
        val moved = rest.take(at) + "tasks" + rest.drop(at)
        p.edit().putBoolean("s_tasks_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** A saved "More" list from before "Duel" existed gets it once, before Exit. */
    private fun withDuel(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_duel_saved", false)) return keys
        val rest = keys.filter { it != "duel" }
        val at = rest.indexOf("exit").let { if (it < 0) rest.size else it }
        val moved = rest.take(at) + "duel" + rest.drop(at)
        p.edit().putBoolean("s_duel_saved", true).putString("s_more", moved.joinToString(",")).apply()
        return moved
    }

    fun save(p: SharedPreferences, s: LauncherSettings) {
        p.edit().putString("s_hub", s.hub.joinToString(",")).putString("s_more", s.more.joinToString(",")).putString("s_timers", s.timers.joinToString(","))
            .putLong("s_gap", s.itemGapMs).putInt("s_loops", s.silentLoops).putLong("s_undo", s.undoMs)
            .putFloat("s_rate", s.speechRate).putBoolean("s_learn", s.learnOrder).putBoolean("s_spearcons", s.spearcons).apply()
    }
}
