package com.faka.invisible

import com.faka.invisible.logickit.NameFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameFinderTest {
    private fun leaf(label: String, w: Double = 1.0) = AppLeaf("pkg.$label", label, "Tools", 0, w)

    /** About 60 made-up apps: 3 or 2 per letter, plus "Maps" as the heavy one and a crowd of "Bank..." apps. */
    private val leaves: List<AppLeaf> = ('A'..'Z').flatMap { c -> listOf("${c}one", "${c}two") + (if (c < 'K') listOf("${c}tre") else emptyList()) }.map { leaf(it) } +
        leaf("Maps", 50.0) + (1..8).map { leaf("Bank $it", 2.0) }

    private fun lists(n: NameFinder.Node): List<NameFinder.Node> = listOf(n) + n.parts.mapNotNull { it.sub }.flatMap { lists(it) }

    @Test fun aHeavyAppIsReachedInTwoSteps() {
        val p = NameFinder.plan(leaves)
        val i = p.parts.indexOfFirst { it.leaf?.label == "Maps" || (it.sub != null && NameFinder.leavesOf(it).any { l -> l.label == "Maps" }) }
        assertTrue("Maps heard at $i", i in 0..1)
        val part = p.parts[i]
        if (part.leaf == null) assertEquals("Maps", part.sub!!.parts.first().leaf?.label)
    }

    @Test fun everyLeafIsReachableOnceAndNoListIsEmptyOrTooLong() {
        val p = NameFinder.plan(leaves)
        assertEquals(leaves.map { it.pkg }.sorted(), NameFinder.leavesOf(p).map { it.pkg }.sorted())
        for (n in lists(p)) { assertTrue(n.parts.isNotEmpty()); assertTrue(n.parts.size <= NameFinder.MAX_PARTS) }
    }

    @Test fun fewLeavesAreJustListedHeaviestFirst() {
        val p = NameFinder.plan(listOf(leaf("Cat", 1.0), leaf("Ant", 5.0), leaf("Bee", 3.0)))
        assertEquals(listOf("Ant", "Bee", "Cat"), p.parts.map { it.leaf!!.label })
    }

    @Test fun rangesAreNamedByTheShortestStartsAndAreNeighbours() {
        val p = NameFinder.plan((listOf("Sam", "Sap", "Sea", "Sky", "Spy", "Sun", "Tab", "Tan", "Tip", "Top") + (1..4).map { "W$it" }).map { leaf(it) })
        val names = p.parts.map { it.label }
        assertTrue(names.toString(), p.parts.any { it.sub != null })
        assertTrue(names.toString(), names.all { it.isNotBlank() })
    }

    @Test fun spacesCaseAndPunctuationAreIgnored() {
        assertEquals("bank1", NameFinder.key("Bank 1"))
        assertEquals("mapsme", NameFinder.key("Maps.me"))
    }

    // ---------- REPORT: Find against the tree on the real usage of the phone ----------
    private val usage = javaClass.classLoader!!.getResource("app_usage.tsv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        .associate { l -> l.split('\t').let { it[0] to (it[1].toInt() to it[3].toInt()) } }
    private fun title(p: String): String {
        val skip = setOf("android", "app", "apps", "es", "core", "ui", "mobile", "red", "wallet", "client", "integrated", "community", "latin", "manager", "m", "main", "mshop", "v2")
        val parts = p.split('.')
        return (parts.reversed().firstOrNull { it !in skip && it.length > 2 } ?: parts.last()).replaceFirstChar { it.uppercase() }
    }

    /** Items heard (+ LEVEL per range entered) to reach [target] in Find. */
    private fun findCost(plan: NameFinder.Node, target: AppLeaf): Double {
        var node = plan; var cost = 0.0
        while (true) {
            val i = node.parts.indexOfFirst { p -> NameFinder.leavesOf(p).any { it.pkg == target.pkg } }
            assertTrue("unreachable ${target.label}", i >= 0)
            cost += i + 1
            val sub = node.parts[i].sub ?: return cost
            cost += AppTree.LEVEL; node = sub
        }
    }

    @Test fun reportFindAgainstTheTreeOnRealUsage() {
        val all = usage.filterKeys { it != AppWeights.SELF }.map { (p, u) -> AppLeaf(p, title(p), AppCategories.categoryOf(p), 0, AppWeights.weight(p, u.first, u.second, 0)) } +
            listOf("hub:messages", "hub:today", "hub:tasks", "hub:timer", "hub:music", "hub:phone", "hub:games").map { AppLeaf(it, title(it.removePrefix("hub:")), AppCategories.categoryOf(it), 0, AppWeights.weight(it, 0, 0, 0)) }
        val tree = AppTree.build(all)
        val treeCost = AppTree.paths(tree).associate { it.app.pkg to (it.heard + AppTree.LEVEL * it.levels) }
        val t0 = System.nanoTime(); val plan = NameFinder.plan(all); val ms = (System.nanoTime() - t0) / 1_000_000
        var sumF = 0.0; var sumT = 0.0; var w = 0.0
        for (l in all) { sumF += l.weight * findCost(plan, l); sumT += l.weight * treeCost.getValue(l.pkg); w += l.weight }   // also proves every leaf is reachable
        println("REPORT plan for ${all.size} leaves: $ms ms")
        println("REPORT expected cost per open (weighted): tree=%.2f find=%.2f".format(sumT / w, sumF / w))
        for (l in all.sortedByDescending { treeCost.getValue(it.pkg) }.take(5))
            println("REPORT %-22s tree=%.1f find=%.1f".format(l.label, treeCost.getValue(l.pkg), findCost(plan, l)))
        println("REPORT home list of Find: " + plan.parts.map { it.label })
        assertTrue("Find within 1 item of the tree", sumF / w <= sumT / w + 1.0)
    }
}
