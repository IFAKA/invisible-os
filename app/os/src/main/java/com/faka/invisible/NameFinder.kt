package com.faka.invisible

import com.faka.invisible.logickit.*

import com.faka.invisible.voicekit.Spoken

/**
 * "Find": reach ANY app or program by its name, without knowing its group. Pure.
 * All leaves are sorted by spoken name. A Find list splits a stretch of that order into at most [MAX_PARTS] neighbouring parts:
 * a part with one leaf is that leaf (its name), a part with more is a RANGE of whole first letters ("A to C", "W"): cuts never fall
 * inside a letter, so the listener needs only first letters. One letter with more than [LETTER_MAX] apps = ranges of its first TWO letters ("Wa to We"), never "Other". Parts are read A to Z. Cost = sum over leaves of weight * (place in its list + [STEP] per range entered).
 * [plan] picks the splits for the lowest cost: exact for stretches of 6 or fewer leaves, and for longer ones the best of a few
 * weight-balanced starts improved by moving one cut at a time (memoised per stretch). It is slow on a phone: build it off the main thread.
 */
object NameFinder {
    const val MAX_PARTS = AppTree.MAX_LIST
    /** A list of one letter's apps (or of one 2-letter start) is read whole up to this many; the phone's hold skips ahead. */
    const val LETTER_MAX = 12
    /** Cost of going one range down: a click and a new list to hold in mind, about one item heard (the tree uses 0.5; Find went 5 deep with it). */
    const val STEP = 1.0

    /** One entry of a Find list: a leaf ([leaf]), or a range ([sub] = the list inside it). [label] is what is spoken for a range. */
    class Part(val label: String, val leaf: AppLeaf?, val sub: Node?)
    /** One Find list, in reading order. */
    class Node(val parts: List<Part>)

    fun key(label: String) = label.lowercase().filter { it.isLetterOrDigit() }

    private fun byName(ls: List<AppLeaf>) = ls.sortedWith(compareBy({ key(it.label) }, { it.pkg }))

    /** The Find home list: the letter ranges of all apps (no favourites block: the tree already promotes the most used apps to home). */
    fun plan(leaves: List<AppLeaf>): Node = Planner(byName(leaves)).root()

    /** Every leaf below a part or list. */
    /** Dev: the lists exactly as they are read, one line per part, indented per level. */
    fun dump(n: Node, ind: String = ""): String = n.parts.joinToString("") { p -> ind + p.label + (if (p.leaf != null) "  [app]" else "") + "\n" + (p.sub?.let { dump(it, ind + "    ") } ?: "") }

    fun leavesOf(n: Node): List<AppLeaf> = n.parts.flatMap { leavesOf(it) }
    fun leavesOf(p: Part): List<AppLeaf> = p.leaf?.let { listOf(it) } ?: leavesOf(p.sub!!)

    private class Planner(val ls: List<AppLeaf>) {
        val n = ls.size
        val keys = ls.map { key(it.label) }
        val first = keys.map { it.firstOrNull() ?: ' ' }
        val pre = DoubleArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + ls[i].weight }
        val cost = Array(n + 1) { DoubleArray(n + 1) { -1.0 } }
        val cuts = Array(n + 1) { arrayOfNulls<IntArray>(n + 1) }
        fun w(i: Int, j: Int) = pre[j] - pre[i]

        /** Expected cost of reading this planner's leaves (weighted places, as in [solve]). */
        fun total(): Double = if (n == 0) 0.0 else solve(0, n)

        fun root(): Node = if (n == 0) Node(emptyList()) else { solve(0, n); build(0, n) }

        /** Where a cut may fall in i until j: only between two first letters, so a range is whole letters. Ends included. */
        fun points(i: Int, j: Int): IntArray = (listOf(i) + (i + 1 until j).filter { first[it] != first[it - 1] } + j).toIntArray()

        /** Cost of reading the stretch i until j as one list, with the best boundaries (kept in [cuts]). */
        fun solve(i: Int, j: Int): Double {
            if (cost[i][j] >= 0) return cost[i][j]
            val pts = points(i, j)
            val m = pts.size - 1   // letters in the stretch
            var best = Double.MAX_VALUE; var bb = intArrayOf(i, j)
            fun offer(b: IntArray) { val c = eval(b); if (c < best - 1e-9) { best = c; bb = b.copyOf() } }
            if (j - i == 1) best = w(i, j)
            else if (m == 1) best = letter((i until j).toList()).first
            else if (m <= MAX_PARTS) {
                for (mask in 0 until (1 shl (m - 1))) {
                    val b = ArrayList<Int>(); b += i
                    for (t in 0 until m - 1) if (mask and (1 shl t) != 0) b += pts[t + 1]
                    b += j; if (b.size > 2) offer(b.toIntArray())
                }
            } else {
                for (k in (MAX_PARTS downTo 3).filter { it <= m }) {
                    var b = start(pts, k); var c = eval(b.map { pts[it] }.toIntArray())
                    repeat(4) {
                        var moved = false
                        for (x in 1 until k) for (p in b[x - 1] + 1 until b[x + 1]) if (p != b[x]) {
                            val t = b.copyOf(); t[x] = p; val tc = eval(t.map { pts[it] }.toIntArray())
                            if (tc < c - 1e-9) { c = tc; b = t; moved = true }
                        }
                        if (!moved) return@repeat
                    }
                    offer(b.map { pts[it] }.toIntArray())
                }
            }
            cost[i][j] = best; cuts[i][j] = bb
            return best
        }

