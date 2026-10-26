package com.faka.invisible

/**
 * An entry that may appear in the tree: an app, or a built-in program of the OS (pkg "hub:tasks"). [category] = place, see [AppCategories].
 * [unread] = its notifications now (shown in the label only; it never changes the order). [weight] = how much it is used, see [AppWeights].
 * [rank] = the user's own order, used only when weights tie (lower first). [native] is only used by the old [AppTreeRules].
 */
data class AppLeaf(val pkg: String, val label: String, val category: String, val unread: Int, val weight: Double, val native: Boolean = false, val rank: Int = 0)

/** One list entry: a group ([children]), a "More" tail, or an app ([app]). [unread] and [weight] are sums over the leaves below. */
class TreeNode(val label: String, val children: List<TreeNode> = emptyList(), val app: AppLeaf? = null, val unread: Int = 0, val weight: Double = 0.0, val isMore: Boolean = false, val rank: Int = 0) {
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
 *  - where a long list is cut by "More": exact dynamic programming per list ([arrange]); the cut is chosen by cost, each list is 3 to 6 long.
 *    "More" taking the light tail is also how a big sub-group is split into a head and a tail by weight.
 *  - the level of each leaf: in its sub-group, flattened into the group list, or promoted to the group list, or (two apps at most) home. A leaf is lifted
 *    only when what it saves beats what it adds to the others (the search compares whole-tree costs). Sub-groups flatten the same way.
 * The level assignment is found by local search (best single move until none helps) from two starts (deepest, sub-groups flattened); each step is an exact layout.
 * Every group is always a direct entry of home (never behind "More"), ordered by total weight, and at most [PROMOTED] apps may be promoted to home on top
 * of them, so home has up to [HOME_MAX] entries. A sub-group with one entry is replaced by it. No other list is more than [MAX_LIST],
 * and a leaf is at most 3 lists deep (home, group, sub-group), "More" tails not counted.
 */
object AppTree {
    const val HOME = 6          // kept for the old builder's comparison
    const val PROMOTED = 2      // apps that may sit on home next to the groups
    const val HOME_MAX = 6 + PROMOTED
    const val MAX_LIST = 6
    const val MIN_LIST = 3
    const val LEVEL = 0.5
    const val MORE = "More"
    private const val EPS = 1e-9

    private class Entry(val node: TreeNode, val inner: Double = 0.0)   // inner = LEVEL * weight + cost of the list below it (0 for an app)
    private class Layout(val cost: Double, val root: TreeNode, val levels: Map<String, Int>)

    private val ORDER = compareByDescending<Entry> { it.node.weight }.thenBy { it.node.rank }.thenBy { it.node.label }.thenBy { it.node.app?.pkg ?: "" }

    fun build(leaves: List<AppLeaf>): TreeNode {
        val ls = leaves.sortedBy { it.pkg }
        if (ls.isEmpty()) return TreeNode("")
        val starts = listOf(ls.associate { it.pkg to maxLevel(it) }, ls.associate { it.pkg to minOf(1, maxLevel(it)) })
        return starts.map { climb(ls, layout(ls, it)) }.minBy { it.cost }.root
    }

    private fun maxLevel(l: AppLeaf) = if (AppCategories.sub(l.category) != null) 2 else 1

    private fun leafNode(a: AppLeaf) = TreeNode(a.label, app = a, unread = a.unread, weight = a.weight, rank = a.rank)
    private fun leafEntry(a: AppLeaf) = Entry(leafNode(a))
    private fun node(label: String, kids: List<TreeNode>, more: Boolean = false, weight: Double = kids.sumOf { it.weight }) =
        TreeNode(label, kids, unread = kids.sumOf { it.unread }, weight = weight, isMore = more)

    // ---- the layout of one level assignment (0 = home, 1 = group list, 2 = sub-group list) ----
    private fun layout(ls: List<AppLeaf>, lv: Map<String, Int>): Layout {
        val home = ls.filter { lv.getValue(it.pkg) == 0 }.map(::leafEntry) +
            ls.filter { lv.getValue(it.pkg) > 0 }.groupBy { AppCategories.main(it.category) }.mapNotNull { (g, l) -> listEntry(g, groupEntries(l, lv)) }
        if (home.size > HOME_MAX || ls.count { lv.getValue(it.pkg) == 0 } > PROMOTED) return Layout(Double.MAX_VALUE, node("", emptyList()), lv)
        val (cost, kids) = arrange(home, HOME_MAX)
        return Layout(cost, node("", kids), lv)
    }

