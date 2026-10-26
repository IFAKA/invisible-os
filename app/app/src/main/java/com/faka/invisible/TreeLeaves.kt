package com.faka.invisible

import android.content.Context

/**
 * The one place that decides which entries are in the spoken tree and where they sit. The voice ([ControllerMode]) and the settings screen both call it,
 * so what you hear and what you see are the same tree. The edits (pin, hide, move) live in [LauncherSettings] and are applied here, once.
 */
object TreeLeaves {
    val GROUPS = listOf("Messages", "Day", "Money", "Media", "Out", "Tools")
    /** A pinned entry gets this much weight, so the tree search lifts it to the first menu. */
    const val PIN_BOOST = 1000.0

    fun programLabel(key: String) = LauncherSettings.HUB_ITEMS[key.removePrefix("hub:")]?.substringBefore(" (") ?: key

    /** Every installed app (muted ones and this app left out), with its place and weight. */
    fun apps(installed: List<InstalledApp>, use: Map<String, AppUse>, muted: (String) -> Boolean, unread: Map<String, Int>, picks: (String) -> Int): List<AppLeaf> =
        installed.filter { it.pkg != AppWeights.SELF && !muted(it.pkg) }.map { a ->
            val u = use[a.pkg] ?: AppUse(0, 0)
            AppLeaf(a.pkg, a.label, AppCategories.categoryOf(a.pkg, a.hint), unread[a.pkg] ?: 0, AppWeights.weight(a.pkg, u.month, u.week, picks("app:${a.pkg}")), rank = APP_RANK)
        }

    /** The edits: hidden entries leave, moved ones change group (no subgroup), pinned ones get [PIN_BOOST]. */
    fun edit(leaves: List<AppLeaf>, s: LauncherSettings): List<AppLeaf> = leaves.filter { it.pkg !in s.hidden }.map { l ->
        l.copy(category = s.moved[l.pkg] ?: l.category, weight = if (l.pkg in s.pinned) l.weight + PIN_BOOST else l.weight)
    }

    /** All entries before the edits, for the settings screen (the OS programs that are always there, plus every app). */
    fun all(c: Context, s: LauncherSettings): List<AppLeaf> {
        val programs = LauncherSettings.HUB_ITEMS.keys.map { k -> val key = "hub:$k"
            AppLeaf(key, programLabel(key), AppCategories.categoryOf(key), 0, AppWeights.weight(key, 0, 0, Actions.choices.intentCount(key)), native = true, rank = s.hub.indexOf(k)) }
        return programs + apps(DeviceApps.installed(c), DeviceApps.use(c), { ControllerNotificationListener.mutes.isMuted(it) }, emptyMap()) { Actions.choices.intentCount(it) }
    }

    const val APP_RANK = 1000
}
