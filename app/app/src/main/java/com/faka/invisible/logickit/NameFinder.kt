package com.faka.invisible.logickit

import com.faka.invisible.AppLeaf
import com.faka.invisible.AppTree

/**
 * "Find": reach ANY app or program by the first letters of its spoken name, without knowing its group. Pure.
 * [next] gives the list to read for the letters picked so far ([prefix]):
 *  - 6 leaves or fewer match: the leaves themselves, heaviest first, unless splitting by the next letter is cheaper (expected items heard, weighted, +[AppTree.LEVEL] per letter step);
 *  - more: the next letters, heaviest first (weight = the sum of the leaves behind the letter); a letter with one leaf behind it is that leaf
 *    (one step less); a leaf whose whole name is the prefix is offered as itself;
 *  - when only one letter is possible, it is added to the prefix at once (nothing to choose), so every list is a real choice.
 * Letters are compared without case; spaces and punctuation are ignored.
 */
object NameFinder {
    const val MAX_LEAVES = 6

    sealed class Choice(val weight: Double) {
        class Letter(val letter: Char, weight: Double) : Choice(weight)
        class Leaf(val leaf: AppLeaf) : Choice(leaf.weight)
    }

    /** [prefix] is the prefix really used (the asked one, plus letters that were forced); [choices] is never empty when something matches. */
    class Step(val prefix: String, val choices: List<Choice>)

    fun key(label: String) = label.lowercase().filter { it.isLetterOrDigit() }

    fun next(leaves: List<AppLeaf>, prefix0: String = ""): Step {
        val all = leaves.map { it to key(it.label) }
        var prefix = key(prefix0)
        while (true) {
            val m = all.filter { it.second.startsWith(prefix) }
            val exact = m.filter { it.second.length == prefix.length }
            val groups = m.filter { it.second.length > prefix.length }.groupBy { it.second[prefix.length] }
            if (exact.isEmpty() && groups.size == 1) { prefix += groups.keys.single(); continue }   // one letter possible: nothing to choose
            if (m.size <= MAX_LEAVES && listCost(m) <= splitCost(m, prefix) + EPS) return Step(prefix, byWeight(m.map { Choice.Leaf(it.first) }))
            return Step(prefix, byWeight(choices(m, prefix)))
        }
    }

    private const val EPS = 1e-9
    private fun choices(m: List<Pair<AppLeaf, String>>, prefix: String): List<Choice> {
        val exact = m.filter { it.second.length == prefix.length }
        val groups = m.filter { it.second.length > prefix.length }.groupBy { it.second[prefix.length] }
        return exact.map { Choice.Leaf(it.first) } +
            groups.map { (ch, g) -> if (g.size == 1) Choice.Leaf(g.single().first) else Choice.Letter(ch, g.sumOf { it.first.weight }) }
    }

    /** Weighted items heard to find a leaf of [m] when the names are listed, heaviest first. */
    private fun listCost(m: List<Pair<AppLeaf, String>>) = m.map { it.first.weight }.sortedDescending().withIndex().sumOf { (i, w) -> (i + 1) * w }

    /** The same when the list is the next letters (each letter costs [AppTree.LEVEL] more, then the best way inside it). */
    private fun splitCost(m: List<Pair<AppLeaf, String>>, prefix: String): Double {
        val exact = m.filter { it.second.length == prefix.length }
        val groups = m.filter { it.second.length > prefix.length }.groupBy { it.second[prefix.length] }
        val parts = exact.map { it.first.weight to 0.0 } + groups.map { (ch, g) ->
            val w = g.sumOf { it.first.weight }
            if (g.size == 1) w to 0.0 else w to (AppTree.LEVEL * w + bestCost(g, prefix + ch))
        }
        return parts.sortedByDescending { it.first }.withIndex().sumOf { (i, p) -> (i + 1) * p.first + p.second }
    }

    private fun bestCost(m: List<Pair<AppLeaf, String>>, prefix: String): Double =
        if (m.size <= 1) listCost(m) else minOf(listCost(m), splitCost(m, prefix))

    private fun byWeight(c: List<Choice>) = c.sortedWith(compareBy({ -it.weight }, { name(it) }))
    private fun name(c: Choice) = when (c) { is Choice.Letter -> c.letter.toString(); is Choice.Leaf -> key(c.leaf.label) + c.leaf.pkg }
}