    private fun groupEntries(l: List<AppLeaf>, lv: Map<String, Int>): List<Entry> =
        l.filter { lv.getValue(it.pkg) == 1 }.map(::leafEntry) +
            l.filter { lv.getValue(it.pkg) == 2 }.groupBy { AppCategories.sub(it.category) }.mapNotNull { (s, x) -> listEntry(s ?: "", x.map(::leafEntry)) }

    /** A group as one list entry; nothing -> none, one entry -> that entry itself. */
    private fun listEntry(label: String, entries: List<Entry>): Entry? = when (entries.size) {
        0 -> null
        1 -> entries[0].let { e -> if (e.node.isApp) e else Entry(node(label, e.node.children, e.node.isMore), e.inner) }   // a group keeps its name; a lone app is itself
        else -> arrange(entries, MAX_LIST).let { (cost, kids) -> node(label, kids).let { Entry(it, LEVEL * it.weight + cost) } }
    }

    private fun listCost(e: List<Entry>, from: Int, count: Int) = (0 until count).sumOf { j -> (j + 1) * e[from + j].node.weight + e[from + j].inner }

    /** Best list for these entries: sorted, and when more than [cap] a head of c entries (c from 2 to cap-1) then "More" holding the exact best list of the rest. */
    private fun arrange(items: List<Entry>, cap: Int): Pair<Double, List<TreeNode>> {
        val e = items.sortedWith(ORDER); val n = e.size
        val tail = DoubleArray(n + 1); for (i in n - 1 downTo 0) tail[i] = tail[i + 1] + e[i].node.weight
        val memo = arrayOfNulls<Pair<Double, List<TreeNode>>>(n + 1)
        fun best(i: Int): Pair<Double, List<TreeNode>> = memo[i] ?: run {
            var b: Pair<Double, List<TreeNode>>? = if (n - i <= cap) listCost(e, i, n - i) to e.subList(i, n).map { it.node } else null
            for (c in MIN_LIST - 1 until cap) {
                if (n - i - c < MIN_LIST) break
                val (rc, rk) = best(i + c)
                val cost = listCost(e, i, c) + (c + 1 + LEVEL) * tail[i + c] + rc
                if (b == null || cost < b.first - EPS) b = cost to (e.subList(i, i + c).map { it.node } + node(MORE, rk, more = true, weight = tail[i + c]))
            }
            b!!.also { memo[i] = it }
        }
        return best(0)
    }

    // ---- local search over the levels ----
    private fun climb(ls: List<AppLeaf>, start: Layout): Layout {
        var cur = start
        while (true) {
            val next = moves(ls, cur.levels).map { layout(ls, it) }.minByOrNull { it.cost }
            if (next == null || next.cost >= cur.cost - EPS) return cur
            cur = next
        }
    }

    /** Every level assignment one move away: one leaf a level up or down, one leaf straight home, a sub-group or a group flattened. */
    private fun moves(ls: List<AppLeaf>, lv: Map<String, Int>): List<Map<String, Int>> {
        val out = ArrayList<Map<String, Int>>()
        for (l in ls) {
            val v = lv.getValue(l.pkg)
            if (v > 0) out += lv + (l.pkg to v - 1)
            if (v < maxLevel(l)) out += lv + (l.pkg to v + 1)
            if (v > 1) out += lv + (l.pkg to 0)
        }
        for (key in ls.map { it.category }.distinct().sorted()) out += flatten(ls, lv) { it.category == key }
        for (key in ls.map { AppCategories.main(it.category) }.distinct().sorted()) out += flatten(ls, lv) { AppCategories.main(it.category) == key }
        return out.filter { it != lv }
    }

    private fun flatten(ls: List<AppLeaf>, lv: Map<String, Int>, inSet: (AppLeaf) -> Boolean): Map<String, Int> =
        lv + ls.filter { inSet(it) && lv.getValue(it.pkg) == 2 }.map { it.pkg to 1 }

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

    /** Levels of lists from the home list to a leaf; a "More" tail is the same level as its list. */
    fun depth(root: TreeNode): Int = 1 + (root.children.maxOfOrNull { c -> if (c.isApp) 0 else if (c.isMore) depth(c) - 1 else depth(c) } ?: 0)

    /** Lists on the longest path with every "More" counted (what the ear really walks). */
    fun rawDepth(root: TreeNode): Int = 1 + (root.children.maxOfOrNull { c -> if (c.isApp) 0 else rawDepth(c) } ?: 0)

    fun apps(root: TreeNode): List<AppLeaf> = root.children.flatMap { if (it.isApp) listOf(it.app!!) else apps(it) }
}
