package com.faka.invisible

import android.content.SharedPreferences

/**
 * What the user can set in the app. [hub] = the first-menu items that are ON, in order (keys of [HUB_ITEMS];).
 * [timers] = the three timer lengths in minutes ("Last used" is always added). [learnOrder] = reorder options by how often you pick them.
 */
data class LauncherSettings(
    val hub: List<String> = DEFAULT_HUB,
    val info: List<String> = DEFAULT_INFO,   // what is inside Info, in order (Back is added by the mode)
    val phone: List<String> = DEFAULT_PHONE,   // what is inside Phone, in order (Back is added by the mode)
    val timers: List<Int> = listOf(5, 10, 25),
    val itemGapMs: Long = ControllerMode.ITEM_GAP_MS,
    val silentLoops: Int = 2,
    val undoMs: Long = 3000,
    val speechRate: Float = 0.9f,
    val learnOrder: Boolean = true,
    val ai: Boolean = false,   // reply sentences and task wording from the model on the phone (needs the 2.6 GB download)
    val pinned: Set<String> = emptySet(),   // tree entries forced to the first menu
    val hidden: Set<String> = emptySet(),   // tree entries removed from the tree (apps by package, OS programs as "hub:x")
    val moved: Map<String, String> = emptyMap(),   // package -> group it was moved to
    val spearcons: Boolean = false,   // a well-known option is scanned as its sped-up word (off: it sounded odd)
) {
    companion object {
        val HUB_ITEMS = linkedMapOf("messages" to "Messages", "tasks" to "Tasks", "music" to "Music", "timer" to "Timer", "today" to "Today (weather, next event, next turn)", "phone" to "Phone (status, volume, torch, do not disturb, find my phone)", "games" to "Games", "alarm" to "Set alarm", "calculator" to "Calculator", "write" to "Write", "compass" to "Compass", "whereami" to "Where am I", "claude" to "Claude (answer Claude Code on the laptop)", "sum" to "Sum prices")
        val DEFAULT_HUB = HUB_ITEMS.keys.toList()

        /** Every key a saved menu has ever used, in a fixed order. Only the old migration steps use it; [cleanHub] drops what is no longer an app. */
        val LEGACY_ORDER = listOf("messages", "tasks", "playing", "music", "timer", "status", "info", "today", "phone", "games")

        /** Old apps -> new apps, once: playing->music, info->today, write/duel->games, status->dropped (it is a Phone option now). Unknown keys are dropped. */
        fun migrateApps(keys: List<String>): List<String> = cleanHub(keys.flatMap { k ->
            when (k) { "playing" -> listOf("music"); "info" -> listOf("today"); "write", "duel" -> listOf("games"); "status" -> emptyList(); else -> listOf(k) }
        })

        val INFO_ITEMS = linkedMapOf("weather" to "Weather", "calendar" to "Next event", "turn" to "Next turn")
        val DEFAULT_INFO = INFO_ITEMS.keys.toList()
        fun cleanInfo(keys: List<String>): List<String> = keys.filter { it in INFO_ITEMS }.distinct()
        val PHONE_ITEMS = linkedMapOf("status" to "Status", "volume" to "Volume", "torch" to "Flashlight", "dnd" to "Do not disturb", "find" to "Find my phone")
        val DEFAULT_PHONE = PHONE_ITEMS.keys.toList()
        fun cleanPhone(keys: List<String>): List<String> = keys.filter { it in PHONE_ITEMS }.distinct()

        /** Keep known keys once each, in order. */
        fun cleanHub(keys: List<String>): List<String> = keys.filter { it in HUB_ITEMS }.distinct()

        /** Menu without "More": drop it and any unknown key, keep the other ON/OFF choices, use the default order. */
        fun withoutMore(keys: List<String>): List<String> = LEGACY_ORDER.filter { it in keys }

        /**
         * Old flat menu -> grouped menu. "info" is ON if any of weather/calendar/turn was; "phone" is ON if any of volume/find (hub) or torch/dnd ([oldMore]) was.
         * Other OFF choices stay OFF. Result is in the new default order. A menu that already has "info" or "phone" is only cleaned.
         */
        fun groupHub(keys: List<String>, oldMore: List<String>): List<String> {
            if ("info" in keys || "phone" in keys) return keys.filter { it in LEGACY_ORDER }.distinct()
            val on = keys.toMutableSet()
            if (keys.any { it in setOf("weather", "calendar", "turn") }) on += "info"
            if (keys.any { it in setOf("volume", "find") } || oldMore.any { it in setOf("torch", "dnd") }) on += "phone"
            return LEGACY_ORDER.filter { it in on }
        }

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
            hub = p.getString("s_hub", null)?.split(',')?.let { LauncherSettings.cleanHub(withWhere(p, withSum(p, withWriteCompass(p, withCalcAlarm(p, withApps(p, withNoMore(p, withOrder(p, withGroups(p, withNav(p, withInfo(p, withGames(p, withTasks(p, withPlaying(p, it)))))))))))))) } ?: d.hub,
            info = p.getString("s_info", null)?.split(',')?.let { LauncherSettings.cleanInfo(it) } ?: d.info,
            phone = p.getString("s_phone", null)?.split(',')?.let { LauncherSettings.cleanPhone(withStatus(p, it)) } ?: d.phone,
            timers = LauncherSettings.cleanTimers(p.getString("s_timers", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: d.timers),
            itemGapMs = p.getLong("s_gap2", d.itemGapMs).coerceIn(100, 4000),
            silentLoops = p.getInt("s_loops", d.silentLoops).coerceIn(1, 5),
            undoMs = p.getLong("s_undo", d.undoMs).coerceIn(1500, 8000),
            speechRate = p.getFloat("s_rate", d.speechRate).coerceIn(0.6f, 1.8f),
            learnOrder = p.getBoolean("s_learn", d.learnOrder),
            ai = p.getBoolean("s_ai", d.ai),
            spearcons = p.getBoolean("s_spearcons", d.spearcons),
            pinned = p.getString("s_pinned", "")!!.split(',').filter { it.isNotEmpty() }.toSet(),
            hidden = p.getString("s_hidden", "")!!.split(',').filter { it.isNotEmpty() }.toSet(),
            moved = p.getString("s_moved", "")!!.split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') },
        )
    }

    /** Once, last: the grouped menu becomes the apps menu (see [LauncherSettings.migrateApps]). */
    private fun withApps(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_apps_saved", false)) return keys
        val moved = LauncherSettings.migrateApps(keys)
        p.edit().putBoolean("s_apps_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: a saved menu gets the Set alarm and Calculator programs at the end; the user can move or switch them off in settings. */
    private fun withWriteCompass(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_write_compass_saved", false)) return keys
        val moved = keys + listOf("write", "compass").filter { it !in keys }
        p.edit().putBoolean("s_write_compass_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: a saved menu gets the Where am I tool at the end. */
    private fun withWhere(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_where_saved", false)) return keys
        val moved = keys + listOf("whereami").filter { it !in keys }
        p.edit().putBoolean("s_where_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: a saved menu gets the Sum prices tool at the end. */
    private fun withSum(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_sum_saved", false)) return keys
        val moved = keys + listOf("sum").filter { it !in keys }
        p.edit().putBoolean("s_sum_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    private fun withCalcAlarm(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_calc_alarm_saved", false)) return keys
        val moved = keys + listOf("alarm", "calculator").filter { it !in keys }
        p.edit().putBoolean("s_calc_alarm_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: a saved Phone list gets "status" as its first item (it used to be in the home menu). */
    private fun withStatus(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_status_phone_saved", false)) return keys
        val moved = listOf("status") + keys.filter { it != "status" }
        p.edit().putBoolean("s_status_phone_saved", true).putString("s_phone", moved.joinToString(",")).apply()
        return moved
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

    /** A saved menu from before "Games" existed gets it once, just before "More". The old "duel" entry in "More" is dropped by cleanMore. */
    private fun withGames(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_games_saved", false)) return keys
        val rest = keys.filter { it != "games" }
        val at = rest.indexOf("more").let { if (it < 0) rest.size else it }
        val moved = rest.take(at) + "games" + rest.drop(at)
        p.edit().putBoolean("s_games_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** A saved menu from before Weather, Next event and Find my phone existed gets them once, just before "Games". */
    private fun withInfo(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_info_saved", false)) return keys
        val rest = keys.filter { it !in setOf("weather", "calendar", "find") }
        val at = rest.indexOf("games").let { if (it < 0) rest.indexOf("more").let { m -> if (m < 0) rest.size else m } else it }
        val moved = rest.take(at) + listOf("weather", "calendar", "find") + rest.drop(at)
        p.edit().putBoolean("s_info_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** A saved menu from before "Next turn" existed gets it once, just before "Games". */
    private fun withNav(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_nav_saved", false)) return keys
        val rest = keys.filter { it != "turn" }
        val at = rest.indexOf("games").let { if (it < 0) rest.indexOf("more").let { m -> if (m < 0) rest.size else m } else it }
        val moved = rest.take(at) + "turn" + rest.drop(at)
        p.edit().putBoolean("s_nav_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: the old flat menu becomes the grouped one. OFF choices are kept (see [LauncherSettings.groupHub]); the old keys are dropped. */
    private fun withGroups(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_groups_saved", false)) return keys
        val more = p.getString("s_more", null)?.split(',') ?: listOf("torch", "dnd")   // torch and Do not disturb used to live in "More"
        val moved = LauncherSettings.groupHub(keys, more)
        p.edit().putBoolean("s_groups_saved", true).putBoolean("s_notifs_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once: put the user's ON items in the fastest order (most used first, "More" last). Items that are OFF stay OFF. */
    private fun withOrder(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_order_saved", false)) return keys
        val moved = LauncherSettings.LEGACY_ORDER.filter { it in keys }
        p.edit().putBoolean("s_order_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    /** Once, last: the "More" menu is gone. Drops "more" and unknown keys, keeps the other ON/OFF choices, default order. */
    private fun withNoMore(p: SharedPreferences, keys: List<String>): List<String> {
        if (p.getBoolean("s_nomore_saved", false)) return keys
        val moved = LauncherSettings.withoutMore(keys)
        p.edit().putBoolean("s_nomore_saved", true).putString("s_hub", moved.joinToString(",")).apply()
        return moved
    }

    fun save(p: SharedPreferences, s: LauncherSettings) {
        p.edit().putString("s_hub", s.hub.joinToString(",")).putString("s_info", s.info.joinToString(",")).putString("s_phone", s.phone.joinToString(",")).putString("s_timers", s.timers.joinToString(","))
            .putLong("s_gap2", s.itemGapMs).putInt("s_loops", s.silentLoops).putLong("s_undo", s.undoMs)
            .putFloat("s_rate", s.speechRate).putBoolean("s_learn", s.learnOrder).putBoolean("s_spearcons", s.spearcons).putBoolean("s_ai", s.ai).putString("s_pinned", s.pinned.joinToString(",")).putString("s_hidden", s.hidden.joinToString(",")).putString("s_moved", s.moved.entries.joinToString(",") { it.key + "=" + it.value }).putBoolean("s_groups_saved", true).putBoolean("s_apps_saved", true).putBoolean("s_status_phone_saved", true).apply()
    }
}
