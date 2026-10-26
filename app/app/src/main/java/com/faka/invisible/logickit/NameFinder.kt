package com.faka.invisible.logickit

import com.faka.invisible.AppLeaf
import com.faka.invisible.AppTree
import com.faka.invisible.voicekit.Spoken

/**
 * "Find": reach ANY app or program by its name, without knowing its group. Pure.
 * All leaves are sorted by spoken name. A Find list splits a stretch of that order into at most [MAX_PARTS] neighbouring parts:
 * a part with one leaf is that leaf (its name), a part with more is a RANGE named by the shortest starts that tell it from its neighbours
 * ("W", "Sa to Sp"). Parts are read A to Z. Cost = sum over leaves of weight * (place in its list + [AppTree.LEVEL] per range entered).
 * [plan] picks the splits for the lowest cost: exact for stretches of 6 or fewer leaves, and for longer ones the best of a few
 * weight-balanced starts improved by moving one cut at a time (memoised per stretch). It is slow on a phone: build it off the main thread.
 */
object NameFinder {
    const val MAX_PARTS = AppTree.MAX_LIST

    /** One entry of a Find list: a leaf ([leaf]), or a range ([sub] = the list inside it). [label] is what is spoken for a range. */
    class Part(val label: String, val leaf: AppLeaf?, val sub: Node?)
    /** One Find list, in reading order. */
    class Node(val parts: List<Part>)

    fun key(label: String) = label.lowercase().filter { it.isLetterOrDigit() }

    fun plan(leaves: List<AppLeaf>): Node = Planner(leaves.sortedWith(compareBy({ key(it.label) }, { it.pkg }))).root()

    /** Every leaf below a part or list. */
    /** Dev: the lists exactly as they are read, one line per part, indented per level. */
    fun dump(n: Node, ind: String = ""): String = n.parts.joinToString("") { p -> ind + p.label + (if (p.leaf != null) "  [app]" else "") + "\n" + (p.sub?.let { dump(it, ind + "    ") } ?: "") }

    fun leavesOf(n: Node): List<AppLeaf> = n.parts.flatMap { leavesOf(it) }
    fun leavesOf(p: Part): List<AppLeaf> = p.leaf?.let { listOf(it) } ?: leavesOf(p.sub!!)

    private class Planner(val ls: List<AppLeaf>) {
        val n = ls.size
        val keys = ls.map { key(it.label) }
        val pre = DoubleArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + ls[i].weight }
        val cost = Array(n + 1) { DoubleArray(n + 1) { -1.0 } }
        val cuts = Array(n + 1) { arrayOfNulls<IntArray>(n + 1) }
        fun w(i: Int, j: Int) = pre[j] - pre[i]

        fun root(): Node = if (n == 0) Node(emptyList()) else { solve(0, n); build(0, n) }

        /** Cost of reading the stretch i until j as one list, with the best boundaries (kept in [cuts]). */
        fun solve(i: Int, j: Int): Double {
            if (cost[i][j] >= 0) return cost[i][j]
            val m = j - i
            var best = Double.MAX_VALUE; var bb = intArrayOf(i, j)
            fun offer(b: IntArray) { val c = eval(b); if (c < best - 1e-9) { best = c; bb = b.copyOf() } }
            if (m == 1) best = w(i, j)
            else if (m <= MAX_PARTS) {
                for (mask in 1 until (1 shl (m - 1))) {
                    val b = ArrayList<Int>(); b += i
                    for (t in 0 until m - 1) if (mask and (1 shl t) != 0) b += i + t + 1
                    b += j; offer(b.toIntArray())
                }
            } else {
                for (k in listOf(MAX_PARTS, 3).distinct()) {
                    var b = start(i, j, k); var c = eval(b)
                    repeat(4) {
                        var moved = false
                        for (x in 1 until k) for (p in b[x - 1] + 1 until b[x + 1]) if (p != b[x]) {
                            val t = b.copyOf(); t[x] = p; val tc = eval(t)
                            if (tc < c - 1e-9) { c = tc; b = t; moved = true }
                        }
                        if (!moved) return@repeat
                    }
                    offer(b)
                }
            }
            cost[i][j] = best; cuts[i][j] = bb
            return best
        }

        /** [k] parts of about equal weight. */
        fun start(i: Int, j: Int, k: Int): IntArray {
            val b = IntArray(k + 1); b[0] = i; b[k] = j
            for (x in 1 until k) {
                var p = i + 1
                while (p < j && pre[p] - pre[i] < w(i, j) * x / k) p++
                b[x] = p.coerceIn(b[x - 1] + 1, j - (k - x))
            }
            return b
        }

        /** Cost of a split: parts read heaviest first; a range also costs LEVEL per leaf-weight and the best list inside it. */
        fun eval(b: IntArray): Double {
            val k = b.size - 1
            val ws = DoubleArray(k) { w(b[it], b[it + 1]) }
            var inner = 0.0
            for (x in 0 until k) if (b[x + 1] - b[x] > 1) inner += AppTree.LEVEL * ws[x] + solve(b[x], b[x + 1])
            return (0 until k).sumOf { x -> (x + 1) * ws[x] } + inner   // A to Z order
        }

        /** A name without a count after it ("Messages, 4 unread", "Tasks, 4"): a count changes, the range must not. */
        fun plain(l: String) = l.replace(Regex(",\\s*\\d+( unread)?$"), "")

        fun build(i: Int, j: Int): Node {
            val b = cuts[i][j]!!
            val k = b.size - 1
            return Node((0 until k).map { x ->
                val a = b[x]; val e = b[x + 1]
                if (e - a == 1) Part(ls[a].label, ls[a], null) else { solve(a, e); Part(Spoken.range(plain(ls[a].label), plain(ls[e - 1].label)), null, build(a, e)) }
            })
        }

        /** The shortest start of name [a] that tells it from the name before. */
        fun from(a: Int): String = cap(if (a == 0) keys[a].take(1) else short(keys[a], keys[a - 1]))
        /** The shortest start of name [z] that tells it from the name after. */
        fun to(z: Int): String = cap(if (z == n - 1) keys[z].take(1) else short(keys[z], keys[z + 1]))
        fun short(k: String, other: String): String = (1..k.length).map { k.take(it) }.firstOrNull { !other.startsWith(it) } ?: k
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    }
}
