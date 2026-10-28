package com.faka.invisible

/**
 * An entry that may appear in the tree: an app, or a built-in program of the OS (pkg "hub:tasks"). [category] = place, see [AppCategories].
 * [unread] = its notifications now (shown in the label only; it never changes the order). [weight] = how much it is used, see [AppWeights].
 * [rank] = the user's own order, used only when weights tie (lower first).
 * [live] = the app is on home as a live entry in front of the tree ([LiveApps]): it is never promoted again, and each live app takes one promoted slot.
 */
data class AppLeaf(val pkg: String, val label: String, val category: String, val unread: Int, val weight: Double, val rank: Int = 0, val live: Boolean = false)

/** One list entry: a group ([children]) or an app ([app]). [unread] and [weight] are sums over the leaves below. */
class TreeNode(val label: String, val children: List<TreeNode> = emptyList(), val app: AppLeaf? = null, val unread: Int = 0, val weight: Double = 0.0, val rank: Int = 0,
               /** Groups only: the names of the best apps inside, heaviest first (at most 2, or all when the group holds 3 apps or fewer), and how many more apps there are. */
               val hint: List<String> = emptyList(), val more: Int = 0) {
    val isApp get() = app != null
}


/**
 * Pure: the spoken tree, searched for the lowest expected cost on the ear.
 *
 * Cost of reaching a leaf = sum of its 1-based positions along the path (items heard) + [LEVEL] for every list entered below home
 * (the extra click and the earcon). Expected cost = sum over leaves of P(leaf) * cost, P = weight / total weight ([AppWeights]).
 *
 * Fixed: the groups and sub-groups of [AppCategories] (a leaf is in its sub-group, or lifted up). Free, and searched:
 *  - the order inside a list: by weight, heaviest first. This is optimal for one list read from the front: swapping a heavier item
 *    behind a lighter one changes the cost by (w_heavy - w_light) * (distance) > 0, so the sorted order cannot be beaten (exchange argument).
 *    The same holds for groups, whose weight is the sum below them (top-level groups end up ordered by total weight).
 *  - there is no "More": a list is never longer than [MAX_LIST]. A place with more apps than that must be split by tags (AppCategories, enforced by a test);
 *    when an untagged pile still overflows, [spread] cuts it by first letter ("A to D") so the list stays sorted and short.
 *  - the level of each leaf: in its sub-group, flattened into the group list, or promoted to the group list, or (two apps at most) home. A leaf is lifted
 *    only when what it saves beats what it adds to the others (the search compares whole-tree costs). Sub-groups flatten the same way.
 * The level assignment is found by local search (best single move until none helps) from two starts (deepest, sub-groups flattened); each step is an exact layout.
 * Every group is always a direct entry of home, ordered by total weight, and at most [PROMOTED] apps may be promoted to home on top
 * of them, so home has up to [HOME_MAX] entries. A sub-group with one entry is replaced by it. No other list is more than [MAX_LIST],
 * and a leaf is at most 3 lists deep (home, group, sub-group).
 */
object AppTree {
    const val HOME = 6          // kept for the old builder's comparison
    const val PROMOTED = 2      // apps that may sit on home next to the groups
    const val HOME_MAX = 8 + PROMOTED
    const val MAX_LIST = 8
    /** Always a direct entry of home (one of the promoted slots): the Laptop tool, the owner's most used place. */
    val PINNED = setOf("hub:claude")
    const val LEVEL = 0.5
    private const val INF = Double.POSITIVE_INFINITY
    private const val EPS = 1e-9

    private class Entry(val node: TreeNode, val inner: Double = 0.0)   // inner = LEVEL * weight + cost of the list below it (0 for an app)
    private class Layout(val cost: Double, val root: TreeNode, val levels: Map<String, Int>)

    private val ORDER = compareByDescending<Entry> { it.node.app?.pkg in PINNED }.thenByDescending { it.node.weight }.thenBy { it.node.rank }.thenBy { it.node.label }.thenBy { it.node.app?.pkg ?: "" }

    fun build(leaves: List<AppLeaf>): TreeNode = built(leaves, null).first

    /**
     * The tree and the level of every leaf. [warm] = the levels of an earlier build: when they still fit, the search starts there and needs 0 to 2 steps
     * instead of about 30 (the phone is about 40 times slower than the laptop). The result is a local optimum of the same cost either way.
     */
    fun built(leaves: List<AppLeaf>, warm: Map<String, Int>?): Pair<TreeNode, Map<String, Int>> {
        val ls = spread(leaves).sortedBy { it.pkg }
        if (ls.isEmpty()) return TreeNode("") to emptyMap()
        val memo = HashMap<List<Any>, List<Entry>>()   // one group's list for one level assignment; a move changes one group, the others are reused
        val fits = warm != null && ls.all { l -> warm[l.pkg]?.let { allowed(l, it) } == true }
        val warmLayout = if (fits) layout(ls, warm!!, memo).takeIf { it.cost < INF } else null
        val best = if (warmLayout != null) climb(ls, warmLayout, memo)
        else climb(ls, layout(ls, ls.associate { it.pkg to if (it.pkg in PINNED) 0 else maxLevel(it) }, memo), memo)
        return (if (best.cost < INF) best.root else crowded(ls)) to best.levels
    }

