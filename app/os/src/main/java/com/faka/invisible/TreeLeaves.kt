package com.faka.invisible

import android.content.Context

/**
 * The one place that decides which entries are in the spoken tree and where they sit. The voice ([ControllerMode]) and the settings screen both call it,
 * so what you hear and what you see are the same tree. The edits (pin, hide, move) live in [LauncherSettings] and are applied here, once.
 */
object TreeLeaves {
    val GROUPS = listOf("Talk", "Plan", "Pay", "Work", "Watch and listen", "Play", "Go", "Tools")
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
    fun edit(leaves: List<AppLeaf>, s: LauncherSettings): List<AppLeaf> = UniqueNames.apply(leaves.filter { it.pkg !in s.hidden }, reservedNames()).map { l ->
        l.copy(category = s.moved[l.pkg] ?: l.category, weight = if (l.pkg in s.pinned) l.weight + PIN_BOOST else l.weight)
    }

    /** Group names are always on the home list: an app may not be called like one. (Sub-groups are renamed in the tags instead; a sub with one entry disappears.) */
    private fun reservedNames(): Set<String> = GROUPS.toSet()

    /** All entries before the edits, for the settings screen (the OS programs that are always there, plus every app). */
    fun all(c: Context, s: LauncherSettings): List<AppLeaf> {
        val programs = LauncherSettings.HUB_ITEMS.keys.map { k -> val key = "hub:$k"
            AppLeaf(key, programLabel(key), AppCategories.categoryOf(key), 0, AppWeights.weight(key, 0, 0, Actions.choices.intentCount(key)), rank = s.hub.indexOf(k)) }
        val use = java.util.concurrent.CompletableFuture.supplyAsync { DeviceApps.use(c) }   // the slow usage query runs while the app list loads
        return programs + apps(DeviceApps.installed(c), use.get(), { ControllerNotificationListener.mutes.isMuted(it) }, emptyMap()) { Actions.choices.intentCount(it) }
    }

    const val APP_RANK = 1000

    /** Dev tool (`am broadcast ... --ez tree true`, logcat tag TREEDUMP): the home tree exactly as the voice walks it, built from this phone's apps and settings. */
    fun dump(c: Context): String {
        val s = SettingsStore.load(c.getSharedPreferences("invisible", Context.MODE_PRIVATE))
        fun d(n: TreeNode, ind: String): String = n.children.joinToString("") { k -> ind + k.label + (if (k.isApp) "" else " (${k.children.size})") + "\n" + d(k, "$ind  ") }
        val leaves = edit(all(c, s), s); val root = tree(leaves)
        // one COST line per app: items heard and levels, in the tree and in Find (for medians and weighted means)
        val plan = NameFinder.plan(leaves)
        fun find(n: NameFinder.Node, pkg: String, heard: Int, levels: Int): Pair<Int, Int>? {
            n.parts.forEachIndexed { i, p -> if (p.leaf?.pkg == pkg) return heard + i + 1 to levels + 1
                p.sub?.let { sub -> find(sub, pkg, heard + i + 1, levels + 1)?.let { return it } } }
            return null
        }
        val cost = AppTree.paths(root).joinToString("") { p -> val f = find(plan, p.app.pkg, 0, 0)
            "COST\t${p.app.label}\t%.1f\t${p.heard}\t${p.levels + 1}\t${f?.first}\t${f?.second}\n".format(p.app.weight) }
        return d(root, "") + cost
    }

    /** Set once at start (Actions.init): where the last levels are kept, so the next tree search starts from them. */
    @Volatile var store: android.content.SharedPreferences? = null

    /** The spoken tree for these leaves: the voice and the settings screen both come here. */
    fun tree(leaves: List<AppLeaf>): TreeNode {
        val p = store
        val warm = p?.getString("tree_levels", null)?.split(',')?.filter { '=' in it }?.associate { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: -1) }
        // Same apps, same places, same pins, same week: the earlier levels still hold, only the order inside each list is recomputed (cheap).
        val sig = leaves.sortedBy { it.pkg }.joinToString("|") { "${it.pkg}:${it.category}:${it.weight >= PIN_BOOST}" }.hashCode().toString() + "/" + System.currentTimeMillis() / (7 * 86_400_000L)
        if (warm != null && p?.getString("tree_sig", null) == sig) AppTree.fromLevels(leaves, warm)?.let { return it }
        val (root, levels) = AppTree.built(leaves, warm)
        p?.edit()?.putString("tree_levels", levels.entries.joinToString(",") { it.key + "=" + it.value })?.putString("tree_sig", sig)?.apply()
        return root
    }
}
