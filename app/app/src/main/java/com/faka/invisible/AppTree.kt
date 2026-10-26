package com.faka.invisible

/**
 * An entry that may appear in the tree: an app, or a built-in program of the OS (pkg "hub:tasks"). [category] = place, see [AppCategories].
 * [unread] = its notifications now. [weight] = how much it is used (1 + picks; higher = earlier in its list).
 * [native] = shown even with nothing unread (built-ins and apps with a native module). [rank] = the user's own order, used when weights tie (lower first).
 */
data class AppLeaf(val pkg: String, val label: String, val category: String, val unread: Int, val weight: Double, val native: Boolean = false, val rank: Int = 0)

/** One list entry: a group ([children]), a "More" tail, or an app ([app]). [unread] and [weight] are sums over the leaves below. */
class TreeNode(val label: String, val children: List<TreeNode> = emptyList(), val app: AppLeaf? = null, val unread: Int = 0, val weight: Double = 0.0, val isMore: Boolean = false, val rank: Int = 0) {
    val isApp get() = app != null
}

/**
 * Pure: the group tree, built so that every spoken list is short (at most [K] entries, the home list [HOME]) and what you use most is early.
 * Depth is cheap, a long list is not. Groups: only what is active appears; a group with one entry is replaced by that entry;
 * a group that fits in K entries is one flat list; a longer one is split by sub-group; what still does not fit goes behind "More"
 * (the best K-1 stay first). Up to [PROMOTE_MAX] leaves that are unread or used a lot are lifted to the home list when it has room.
 */
object AppTree {
    const val K = 5
    const val HOME = 6
    const val MORE = "More"
    const val PROMOTE_MAX = 2
    const val PROMOTE_WEIGHT = 4.0   // used 3 times

    fun build(leaves: List<AppLeaf>, k: Int = K, home: Int = HOME): TreeNode {
        val active = leaves.filter { it.unread > 0 || it.native }
        if (active.size <= k) return list(order(active.map(::leaf)))
        val (rest, lifted) = promote(active, k, home)
        var top = groups(rest, k)
        if (top.size == 1 && !top[0].isApp && top[0].children.size + lifted.size <= home) top = top[0].children
        return list(fit(order(top + lifted.map(::leaf)), home))
    }

    private fun leaf(a: AppLeaf) = TreeNode(a.label, app = a, unread = a.unread, weight = a.weight, rank = a.rank)
    private fun node(label: String, children: List<TreeNode>, more: Boolean = false) =
        TreeNode(label, children, unread = children.sumOf { it.unread }, weight = children.sumOf { it.weight }, isMore = more, rank = children.minOfOrNull { it.rank } ?: 0)
    private fun list(children: List<TreeNode>) = node("", children)

    /** Unread first, then more used, then label (so positions do not jump between runs). */
    private fun order(nodes: List<TreeNode>) = nodes.sortedWith(compareByDescending<TreeNode> { it.unread > 0 }.thenByDescending { it.weight }.thenBy { it.rank }.thenBy { it.label }.thenBy { it.app?.pkg ?: "" })

    private fun collapse(n: TreeNode) = if (!n.isApp && n.children.size == 1) n.children[0] else n

    private fun groups(ls: List<AppLeaf>, k: Int): List<TreeNode> =
        order(ls.groupBy { AppCategories.main(it.category) }.map { (name, l) -> collapse(group(name, l, k)) })

    /** Lifts the best leaves one by one while the home list still has room for them. */
    private fun promote(active: List<AppLeaf>, k: Int, home: Int): Pair<List<AppLeaf>, List<AppLeaf>> {
        val best = active.filter { it.unread > 0 || it.weight >= PROMOTE_WEIGHT }
            .sortedWith(compareByDescending<AppLeaf> { it.unread > 0 }.thenByDescending { it.weight }.thenBy { it.rank }.thenBy { it.pkg }).take(PROMOTE_MAX)
        for (n in best.size downTo 1) {
            val lifted = best.take(n); val rest = active - lifted.toSet()
            if (groups(rest, k).size + n <= home) return rest to lifted
        }
        return active to emptyList()
    }

    /** One group: flat when it fits in k, else split by sub-group. */
    private fun group(name: String, ls: List<AppLeaf>, k: Int): TreeNode {
        if (ls.size <= k) return node(name, order(ls.map(::leaf)))
        val kids = ls.groupBy { AppCategories.sub(it.category) }.flatMap { (s, l) ->
            if (s == null) l.map(::leaf) else listOf(collapse(node(s, fit(order(l.map(::leaf)), k))))
        }
        return node(name, fit(order(kids), k))
    }

    /** At most k entries: the best k-1 stay, the rest go into "More" (which is fitted again). */
    private fun fit(nodes: List<TreeNode>, k: Int): List<TreeNode> =
        if (nodes.size <= k) nodes else nodes.take(k - 1) + node(MORE, fit(nodes.drop(k - 1), k), more = true)

    /** Expected number of steps to reach a leaf: sum over leaves of weight * (sum of 1-based positions along its path). */
    fun expectedCost(root: TreeNode): Double = cost(root, 0)

    private fun cost(n: TreeNode, pathSum: Int): Double = n.children.withIndex().sumOf { (i, c) ->
        val p = pathSum + i + 1
        if (c.isApp) c.weight * p else cost(c, p)
    }

    /** The longest list below [root] (the home list is [root] itself). */
    fun maxListLength(root: TreeNode): Int = maxOf(root.children.size, root.children.maxOfOrNull { maxListLength(it) } ?: 0)

    /** Levels of lists from the home list to a leaf; a "More" tail is the same level as its list. */
    fun depth(root: TreeNode): Int = 1 + (root.children.maxOfOrNull { c -> if (c.isApp) 0 else if (c.isMore) depth(c) - 1 else depth(c) } ?: 0)

    /** Lists on the longest path with every "More" counted (what the ear really walks). */
    fun rawDepth(root: TreeNode): Int = 1 + (root.children.maxOfOrNull { c -> if (c.isApp) 0 else rawDepth(c) } ?: 0)

    fun apps(root: TreeNode): List<AppLeaf> = root.children.flatMap { if (it.isApp) listOf(it.app!!) else apps(it) }
}