    /** A place (group, or group/sub) with more than [MAX_LIST] apps is cut into chunks of the label's first letters ("A to D"), each a sub-group. */
    fun spread(leaves: List<AppLeaf>): List<AppLeaf> = leaves.groupBy { it.category }.flatMap { (place, ls) ->
        if (ls.size <= MAX_LIST) ls else {
            val sorted = ls.sortedBy { it.label.lowercase() }
            val k = (sorted.size + MAX_LIST - 1) / MAX_LIST; val size = (sorted.size + k - 1) / k
            val chunks = sorted.chunked(size)
            fun pre(t: String, n: Int) = t.take(n).replaceFirstChar { it.uppercaseChar() }
            fun name(c: List<AppLeaf>, n: Int) = pre(c.first().label, n).let { a -> pre(c.last().label, n).let { b -> if (a == b) a else "$a to $b" } }
            val n = (1..3).firstOrNull { n -> chunks.map { name(it, n) }.distinct().size == chunks.size } ?: 3
            chunks.withIndex().flatMap { (idx, c) ->
                val tag = name(c, n).let { t -> if (chunks.map { name(it, n) }.distinct().size == chunks.size) t else "$t ${idx + 1}" }
                c.map { it.copy(category = AppCategories.main(place) + "/" + (AppCategories.sub(place)?.let { s -> "$s $tag" } ?: tag)) }
            }
        }
    }

    /** Last resort when no layout fits the list limits (a test guards the real tags): the groups with their apps sorted, still no "More". */
    private fun crowded(ls: List<AppLeaf>): TreeNode =
        node("", ls.groupBy { AppCategories.main(it.category) }.map { (g, l) -> node(g, l.sortedWith(compareByDescending<AppLeaf> { it.weight }.thenBy { it.label }).map(::leafNode)) }.sortedByDescending { it.weight })

    /** The tree for levels found earlier: one layout, no search. Null when the levels no longer fit these leaves. */
    fun fromLevels(leaves: List<AppLeaf>, levels: Map<String, Int>): TreeNode? {
        val ls = spread(leaves).sortedBy { it.pkg }
        if (ls.isEmpty() || !ls.all { l -> levels[l.pkg]?.let { allowed(l, it) } == true }) return null
        return layout(ls, levels, HashMap()).takeIf { it.cost < INF }?.root
    }

    /** An app is in its own place (group, or group/sub) or promoted to home: it is never lifted out of its sub-group into a mixed list, so each list holds one kind of thing. */
    private fun allowed(l: AppLeaf, v: Int) = if (l.pkg in PINNED) v == 0 else (v == 0 && !l.live) || v == maxLevel(l)

    private fun maxLevel(l: AppLeaf) = if (AppCategories.sub(l.category) != null) 2 else 1

    private fun leafNode(a: AppLeaf) = TreeNode(a.label, app = a, unread = a.unread, weight = a.weight, rank = a.rank)
    private fun leafEntry(a: AppLeaf) = Entry(leafNode(a))
    private fun node(label: String, kids: List<TreeNode>, weight: Double = kids.sumOf { it.weight }): TreeNode {
        val (hint, more) = scent(kids)
        return TreeNode(label, kids, unread = kids.sumOf { it.unread }, weight = weight, hint = hint, more = more)
    }

    private fun appCount(n: TreeNode): Int = if (n.isApp) 1 else n.children.sumOf { appCount(it) }

    /** What a group says about itself (information scent): the top app of each of its first entries (a sub-group gives its top app), or every app when there are 3 or fewer. */
    fun scent(kids: List<TreeNode>): Pair<List<String>, Int> {
        val total = kids.sumOf { appCount(it) }
        fun top(n: TreeNode): List<String> = if (n.isApp) listOf(n.label) else n.hint.take(1).ifEmpty { n.children.take(1).flatMap { top(it) } }
        val all = if (total <= 3) kids.flatMap { if (it.isApp) listOf(it.label) else apps(it).map { a -> a.label } } else kids.flatMap { top(it) }
        val names = all.distinct().take(if (total <= 3) 3 else 2)
        return names to (total - names.size)
    }