        /** [k] parts of about equal weight, as indexes into [pts]. */
        fun start(pts: IntArray, k: Int): IntArray {
            val m = pts.size - 1; val i = pts[0]; val tot = w(i, pts[m])
            val b = IntArray(k + 1); b[k] = m
            for (x in 1 until k) {
                var p = 1
                while (p < m && pre[pts[p]] - pre[i] < tot * x / k) p++
                b[x] = p.coerceIn(b[x - 1] + 1, m - (k - x))
            }
            return b
        }

        /** Cost of a split: parts read A to Z; a range also costs LEVEL per leaf-weight and the best list inside it. */
        fun eval(b: IntArray): Double {
            val k = b.size - 1
            val ws = DoubleArray(k) { w(b[it], b[it + 1]) }
            var inner = 0.0
            for (x in 0 until k) if (b[x + 1] - b[x] > 1) inner += STEP * ws[x] + solve(b[x], b[x + 1])
            return (0 until k).sumOf { x -> (x + 1) * ws[x] } + inner
        }

        /** The list of ONE letter ([idx], A to Z, same first letter). Up to [LETTER_MAX] apps are all read, the most used first (you stop it at yours).
         *  More are split by the first TWO letters into named ranges ("Wa to We"), never "Other". Returns the cost and the list. */
        fun letter(idx: List<Int>): Pair<Double, Node> = groups(idx.groupBy { keys[it].take(2) }.toSortedMap().values.toList())

        fun flat(idx: List<Int>): Pair<Double, Node> {
            val byUse = idx.sortedWith(compareByDescending<Int> { ls[it].weight }.thenBy { it })
            return byUse.withIndex().sumOf { (x, l) -> (x + 1) * ls[l].weight } to Node(byUse.map { Part(ls[it].label, ls[it], null) })
        }

        /** [gs] = apps of one letter grouped by their 2-letter start, in order. */
        fun groups(gs: List<List<Int>>): Pair<Double, Node> {
            val all = gs.flatten()
            if (all.size <= LETTER_MAX || gs.size == 1) return flat(all)
            val tot = all.sumOf { ls[it].weight }
            var best: Pair<Double, Node>? = null
            for (k in 2..minOf(MAX_PARTS, gs.size)) {
                val cuts = ArrayList<Int>(); var acc = 0.0; var g = 0
                for (x in 1 until k) {   // part x ends where the weight so far reaches x/k of the total; every part keeps at least one group
                    val minEnd = (cuts.lastOrNull() ?: 0) + 1; val maxEnd = gs.size - (k - x)
                    while (g < maxEnd && (g < minEnd || acc < tot * x / k)) { acc += gs[g].sumOf { ls[it].weight }; g++ }
                    cuts += g
                }
                cuts += gs.size
                var cost = 0.0; var from = 0
                val parts = ArrayList<Part>()
                cuts.forEachIndexed { x, to ->
                    val part = gs.subList(from, to); val ids = part.flatten(); val w = ids.sumOf { ls[it].weight }
                    if (ids.size == 1) { cost += (x + 1) * w; parts += Part(ls[ids[0]].label, ls[ids[0]], null) }
                    else {
                        val (ic, inner) = groups(part)
                        cost += (x + 1) * w + STEP * w + ic
                        parts += Part(Spoken.range(prefix(part.first()), prefix(part.last())), null, inner)
                    }
                    from = to
                }
                if (best == null || cost < best.first - 1e-9) best = cost to Node(parts)
            }
            return best!!
        }

        fun prefix(g: List<Int>) = keys[g[0]].take(2).replaceFirstChar { it.uppercase() }

        fun up(l: Int) = first[l].uppercase()

        fun build(i: Int, j: Int): Node {
            if (j - i > 1 && points(i, j).size == 2) return letter((i until j).toList()).second
            val b = cuts[i][j]!!
            val k = b.size - 1
            return Node((0 until k).map { x ->
                val a = b[x]; val e = b[x + 1]
                if (e - a == 1) Part(ls[a].label, ls[a], null) else { solve(a, e); Part(Spoken.range(up(a), up(e - 1)), null, build(a, e)) }
            })
        }
    }
}
