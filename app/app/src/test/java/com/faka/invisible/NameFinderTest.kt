package com.faka.invisible

import com.faka.invisible.logickit.NameFinder
import com.faka.invisible.logickit.NameFinder.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameFinderTest {
    private fun leaf(label: String, w: Double = 1.0) = AppLeaf("pkg.$label", label, "Tools", 0, w)

    /** About 60 made-up apps: 3 or 2 per letter, plus "Maps" as the heavy one and a crowd of "Bank..." apps. */
    private val leaves: List<AppLeaf> = ('A'..'Z').flatMap { c -> listOf("${c}one", "${c}two") + (if (c < 'K') listOf("${c}tre") else emptyList()) }.map { leaf(it) } +
        leaf("Maps", 50.0) + (1..8).map { leaf("Bank $it", 2.0) }

    private fun step(vararg letters: Char): NameFinder.Step = NameFinder.next(leaves, letters.joinToString(""))

    @Test fun aHeavyAppIsReachedInTwoSteps() {
        val s1 = step()
        assertTrue(s1.choices.first().let { it is Choice.Letter && it.letter == 'm' })   // M is the heaviest letter
        val s2 = step('m')
        assertEquals("Maps", (s2.choices.first() as Choice.Leaf).leaf.label)
    }

    @Test fun everyLeafIsReachableAndNoListIsEmpty() {
        for (target in leaves) {
            val k = NameFinder.key(target.label)
            var prefix = ""
            var steps = 0
            while (true) {
                val s = NameFinder.next(leaves, prefix)
                assertTrue("empty list at '$prefix' for ${target.label}", s.choices.isNotEmpty())
                steps++
                assertTrue("too many steps for ${target.label}", steps < 10)
                val hit = s.choices.firstOrNull { it is Choice.Leaf && it.leaf.pkg == target.pkg }
                if (hit != null) break
                val letter = s.choices.filterIsInstance<Choice.Letter>().single { k.startsWith(s.prefix + it.letter) }
                prefix = s.prefix + letter.letter
            }
        }
    }

    @Test fun aLetterWithOneLeafIsOfferedAsTheLeaf() {
        val few = listOf("Alpha", "Bravo", "Beta", "Cat", "Cow", "Dog", "Dot", "Eel").map { leaf(it) }
        val s = NameFinder.next(few, "")
        assertTrue(s.choices.any { it is Choice.Leaf && it.leaf.label == "Alpha" } || s.choices.size <= 6)
        val many = few + listOf("Fig", "Fox", "Gum").map { leaf(it) }
        val t = NameFinder.next(many, "")
        assertTrue(t.choices.any { it is Choice.Leaf && it.leaf.label == "Eel" })   // E has one leaf: no extra step
        assertTrue(t.choices.any { it is Choice.Letter && it.letter == 'b' })
    }

    @Test fun aForcedLetterIsSkipped() {
        val l = (1..7).map { leaf("Zed $it") } + leaf("Apple") + leaf("Bean")
        val s = NameFinder.next(l, "z")   // after Z only "e" is possible, then "d", then a digit choice
        assertEquals("zed", s.prefix)
        assertEquals(7, s.choices.size)
        val top = NameFinder.next(l, "")
        assertEquals('z', (top.choices.first() as Choice.Letter).letter)   // 7 leaves behind Z: heaviest letter
    }

    @Test fun spacesCaseAndPunctuationAreIgnored() {
        assertEquals("bank1", NameFinder.key("Bank 1"))
        assertEquals("mapsme", NameFinder.key("Maps.me"))
    }

    @Test fun aLeafWhoseNameIsThePrefixIsStillOffered() {
        val l = listOf(leaf("Go", 1.0)) + (1..7).map { leaf("Go $it") }
        val s = NameFinder.next(l, "go")
        assertTrue(s.choices.any { it is Choice.Leaf && it.leaf.label == "Go" })
    }

    // ---------- REPORT: Find against the tree on the real usage of the phone ----------
    private val usage = javaClass.classLoader!!.getResource("app_usage.tsv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        .associate { l -> l.split('\t').let { it[0] to (it[1].toInt() to it[3].toInt()) } }
    private fun title(p: String): String {
        val skip = setOf("android", "app", "apps", "es", "core", "ui", "mobile", "red", "wallet", "client", "integrated", "community", "latin", "manager", "m", "main", "mshop", "v2")
        val parts = p.split('.')
        return (parts.reversed().firstOrNull { it !in skip && it.length > 2 } ?: parts.last()).replaceFirstChar { it.uppercase() }
    }

    /** Items heard (+ LEVEL per letter step) to reach [target] in Find, never hearing the name list twice. */
    private fun findCost(all: List<AppLeaf>, target: AppLeaf): Double {
        val k = NameFinder.key(target.label); var prefix = ""; var cost = 0.0
        repeat(12) {
            val s = NameFinder.next(all, prefix)
            val i = s.choices.indexOfFirst { it is Choice.Leaf && it.leaf.pkg == target.pkg }
            if (i >= 0) return cost + i + 1
            val j = s.choices.indexOfFirst { it is Choice.Letter && k.startsWith(s.prefix + it.letter) }
            assertTrue("unreachable ${target.label}", j >= 0)
            cost += j + 1 + AppTree.LEVEL; prefix = s.prefix + (s.choices[j] as Choice.Letter).letter
        }
        throw AssertionError("too many steps for ${target.label}")
    }

    @Test fun reportFindAgainstTheTreeOnRealUsage() {
        val all = usage.filterKeys { it != AppWeights.SELF }.map { (p, u) -> AppLeaf(p, title(p), AppCategories.categoryOf(p), 0, AppWeights.weight(p, u.first, u.second, 0)) } +
            listOf("hub:messages", "hub:today", "hub:tasks", "hub:timer", "hub:music", "hub:phone", "hub:games").map { AppLeaf(it, title(it.removePrefix("hub:")), AppCategories.categoryOf(it), 0, AppWeights.weight(it, 0, 0, 0)) }
        val tree = AppTree.build(all)
        val treeCost = AppTree.paths(tree).associate { it.app.pkg to (it.heard + AppTree.LEVEL * it.levels) }
        var sumF = 0.0; var sumT = 0.0; var w = 0.0
        for (l in all) { sumF += l.weight * findCost(all, l); sumT += l.weight * treeCost.getValue(l.pkg); w += l.weight }   // also proves every leaf is reachable
        println("REPORT expected cost per open (weighted): tree=%.2f find=%.2f".format(sumT / w, sumF / w))
        for (l in all.sortedByDescending { treeCost.getValue(it.pkg) }.take(5))
            println("REPORT %-22s tree=%.1f find=%.1f".format(l.label, treeCost.getValue(l.pkg), findCost(all, l)))
        assertTrue(all.all { findCost(all, it) < 30 })
    }
}