    // ---- the layout of one level assignment (0 = home, 1 = group list, 2 = sub-group list) ----
    private fun layout(ls: List<AppLeaf>, lv: Map<String, Int>, memo: MutableMap<List<Any>, List<Entry>>): Layout {
        val home = ls.filter { lv.getValue(it.pkg) == 0 }.map(::leafEntry) +
            ls.filter { lv.getValue(it.pkg) > 0 }.groupBy { AppCategories.main(it.category) }.flatMap { (g, l) ->
                memo.getOrPut(listOf<Any>(g) + l.map { it.pkg to lv.getValue(it.pkg) }) { listOfNotNull(listEntry(g, groupEntries(l, lv))) }
            }
        val slots = maxOf(0, PROMOTED - ls.count { it.live })   // live apps are shown in front of the tree and use up the promoted slots
        if (home.size > HOME_MAX - PROMOTED + slots || ls.count { lv.getValue(it.pkg) == 0 } > slots) return Layout(INF, node("", emptyList()), lv)
        val (cost, kids) = arrange(home, HOME_MAX)
        return Layout(cost, node("", kids), lv)
    }

    private fun groupEntries(l: List<AppLeaf>, lv: Map<String, Int>): List<Entry> =
        l.filter { lv.getValue(it.pkg) == 1 }.map(::leafEntry) +
            l.filter { lv.getValue(it.pkg) == 2 }.groupBy { AppCategories.sub(it.category) }.mapNotNull { (s, x) -> listEntry(s ?: "", x.map(::leafEntry)) }

    /** A group as one list entry; nothing -> none, one entry -> that entry itself. */
    private fun listEntry(label: String, entries: List<Entry>): Entry? = when (entries.size) {
        0 -> null
        1 -> entries[0].let { e -> if (e.node.isApp) e else Entry(node(label, e.node.children), e.inner) }   // a group keeps its name; a lone app is itself
        else -> arrange(entries, MAX_LIST).let { (cost, kids) -> node(label, kids).let { Entry(it, LEVEL * it.weight + cost) } }
    }

    private fun listCost(e: List<Entry>, from: Int, count: Int) = (0 until count).sumOf { j -> (j + 1) * e[from + j].node.weight + e[from + j].inner }

    /** The list for these entries: sorted by weight. More than [cap] entries do not fit: infinite cost. */
    private fun arrange(items: List<Entry>, cap: Int): Pair<Double, List<TreeNode>> {
        val e = items.sortedWith(ORDER)
        return (if (e.size > cap) INF else listCost(e, 0, e.size)) to e.map { it.node }
    }

    // ---- local search over the levels ----
    private fun climb(ls: List<AppLeaf>, start: Layout, memo: MutableMap<List<Any>, List<Entry>>): Layout {
        var cur = start
        while (true) {
            val next = moves(ls, cur.levels).map { layout(ls, it, memo) }.minByOrNull { it.cost }
            if (next == null || next.cost >= cur.cost - EPS) return cur
            cur = next
        }
    }

    /** Every level assignment one move away: one app straight home (promoted) or back to its place. */
    private fun moves(ls: List<AppLeaf>, lv: Map<String, Int>): List<Map<String, Int>> =
        ls.filter { !it.live && it.pkg !in PINNED }.map { l -> lv + (l.pkg to if (lv.getValue(l.pkg) > 0) 0 else maxLevel(l)) }

    // ---- measuring any tree (the old one, the flat list, this one) ----
    class Path(val app: AppLeaf, val heard: Int, val levels: Int)

    fun paths(root: TreeNode): List<Path> = walk(root, 0, 0)
    private fun walk(n: TreeNode, heard: Int, levels: Int): List<Path> = n.children.withIndex().flatMap { (i, c) ->
        if (c.isApp) listOf(Path(c.app!!, heard + i + 1, levels)) else walk(c, heard + i + 1, levels + 1)
    }

    /** Expected cost per open: sum of P(leaf) * (items heard + LEVEL * lists entered). */
    fun expectedCost(root: TreeNode): Double = mean(root) { it.heard + LEVEL * it.levels }

    /** Expected number of items heard per open (no level cost). */
    fun expectedHeard(root: TreeNode): Double = mean(root) { it.heard.toDouble() }

    private fun mean(root: TreeNode, f: (Path) -> Double): Double {
        val ps = paths(root); val total = ps.sumOf { it.app.weight }
        return if (total <= 0.0) 0.0 else ps.sumOf { it.app.weight * f(it) } / total
    }

    /** Items heard to reach the [n] most used apps (summed over them). */
    fun heardToTop(root: TreeNode, n: Int): Double =
        paths(root).sortedWith(compareByDescending<Path> { it.app.weight }.thenBy { it.app.pkg }).take(n).sumOf { it.heard.toDouble() }

    /** The longest list below [root] (the home list is [root] itself). */
    fun maxListLength(root: TreeNode): Int = maxOf(root.children.size, root.children.maxOfOrNull { maxListLength(it) } ?: 0)

    /** Levels of lists from the home list to a leaf. */
    fun depth(root: TreeNode): Int = 1 + (root.children.maxOfOrNull { c -> if (c.isApp) 0 else depth(c) } ?: 0)

    fun apps(root: TreeNode): List<AppLeaf> = root.children.flatMap { if (it.isApp) listOf(it.app!!) else apps(it) }
}
