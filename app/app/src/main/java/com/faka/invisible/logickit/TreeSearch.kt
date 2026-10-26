package com.faka.invisible.logickit

import com.faka.invisible.AppLeaf
import com.faka.invisible.TreeNode

/**
 * Pure: type part of a name, get where it sits in the spoken menu. Two ways to reach it: walking the tree from home
 * ("Media (2nd) > Volume (3rd)") and Find, the hold on home ("A to C (1st) > Volume (2nd)"). The place is the position
 * heard in each list, so it is the number of clicks to wait.
 */
object TreeSearch {
    const val MAX_HITS = 8
    class Hit(val app: AppLeaf, val tree: String, val find: String?)

    fun ordinal(n: Int) = n.toString() + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    /** Leaves whose name has [query] in it (letters and digits only, any case); names that start with it first. [find] may be null while its plan is still being built. */
    fun search(root: TreeNode, find: NameFinder.Node?, query: String): List<Hit> {
        val q = NameFinder.key(query)
        if (q.isEmpty()) return emptyList()
        val found = ArrayList<Pair<AppLeaf, String>>()
        fun walk(n: TreeNode, trail: List<String>) { n.children.forEachIndexed { i, c ->
            val step = "${c.app?.label ?: c.label} (${ordinal(i + 1)})"
            val a = c.app
            if (a != null) { if (q in NameFinder.key(a.label)) found += a to (trail + step).joinToString(" > ") }
            else walk(c, trail + step)
        } }
        walk(root, emptyList())
        return found.sortedWith(compareBy({ !NameFinder.key(it.first.label).startsWith(q) }, { NameFinder.key(it.first.label) })).take(MAX_HITS)
            .map { (a, t) -> Hit(a, t, find?.let { findPath(it, a.pkg) }) }
    }

    /** The steps of Find to this app, or null if the plan has no such app. */
    fun findPath(n: NameFinder.Node, pkg: String): String? {
        n.parts.forEachIndexed { i, p ->
            val step = "${p.label} (${ordinal(i + 1)})"
            if (p.leaf?.pkg == pkg) return step
            val sub = p.sub?.let { findPath(it, pkg) }
            if (sub != null) return "$step > $sub"
        }
        return null
    }
}
