package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class AppTreeTest {
    private val pkgs = javaClass.classLoader!!.getResource("phone_apps.txt")!!.readText().lines().map { it.trim() }.filter { it.isNotEmpty() && it != "com.faka.invisible" }
    private val builtins = listOf("hub:messages", "hub:today", "hub:tasks", "hub:timer", "hub:music", "hub:phone", "hub:games")

    @Test fun everyPhonePackageIsInATableGroup() {
        for (p in pkgs.filter { it != "com.faka.invisible" }) assertTrue("$p not in the table", p in AppCategories.EXACT)
        for (b in builtins) assertTrue(b, b in AppCategories.EXACT)
    }

    @Test fun knownApps() {
        fun g(p: String) = AppCategories.main(AppCategories.categoryOf(p))
        for (p in listOf("com.revolut.revolut", "com.bbva.bbvacontigo", "com.brubank", "com.mercadopago.wallet", "de.traderepublic.app", "com.transferwise.android", "com.westernunion.moneytransferr3app.es", "es.bancosantander.apps", "com.myinvestor.es")) assertEquals(p, "Money", g(p))
        for (p in listOf("com.whatsapp", "com.microsoft.teams", "com.tencent.mm", "com.discord", "com.google.android.apps.messaging", "com.linkedin.android", "com.zhiliaoapp.musically", "com.google.android.gm")) assertEquals(p, "Messages", g(p))
        for (p in listOf("com.google.android.calendar", "com.google.android.apps.docs", "com.google.android.keep", "com.atlassian.android.jira.core")) assertEquals(p, "Day", g(p))
        for (p in listOf("com.android.settings", "com.miui.securitycenter", "com.x8bit.bitwarden")) assertEquals(p, "Tools", g(p))
    }

    @Test fun unknownPackageUsesWordsThenHintThenDefault() {
        assertEquals("Money/Pay", AppCategories.categoryOf("com.some.newbank"))
        assertEquals("Media/Play", AppCategories.categoryOf("com.zzz.app", 0))
        assertEquals("Out/Move", AppCategories.categoryOf("com.zzz.app", 6))
        assertEquals("Tools/Dev", AppCategories.categoryOf("com.zzz.app", -1))
    }

    // ---------- the optimized tree ----------
    private val usage: Map<String, Pair<Int, Int>> = javaClass.classLoader!!.getResource("app_usage.tsv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        .associate { l -> l.split('\t').let { it[0] to (it[1].toInt() to it[3].toInt()) } }

    private fun leaf(p: String, w: Double, unread: Int = 0) = AppLeaf(p, p.removePrefix("hub:").substringAfterLast('.'), AppCategories.categoryOf(p), unread, w)

    /** All apps of the owner's phone (not this app) with weights from real launches, plus the OS programs. */
    private fun phone(): List<AppLeaf> = usage.filterKeys { it != AppWeights.SELF }.map { (p, u) -> leaf(p, AppWeights.weight(p, u.first, u.second, 0)) } +
        builtins.map { leaf(it, AppWeights.weight(it, 0, 0, 0)) }

    private fun lists(n: TreeNode): List<TreeNode> = listOf(n) + n.children.filter { !it.isApp }.flatMap { lists(it) }
    private fun dump(n: TreeNode, ind: String = ""): String = n.children.joinToString("") { c ->
        ind + c.label + (if (c.isApp) " [%.0f]".format(c.weight) else " (${c.children.size}) [%.0f]".format(c.weight)) + "\n" + (if (c.isApp) "" else dump(c, "$ind  "))
    }
    private fun flat(l: List<AppLeaf>) = TreeNode("", l.sortedWith(compareByDescending<AppLeaf> { it.weight }.thenBy { it.pkg }).map { TreeNode(it.label, app = it, weight = it.weight) })

    @Test fun weightUsesLaunchesNotMinutes() {
        assertEquals(1.0 + 10 + 2 * 3 + 5 * 2, AppWeights.weight("com.x", 10, 3, 2), 1e-9)
        assertEquals(3.0, AppWeights.weight("hub:tasks", 0, 0, 0), 1e-9)
        assertEquals(1.0, AppWeights.weight("com.unseen", 0, 0, 0), 1e-9)
    }

    @Test fun report() {
        val l = phone(); assertEquals(96, l.size)
        val t0 = System.nanoTime()
        val opt = AppTree.build(l)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val old = AppTreeRules.build(l.map { it.copy(native = true) })
        val fl = flat(l)
        val cOpt = AppTree.expectedCost(opt); val cOld = AppTree.expectedCost(old); val cFlat = AppTree.expectedCost(fl)
        println("REPORT build ${ms} ms, ${l.size} leaves")
        println("REPORT expected cost per open (items heard + 0.5 per level): flat=%.2f old=%.2f optimized=%.2f".format(cFlat, cOld, cOpt))
        println("REPORT expected items heard per open: flat=%.2f old=%.2f optimized=%.2f".format(AppTree.expectedHeard(fl), AppTree.expectedHeard(old), AppTree.expectedHeard(opt)))
        println("REPORT items heard to reach the top 5 apps (sum): flat=%.0f old=%.0f optimized=%.0f".format(AppTree.heardToTop(fl, 5), AppTree.heardToTop(old, 5), AppTree.heardToTop(opt, 5)))
        println("REPORT list lengths: " + lists(opt).map { (it.label.ifEmpty { "HOME" }) + "=" + it.children.size })
        println("REPORT depth=${AppTree.depth(opt)} rawDepth=${AppTree.rawDepth(opt)} maxList=${AppTree.maxListLength(opt)}")
        println("REPORT tree (label (length) [weight]):\n" + dump(opt))
        assertTrue("$cOpt <= $cOld", cOpt <= cOld + 1e-9); assertTrue("$cOld <= $cFlat", cOld <= cFlat)
        assertTrue(AppTree.maxListLength(opt) <= AppTree.MAX_LIST); assertTrue(opt.children.size <= AppTree.HOME)
        assertTrue(AppTree.depth(opt) <= 4)
        assertEquals(l.map { it.pkg }.sorted(), AppTree.apps(opt).map { it.pkg }.sorted())
        assertTrue("build took $ms ms", ms < 1000)
    }

    @Test fun deterministic() {
        val l = phone()
        assertEquals(dump(AppTree.build(l)), dump(AppTree.build(l.reversed())))
    }

    @Test fun unreadNeverChangesOrder() {
        val l = phone()
        val noisy = l.map { if (it.pkg == "com.termux" || it.pkg == "com.tencent.mm") it.copy(unread = 9) else it }
        assertEquals(dump(AppTree.build(l)), dump(AppTree.build(noisy)))
    }

    @Test fun heaviestFirstInEveryListAndMoreHoldsTheTail() {
        val opt = AppTree.build(phone())
        for (x in lists(opt)) {
            val w = x.children.filter { !it.isMore }.map { it.weight }
            assertEquals(w, w.sortedDescending())
            if (x.children.any { it.isMore }) assertTrue(x.children.last().isMore)
        }
    }

    @Test fun dpMatchesHandComputedCost() {
        // one group, 8 leaves, weights 100,50,25,12,6,3,2,1: all in one flat list at home (no categories to split them)
        val ws = listOf(100.0, 50.0, 25.0, 12.0, 6.0, 3.0, 2.0, 1.0)
        val l = ws.mapIndexed { i, w -> AppLeaf("a$i", "A$i", "Tools/Dev", 0, w) }
        val t = AppTree.build(l)
        // the best possible by brute force over the cut c in 2..5 (head c, then More with the rest as one list of at most 6)
        val best = (2..5).minOf { c -> ws.take(c).withIndex().sumOf { (i, w) -> w * (i + 1) } + ws.drop(c).withIndex().sumOf { (i, w) -> w * (c + 1 + AppTree.LEVEL + i + 1) } }
        val flatAll = ws.withIndex().sumOf { (i, w) -> w * (i + 1) }
        val want = minOf(best, flatAll.takeIf { false } ?: best) / ws.sum()
        assertTrue("cost ${AppTree.expectedCost(t)} vs $want", AppTree.expectedCost(t) <= want + 1e-9)
        assertTrue(AppTree.maxListLength(t) <= 6)
    }

    @Test fun aHeavyLeafIsPromotedAndALightGroupStaysDown() {
        val l = (1..8).map { AppLeaf("m$it", "M$it", "Money/Banks", 0, 1.0) } + AppLeaf("star", "Star", "Tools/Web", 0, 500.0) + (1..8).map { AppLeaf("d$it", "D$it", "Tools/Dev", 0, 1.0) }
        val t = AppTree.build(l)
        assertEquals("Star", t.children.first().label)
        assertTrue(t.children.size <= AppTree.HOME)
    }

    @Test fun emptyAndSingle() {
        assertTrue(AppTree.build(emptyList()).children.isEmpty())
        val one = AppTree.build(listOf(leaf("com.x", 1.0)))
        assertEquals(1, one.children.size); assertTrue(one.children[0].isApp)
    }

    @Test fun oldBuilderStillWorks() {
        val l = phone().map { it.copy(native = true) }
        assertEquals(l.map { it.pkg }.sorted(), AppTree.apps(AppTreeRules.build(l)).map { it.pkg }.sorted())
    }
}
